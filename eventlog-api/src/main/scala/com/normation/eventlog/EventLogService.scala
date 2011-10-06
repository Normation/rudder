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

package com.normation.eventlog

import java.security.Principal
import net.liftweb.common.Box
/**
 * This class defines the main methods to save the EventLog
 * @author Nicolas CHARLES
 *
 */
trait EventLogService {

  /**
   * Save an entry 
   */
  def saveEventLog(entry : EventLog) : Box[EventLog]
  
  /**
   * Save several entries at once. This is especially useful when saving a 
   * batch of operations depending each from another.
   * This is really the only way to save the entrylog along with their causes
   */
  def saveEventLogs(entries : EventLogNode) : Box[Seq[EventLog]]
  
}


trait GetEventLogService {

  /**
   * Return an eventLog by its id
   * @param id
   * @return
   */
  def getEventLogById(id : Int) : Box[EventLog]

}