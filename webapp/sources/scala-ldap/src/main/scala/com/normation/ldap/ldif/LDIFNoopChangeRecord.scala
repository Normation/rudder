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

import com.unboundid.ldap.sdk.ChangeType
import com.unboundid.ldap.sdk.Control
import com.unboundid.ldap.sdk.DN
import com.unboundid.ldap.sdk.LDAPInterface
import com.unboundid.ldap.sdk.LDAPResult
import com.unboundid.ldap.sdk.ResultCode
import com.unboundid.ldif.LDIFChangeRecord
import com.unboundid.util.ByteStringBuffer

final case class LDIFNoopChangeRecord(dn: DN) extends LDIFChangeRecord(dn.toString, null) {

  override def processChange(con: LDAPInterface, includeControle: Boolean) = new LDAPResult(0, ResultCode.NO_OPERATION)

  override val getChangeType = ChangeType.MODIFY

  override def toLDIF(buffer: ByteStringBuffer, wrapColumn: Int): Unit = {}

  override def toLDIF(i: Int): Array[String] = Array()

  override def toLDIFString(buffer: java.lang.StringBuilder, wrapColumn: Int): Unit = {}

  override def toString(buffer: java.lang.StringBuilder): Unit = {
    buffer.append("NoopChangeRecord:").append(dn.toString)
    () // unit is expected
  }

  override def duplicate(controls: Control*): LDIFChangeRecord = LDIFNoopChangeRecord(dn)
}
