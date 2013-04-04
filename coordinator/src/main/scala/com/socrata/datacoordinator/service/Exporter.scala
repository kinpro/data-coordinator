package com.socrata.datacoordinator
package service

import com.socrata.datacoordinator.truth.universe.{DatasetReaderProvider, Universe}
import com.socrata.datacoordinator.util.collection.ColumnIdMap
import com.socrata.datacoordinator.truth.metadata.{CopyInfo, ColumnInfo}
import com.socrata.soql.environment.ColumnName
import com.socrata.datacoordinator.truth.CopySelector

object Exporter {
  def export[CT, CV, T](u: Universe[CT, CV] with DatasetReaderProvider, id: String, copy: CopySelector, columns: Option[Set[ColumnName]], limit: Option[Long], offset: Option[Long])(f: (CopyInfo, ColumnIdMap[ColumnInfo], Iterator[Row[CV]]) => T): Option[T] = {
    for {
      ctxOpt <- u.datasetReader.openDataset(id, copy)
      ctx <- ctxOpt
    } yield {
      import ctx. _

      val selectedSchema = columns match {
        case Some(set) => schema.filter { case (_, ci) => set(ci.logicalName) }
        case None => schema
      }

      withRows(selectedSchema.keySet, limit, offset) { it =>
        f(copyInfo, selectedSchema, it)
      }
    }
  }
}
