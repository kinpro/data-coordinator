package com.socrata.datacoordinator
package truth.loader

import com.socrata.datacoordinator.truth.metadata.{CopyInfo, ColumnInfo}
import com.socrata.datacoordinator.id.RowId

class NullLogger[CT, CV] extends Logger[CT, CV] {
  def columnCreated(info: ColumnInfo[CT]) {}

  def columnRemoved(info: ColumnInfo[CT]) {}

  def rowIdentifierSet(info: ColumnInfo[CT]) {}

  def rowIdentifierCleared(info: ColumnInfo[CT]) {}

  def systemIdColumnSet(info: ColumnInfo[CT]) {}

  def workingCopyCreated(info: CopyInfo) {}

  def dataCopied() {}

  def snapshotDropped(info: CopyInfo) {}

  def workingCopyDropped() {}

  def workingCopyPublished() {}

  def endTransaction() = None

  def insert(systemID: RowId, row: Row[CV]) {}

  def update(sid: RowId, oldRow: Option[Row[CV]], newRow: Row[CV]) {}

  def delete(systemID: RowId, oldRow: Option[Row[CV]]) {}

  def counterUpdated(nextCtr: Long) {}

  def close() {}

  def truncated() {}

  def versionColumnSet(info: ColumnInfo[CT]) {}
}

object NullLogger extends NullLogger[Any, Any] {
  def apply[A, B] = this.asInstanceOf[NullLogger[A, B]]
}
