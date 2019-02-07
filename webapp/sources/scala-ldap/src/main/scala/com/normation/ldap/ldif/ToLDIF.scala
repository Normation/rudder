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

package com.normation.ldap.ldif


/**
 * Representation of objects as LDIF string
 * or records
 */

import com.unboundid.ldif.LDIFRecord
import java.lang.StringBuilder

/**
 * Get an LDIF String reprensentation of the object
 */
trait ToLDIFString {

  /**
   * Add the LDIF string representation of the object
   * to the given StringBuild
   */
  def toLDIFString(sb:StringBuilder) : Unit

  /**
   * Get the LDIF string representation of the object
   */
  def toLDIFString() : String = {
    val sb = new StringBuilder
    this.toLDIFString(sb)
    sb.toString
  }
}

trait ToLDIFRecord {
  /**
   * Get the object as an LDIFRecord
   */
  def toLDIFRecord(): LDIFRecord
}

trait ToLDIFRecords extends ToLDIFString {

  /**
   * Get the object as list of LDIFRecords
   */
  def toLDIFRecords(): Seq[LDIFRecord]

  override def toLDIFString(sb:StringBuilder) : Unit = {
    this.toLDIFRecords.foreach { r =>
      r.toLDIFString(sb)
    }
  }

}