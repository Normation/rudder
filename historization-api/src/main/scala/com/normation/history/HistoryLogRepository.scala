/*
*************************************************************************************
* Copyright 2011 Normation SAS
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

package com.normation.history

import net.liftweb.common.Box
import org.joda.time.DateTime

trait WriteOnlyHistoryLogRepository[ID, V, T, HLog <: HistoryLog[ID,V, T]] {

  /**
   * Save a report and return the ID of the saved report, and
   * it's version
   * @param historyLog
   * @return
   */
  def save(id:ID,data:T,datetime:DateTime = DateTime.now) : Box[HLog]

}

trait ReadOnlyHistoryLogRepository[ID, V, T, HLog <: HistoryLog[ID,V, T]] {

  /**
   * Retrieve all ids known by the repository
   */
  def getIds : Box[Seq[ID]]

  /**
   * Get the list of record for the given ID
   * @return
   *   Failure(message) if an error happened
   *   Empty if a not specified error happened
   *   Full(seq) if that id exists. Seq may be empty
   *     if no version are available.
   */
  def getAll(id:ID) : Box[Seq[HLog]]

  /**
   * Get the last record for the given ID.
   * @return
   *   Failure(message) or Empty if an error happened, or
   *     if the id does not exists or has no recorded history
   *   Full(hlog) the last recorded version of hlog
   */
  def getLast(id:ID) : Box[HLog]

  /**
   * Get the last record for the given ID and version.
   * @return
   *   Failure(message) or Empty if an error happened, or
   *     if the id does not exists or has no such version in
   *     recorded history
   *   Full(hlog) the recorded version of hlog
   */
  def get(id:ID, version:V) : Box[HLog]


  /**
   * Return the list of version for ID.
   * @return
   *   Failure(message) if an error happened
   *   Empty if a not specified error happened
   *   Full(seq) if that id exists. Seq may be empty
   *     if no version are available.
   *     Seq is sorted with last version (most recent) first
   *     and so ( versions.head > versions.head.head )
   */
  def versions(id:ID) : Box[Seq[V]]
}

trait HistoryLogRepository[ID, V, T, HLog <: HistoryLog[ID,V, T]] extends
  WriteOnlyHistoryLogRepository[ID, V, T, HLog] with
  ReadOnlyHistoryLogRepository[ID, V, T, HLog] {}
