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

import com.normation.errors.IOResult
import com.normation.inventory.ldap.core.LDAPConstants.*
import com.normation.ldap.sdk.BuildFilter.*
import com.normation.ldap.sdk.LDAPBoolean
import com.normation.ldap.sdk.LDAPConnectionProvider
import com.normation.ldap.sdk.LDAPEntry
import com.normation.ldap.sdk.RoLDAPConnection
import com.normation.ldap.sdk.Sub
import com.normation.rudder.domain.RudderDit
import com.normation.rudder.domain.RudderLDAPConstants.*
import com.normation.rudder.domain.policies.Tag
import com.normation.rudder.domain.policies.TagName
import com.normation.rudder.domain.policies.Tags as TAGS
import com.normation.rudder.domain.policies.TagValue
import com.normation.rudder.domain.properties.NodeProperty
import com.normation.rudder.facts.nodes.CoreNodeFact
import com.normation.rudder.facts.nodes.MinimalNodeFactInterface
import com.normation.rudder.facts.nodes.NodeFactRepository
import com.normation.rudder.facts.nodes.QueryContext
import com.normation.rudder.ncf.EditorTechnique
import com.normation.rudder.ncf.EditorTechniqueReader
import com.normation.rudder.ncf.MethodBlock
import com.normation.rudder.ncf.MethodCall
import com.normation.rudder.ncf.MethodElem
import com.normation.rudder.repository.RoDirectiveRepository
import com.unboundid.ldap.sdk.Attribute
import com.unboundid.ldap.sdk.Filter
import java.util.regex.Pattern
import net.liftweb.common.Loggable
import scala.util.control.NonFatal
import zio.json.*
import zio.syntax.*

/**
 * Correctly quote a token
 */
object QSPattern {
  def apply(token: String): Pattern = s"""(?iums).*${Pattern.quote(token)}.*""".r.pattern
}

/**
 * This file contains the different possible implementations of
 * quick search backends.
 *
 * Backend are able to transform a query in a set of
 * quicksearch results.
 *
 * For now, we have one for nodefacts, one for directive, and a different one
 * of everything else.
 */
object QSNodeFactBackend extends Loggable {
  import QSAttribute.{NodeId as QSNodeId, *}
  import QSObject.Node as QSNode
  import QuickSearchResultId.QRNodeId
  import zio.syntax.*

  /*
   * The filter that allows to know if a couple (activeTechnique, directive) match
   * the expected query
   */

  /**
   * Lookup directives
   */
  def search(query: Query)(implicit repo: NodeFactRepository, qc: QueryContext): IOResult[Seq[QuickSearchResult]] = {

    // only search if query is on Directives and attributes contains
    // DirectiveId, DirectiveVarName, DirectiveVarValue, TechniqueName, TechniqueVersion

    val attributes: Set[QSAttribute] = query.attributes.intersect(QSObject.Node.attributes)

    if (query.objectClass.contains(QSNode) && attributes.nonEmpty) {
      repo
        .getAll()
        .map(_.flatMap { case (_, n) => attributes.flatMap(a => a.find(n, query.userToken)) }.toSeq)
    } else {
      Seq().succeed
    }
  }

