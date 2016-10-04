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
import com.normation.ldap.sdk.LDAPBoolean
import com.unboundid.ldap.sdk.Attribute

/**
 * Correctly quote a token
 */
object QSPattern {

  def apply(token: String) = s"""(?iums).*${Pattern.quote(token)}.*""".r.pattern
}

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
                       if(!at.isSystem && !dir.isSystem)
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

      val enableToken  = Set("true" , "enable" , "enabled" , "isenable" , "isenabled" )
      val disableToken = Set("false", "disable", "disabled", "isdisable", "isdisabled")

      def isEnabled =
        (if(at.isEnabled ) enableToken.map(t => (t, "Technique is enabled")) else disableToken.map(t => (t, "Technique is disabled"))) ++
        (if(dir.isEnabled) enableToken.map(t => (t, "Directive is enabled")) else disableToken.map(t => (t, "Directive is disabled")))

      /*
       * A set of value to check against / value to return to the user
       */
      val toMatch: Option[Set[(String,String)]] = a match {
        case QSDirectiveId     => Some(Set((dir.id.value,dir.id.value)))
        case DirectiveVarName  => Some(dir.parameters.flatMap(param => param._2.map(value => (param._1, param._1+":"+ value))).toSet)
        case DirectiveVarValue => Some(dir.parameters.flatMap(param => param._2.map(value => (value, param._1+":"+ value))).toSet)
        case TechniqueName     => Some(at.techniques.map { case (_,t) => (t.name, t.name) }.toSet)
        case TechniqueId       => Some(Set((at.techniqueName.toString,at.techniqueName.toString)))
        case TechniqueVersion  => Some(Set((dir.techniqueVersion.toString,dir.techniqueVersion.toString)))
        case Description       => Some(Set((dir.shortDescription,dir.shortDescription), (dir.longDescription,dir.longDescription)))
        case LongDescription   => Some(Set((dir.longDescription,dir.longDescription)))
        case Name              => Some(Set((dir.name,dir.name)))
        case IsEnabled         => Some(isEnabled)
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

      toMatch.flatMap { set => set.find{case (s,value) => QSPattern(token).matcher(s).matches } }.map{ case (_, value) =>
        QuickSearchResult(
            QRDirectiveId(dir.id.value)
          , dir.name
          , Some(a)
          , value
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
          , OR(attrFilter: _*)
        )
        // the ldap query part. It should be in a box, but the person who implemented ldap backend was
        // not really strict on the semantic
        val returnedAttributes = query.attributes.map( _.ldapName).toSeq ++ Seq(A_OC, A_UUID, A_PARAMETER_NAME, A_IS_SYSTEM) // the second group is always needed (id+test system)
        val entries = connection.search(nodeDit.BASE_DN, Sub, filter, returnedAttributes:_*)

        // transformat LDAPEntries to quicksearch results, keeping only the attribute
        // that matches the query on the result and no system entries but nodes.
        entries.flatMap( _.toResult(query.userToken))
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
      , TechniqueId       -> ""
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
  object QSAttributeLdapFilter {
    def sub(a: QSAttribute, token: String) = Some(SUB(a.ldapName, null, Array(token), null ))

    //use val to compile only one time these patterns
    val boolEnablePattern = {
      List("true", "enable", "enabled", "isenabled").map(QSPattern.apply)
    }

    //use val to compile only one time these patterns
    val boolDisablePattern = {
      List("false", "disable", "disabled", "isdisabled").map(QSPattern.apply)
    }

    val boolDynamicPattern = {
      List("true", "dynamic", "isdynamic").map(QSPattern.apply)
    }

    //use val to compile only one time these patterns
    val boolStaticPattern = {
      List("false", "static", "isstatic").map(QSPattern.apply)
    }

    trait Matcher {
      val isTrue :  String => Boolean
      val isFalse: String => Boolean
    }

    object MatchEnable extends Matcher {
        val isTrue  = (token: String) => boolEnablePattern.exists(_.matcher(token).matches)
        val isFalse = (token: String) => boolDisablePattern.exists(_.matcher(token).matches)
    }

    object MatchDynamic extends Matcher {
        val isTrue  = (token: String) => boolDynamicPattern.exists(_.matcher(token).matches)
        val isFalse = (token: String) => boolStaticPattern.exists(_.matcher(token).matches)
    }

    def bool(matcher: Matcher, a: QSAttribute, token: String) = {
      if(matcher.isTrue(token)) {
        Some(EQ(a.ldapName, LDAPBoolean(true ).toLDAPString))
      } else if(matcher.isFalse(token)) {
        Some(EQ(a.ldapName, LDAPBoolean(false).toLDAPString))
      } else {
        None
      }
    }
  }

  implicit class QSAttributeLdapFilter(a: QSAttribute) {
    import QSAttributeLdapFilter._

    def filter(token: String): Option[Filter] = {
      a match {
        case Name              => sub(a, token)
        case Description       => sub(a, token)
        case LongDescription   => sub(a, token)
        case NodeId            => sub(a, token)
        case Fqdn              => sub(a, token)
        case OsType            => sub(a, token)
        case OsName            => sub(a, token)
        case OsVersion         => sub(a, token)
        case OsFullName        => sub(a, token)
        case OsKernelVersion   => sub(a, token)
        case OsServicePack     => sub(a, token)
        case Arch              => sub(a, token)
        case Ram               => Some(EQ(a.ldapName, token)) //int doesn't have substring match - and its in kb :/
        case IpAddresses       => Some(EQ(a.ldapName, token)) //ipHostNumber doesn't have substring match :/
        case PolicyServerId    => sub(a, token)
        case Properties        => sub(a, token)
        case RudderRoles       => sub(a, token)
        case GroupId           => sub(a, token)
        case IsEnabled         => bool(MatchEnable, a, token)
        case IsDynamic         => bool(MatchDynamic, a, token)
        case DirectiveId       => None
        case DirectiveVarName  => None
        case DirectiveVarValue => None
        case TechniqueName     => None
        case TechniqueId       => None
        case TechniqueVersion  => None
        case ParameterName     => sub(a, token)
        case ParameterValue    => sub(a, token)
        case RuleId            => sub(a, token)
        case DirectiveIds      => sub(a, token)
        case Targets           => sub(a, token)
      }
    }
  }

  /**
   * Build LDAP filter for a QSObject
   */
  final implicit class QSObjectLDAPFilter(obj: QSObject)(implicit inventoryDit: InventoryDit, nodeDit: NodeDit, rudderDit: RudderDit) {

    def filter() = obj match {
      case Common    => Nil
      case Node      => (  AND(IS(OC_NODE)             , Filter.create(s"entryDN:dnOneLevelMatch:=${ inventoryDit.NODES.dn.toString      }"))
                        :: AND(IS(OC_RUDDER_NODE)      , Filter.create(s"entryDN:dnOneLevelMatch:=${ nodeDit.     NODES.dn.toString      }")) :: Nil )
      case Group     =>    AND(IS(OC_RUDDER_NODE_GROUP), Filter.create(s"entryDN:dnSubtreeMatch:=${  rudderDit.   GROUP.dn.toString      }")) :: Nil
      case Directive =>    Nil
      case Parameter =>    AND(IS(OC_PARAMETER        ), Filter.create(s"entryDN:dnOneLevelMatch:=${ rudderDit.   PARAMETERS.dn.toString }")) :: Nil
      case Rule      =>    AND(IS(OC_RULE)             , Filter.create(s"entryDN:dnSubtreeMatch:=${  rudderDit.   RULES.dn.toString      }")) :: Nil
    }
  }

  /**
   * correctly transform entry to a result, putting what is needed in type and description
   */
  final implicit class EntryToSearchResult(e: LDAPEntry) {
    import QuickSearchResultId._
    import QSAttributeLdapFilter._

    def toResult(token: String): Option[QuickSearchResult] = {
      def getId(e: LDAPEntry): Option[QuickSearchResultId] = {
        if       (e.isA(OC_NODE             )) { e(A_NODE_UUID      ).map( QRNodeId      )
        } else if(e.isA(OC_RUDDER_NODE      )) { e(A_NODE_UUID      ).map( QRNodeId      )
        } else if(e.isA(OC_RULE             )) { e(A_RULE_UUID      ).map( QRRuleId      )
        } else if(e.isA(OC_RUDDER_NODE_GROUP)) { e(A_NODE_GROUP_UUID).map( QRGroupId     )
        } else if(e.isA(OC_PARAMETER        )) { e(A_PARAMETER_NAME ).map( QRParameterId )
        //no directive
        } else { None
        }
      }

      /*
       * Depending of the attribute type, we must use a different
       * match methods.
       * Returns Option[(attribute name, matching value)]
       */
      def matchValue(a: Attribute, token: String, pattern: Pattern): Option[(String, String)] = {
        //test a matcher against values
        def findValue(test: String => Boolean): Option[(String, String)] = {
          a.getValues.find(test).map(v => (a.getName, v))
        }
        //test a list of pattern matcher against values
        def testAndFindValue(testers: List[String => Boolean]): Option[(String, String)] = {
          testers.find(f => f(token)).flatMap(findValue)
        }

        if(a.getName == A_OC) {
          None
        } else if(a.getName == A_IS_ENABLED) { // boolean "isEnabled"
          //we must be sure that it is the same matcher that maches the token and the
          //value, else we get a value for "disable" when ENABLE="true"
          testAndFindValue(MatchEnable.isTrue :: MatchEnable.isFalse :: Nil)
        } else if(a.getName == A_IS_DYNAMIC) { // boolean "isDynamic"
          testAndFindValue(MatchDynamic.isTrue :: MatchDynamic.isFalse :: Nil)
        } else {
          findValue(s => pattern.matcher(s).matches)
        }
      }

      def isNodeOrNotSystem(e: LDAPEntry): Boolean = {
        //if isSystem is absent, then the object is not system
        val isSystem = e.getAsBoolean(A_IS_SYSTEM).getOrElse(false)

        if(isSystem && (e.isA(OC_RULE) || e.isA(OC_RUDDER_NODE_GROUP) || e.isA(OC_PARAMETER))) false
        else true
      }

      // get the attribute value matching patterns
      // if several, take only one at random. If none, that's strange, reject entry
      // also, don't look in objectClass to find the pattern
      val pattern = QSPattern(token)
      for {
        (attr, desc) <- (Option.empty[(String, String)] /: e.attributes) { (current, next) => (current, next ) match {
                          case (Some(x), _) => Some(x)
                          case (None   , a) => matchValue(a, token, pattern)
                        } }
        id           <- getId(e)
       if(isNodeOrNotSystem(e))
      } yield {
        //prefer hostname for nodes
        val name = e(A_HOSTNAME).orElse(e(A_NAME)).getOrElse(id.value)

        QuickSearchResult(id, name, ldapNameMapping.get(attr), desc)
      }
    }
  }
}
