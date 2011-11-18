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

import scala.collection._
import com.normation.utils.HashcodeCaching

/**
 * The class that hold the hierarchies of the event log for serialisation only
 * Use :
 * Create the EventLogTree with a root cause
 * Create eventual children EventLogNode, and add them to the root cause
 * Repeat as necessary for root and children
 * Serialize the tree
 * 
 */
case class EventLogNode(val entry : EventLog, val children : Seq[EventLogNode] = Seq[EventLogNode]()) extends HashcodeCaching 

 