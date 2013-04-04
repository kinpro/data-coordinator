package com.socrata.datacoordinator.service

import java.io._
import javax.servlet.http.HttpServletRequest

import com.socrata.http.server.implicits._
import com.socrata.http.server.{HttpResponse, SocrataServerJetty, HttpService}
import com.socrata.http.server.responses._
import com.socrata.http.routing.{ExtractingRouter, RouterSet}
import com.rojoma.json.util.{JsonArrayIterator, AutomaticJsonCodecBuilder, JsonKey, JsonUtil}
import com.rojoma.json.io._
import com.rojoma.json.ast.{JNull, JValue, JNumber, JObject}
import com.ibm.icu.text.Normalizer
import com.socrata.datacoordinator.common.soql.{SoQLRep, SoQLRowLogCodec, PostgresSoQLDataContext}
import java.util.concurrent.{TimeUnit, Executors}
import org.postgresql.ds.PGSimpleDataSource
import com.socrata.datacoordinator.truth._
import com.typesafe.config.ConfigFactory
import com.socrata.datacoordinator.common.{StandardObfuscationKeyGenerator, DataSourceFromConfig, StandardDatasetMapLimits}
import java.sql.Connection
import com.rojoma.simplearm.util._
import com.socrata.datacoordinator.truth.sql.SqlDataReadingContext
import com.socrata.datacoordinator.truth.metadata.{DatasetInfo, AbstractColumnInfoLike, ColumnInfo, UnanchoredCopyInfo}
import scala.concurrent.duration.Duration
import com.socrata.datacoordinator.secondary.{Secondary, NamedSecondary, PlaybackToSecondary, SecondaryLoader}
import com.socrata.datacoordinator.util.collection.DatasetIdMap
import com.socrata.datacoordinator.id.{RowId, ColumnId, DatasetId}
import com.socrata.datacoordinator.secondary.sql.SqlSecondaryManifest
import com.socrata.datacoordinator.truth.metadata.sql.PostgresDatasetMapReader
import com.socrata.datacoordinator.truth.loader.sql.SqlDelogger
import com.socrata.datacoordinator.primary.{WorkingCopyCreator, Publisher}
import java.net.URLDecoder
import com.socrata.datacoordinator.util.{StackedTimingReport, LoggedTimingReport}
import javax.activation.{MimeTypeParseException, MimeType}
import com.socrata.datacoordinator.secondary.NamedSecondary
import scala.Some
import com.socrata.datacoordinator.truth.universe.sql.{PostgresCopyIn, PostgresUniverse}
import com.rojoma.simplearm.SimpleArm
import com.socrata.datacoordinator.common.soql.universe.PostgresUniverseCommonSupport
import com.socrata.soql.types.SoQLType
import com.socrata.soql.environment.ColumnName
import com.socrata.datacoordinator.common.soql.SoQLRep.IdObfuscationContext
import com.socrata.datacoordinator.truth.json.JsonColumnRep
import com.socrata.datacoordinator.common.util.RowIdObfuscator

case class Field(name: String, @JsonKey("type") typ: String)
object Field {
  implicit val jCodec = AutomaticJsonCodecBuilder[Field]
}

