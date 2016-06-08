/*
*************************************************************************************
* Copyright 2016 Normation SAS
*************************************************************************************
*
* This file is part of Rudder.
*
* Rudder is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU General Public License version 3, the copyright holders add
* the following Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU General
* Public License version 3, when you create a Related Module, this
* Related Module is not considered as a part of the work and may be
* distributed under the license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* Rudder is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with Rudder.  If not, see <http://www.gnu.org/licenses/>.

*
*************************************************************************************
*/

package com.normation.rudder.services.quicksearch

import java.util.regex.Pattern

import com.normation.inventory.ldap.core.InventoryDit
import com.normation.inventory.ldap.core.LDAPConstants._
import com.normation.ldap.sdk.BuildFilter._
import com.normation.ldap.sdk.LDAPConnectionProvider
import com.normation.ldap.sdk.LDAPEntry
import com.normation.ldap.sdk.RoLDAPConnection
import com.normation.ldap.sdk.Sub

import com.normation.rudder.domain.NodeDit
import com.normation.rudder.domain.RudderDit
import com.normation.rudder.domain.RudderLDAPConstants._
import com.normation.rudder.repository.RoDirectiveRepository
import com.unboundid.ldap.sdk.Filter

import net.liftweb.common.Box


/**
 * This file contains the differents possible implementation of
 * quick search backends.
 *
 * Backend are able to transform a query in a set of
 * quicksearch results.
 *
 * For now, we have one for directive, and a different one
 * of everything else.
 */



object QSDirectiveBackend {


  /*
   * The filter that allows to know if a couple (activeTechnique, directive) match
   * the expected query
   */


  /**
   * Lookup directives
   */
  def search(query: Query)(implicit repo: RoDirectiveRepository): Box[Set[QuickSearchResult]] = {



    for {
      directiveLib <- repo.getFullDirectiveLibrary
    } yield {

      directiveLib.allDirectives.collect { case (id, (at, dir) ) =>
        ???
      }
    }

    ???
  }

}


/**
 * The whole LDAP backend logic: look for Nodes, NodeGroups, Parameters, Rules,
 * but not Directives.
 */
object QSLdapBackend {
  import QSAttribute._
  import QSObject._

  /**
   * The actual search logic, everything else is just glue to make it works.
   */
  def search(query: Query)(implicit
      ldap        : LDAPConnectionProvider[RoLDAPConnection]
    , inventoryDit: InventoryDit
    , nodeDit     : NodeDit
    , rudderDit   : RudderDit
  ): Box[Seq[QuickSearchResult]] = {
    for {
      connection  <- ldap
    } yield {
      //the filter for attribute and for attributes must be non empty, else return nothing
      val ocFilter   = query.objectClass.map( _.filter ).flatten.toSeq
      val attrFilter = query.attributes.map( _.filter(query.userToken) ).flatten.toSeq

      if(ocFilter.isEmpty || attrFilter.isEmpty) { // nothing to search for in that backend
        Seq()
      } else {
        val filter = AND(
            OR(ocFilter: _*)
        ,   OR(attrFilter: _*)
        )
        // the ldap query part. It should be in a box, but the person who implemented ldap backend was
        // not really strict on the semantic
        val returnedAttributes = query.attributes.map( _.ldapName).toSeq ++ Seq(A_OC, A_UUID, A_PARAMETER_NAME) // the second group is always needed (id)
        val entries = connection.search(nodeDit.BASE_DN, Sub, filter, returnedAttributes:_*)

        // transformat LDAPEntries to quicksearch results, keeping only the attribute
        // that matches the query on the result
        val keepAttribute = s"""(?i).*${query.userToken}.*""".r.pattern
        entries.flatMap( _.toResult(keepAttribute))
      }
    }
  }



  /**
   * Mapping between attribute and their ldap name
   */
  private[this] val attributeNameMapping: Map[QSAttribute, String] = {
     val m: Map[QSAttribute, String] = Map(
        Name              -> A_NAME
      , Description       -> A_DESCRIPTION
      , ShortDescription  -> A_DESCRIPTION
      , LongDescription   -> A_LONG_DESCRIPTION
      , IsEnabled         -> A_IS_ENABLED
      , NodeId            -> A_NODE_UUID
      , Fqdn              -> A_HOSTNAME
      , OsType            -> A_OC
      , OsName            -> A_OS_NAME
      , OsVersion         -> A_OS_VERSION
      , OsFullName        -> A_OS_FULL_NAME
      , OsKernelVersion   -> A_OS_KERNEL_VERSION
      , OsServicePack     -> A_OS_SERVICE_PACK
      , Arch              -> A_ARCH
      , Ram               -> A_OS_RAM
      , IpAddresses       -> A_LIST_OF_IP
      , PolicyServerId    -> A_POLICY_SERVER_UUID
      , Properties        -> A_NODE_PROPERTY
      , RudderRoles       -> A_SERVER_ROLE
      , GroupId           -> A_NODE_GROUP_UUID
      , IsDynamic         -> A_IS_DYNAMIC
      , DirectiveId       -> ""
      , DirectiveVarName  -> ""
      , DirectiveVarValue -> ""
      , TechniqueName     -> ""
      , TechniqueVersion  -> ""
      , ParameterName     -> A_PARAMETER_NAME
      , ParameterValue    -> A_PARAMETER_VALUE
    )

    if(m.size != QSAttribute.all.size) {
      throw new IllegalArgumentException("Be carefull, it seems that the list of attributes in QSAttribute was modified, but not the list of name mapping" +
          s"Please check for '${(m.keySet.diff(QSAttribute.all)++QSAttribute.all.diff(m.keySet)).mkString("', '")}'"
      )
    }
    m
  }