  implicit class QSAttributeFilter(val a: QSAttribute) extends AnyVal {

    private def toMatch(node: CoreNodeFact): Option[Set[(String, String)]] = {

      def someSet(v: String)         = Some(Set((v, v)))
      def optSet(v:  Option[String]) = v.map(x => Set((x, x)))

      /*
       * A set of value to check against / value to return to the user
       */
      a match {
        case QSNodeId             => someSet(node.id.value)
        case Fqdn                 => someSet(node.fqdn)
        case OsType               => someSet(node.os.os.kernelName)
        case OsName               => someSet(node.os.os.name)
        case OsVersion            => someSet(node.os.version.value)
        case OsFullName           => someSet(node.os.fullName)
        case OsKernelVersion      => someSet(node.os.kernelVersion.value)
        case OsServicePack        => optSet(node.os.servicePack)
        case Arch                 => optSet(node.archDescription)
        case Ram                  => optSet(node.ram.map(_.size.toString))
        case IpAddresses          => Some(MinimalNodeFactInterface.ipAddresses(node).map(ip => (ip, ip)).toSet)
        case PolicyServerId       => someSet(node.rudderSettings.policyServerId.value)
        case Properties           =>
          Some(node.properties.collect {
            case p if (p.provider != Some(NodeProperty.customPropertyProvider)) => (p.toData, p.toData)
          }.toSet)
        case GroupProperties      => None
        case CustomProperties     =>
          Some(node.properties.collect {
            case p if (p.provider == Some(NodeProperty.customPropertyProvider)) => (p.toData, p.toData)
          }.toSet)
        case NodeState            => someSet(node.rudderSettings.state.name)
        case DirectiveId          => None
        case DirectiveVarName     => None
        case DirectiveVarValue    => None
        case TechniqueName        => None
        case TechniqueId          => None
        case TechniqueVersion     => None
        case TechniqueMethodValue => None
        case Description          => None
        case LongDescription      => None
        case Name                 => None
        case IsEnabled            => None
        case Tags                 => None
        case TagKeys              => None
        case TagValues            => None
        case GroupId              => None
        case IsDynamic            => None
        case ParameterName        => None
        case ParameterValue       => None
        case RuleId               => None
        case DirectiveIds         => None
        case Targets              => None
      }
    }

    def find(node: CoreNodeFact, token: String): Option[QuickSearchResult] = {
      toMatch(node).flatMap { set =>
        set.collectFirst {
          case (s, value) if QSPattern(token).matcher(s).matches =>
            QuickSearchResult(
              QRNodeId(node.id.value),
              s"${node.fqdn} [${node.id.value}]",
              Some(a),
              value
            )
        }
      }
    }
  }
}

object QSDirectiveBackend extends Loggable {
  import QSAttribute.{DirectiveId as QSDirectiveId, *}
  import QSObject.Directive as QSDirective
  import QuickSearchResultId.QRDirectiveId
  import com.normation.rudder.domain.policies.Directive
  import com.normation.rudder.repository.FullActiveTechnique
  import zio.syntax.*

  /*
   * The filter that allows to know if a couple (activeTechnique, directive) match
   * the expected query
   */

  /**
   * Lookup directives
   */
  def search(query: Query)(implicit repo: RoDirectiveRepository): IOResult[Seq[QuickSearchResult]] = {

    // only search if query is on Directives and attributes contains
    // DirectiveId, DirectiveVarName, DirectiveVarValue, TechniqueName, TechniqueVersion

    val attributes: Set[QSAttribute] = query.attributes.intersect(QSObject.Directive.attributes)

    if (query.objectClass.contains(QSDirective) && attributes.nonEmpty) {
      for {
        directiveLib <- repo.getFullDirectiveLibrary()
      } yield {
        (for {
          (at, dir) <- directiveLib.allDirectives.values
          if (at.policyTypes.isBase && !dir.isSystem)
          attribute <- attributes
        } yield {
          attribute.find(at, dir, query.userToken)
        }).flatten.toSeq
      }
    } else {
      Seq().succeed
    }
  }

