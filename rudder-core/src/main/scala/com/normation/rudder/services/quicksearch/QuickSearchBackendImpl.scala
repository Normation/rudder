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
import net.liftweb.common.Full


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
  import com.normation.rudder.repository.FullActiveTechnique
  import com.normation.rudder.domain.policies.Directive
  import com.normation.rudder.domain.policies.DirectiveId
  import QSObject.{Directive => QSDirective }
  import QSAttribute.{ DirectiveId => QSDirectiveId, _ }
  import QuickSearchResultId.QRDirectiveId

  /*
   * The filter that allows to know if a couple (activeTechnique, directive) match
   * the expected query
   */


  /**
   * Lookup directives
   */
  def search(query: Query)(implicit repo: RoDirectiveRepository): Box[Set[QuickSearchResult]] = {

    // only search if query is on Directives and attributes contains
    // DirectiveId, DirectiveVarName, DirectiveVarValue, TechniqueName, TechniqueVersion

    val attributes: Set[QSAttribute] = query.attributes.intersect(QSObject.Directive.attributes)

    if(query.objectClass.contains(QSDirective) && attributes.nonEmpty) {
      for {
        directiveLib <- repo.getFullDirectiveLibrary
      } yield {
        (for {
          (at, dir) <- directiveLib.allDirectives.values
          attribute <- attributes
        } yield {
          attribute.find(at, dir, query.userToken)
        }).flatten.toSet
      }
    } else {
      Full(Set())
    }
  }

  implicit class QSAttributeFilter(a: QSAttribute) {
    def find(at: FullActiveTechnique, dir: Directive, token: String): Option[QuickSearchResult] = {

      val pattern = s""".*${Pattern.quote(token)}.*""".r.pattern

      val toMatch: Option[Set[String]] = a match {
        case QSDirectiveId     => Some(Set(dir.id.value))
        case DirectiveVarName  => Some(dir.parameters.keySet)
        case DirectiveVarValue => Some(dir.parameters.values.flatten.toSet)
        case TechniqueName     => Some(Set(at.techniqueName.toString))
        case TechniqueVersion  => Some(Set(dir.techniqueVersion.toString))
        case Description       => Some(Set(dir.shortDescription, dir.longDescription))
        case ShortDescription  => Some(Set(dir.shortDescription))
        case LongDescription   => Some(Set(dir.longDescription))
        case Name              => Some(Set(dir.name))
        case IsEnabled         => None
        case NodeId            => None
        case Fqdn              => None
        case OsType            => None
        case OsName            => None
        case OsVersion         => None
        case OsFullName        => None
        case OsKernelVersion   => None
        case OsServicePack     => None
        case Arch              => None
        case Ram               => None
        case IpAddresses       => None
        case PolicyServerId    => None
        case Properties        => None
        case RudderRoles       => None
        case GroupId           => None
        case IsDynamic         => None
        case ParameterName     => None
        case ParameterValue    => None
        case RuleId            => None
        case DirectiveIds      => None
        case Targets           => None
      }

      toMatch.flatMap { set => set.find( s => pattern.matcher(s).matches) }.map{ s =>
        QuickSearchResult(
            QRDirectiveId(dir.id.value)
          , dir.name
          , Some(a)
          , s
        )
      }
    }
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
        val keepAttribute = s"""(?i).*${Pattern.quote(query.userToken)}.*""".r.pattern
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
      , RuleId            -> A_RULE_UUID
      , DirectiveIds      -> A_DIRECTIVE_UUID
      , Targets           -> A_RULE_TARGET
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
    def filter(token: String): Option[Filter] = {
      def sub() = Some(SUB(a.ldapName, null, Array(token), null ))
      a match {
        case Name              => sub
        case Description       => sub
        case ShortDescription  => sub
        case LongDescription   => sub
        case IsEnabled         => sub
        case NodeId            => sub
        case Fqdn              => sub
        case OsType            => Some(EQ(a.ldapName, token+"Node")) // objectClass=linuxNode etc
        case OsName            => sub
        case OsVersion         => sub
        case OsFullName        => sub
        case OsKernelVersion   => sub
        case OsServicePack     => sub
        case Arch              => sub
        case Ram               => sub
        case IpAddresses       => sub
        case PolicyServerId    => sub
        case Properties        => sub
        case RudderRoles       => sub
        case GroupId           => sub
        case IsDynamic         => sub
        case DirectiveId       => None
        case DirectiveVarName  => None
        case DirectiveVarValue => None
        case TechniqueName     => None
        case TechniqueVersion  => None
        case ParameterName     => sub
        case ParameterValue    => sub
        case RuleId            => sub
        case DirectiveIds      => sub
        case Targets           => sub
      }
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




