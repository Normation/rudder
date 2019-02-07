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

import org.joda.time.DateTime

/**
 * Represent history data.
 * The actual data type is let to be defined.
 */
trait HistoryLog[ID,V, T] {

  /**
   * Id of the history log.
   * One log has seceral version for
   * only one ID (the couple (ID,version) is unique)
   */
  def id:ID

  /**
   * Date and Time for which the data are saved
   * @return
   */
  def datetime : DateTime

  /**
   * History type, should be linked to T
   */
  def historyType : String

  /**
   * Version of the history
   * Version must be comparable, but du to
   * Inconsistencies between libraries and APIs,
   * we are not able to provide a type constrain.
   * bigger are newer
   * @return
   */
  def version:V

  /**
   * Actual data saved in the history
   * @return
   */
  def data : T

}