  implicit class QSAttributeFilter(val a: QSAttribute) extends AnyVal {

    private def toMatch(at: FullActiveTechnique, dir: Directive): Option[Set[(String, String)]] = {

      val enableToken  = Set("true", "enable", "enabled", "isenable", "isenabled")
      val disableToken = Set("false", "disable", "disabled", "isdisable", "isdisabled")

      def isEnabled = {
        (if (at.isEnabled) enableToken.map(t => (t, "Technique is enabled"))
         else disableToken.map(t => (t, "Technique is disabled"))) ++
        (if (dir.isEnabled) enableToken.map(t => (t, "Directive is enabled"))
         else disableToken.map(t => (t, "Directive is disabled")))
      }

      /*
       * A set of value to check against / value to return to the user
       */
      a match {
        case QSDirectiveId        => Some(Set((dir.id.uid.value, dir.id.uid.value)))
        case DirectiveVarName     =>
          Some(dir.parameters.flatMap(param => param._2.map(value => (param._1, param._1 + ":" + value))).toSet)
        case DirectiveVarValue    =>
          Some(dir.parameters.flatMap(param => param._2.map(value => (value, param._1 + ":" + value))).toSet)
        case TechniqueName        => Some(at.techniques.map { case (_, t) => (t.name, t.name) }.toSet)
        case TechniqueId          => Some(Set((at.techniqueName.value, at.techniqueName.value)))
        case TechniqueVersion     => Some(Set((dir.techniqueVersion.serialize, dir.techniqueVersion.serialize)))
        case TechniqueMethodValue => None
        case Description          => Some(Set((dir.shortDescription, dir.shortDescription), (dir.longDescription, dir.longDescription)))
        case LongDescription      => Some(Set((dir.longDescription, dir.longDescription)))
        case Name                 => Some(Set((dir.name, dir.name)))
        case IsEnabled            => Some(isEnabled)
        case Tags                 => Some(dir.tags.map { case Tag(TagName(k), TagValue(v)) => (s"$k=$v", s"$k=$v") }.toSet)
        case TagKeys              => Some(dir.tags.map { case Tag(TagName(k), _) => (k, k) }.toSet)
        case TagValues            => Some(dir.tags.map { case Tag(_, TagValue(v)) => (v, v) }.toSet)
        case NodeId               => None
        case Fqdn                 => None
        case OsType               => None
        case OsName               => None
        case OsVersion            => None
        case OsFullName           => None
        case OsKernelVersion      => None
        case OsServicePack        => None
        case Arch                 => None
        case Ram                  => None
        case IpAddresses          => None
        case PolicyServerId       => None
        case Properties           => None
        case GroupProperties      => None
        case CustomProperties     => None
        case NodeState            => None
        case GroupId              => None
        case IsDynamic            => None
        case ParameterName        => None
        case ParameterValue       => None
        case RuleId               => None
        case DirectiveIds         => None
        case Targets              => None
      }
    }

    def find(at: FullActiveTechnique, dir: Directive, token: String): Option[QuickSearchResult] = {
      toMatch(at, dir).flatMap { set =>
        set.collectFirst {
          case (s, value) if QSPattern(token).matcher(s).matches =>
            QuickSearchResult(
              QRDirectiveId(dir.id.uid.value),
              dir.name,
              Some(a),
              value
            )
        }
      }
    }
  }
}

object QSTechniqueBackend extends Loggable {
  import QSAttribute.{TechniqueId as QSTechniqueId, *}
  import QSObject.Technique as QSTechnique
  import QuickSearchResultId.QRTechniqueId
  import zio.syntax.*

  /**
   * Lookup techniques
   */
  def search(query: Query)(implicit techniqueReader: EditorTechniqueReader): IOResult[Seq[QuickSearchResult]] = {

    val attributes: Set[QSAttribute] = query.attributes.intersect(QSObject.Technique.attributes)

    if (query.objectClass.contains(QSTechnique) && attributes.nonEmpty) {
      for {
        res               <- techniqueReader.readTechniquesMetadataFile
        (techniques, _, _) = res
      } yield {
        techniques.flatMap(t => attributes.flatMap(a => a.find(t, query.userToken)).toSeq)
      }
    } else {
      Seq().succeed
    }
  }

