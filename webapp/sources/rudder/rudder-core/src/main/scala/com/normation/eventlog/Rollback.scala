/*
 *************************************************************************************
 * Copyright 2026 Normation SAS
 *************************************************************************************
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 *************************************************************************************
 */

package com.normation.eventlog

import com.normation.rudder.git.GitCommitId
import enumeratum.*
import io.scalaland.chimney.Transformer
import io.scalaland.chimney.syntax.*
import zio.json.enumeratum.EnumCodec

/**
 * The state an event log is rolled back to.
 * Serialization is in lowercase in the eventlog details
 * Deserialization is in API
 */
sealed trait RollbackPosition(override val entryName: String) extends EnumEntry {
  def serialize: String = entryName
}

object RollbackPosition extends Enum[RollbackPosition] with EnumCodec[RollbackPosition] {
  case object Before extends RollbackPosition("before")
  case object After  extends RollbackPosition("after")

  override def values: IndexedSeq[RollbackPosition] = findValues
}

/**
 * ID of event log that is rolled back.
 *
 * Same underlying type as event log ID
 */

opaque type RollbackEventId = Long
object RollbackEventId {
  def apply(eventLogId: Long): RollbackEventId = eventLogId
  extension (self:      RollbackEventId) {
    def eventLogId: Long = self
  }
}

/**
 * The rollback of item needs a commit, which is the target we want to restore them to.
 */
enum RollbackTarget(val archiveCommit: GitCommitId) {
  case Before(override val archiveCommit: GitCommitId) extends RollbackTarget(archiveCommit)
  case After(override val archiveCommit: GitCommitId)  extends RollbackTarget(archiveCommit)
}

object RollbackTarget {
  given Transformer[RollbackTarget, RollbackPosition] = Transformer.derive[RollbackTarget, RollbackPosition]

  extension (self: RollbackTarget) {
    def rollbackPosition: RollbackPosition = self.transformInto[RollbackPosition]
  }

  def from(rollbackPosition: RollbackPosition, current: GitCommitId): RollbackTarget = rollbackPosition match {
    // the state just *before* a change is the parent of the commit that change led to
    case RollbackPosition.Before => Before(GitCommitId(current.value + "^"))
    case RollbackPosition.After  => After(current)
  }

}
