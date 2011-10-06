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
package impl

import org.joda.time.DateTime

/**
 * A version of history log which uses DateTime as
 * version number. 
 *
 */
trait DatedHistoryLog[ID,T] extends HistoryLog[ID, DateTime, T] {
  
  def datetime = version
  
}


case class DefaultHLog[ID,T](
  id : ID,
  version: DateTime,
  data : T
) extends DatedHistoryLog[ID,T] {
  override val datetime = version
  override val historyType = "default"
}