  implicit class QSAttributeFilter(val a: QSAttribute) extends AnyVal {

    private def toMatch(tech: EditorTechnique): Option[Set[(String, String)]] = {
      def getParam(methods: MethodElem): List[(String, List[String])] = {
        methods match {
          case b: MethodBlock => b.calls.flatten(getParam)
          case c: MethodCall  => List((c.method.value, c.parameters.values.toList))
        }
      }

      def someSet(v: String) = Some(Set((v, v)))
      /*
       * A set of value to check against / value to return to the user
       */
      a match {
        case QSTechniqueId        => someSet(tech.id.value)
        case TechniqueMethodValue =>
          Some(tech.calls.flatMap(c => getParam(c).flatMap(m => m._2.map(value => (value, m._1 + ":" + value)).toSet)).toSet)
        case TechniqueName        => someSet(tech.name)
        case DirectiveVarValue    => None
        case DirectiveVarName     => None
        case DirectiveId          => None
        case TechniqueVersion     => None
        case Description          => None
        case LongDescription      => None
        case Name                 => None
        case IsEnabled            => None
        case Tags                 => None
        case TagKeys              => None
        case TagValues            => None
        case NodeId               => None
        case Fqdn                 => None
        case OsType               => None
        case OsName               => None
        case OsVersion            => None
        case OsFullName           => None
        case OsKernelVersion      => None
        case OsServicePack        => None
        case Arch                 => None
        case Ram                  => None
        case IpAddresses          => None
        case PolicyServerId       => None
        case Properties           => None
        case GroupProperties      => None
        case CustomProperties     => None
        case NodeState            => None
        case GroupId              => None
        case IsDynamic            => None
        case ParameterName        => None
        case ParameterValue       => None
        case RuleId               => None
        case DirectiveIds         => None
        case Targets              => None
      }
    }

    def find(tech: EditorTechnique, token: String): Option[QuickSearchResult] = {
      toMatch(tech).flatMap { set =>
        set.collectFirst {
          case (s, value) if QSPattern(token).matcher(s).matches =>
            QuickSearchResult(
              QRTechniqueId(tech.id.value),
              tech.name,
              Some(a),
              value
            )
        }
      }
    }
  }
}

/**
 * The whole LDAP backend logic: look for NodeGroups, Parameters, Rules,
 * but not Directives nor Nodes
 */
object QSLdapBackend {
  import QSAttribute.*
  import QSObject.*

  /**
   * The actual search logic, everything else is just glue to make it works.
   */
  def search(query: Query)(implicit
      ldap:      LDAPConnectionProvider[RoLDAPConnection],
      rudderDit: RudderDit
  ): IOResult[Seq[QuickSearchResult]] = {
    // the filter for attribute and for attributes must be non empty, else return nothing
    val ocFilter           = query.objectClass.map(_.filter).flatten.toSeq
    val attrFilter         = query.attributes.map(_.filter(query.userToken)).flatten.toSeq
    val filter             = AND(
      OR(ocFilter*),
      OR(attrFilter*)
    )
    // the ldap query part. It should be in a box, but the person who implemented ldap backend was
    // not really strict on the semantic
    // the second group is always needed (displayed name, id+test system)
    val returnedAttributes =
      (query.attributes.map(_.ldapName).toSeq ++ Seq(A_OC, A_HOSTNAME, A_NAME, A_UUID, A_PARAMETER_NAME, A_IS_SYSTEM)).distinct

    if (ocFilter.isEmpty || attrFilter.isEmpty) { // nothing to search for in that backend
      Seq.empty[QuickSearchResult].succeed
    } else {
      for {
        connection <- ldap
        entries    <- connection.search(rudderDit.BASE_DN, Sub, filter, returnedAttributes*)
      } yield {
        // transformat LDAPEntries to quicksearch results, keeping only the attribute
        // that matches the query on the result and no system entries but nodes.
        // Also, only keep nodes that exists.
        entries.flatMap(_.toResult(query))
      }
    }
  }

