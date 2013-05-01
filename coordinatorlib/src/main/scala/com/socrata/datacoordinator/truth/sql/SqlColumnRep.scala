package com.socrata.datacoordinator.truth.sql

import java.sql.{ResultSet, PreparedStatement}

trait SqlColumnCommonRep[Type] {
  /** The logical type of this column */
  def representedType: Type

  /** The "base name" from which physical column names are derived.  This must
    * be a legal SQL column name without quotes! */
  def base: String

  /** Physical SQL table columns used by this logical column. */
  def physColumns: Array[String]

  /** Types of the physical SQL table columns used by this logical column.
    * @note This will have the same length as `physColumns`. */
  def sqlTypes: Array[String]

  /** Helper function to create physical column names for types with multiple physical columns. */
  protected def physCol(suffix: String) = "\"" + base + "$" + suffix + "\""

  def isPKableRep: Boolean = false
}

trait SqlColumnReadRep[Type, Value] extends SqlColumnCommonRep[Type] {
  /** Extract a value from the result set.  This will "use up" a number of
    * columns equal to `physColumnsForQuery.length`.
    */
  def fromResultSet(rs: ResultSet, start: Int): Value
  def asPKableRep: SqlPKableColumnReadRep[Type, Value] = sys.error("Not a PKable rep")
}

trait SqlOrderableColumnRep {  this: SqlColumnCommonRep[_] =>
  /** Produce an order specification for this column. */
  def orderBy(ascending: Boolean = true, nullsFirst: Option[Boolean] = None): String =
    simpleOrderBy(physColumns, ascending, nullsFirst)

  protected def simpleOrderBy(cols: Array[String], ascending: Boolean, nullsFirst: Option[Boolean]) = {
    val sb = new java.lang.StringBuilder
    def oneCol(col: String) {
      sb.append(col)
      sb.append(if(ascending) " ASC" else " DESC")
      nullsFirst.foreach { nf =>
        sb.append(if(nf) " NULLS FIRST" else " NULLS LAST")
      }
    }
    cols.foreach(oneCol)
    sb.toString
  }
}

trait SqlColumnWriteRep[Type, Value] extends SqlColumnCommonRep[Type] {
  /** @param sb The `StringBuilder` to which to add the data.
    * @param v The value to add; its type must be compatible with `representedType`.
    */
  def csvifyForInsert(sb: java.lang.StringBuilder, v: Value)

  /** Produce a prepared statement fragment containing one `?` for each
    * column in `physColumns`.
    */
  def templateForInsert: String = simpleTemplateForInsert
  private lazy val simpleTemplateForInsert = physColumns.map(_ => "?").mkString(",")

  def prepareInsert(stmt: PreparedStatement, v: Value, start: Int): Int

  def prepareUpdate(stmt: PreparedStatement, v: Value, start: Int): Int = prepareInsert(stmt, v, start)

  /** @return An estimate of the size of the data that would be generated by `csvifyForInsert` or `prepareInsert`.
    * @note This is only a ballpark estimate. */
  def estimateInsertSize(v: Value): Int

  /** @param sb The `StringBuilder` to which to add the data.
    * @param v The value to add; its type must be compatible with `representedType`.
    */
  def SETsForUpdate(sb: java.lang.StringBuilder, v: Value)

  /** @return An estimate of the size of the data that would be generated by `SETsForUpdate`.
    * @note This is only a ballpark estimate. */
  def estimateUpdateSize(v: Value): Int
}

trait SqlColumnRep[Type, Value] extends SqlColumnReadRep[Type, Value] with SqlColumnWriteRep[Type, Value] {
  override def asPKableRep: SqlPKableColumnRep[Type, Value] = sys.error("Not a PKable rep")
}

trait SqlPKableColumnReadRep[Type, Value] extends SqlColumnReadRep[Type, Value] with SqlOrderableColumnRep {
  /** Generates sql equivalent to "column in (?, ...)" where there are `n` placeholders to be filled in by
    * `prepareMultiLookup`.
    * @param n The number of values to prepare slots for.
    * @note `n` must be greater than zero.
    */
  def templateForMultiLookup(n: Int): String

  /** Fill in one placeholder in a template created by `prepareMultiLookup`.
    * @return The position of the first prepared statement parameter after this placeholder.
    */
  def prepareMultiLookup(stmt: PreparedStatement, v: Value, start: Int): Int

  /** Generates a SQL expression equivalent to "`column in (literals...)`".
    * @param literals The `StringBuilder` to which to add the data.  Must be non-empty.
    *                 The individual values' types must be equal to (not merely compatible with!)
    *                 `representedType`.
    * @return An expression suitable for splicing into a SQL statement.
    */
  def sql_in(literals: Iterable[Value]): String

  /** Generates SQL equivalent to "column = ?", where the placeholder can be filled
    * in by `prepareSingleLookup`
    */
  def templateForSingleLookup: String

  /** Fill in the placeholder in a template created by `prepareSingleLookup`.
    * @return The position of the first prepared statement parameter after this placeholder.
    */
  def prepareSingleLookup(stmt: PreparedStatement, v: Value, start: Int): Int

  /** Generates a SQL expression equivalent to "`column = literal`".
    * @param literal The `StringBuilder` to which to add the data.  The value's type
    *                must be equal to (not merely compatible with!) `representedType`.
    * @return An expression suitable for splicing into a SQL statement.
    */
  def sql_==(literal: Value): String

  /** Generates a SQL expression which can be used to create an index on this column suitable
    * for use in equality expressions. */
  def equalityIndexExpression: String

  override def isPKableRep = true

  override def asPKableRep: this.type = this
}

trait SqlPKableColumnRep[Type, Value] extends SqlColumnRep[Type, Value] with SqlPKableColumnReadRep[Type, Value]