class Service(processMutation: Iterator[JValue] => Unit,
              datasetContents: (String, CopySelector, Option[Set[ColumnName]], Option[Long], Option[Long]) => (Iterator[JObject] => Unit) => Boolean,
              secondaries: Set[String],
              datasetsInStore: (String) => DatasetIdMap[Long],
              versionInStore: (String, String) => Option[Long],
              updateVersionInStore: (String, String) => Unit,
              secondariesOfDataset: String => Option[Map[String, Long]])
{
  val log = org.slf4j.LoggerFactory.getLogger(classOf[Service])

  val normalizationMode: Normalizer.Mode = Normalizer.NFC

  def norm(s: String) = Normalizer.normalize(s, normalizationMode)

  def normalizeJson(token: JsonToken): JsonToken = {
    def position(t: JsonToken) = { t.position = token.position; t }
    token match {
      case TokenString(s) =>
        position(TokenString(norm(s)))
      case TokenIdentifier(s) =>
        position(TokenIdentifier(norm(s)))
      case other =>
        other
    }
  }

  def doExportFile(id: String)(req: HttpServletRequest): HttpResponse = { resp =>
    val onlyColumns = Option(req.getParameterValues("c")).map(_.flatMap { c => norm(c).split(',').map(ColumnName) }.toSet)
    val limit = Option(req.getParameter("limit")).map { limStr =>
      try {
        limStr.toLong
      } catch {
        case _: NumberFormatException =>
          return BadRequest ~> Content("Bad limit")
      }
    }
    val offset = Option(req.getParameter("offset")).map { offStr =>
      try {
        offStr.toLong
      } catch {
        case _: NumberFormatException =>
          return BadRequest ~> Content("Bad offset")
      }
    }
    val copy = Option(req.getParameter("copy")).getOrElse("latest").toLowerCase match {
      case "latest" => LatestCopy
      case "published" => PublishedCopy
      case "working" => WorkingCopy
      case other =>
        try { Snapshot(other.toInt) }
        catch { case _: NumberFormatException =>
          return BadRequest ~> Content("Bad copy selector")
        }
    }
    val found = datasetContents(norm(id), copy, onlyColumns, limit, offset) { rows =>
      resp.setContentType("application/json")
      resp.setCharacterEncoding("utf-8")
      val out = new BufferedWriter(resp.getWriter)
      out.write('[')
      val jsonWriter = new CompactJsonWriter(out)
      var didOne = false
      while(rows.hasNext) {
        if(didOne) out.write(',')
        else didOne = true
        jsonWriter.write(rows.next())
      }
      out.write(']')
      out.flush()
    }
    if(!found)
      NotFound(resp)
  }

  def doGetSecondaries()(req: HttpServletRequest): HttpResponse =
    OK ~> ContentType("application/json; charset=utf-8") ~> Write(JsonUtil.writeJson(_, secondaries.toSeq, buffer = true))

  def doGetSecondaryManifest(storeId: String)(req: HttpServletRequest): HttpResponse = {
    if(!secondaries(storeId)) return NotFound
    val ds = datasetsInStore(storeId)
    val dsConverted = ds.foldLeft(Map.empty[String, Long]) { (acc, kv) =>
      acc + (kv._1.toString -> kv._2)
    }
    OK ~> ContentType("application/json; charset=utf-8") ~> Write(JsonUtil.writeJson(_, dsConverted, buffer = true))
  }

  def doGetDataVersionInSecondary(storeId: String, datasetIdRaw: String)(req: HttpServletRequest): HttpResponse = {
    if(!secondaries(storeId)) return NotFound
    val datasetId =
      try { URLDecoder.decode(datasetIdRaw, "UTF-8") }
      catch { case _: IllegalArgumentException => return BadRequest }
    versionInStore(storeId, datasetId) match {
      case Some(v) =>
        OK ~> ContentType("application/json; charset=utf-8") ~> Write(JsonUtil.writeJson(_, Map("version" -> v), buffer = true))
      case None =>
        NotFound
    }
  }

  def doUpdateVersionInSecondary(storeId: String, datasetIdRaw: String)(req: HttpServletRequest): HttpResponse = {
    if(!secondaries(storeId)) return NotFound
    val datasetId =
      try { URLDecoder.decode(datasetIdRaw, "UTF-8") }
      catch { case _: IllegalArgumentException => return BadRequest }
    updateVersionInStore(storeId, datasetId)
    OK
  }

  def doGetSecondariesOfDataset(datasetIdRaw: String)(req: HttpServletRequest): HttpResponse = {
    val datasetId =
      try { URLDecoder.decode(datasetIdRaw, "UTF-8") }
      catch { case _: IllegalArgumentException => return BadRequest }
    secondariesOfDataset(datasetId) match {
      case Some(ss) =>
        OK ~> ContentType("application/json; charset=utf-8") ~> Write(JsonUtil.writeJson(_, ss, buffer = true))
      case None =>
        NotFound
    }
  }

  def jsonStream(req: HttpServletRequest): Either[HttpResponse, JsonEventIterator] = {
    val nullableContentType = req.getContentType
    if(nullableContentType == null)
      return Left(BadRequest ~> Content("No content-type specified"))
    val contentType =
      try { new MimeType(nullableContentType) }
      catch { case _: MimeTypeParseException =>
        return Left(BadRequest ~> Content("Unparsable content-type"))
      }
    if(!contentType.`match`("application/json")) {
      return Left(UnsupportedMediaType ~> Content("Not application/json"))
    }
    val reader =
      try { req.getReader }
      catch { case _: UnsupportedEncodingException =>
        return Left(UnsupportedMediaType ~> Content("Unknown character encoding"))
      }
    Right(new JsonEventIterator(new BlockJsonTokenIterator(reader).map(normalizeJson)))
  }

  def doMutation()(req: HttpServletRequest): HttpResponse = {
    jsonStream(req) match {
      case Right(events) =>
        try {
          val iterator = try {
            JsonArrayIterator[JValue](events)
          } catch { case _: JsonBadParse =>
            return BadRequest ~> Content("Not a JSON array")
          }
          processMutation(iterator)
          OK
        } catch {
          case _: DatasetIdInUseByWriterException =>
            Conflict ~> Content("Dataset in use by some other writer; retry later")
          case r: JsonReaderException =>
            BadRequest ~> Content("Malformed JSON : " + r.getMessage)
        }
      case Left(response) =>
        response
    }
  }

  val router = RouterSet(
    ExtractingRouter[HttpService]("POST", "/mutate")(doMutation _),
    ExtractingRouter[HttpService]("GET", "/export/?")(doExportFile _),
    ExtractingRouter[HttpService]("GET", "/secondary-manifest")(doGetSecondaries _),
    ExtractingRouter[HttpService]("GET", "/secondary-manifest/?")(doGetSecondaryManifest _),
    ExtractingRouter[HttpService]("GET", "/secondary-manifest/?/?")(doGetDataVersionInSecondary _),
    ExtractingRouter[HttpService]("POST", "/secondary-manifest/?/?")(doUpdateVersionInSecondary _),
    ExtractingRouter[HttpService]("GET", "/secondaries-of-dataset/?")(doGetSecondariesOfDataset _)
  )

  private def handler(req: HttpServletRequest): HttpResponse = {
    router.apply(req.getMethod, req.getPathInfo.split('/').drop(1)) match {
      case Some(result) =>
        result(req)
      case None =>
        NotFound
    }
  }

  def run(port: Int) {
    val server = new SocrataServerJetty(handler, port = port)
    server.run()
  }
}

