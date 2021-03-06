package com.socrata.datacoordinator.primary

import java.io.File

import com.socrata.thirdparty.opencsv.CSVIterator

import org.postgresql.ds._
import com.rojoma.simplearm.util._

import com.socrata.datacoordinator.truth.metadata._
import com.socrata.datacoordinator.{Row, MutableRow}
import com.socrata.datacoordinator.common.{SoQLCommon, StandardDatasetMapLimits}
import com.socrata.soql.brita.IdentifierFilter
import com.socrata.datacoordinator.util.{NullCache, StackedTimingReport, LoggedTimingReport}
import org.slf4j.LoggerFactory
import com.socrata.soql.environment.{ColumnName, TypeName}
import com.socrata.datacoordinator.truth.universe.sql.PostgresCopyIn
import com.socrata.datacoordinator.truth.csv.CsvColumnReadRep
import com.socrata.datacoordinator.common.soql.SoQLRep
import org.apache.log4j.PropertyConfigurator
import scala.concurrent.duration.{FiniteDuration, Duration}
import java.util.concurrent.TimeUnit
import com.socrata.datacoordinator.id.UserColumnId
import com.socrata.datacoordinator.util.collection.UserColumnIdMap

object ChicagoCrimesLoadScript extends App {
  val loggingProps = new java.util.Properties
  loggingProps.put("log4j.rootLogger","INFO,console")
  loggingProps.put("log4j.logger.com.socrata","TRACE")
  loggingProps.put("log4j.appender.console","org.apache.log4j.ConsoleAppender")
  loggingProps.put("log4j.appender.console.layout","org.apache.log4j.PatternLayout")
  loggingProps.put("log4j.appender.console.layout.ConversionPattern","[%t] %d %c %m%n")
  PropertyConfigurator.configure(loggingProps)

  val url =
  // "jdbc:postgresql://10.0.5.104:5432/robertm"
    "jdbc:postgresql://localhost:5432/robertm"
  val username =
  // "robertm"
    "blist"
  val pwd =
  // "lof9afw3"
    "blist"

  val inputFile = args.lift(2).getOrElse("/home/robertm/chicagocrime.csv")

  val ds = new PGSimpleDataSource
  ds.setServerName("localhost")
  ds.setPortNumber(5432)
  ds.setUser("blist")
  ds.setPassword("blist")
  ds.setDatabaseName("robertm")

  val executor = java.util.concurrent.Executors.newCachedThreadPool()
  try {

    val common = new SoQLCommon(
      ds,
      PostgresCopyIn,
      executor,
      Function.const(None),
      new LoggedTimingReport(LoggerFactory.getLogger("timing-report")) with StackedTimingReport,
      allowDdlOnPublishedCopies = false,
      new FiniteDuration(10, TimeUnit.SECONDS),
      "dummy instance",
      new File(System.getProperty("java.io.tmpdir")),
      NullCache
    )

    val datasetCreator = new DatasetCreator(common.universe, common.SystemColumns.schemaFragment, common.SystemColumns.id, common.SystemColumns.version, common.physicalColumnBaseBase)

    val columnAdder = new ColumnAdder(common.universe, common.physicalColumnBaseBase)

    val primaryKeySetter = new PrimaryKeySetter(common.universe)

    val upserter = new Upserter(common.universe)

    val publisher = new Publisher(common.universe)

    val workingCopyCreator = new WorkingCopyCreator(common.universe)

    // Above this can be re-used for every query

    val user = "robertm"

    val datasetId = datasetCreator.createDataset(user, "en_US")
    println("Created " + datasetId)
    using(CSVIterator.fromFile(new File(inputFile))) { it =>
      val NumberT = common.typeContext.typeNamespace.typeForUserType(TypeName("number")).get
      val TextT = common.typeContext.typeNamespace.typeForUserType(TypeName("text")).get
      val BooleanT = common.typeContext.typeNamespace.typeForUserType(TypeName("boolean")).get
      val FloatingTimestampT = common.typeContext.typeNamespace.typeForUserType(TypeName("floating_timestamp")).get
      val LocationT = common.typeContext.typeNamespace.typeForUserType(TypeName("location")).get
      val types = Map(
        "id" -> NumberT,
        "case_number" -> TextT,
        "date" -> FloatingTimestampT,
        "block" -> TextT,
        "iucr" -> TextT,
        "primary_type" -> TextT,
        "description" -> TextT,
        "location_description" -> TextT,
        "arrest" -> BooleanT,
        "domestic" -> BooleanT,
        "beat" -> TextT,
        "district" -> TextT,
        "ward" -> NumberT,
        "community_area" -> TextT,
        "fbi_code" -> TextT,
        "x_coordinate" -> NumberT,
        "y_coordinate" -> NumberT,
        "year" -> NumberT,
        "updated_on" -> FloatingTimestampT,
        "latitude" -> NumberT,
        "longitude" -> NumberT,
        "location" -> LocationT
      )
      val headers = it.next().map { t => IdentifierFilter(t) }
      val schema = columnAdder.addToSchema(datasetId, headers.map { x => x -> types(x) }.toMap, new UserColumnId(_), user)
      primaryKeySetter.makePrimaryKey(datasetId, new UserColumnId("id"), user)
      val start = System.nanoTime()
      upserter.upsert(datasetId, user) { _ =>
        val plan = rowDecodePlan(schema, headers.map(new UserColumnId(_)), SoQLRep.csvRep)
        it.map { row =>
          val result = plan(row)
          if(result._1.nonEmpty) throw new Exception("Error decoding row; unable to decode columns: " + result._1.mkString(", "))
          result._2
        }.map(Right(_))
      }
      val end = System.nanoTime()
      println(s"Upsert took ${(end - start) / 1000000L}ms")
      publisher.publish(datasetId, None, user)
      workingCopyCreator.copyDataset(datasetId, user, copyData = true)
      val ci = for {
        u <- common.universe
        u.datasetMutator.CopyOperationComplete(ctx) <- u.datasetMutator.dropCopy(user)(datasetId, _ => ())
      } yield {
        ctx.copyInfo.unanchored
      }
      workingCopyCreator.copyDataset(datasetId, user, copyData = true)
      println(ci)
    }
  } finally {
    executor.shutdown()
  }

  def rowDecodePlan[CT, CV](schema: UserColumnIdMap[ColumnInfo[CT]], headers: IndexedSeq[UserColumnId], csvRepForColumn: ColumnInfo[CT] => CsvColumnReadRep[CT, CV]): IndexedSeq[String] => (Seq[UserColumnId], Row[CV]) = {
    val colInfo = headers.zipWithIndex.map { case (header, idx) =>
      val ci = schema(header)
      (header, ci.systemId, csvRepForColumn(ci), Array(idx) : IndexedSeq[Int])
    }
    (row: IndexedSeq[String]) => {
      val result = new MutableRow[CV]
      val bads = colInfo.flatMap { case (header, systemId, rep, indices) =>
        try {
          result += systemId -> rep.decode(row, indices).get
          Nil
        } catch { case e: Exception => List(header) }
      }
      (bads, result.freeze())
    }
  }
}