  /**
   * Mapping between attribute and their ldap name
   */
  private val attributeNameMapping: Map[QSAttribute, String] = {
    val m: Map[QSAttribute, String] = Map(
      Name                 -> A_NAME,
      Description          -> A_DESCRIPTION,
      LongDescription      -> A_LONG_DESCRIPTION,
      IsEnabled            -> A_IS_ENABLED,
      GroupId              -> A_NODE_GROUP_UUID,
      IsDynamic            -> A_IS_DYNAMIC,
      NodeId               -> A_NODE_UUID,
      Fqdn                 -> "",
      OsType               -> "",
      OsName               -> "",
      OsVersion            -> "",
      OsFullName           -> "",
      OsKernelVersion      -> "",
      OsServicePack        -> "",
      Arch                 -> "",
      Ram                  -> "",
      IpAddresses          -> "",
      PolicyServerId       -> "",
      Properties           -> "",
      GroupProperties      -> A_JSON_PROPERTY,
      CustomProperties     -> "",
      NodeState            -> "",
      DirectiveId          -> "",
      DirectiveVarName     -> "",
      DirectiveVarValue    -> "",
      TechniqueId          -> "",
      TechniqueName        -> "",
      TechniqueVersion     -> "",
      TechniqueMethodValue -> "",
      ParameterName        -> A_PARAMETER_NAME,
      ParameterValue       -> A_PARAMETER_VALUE,
      RuleId               -> A_RULE_UUID,
      DirectiveIds         -> A_DIRECTIVE_UUID,
      Targets              -> A_RULE_TARGET,
      Tags                 -> A_SERIALIZED_TAGS,
      TagKeys              -> A_SERIALIZED_TAGS,
      TagValues            -> A_SERIALIZED_TAGS
    )

    if (m.size != QSAttribute.values.size) {
      throw new IllegalArgumentException(
        "Be carefull, it seems that the list of attributes in QSAttribute was modified, but not the list of name mapping" +
        s"Please check for '${(m.keySet.diff(QSAttribute.all) ++ QSAttribute.all.diff(m.keySet)).mkString("', '")}'"
      )
    }
    m
  }

  implicit class QSAttributeLdapName(val a: QSAttribute) extends AnyVal {
    def ldapName: String = attributeNameMapping(a)
  }

  /**
   * Mapping between attributes and their filter in the backend
   */
  object QSAttributeLdapFilter {
    //
    val NODE_POSTFIX = "Node"

    // the list of value to avoid in OC for matching
    val ocRudderTypes = Set("top", OC_RUDDER_NODE, OC_POLICY_SERVER_NODE, OC_NODE, OC_RULE, OC_PARAMETER, OC_RUDDER_NODE_GROUP)

    // Find a substring in a LDAP attribute, i.e 'token' becomes "*token*"
    def sub(a: QSAttribute, token: String) = Some(SUB(a.ldapName, null, Array(token), null))

    // use val to compile only one time these patterns
    val boolEnablePattern = {
      List("true", "enable", "enabled", "isenabled").map(QSPattern.apply)
    }

    // use val to compile only one time these patterns
    val boolDisablePattern = {
      List("false", "disable", "disabled", "isdisabled").map(QSPattern.apply)
    }

    val boolDynamicPattern = {
      List("true", "dynamic", "isdynamic").map(QSPattern.apply)
    }

    // use val to compile only one time these patterns
    val boolStaticPattern = {
      List("false", "static", "isstatic").map(QSPattern.apply)
    }

    trait Matcher {
      val isTrue:  String => Boolean
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
      if (matcher.isTrue(token)) {
        Some(EQ(a.ldapName, LDAPBoolean(true).toLDAPString))
      } else if (matcher.isFalse(token)) {
        Some(EQ(a.ldapName, LDAPBoolean(false).toLDAPString))
      } else {
        None
      }
    }
  }