object Service extends App { self =>
  val log = org.slf4j.LoggerFactory.getLogger(classOf[Service])

  val timingReport = new LoggedTimingReport(org.slf4j.LoggerFactory.getLogger("timing-report")) with StackedTimingReport

  val config = ConfigFactory.load()
  val serviceConfig = config.getConfig("com.socrata.coordinator-service")
  println(config.root.render)

  val secondaries = SecondaryLoader.load(serviceConfig.getConfig("secondary.configs"), new File(serviceConfig.getString("secondary.path")))

  val (dataSource, copyInForDataSource) = DataSourceFromConfig(serviceConfig)

  com.rojoma.simplearm.util.using(dataSource.getConnection()) { conn =>
    com.socrata.datacoordinator.truth.sql.DatabasePopulator.populate(conn, StandardDatasetMapLimits)
  }

  val port = serviceConfig.getInt("network.port")

  val executorService = Executors.newCachedThreadPool()
  try {
    val dataContext = new PostgresSoQLDataContext {
      val obfuscationKeyGenerator = StandardObfuscationKeyGenerator
      val initialRowId = new RowId(0L)
      val dataSource = self.dataSource
      val executorService = self.executorService
      def copyIn(conn: Connection, sql: String, input: Reader): Long =
        copyInForDataSource(conn, sql, input)
      def tablespace(s: String) = Some("pg_default")
      val datasetMapLimits = StandardDatasetMapLimits
      val timingReport = self.timingReport
      val datasetMutatorLockTimeout = Duration.Inf
    }

    def datasetsInStore(storeId: String): DatasetIdMap[Long] =
      using(dataSource.getConnection()) { conn =>
        val secondaryManifest = new SqlSecondaryManifest(conn)
        secondaryManifest.datasets(storeId)
      }
    def versionInStore(storeId: String, datasetId: String): Option[Long] =
      using(dataSource.getConnection()) { conn =>
        val secondaryManifest = new SqlSecondaryManifest(conn)
        val mapReader = new PostgresDatasetMapReader(conn, timingReport)
        for {
          systemId <- mapReader.datasetId(datasetId)
          result <- secondaryManifest.readLastDatasetInfo(storeId, systemId)
        } yield result._1
      }
    def updateVersionInStore(storeId: String, datasetId: String): Unit =
      using(dataSource.getConnection()) { conn =>
        conn.setAutoCommit(false)
        val secondaryManifest = new SqlSecondaryManifest(conn)
        val secondary = secondaries(storeId).asInstanceOf[Secondary[dataContext.CV]]
        val pb = new PlaybackToSecondary[dataContext.CT, dataContext.CV](conn, secondaryManifest, dataContext.sqlRepForColumn, timingReport)
        val mapReader = new PostgresDatasetMapReader(conn, timingReport)
        for {
          systemId <- mapReader.datasetId(datasetId)
          datasetInfo <- mapReader.datasetInfo(systemId)
        } yield {
          val delogger = new SqlDelogger(conn, datasetInfo.logTableName, dataContext.newRowLogCodec)
          pb(systemId, NamedSecondary(storeId, secondary), mapReader, delogger)
        }
        conn.commit()
      }
    def secondariesOfDataset(datasetId: String): Option[Map[String, Long]] =
      using(dataSource.getConnection()) { conn =>
        conn.setAutoCommit(false)
        val mapReader = new PostgresDatasetMapReader(conn, timingReport)
        val secondaryManifest = new SqlSecondaryManifest(conn)
        for {
          systemId <- mapReader.datasetId(datasetId)
        } yield secondaryManifest.stores(systemId)
      }

    val commonSupport = new PostgresUniverseCommonSupport(executorService, _ => None, PostgresCopyIn, StandardObfuscationKeyGenerator, dataContext.initialRowId)

    type SoQLUniverse = PostgresUniverse[SoQLType, Any]
    def soqlUniverse(conn: Connection) =
      new SoQLUniverse(conn, commonSupport, timingReport, ":secondary-watcher")

    val universeProvider = new SimpleArm[SoQLUniverse] {
      def flatMap[B](f: SoQLUniverse => B): B = {
        using(dataSource.getConnection()) { conn =>
          conn.setAutoCommit(false)
          val result = f(soqlUniverse(conn))
          conn.commit()
          result
        }
      }
    }

    val mutationCommon = new MutatorCommon[SoQLType, Any] {
      def physicalColumnBaseBase(logicalColumnName: ColumnName, systemColumn: Boolean): String =
        dataContext.physicalColumnBaseBase(logicalColumnName, systemColumn = systemColumn)

      def isLegalLogicalName(identifier: ColumnName): Boolean =
        dataContext.isLegalLogicalName(identifier)

      def isSystemColumnName(identifier: ColumnName): Boolean =
        dataContext.isSystemColumn(identifier)

      val magicDeleteKey = ColumnName(":delete")

      def systemSchema = dataContext.systemColumns.mapValues(dataContext.typeContext.nameFromType)
      def systemIdColumnName = dataContext.systemIdColumnName
    }

    val mutator = new Mutator(mutationCommon)

    def obfuscationContextFor(key: Array[Byte]) = new RowIdObfuscator(key)

    def jsonRepForColumn(datasetInfo: DatasetInfo): AbstractColumnInfoLike => JsonColumnRep[SoQLType, Any] = {
      val reps = SoQLRep.jsonRepFactories(obfuscationContextFor(datasetInfo.obfuscationKey));
      { (col: AbstractColumnInfoLike) =>
        reps(dataContext.typeContext.typeFromName(col.typeName))(col.logicalName)
      }
    }

    def processMutation(input: Iterator[JValue]) = {
      for(u <- universeProvider) {
        mutator(u, jsonRepForColumn, input)
      }
    }

    def exporter(id: String, copy: CopySelector, columns: Option[Set[ColumnName]], limit: Option[Long], offset: Option[Long])(f: Iterator[JObject] => Unit): Boolean = {
      val res = for(u <- universeProvider) yield {
        Exporter.export(u, id, copy, columns, limit, offset) { (copyInfo, schema, it) =>
          val jsonSchema = schema.mapValuesStrict(jsonRepForColumn(copyInfo.datasetInfo))
          f(it.map { row =>
            val res = new scala.collection.mutable.HashMap[String, JValue]
            row.foreach { case (cid, value) =>
              if(jsonSchema.contains(cid)) {
                val rep = jsonSchema(cid)
                val v = rep.toJValue(value)
                if(JNull != v) res(rep.name.name) = v
              }
            }
            JObject(res)
          })
        }
      }
      res.isDefined
    }

    val serv = new Service(processMutation, exporter,
      secondaries.keySet, datasetsInStore, versionInStore, updateVersionInStore,
      secondariesOfDataset)
    serv.run(port)

    secondaries.values.foreach(_.shutdown())
  } finally {
    executorService.shutdown()
  }
  executorService.awaitTermination(Long.MaxValue, TimeUnit.SECONDS)
}
