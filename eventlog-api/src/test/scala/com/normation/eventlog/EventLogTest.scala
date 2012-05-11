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

import org.junit.Test;
import org.junit._
import org.junit.Assert._

import net.liftweb.common._
import scala.xml._
import junit.framework.TestSuite
import org.junit.runner.RunWith
import org.junit.runners.BlockJUnit4ClassRunner
import org.joda.time.DateTime
import java.security.Principal
import scala.collection._
import scala.collection.JavaConversions._ 
/**
 * Example for manipulations of EventLogs and EventLogSpecializer
 * @author Nicolas CHARLES
 */
@RunWith(classOf[BlockJUnit4ClassRunner])
class EventLogTest {
  
  /**
   * Create a simple entry, and check that it works
   */
  @Test
  def createEventLog() {
    val dummyEventLog = new EventLogWithValues(
        EventLogDetails(None, EventActor("foo"),DateTime.now(), None, 0, None, NodeSeq.Empty )
      , "12"
      , "13")
      
    val eventLog = new EventLogWithValues(
        EventLogDetails(Some(13), EventActor("foo"),DateTime.now(), None, 0, None, NodeSeq.Empty ) 
      , "bar"
      , "baz")
    
    assertEquals(eventLog.id, Some(13))
    assertEquals(eventLog.principal.name, "foo")
    assertEquals(eventLog.cause, None)
    assertEquals(eventLog.firstValue, "bar")
    assertEquals(eventLog.secondValue, "baz")
    
  }
  
}