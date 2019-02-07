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

package com.normation.ldap.sdk

/**
 * An utility type for Boolean format in
 * OpenLDAP directory.
 * It simply knows how to print itself to a
 * (strictly) compatible string format.
 */
trait LDAPBoolean {

  /**
   * Give the correct string for
   * LDAP.
   * It can be used directly in place of
   * toString for implicit conversion.
   * For ex, use:
   * true.toLDAPString
   */
  def toLDAPString : String
  override def toString = toLDAPString
}

object LDAPBoolean {
  def apply(b:Boolean) = if(b) TRUE else FALSE
}

case object TRUE extends LDAPBoolean {
  override def toLDAPString = "TRUE"
}

case object FALSE extends LDAPBoolean {
  override def toLDAPString = "FALSE"
}
