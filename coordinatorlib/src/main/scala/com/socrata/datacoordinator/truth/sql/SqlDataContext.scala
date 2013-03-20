package com.socrata.datacoordinator.truth
package sql

import javax.sql.DataSource
import java.sql.Connection

import org.joda.time.DateTime
import com.socrata.id.numeric.IdProvider

import com.socrata.datacoordinator.truth.metadata._
import com.socrata.datacoordinator.truth.loader.sql.{PostgresSqlLoaderProvider, AbstractSqlLoaderProvider}
import com.socrata.datacoordinator.util.collection.ColumnIdMap
import com.socrata.datacoordinator.truth.loader.{SchemaLoader, DatasetContentsCopier, Loader, Logger}
import java.io.Reader
import com.socrata.datacoordinator.truth.metadata.sql.{PostgresDatasetMapReader, PostgresGlobalLog, PostgresDatasetMapWriter}
import scala.concurrent.duration.Duration
import com.socrata.datacoordinator.util.{RowIdProvider, TimingReport}
import com.socrata.datacoordinator.truth.metadata.ColumnInfo
import com.socrata.datacoordinator.truth.metadata.CopyInfo

trait SqlDataTypeContext extends DataTypeContext {
  val dataSource: DataSource

  type SqlRepType <: SqlColumnCommonRep[CT]
  def sqlRepForColumn(physicalColumnBase: String, typ: CT): SqlRepType
  final def sqlRepForColumn(ci: ColumnInfo): SqlRepType = sqlRepForColumn(ci.physicalColumnBase, typeContext.typeFromName(ci.typeName))
}

trait DatasetLockContext {
  val datasetLock: DatasetLock
  val datasetLockTimeout: Duration
}

trait SqlDataWritingContext extends SqlDataTypeContext with DataWritingContext { this: DatasetLockContext =>
  type SqlRepType <: SqlColumnWriteRep[CT, CV]

  protected val loaderProvider: AbstractSqlLoaderProvider[CT, CV]

  protected final def loaderFactory(conn: Connection, now: DateTime, copy: CopyInfo, schema: ColumnIdMap[ColumnInfo], idProvider: RowIdProvider, logger: Logger[CV]): Loader[CV] = {
    loaderProvider(conn, copy, schema, rowPreparer(now, schema), idProvider, logger)
  }

  val databaseMutator: LowLevelDatabaseMutator[CV]

  final lazy val datasetMutator = DatasetMutator(databaseMutator, datasetLock, datasetLockTimeout)
}

trait SqlDataReadingContext extends SqlDataTypeContext with DataReadingContext {
  type SqlRepType <: SqlColumnReadRep[CT, CV]

  val databaseReader: LowLevelDatabaseReader[CV]
  final lazy val datasetReader = DatasetReader(databaseReader)
}

trait PostgresDataContext extends SqlDataWritingContext with SqlDataReadingContext { self: DataSchemaContext with ExecutionContext with DatasetLockContext =>
  type SqlRepType = SqlColumnRep[CT, CV]

  protected def tablespace(s: String): Option[String]

  protected def copyIn(conn: Connection, sql: String, input: Reader): Long

  protected final lazy val loaderProvider = new AbstractSqlLoaderProvider(executorService, typeContext, sqlRepForColumn, isSystemColumn, timingReport) with PostgresSqlLoaderProvider[CT, CV] {
    def copyIn(conn: Connection, sql: String, input: Reader) = self.copyIn(conn, sql, input)
  }

  private def mapReaderFactory(conn: Connection) = new PostgresDatasetMapReader(conn)

  final lazy val databaseReader: LowLevelDatabaseReader[CV] =
    new PostgresDatabaseReader[CT, CV](dataSource, mapReaderFactory, sqlRepForColumn)

  final lazy val databaseMutator: LowLevelDatabaseMutator[CV] = {
    import com.rojoma.simplearm.{SimpleArm, Managed}
    import com.rojoma.simplearm.util._
    import com.socrata.datacoordinator.truth.universe._
    abstract class UniverseType extends Universe[CT, CV] with LoggerProvider with SchemaLoaderProvider with LoaderProvider with DatasetContentsCopierProvider with DatasetMapWriterProvider with GlobalLogProvider
    new PostgresDatabaseMutator(new SimpleArm[UniverseType] {
      def flatMap[B](f: UniverseType => B): B = {
        // dataSource, sqlRepForColumn, newRowLogCodec, mapWriterFactory, globalLogFactory, loaderFactory, tablespace, timingReport
        using(dataSource.getConnection()) { conn =>
          conn.setAutoCommit(false)

          val universe = new UniverseType {
            import com.socrata.datacoordinator.truth.loader.sql._

            def schemaLoader(datasetInfo: DatasetInfo): SchemaLoader =
              new RepBasedSqlSchemaLoader(conn, logger(datasetInfo), sqlRepForColumn, tablespace)

            def datasetContentsCopier(datasetInfo: DatasetInfo): DatasetContentsCopier =
              new RepBasedSqlDatasetContentsCopier(conn, logger(datasetInfo), sqlRepForColumn)

            var loggerCache = Map.empty[String, Logger[CV]]

            def logger(datasetInfo: DatasetInfo): Logger[CV] =
              loggerCache.get(datasetInfo.logTableName) match {
                case Some(logger) =>
                  logger
                case None =>
                  val logger = new SqlLogger(conn, datasetInfo.logTableName, newRowLogCodec, timingReport)
                  loggerCache += datasetInfo.logTableName -> logger
                  logger
              }

            def loader(copy: CopyInfo, schema: ColumnIdMap[ColumnInfo]): Managed[Loader[CV]] =
              new SimpleArm[Loader[CV]] {
                def flatMap[B](f: Loader[CV] => B): B = {
                   val idProvider = new com.socrata.datacoordinator.util.RowIdProvider(copy.datasetInfo.nextRowId)
                  f(loaderProvider(conn, copy, schema, rowPreparer(transactionStart, schema), idProvider, logger(copy.datasetInfo)))
                }
              }

            def commit() {
              loggerCache.values.foreach(_.close())
              loggerCache = Map.empty
              conn.commit()
              transactionStart = DateTime.now()
            }

            val timingReport: TimingReport = self.timingReport
            var transactionStart: DateTime = DateTime.now()
            val globalLog: GlobalLog = new PostgresGlobalLog(conn)
            val datasetMapWriter: DatasetMapWriter = new PostgresDatasetMapWriter(conn)
          }

          val result = f(universe)
          universe.commit()
          result
        }
      }
    })
  }
}
