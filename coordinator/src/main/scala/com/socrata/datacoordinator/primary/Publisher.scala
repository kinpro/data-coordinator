package com.socrata.datacoordinator.primary

import com.rojoma.simplearm.Managed

import com.socrata.datacoordinator.truth.metadata.UnanchoredCopyInfo
import com.socrata.datacoordinator.truth.universe.{Universe, DatasetMutatorProvider}
import com.socrata.datacoordinator.id.DatasetId

class Publisher(universe: Managed[Universe[_, _] with DatasetMutatorProvider]) extends ExistingDatasetMutator {
  def publish(dataset: DatasetId, snapshotsToKeep: Option[Int], username: String): UnanchoredCopyInfo = {
    finish(dataset) {
      for {
        u <- universe
        ctxOpt <- u.datasetMutator.publishCopy(as = username)(dataset, snapshotsToKeep, _ => ())
      } yield ctxOpt match {
        case u.datasetMutator.CopyOperationComplete(ctx) =>
          Some(ctx.copyInfo.unanchored)
        case _ =>
          None
      }
    }
  }
}