  private[this] val ldapNameMapping = attributeNameMapping.map { case(k,v) => (v, k) }.toMap.filterKeys( _ != "")

  implicit class QSAttributeLdapName(a: QSAttribute) {
    def ldapName(): String = attributeNameMapping(a)
  }

  /**
   * Mapping between attributes and their filter in the backend
   */
  implicit class QSAttributeLdapFilter(a: QSAttribute) {
    def filter(token: String): Option[Filter] = a match {
      case Name              => Some(SUB(a.ldapName, null, Array(token), null ))
      case Description       => Some(SUB(a.ldapName, null, Array(token), null ))
      case ShortDescription  => Some(SUB(a.ldapName, null, Array(token), null ))
      case LongDescription   => Some(SUB(a.ldapName, null, Array(token), null ))
      case IsEnabled         => Some(SUB(a.ldapName, null, Array(token), null ))
      case NodeId            => Some(SUB(a.ldapName, null, Array(token), null ))
      case Fqdn              => Some(SUB(a.ldapName, null, Array(token), null ))
      case OsType            => Some(EQ(a.ldapName, token+"Node")) // objectClass=linuxNode etc
      case OsName            => Some(SUB(a.ldapName, null, Array(token), null ))
      case OsVersion         => Some(SUB(a.ldapName, null, Array(token), null ))
      case OsFullName        => Some(SUB(a.ldapName, null, Array(token), null ))
      case OsKernelVersion   => Some(SUB(a.ldapName, null, Array(token), null ))
      case OsServicePack     => Some(SUB(a.ldapName, null, Array(token), null ))
      case Arch              => Some(SUB(a.ldapName, null, Array(token), null ))
      case Ram               => Some(SUB(a.ldapName, null, Array(token), null ))
      case IpAddresses       => Some(SUB(a.ldapName, null, Array(token), null ))
      case PolicyServerId    => Some(SUB(a.ldapName, null, Array(token), null ))
      case Properties        => Some(SUB(a.ldapName, null, Array(token), null ))
      case RudderRoles       => Some(SUB(a.ldapName, null, Array(token), null ))
      case GroupId           => Some(SUB(a.ldapName, null, Array(token), null ))
      case IsDynamic         => Some(SUB(a.ldapName, null, Array(token), null ))
      case DirectiveId       => None
      case DirectiveVarName  => None
      case DirectiveVarValue => None
      case TechniqueName     => None
      case TechniqueVersion  => None
      case ParameterName     => Some(SUB(a.ldapName, null, Array(token), null ))
      case ParameterValue    => Some(SUB(a.ldapName, null, Array(token), null ))
    }
  }

  /**
   * Build LDAP filter for a QSObject
   */
  final implicit class QSObjectLDAPFilter(obj: QSObject)(implicit inventoryDit: InventoryDit, nodeDit: NodeDit, rudderDit: RudderDit) {

    def filter() = obj match {
      case Node      => (  AND(IS(OC_NODE)             , Filter.create(s"entryDN:dnOneLevelMatch:=${ inventoryDit.NODES.dn.toString                }"))
                        :: AND(IS(OC_RUDDER_NODE)      , Filter.create(s"entryDN:dnOneLevelMatch:=${ nodeDit.     NODES.dn.toString                }")) :: Nil )
      case Group     =>    AND(IS(OC_RUDDER_NODE_GROUP), Filter.create(s"entryDN:dnSubtreeMatch:=${  rudderDit.   GROUP.dn.toString                }")) :: Nil
      case Directive =>    Nil
      case Parameter =>    AND(IS(OC_PARAMETER        ), Filter.create(s"entryDN:dnOneLevelMatch:=${ rudderDit.   PARAMETERS.dn.toString           }")) :: Nil
      case Rule      =>    AND(IS(OC_RULE)             , Filter.create(s"entryDN:dnSubtreeMatch:=${  rudderDit.   RULES.dn.toString                }")) :: Nil
    }
  }



  /**
   * correctly transform entry to a result, putting what is needed in type and description
   */
  final implicit class EntryToSearchResult(e: LDAPEntry) {
    import QuickSearchResultId._
    def toResult(pattern: Pattern): Option[QuickSearchResult] = {
      def getId(e: LDAPEntry): Option[QuickSearchResultId] = {
        if       (e.isA(OC_NODE             )) { e(A_NODE_UUID      ).map( QRNodeId(_)      )
        } else if(e.isA(OC_RUDDER_NODE      )) { e(A_NODE_UUID      ).map( QRNodeId      )
        } else if(e.isA(OC_RULE             )) { e(A_RULE_UUID      ).map( QRRuleId      )
        } else if(e.isA(OC_RUDDER_NODE_GROUP)) { e(A_NODE_GROUP_UUID).map( QRGroupId     )
        } else if(e.isA(OC_PARAMETER        )) { e(A_PARAMETER_NAME ).map( QRParameterId )
        //no directive
        } else { None
        }
      }

      // get the attribute value matching patters
      // if several, take only one at random. If none, that's strange, reject entry
      // also, don't look in objectClass to find the pattern
      for {
        (attr, desc) <- (Option.empty[(String, String)] /: e.attributes) { (current, next) => (current, next ) match {
                          case (Some(x), _) => Some(x)
                          case (None   , a) => if(a.getName == A_OC) {
                                              None
                                            } else {
                                              a.getValues.find(v => pattern.matcher(v).matches).map(v => (a.getName, v))
                                            }
                        } }
        id           <- getId(e)
      } yield {
        //prefer hostname for nodes
        val name = e(A_HOSTNAME).orElse(e(A_NAME)).getOrElse(id.value)

        QuickSearchResult(id, name, ldapNameMapping.get(attr), desc)
      }
    }
  }
}




