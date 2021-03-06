package com.socrata.datacoordinator.common.soql.sqlreps

import com.socrata.datacoordinator.truth.sql.SqlColumnRep
import com.socrata.soql.types.{SoQLNull, SoQLObject, SoQLValue, SoQLType}
import com.rojoma.json.io.{JsonReader, CompactJsonWriter}
import java.lang.StringBuilder
import java.sql.{ResultSet, Types, PreparedStatement}
import com.rojoma.json.ast.JObject

class ObjectRep (val base: String) extends RepUtils with SqlColumnRep[SoQLType, SoQLValue] {
  def representedType = SoQLObject

  def string(v: SoQLValue) = CompactJsonWriter.toString(v.asInstanceOf[SoQLObject].value)

  val physColumns: Array[String] = Array(base)

  val sqlTypes: Array[String] = Array("TEXT")

  def csvifyForInsert(sb: StringBuilder, v: SoQLValue) {
    if(SoQLNull == v) { /* pass */ }
    else csvescape(sb, string(v))
  }

  def prepareInsert(stmt: PreparedStatement, v: SoQLValue, start: Int): Int = {
    if(SoQLNull == v) stmt.setNull(start, Types.VARCHAR)
    else stmt.setString(start, string(v))
    start + 1
  }

  def estimateSize(v: SoQLValue): Int =
    if(SoQLNull == v) standardNullInsertSize
    else string(v).length //ick

  def fromResultSet(rs: ResultSet, start: Int): SoQLValue = {
    val v = rs.getString(start)
    if(v == null) SoQLNull
    else SoQLObject(JsonReader.fromString(v).asInstanceOf[JObject])
  }
}
