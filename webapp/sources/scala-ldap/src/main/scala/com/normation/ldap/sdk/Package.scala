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

package com.normation.ldap
import com.normation.exceptions.TechnicalException
import com.unboundid.ldap.sdk.{
  DN,RDN,Filter,
  Entry => UEntry,
  LDAPConnection => ULDAPConnection,
  LDAPResult => ULDAPResult
}

package object sdk {

  import scala.language.implicitConversions

  /**
   * Alias Unboundid.ldap.sdk.Entry and LDAPConnection to UnboundXXX so that the main
   * "entry" type is our own.
   */
  type UnboundidEntry = UEntry
  type UnboundidLDAPConnection = ULDAPConnection
  //that seems to be needed to be able to create an object LDAPResult
  //without having naming clashes
  type LDAPResult = ULDAPResult

  //we create an instance of RDN, used as a singleton object to access its static methods
  //val RDN = new com.unboundid.ldap.sdk.RDN("invalid=attributeName")

  //implicit conversion between an LDAP SDK entry and our enhanced one
  implicit def unboundidEntry2ScalaEntry(e:UnboundidEntry) : LDAPEntry = LDAPEntry(e)

  implicit def unboundidEntry2LDAPTree(e:UnboundidEntry) : LDAPTree = LDAPTree(LDAPEntry(e))

  implicit def string2dn(dn:String) = new DN(dn)

  implicit def dn2listRdn(dn:DN) = dn.getRDNs.toList
  implicit def listRdn2dn(rdns:List[RDN]) = new DN(rdns.toSeq:_*)

  implicit def searchScopeImplicit(s:SearchScope) : com.unboundid.ldap.sdk.SearchScope = {
    import com.unboundid.ldap.sdk.SearchScope._
    s match {
      case One => ONE
      case Base => BASE
      case Sub => SUB
      case SubordinateSubtree => SUBORDINATE_SUBTREE
    }
  }

  implicit def string2SearchScope(scope:String) : SearchScope = {
    scope.toLowerCase match {
      case "one" => One
      case "sub" | "subtree" => Sub
      case "base" => Base
      case "subordinatesubtree" => SubordinateSubtree
      case x => throw new TechnicalException("Can not recognize %s search scope. Possible values are one, sub, base, subordinateSubtree")
    }
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

  implicit val DnOrdering : Ordering[DN] = new Ordering[DN] {
    def compare(x:DN,y:DN) : Int = x.compareTo(y)
  }

  implicit def boolean2LDAP(b:Boolean) : LDAPBoolean = LDAPBoolean(b)
}
