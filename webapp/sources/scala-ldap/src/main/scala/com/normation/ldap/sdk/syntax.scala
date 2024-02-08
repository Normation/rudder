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

import com.unboundid.ldap.sdk
import com.unboundid.ldap.sdk.{Entry => UEntry}
import com.unboundid.ldap.sdk.{LDAPConnection => ULDAPConnection}
import com.unboundid.ldap.sdk.{LDAPResult => ULDAPResult}
import com.unboundid.ldap.sdk.DN

object syntax {

  /**
   * Alias Unboundid.ldap.sdk.Entry and LDAPConnection to UnboundXXX so that the main
   * "entry" type is our own.
   */
  type UnboundidEntry          = UEntry
  type UnboundidLDAPConnection = ULDAPConnection
  // that seems to be needed to be able to create an object LDAPResult
  // without having naming clashes
  type LDAPResult              = ULDAPResult

  implicit class SearchScopeTonbound(s: SearchScope) {
    def toUnboundid: sdk.SearchScope = {
      import com.unboundid.ldap.sdk.SearchScope._
      s match {
        case One                => ONE
        case Base               => BASE
        case Sub                => SUB
        case SubordinateSubtree => SUBORDINATE_SUBTREE
      }
    }
  }

  implicit class BooleanToLdapString(b: Boolean) {
    def toLDAPString: String = LDAPBoolean(b).toLDAPString
  }

  /*
   * Define the total order on DN
   * /!\ /!\ Be careful, the comparison is meaningful only for
   * complete DN, base included.
   *
   * The order is :
   * empty RDN is always the smaller than any other RDN
   * if the two DN have different base (size does not matter)
   * - compare alphabetically the rdn attribute name, lower first
   *   i.e: o=com > dc=org
   * - if equals, compare alphabetically the rdn attribute value, lower first
   *   i.e: dc=com < dc=org
   * else :
   * - lower size first, i.e: dc=foo,dc=com < dc=bar,dc=foo,dc=com
   * - at same depth, alphabetical order of the rdn attribute name
   *   (lower first), ie: ou=bar,BASE > o=plop,BASE
   * - at same rdn attribute name, alphabetical order of the value
   *   (lower first), ie: ou=foo,BASE > ou=bar,BASE
   */

  implicit val DnOrdering: Ordering[DN] = new Ordering[DN] {
    def compare(x: DN, y: DN): Int = x.compareTo(y)
  }

}