  implicit class QSAttributeLdapFilter(val a: QSAttribute) extends AnyVal {
    import QSAttributeLdapFilter.*

    def filter(token: String): Option[Filter] = {
      a match {
        case Name                 => sub(a, token)
        case Description          => sub(a, token)
        case LongDescription      => sub(a, token)
        case NodeId               => sub(a, token)
        case Fqdn                 => sub(a, token)
        case OsType               => Some(EQ(a.ldapName, token + NODE_POSTFIX)) // // objectClass=linuxNode, and only EQ is supported
        case OsName               => sub(a, token)
        case OsVersion            => sub(a, token)
        case OsFullName           => sub(a, token)
        case OsKernelVersion      => sub(a, token)
        case OsServicePack        => sub(a, token)
        case Arch                 => sub(a, token)
        case Ram                  => Some(EQ(a.ldapName, token))                // int doesn't have substring match - and its in kb :/
        case IpAddresses          => Some(EQ(a.ldapName, token))                // ipHostNumber doesn't have substring match :/
        case PolicyServerId       => sub(a, token)
        case Properties           => sub(a, token)
        case GroupProperties      => sub(a, token)
        case CustomProperties     => sub(a, token)
        case NodeState            => sub(a, token)
        case GroupId              => sub(a, token)
        case IsEnabled            => bool(MatchEnable, a, token)
        case IsDynamic            => bool(MatchDynamic, a, token)
        case DirectiveId          => None
        case DirectiveVarName     => None
        case DirectiveVarValue    => None
        case TechniqueName        => None
        case TechniqueId          => None
        case TechniqueVersion     => None
        case TechniqueMethodValue => None
        case ParameterName        => sub(a, token)
        case ParameterValue       => sub(a, token)
        case RuleId               => sub(a, token)
        case DirectiveIds         => sub(a, token)
        case Targets              => sub(a, token)
        case Tags                 => sub(a, token)
        case TagKeys              => sub(a, token)
        case TagValues            => sub(a, token)
      }
    }
  }

  /**
   * How to find the actual value to display from a token for
   * an attribute.
   * The transformation may fail (option = none).
   */
  implicit class LdapAttributeValueTransform(val a: QSAttribute) extends AnyVal {

    def transform(pattern: Pattern, value: String): Option[String] = {

      def parseTag(value: String, matcher: Tag => Boolean, transform: Tag => String) = {
        try {
          value.fromJson[TAGS] match {
            case Right(tags) => tags.tags.collectFirst { case t if matcher(t) => transform(t) }
            case _           => None
          }
        } catch {
          case NonFatal(ex) => None
        }
      }

      a match {
        case Tags      =>
          def matcher(t:     Tag) = pattern.matcher(t.name.value).matches || pattern.matcher(t.value.value).matches
          def transform(tag: Tag) = s"${tag.name.value}=${tag.value.value}"
          parseTag(value, matcher, transform)
        case TagKeys   =>
          def matcher(t:     Tag) = pattern.matcher(t.name.value).matches
          def transform(tag: Tag) = tag.name.value
          parseTag(value, matcher, transform)
        case TagValues =>
          def matcher(t:     Tag) = pattern.matcher(t.value.value).matches
          def transform(tag: Tag) = tag.value.value
          parseTag(value, matcher, transform)

        // main case: no more transformation
        case _         => Some(value)
      }
    }
  }

  /**
   * Build LDAP filter for a QSObject
   */
  implicit final class QSObjectLDAPFilter(obj: QSObject)(implicit
      rudderDit: RudderDit
  ) {

    def filter: List[Filter] = obj match {
      case Common    => Nil
      case Node      => Nil
      case Group     => AND(IS(OC_RUDDER_NODE_GROUP), Filter.create(s"entryDN:dnSubtreeMatch:=${rudderDit.GROUP.dn.toString}")) :: Nil
      case Directive => Nil
      case Technique => Nil
      case Parameter =>
        AND(IS(OC_PARAMETER), Filter.create(s"entryDN:dnOneLevelMatch:=${rudderDit.PARAMETERS.dn.toString}")) :: Nil
      case Rule      => AND(IS(OC_RULE), Filter.create(s"entryDN:dnSubtreeMatch:=${rudderDit.RULES.dn.toString}")) :: Nil
    }
  }

  /**
   * correctly transform entry to a result, putting what is needed in type and description
   */
  implicit final class EntryToSearchResult(val e: LDAPEntry) {
    import QSAttributeLdapFilter.*
    import QuickSearchResultId.*

    def toResult(query: Query): Option[QuickSearchResult] = {
      def getId(e: LDAPEntry): Option[QuickSearchResultId] = {
        if (e.isA(OC_RULE)) { e(A_RULE_UUID).map(QRRuleId.apply) }
        else if (e.isA(OC_RUDDER_NODE_GROUP)) { e(A_NODE_GROUP_UUID).map(QRGroupId.apply) }
        else if (e.isA(OC_PARAMETER)) {
          e(A_PARAMETER_NAME).map(QRParameterId.apply)
          // no directive
        } else { None }
      }

      val pattern = QSPattern(query.userToken)

      /*
       * Depending of the attribute type, we must use a different
       * match methods.
       * Returns Option[(attribute name, matching value)]
       */
      def matchValue(a: Attribute): Option[(String, String)] = {

        // test a matcher against values
        def findValue(values: Array[String], test: String => Boolean): Option[(String, String)] = {
          values.find(test).map(v => (a.getName, v))
        }
        // test a list of pattern matcher against values
        def testAndFindValue(testers: List[String => Boolean]):        Option[(String, String)] = {
          testers.find(f => f(query.userToken)).flatMap(findValue(a.getValues, _))
        }

        if (a.getName == A_OC) {
          // here we must just avoid "top" and "kind" object class
          // and also remove the "node" part for "linuxNode", etc
          val values = a.getValues.flatMap(s => if (ocRudderTypes.contains(s)) None else Some(s.replace(NODE_POSTFIX, "")))
          findValue(values, s => pattern.matcher(s).matches)
        } else if (a.getName == A_IS_ENABLED) { // boolean "isEnabled"
          // we must be sure that it is the same matcher that maches the token and the
          // value, else we get a value for "disable" when ENABLE="true"
          testAndFindValue(MatchEnable.isTrue :: MatchEnable.isFalse :: Nil)
        } else if (a.getName == A_IS_DYNAMIC) { // boolean "isDynamic"
          testAndFindValue(MatchDynamic.isTrue :: MatchDynamic.isFalse :: Nil)
        } else {
          findValue(a.getValues, s => pattern.matcher(s).matches)
        }
      }

      def isNodeOrNotSystem(e: LDAPEntry): Boolean = {
        // if isSystem is absent, then the object is not system
        val isSystem = e.getAsBoolean(A_IS_SYSTEM).getOrElse(false)

        if (isSystem && (e.isA(OC_RULE) || e.isA(OC_RUDDER_NODE_GROUP) || e.isA(OC_PARAMETER))) false
        else true
      }

      // get the attribute value matching patterns
      // if several, take only one at random. If none, that's strange, reject entry
      // also, don't look in objectClass to find the pattern
      for {
        (attr, desc) <- e.attributes.foldLeft(Option.empty[(QSAttribute, String)]) { (current, next) =>
                          (current, next) match {
                            case (Some(x), _) => Some(x)
                            case (None, a)    =>
                              for {
                                (attr, value) <- matchValue(a)

                                attribute <- query.attributes.find(attributeNameMapping(_) == attr)
                                trans     <- attribute.transform(pattern, value)
                              } yield {
                                (attribute, trans)
                              }
                          }
                        }
        id           <- getId(e)
        if (isNodeOrNotSystem(e))
      } yield {
        // prefer hostname for nodes
        val name = e(A_HOSTNAME).orElse(e(A_NAME)).getOrElse(id.value)
        QuickSearchResult(id, name, Some(attr), desc)
      }
    }
  }
}
