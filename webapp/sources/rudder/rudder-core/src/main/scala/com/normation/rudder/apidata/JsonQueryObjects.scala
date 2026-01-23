/*
 *************************************************************************************
 * Copyright 2021 Normation SAS
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

package com.normation.rudder.apidata

import cats.syntax.bifunctor.*
import cats.syntax.traverse.*
import com.normation.GitVersion
import com.normation.GitVersion.ParseRev
import com.normation.GitVersion.Revision
import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.domain.TechniqueVersion
import com.normation.errors.*
import com.normation.inventory.domain.CertifiedKey
import com.normation.inventory.domain.KeyStatus
import com.normation.inventory.domain.NodeId
import com.normation.inventory.domain.SecurityToken
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.nodes.NodeGroupCategoryId
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.nodes.NodeState
import com.normation.rudder.domain.policies.*
import com.normation.rudder.domain.properties.CompareProperties
import com.normation.rudder.domain.properties.GenericProperty
import com.normation.rudder.domain.properties.GlobalParameter
import com.normation.rudder.domain.properties.GroupProperty
import com.normation.rudder.domain.properties.InheritMode
import com.normation.rudder.domain.properties.NodeProperty
import com.normation.rudder.domain.properties.PatchProperty
import com.normation.rudder.domain.properties.PropertyProvider
import com.normation.rudder.domain.properties.Visibility.Hidden
import com.normation.rudder.domain.queries.Query
import com.normation.rudder.domain.queries.QueryReturnType
import com.normation.rudder.domain.queries.QueryReturnType.NodeReturnType
import com.normation.rudder.repository.FullNodeGroupCategory
import com.normation.rudder.rule.category.RuleCategory
import com.normation.rudder.rule.category.RuleCategoryId
import com.normation.rudder.services.policies.PropertyParser
import com.normation.rudder.services.queries.CmdbQueryParser
import com.normation.rudder.services.queries.JsonQueryLexer
import com.normation.rudder.services.queries.StringCriterionLine
import com.normation.rudder.services.queries.StringQuery
import com.normation.rudder.services.servers.AllowedNetwork
import com.normation.rudder.services.servers.DeleteMode
import com.typesafe.config.ConfigValue
import enumeratum.Enum
import enumeratum.EnumEntry
import io.scalaland.chimney.Transformer
import io.scalaland.chimney.dsl.*
import net.liftweb.common.*
import net.liftweb.http.Req
import zio.Chunk
import zio.NonEmptyChunk
import zio.json.*

/*
 * This class deals with everything serialisation related for API.
 * Change things with care! Everything must be versionned!
 * Even changing a field name can lead to an API incompatible change and
 * so will need a new API version number (and be sure that old behavior is kept
 * for previous versions).
 */

///////////////////////////// decoder ///////////////

/*
 * Object used in JSON query POST/PUT request.
 * For disambiguation, objects are prefixed by JQ.
 *
 * Note about id/revision: it does not make sense to try to change a
 * revision, it's immutable. Only head can be changed, so rev is never
 * specified in JQ object.
 * It can make sense to clone a specific revision, so sourceId can have a
 * sourceRevision too.
 */
object JsonQueryObjects {
  import JsonResponseObjects.JRRuleTarget

  // Node
  final case class JQDeleteMode(
      mode: Option[DeleteMode]
  )
  final case class JQClasses(
      classes: Option[List[String]]
  )
  final case class JQNodeIdChunk(
      nodeId: Option[Chunk[NodeId]]
  )
  final case class JQNodeIdStatus(
      nodeId: List[NodeId],
      status: JQNodeStatusAction
  )
  final case class JQNodeStatus(
      status: JQNodeStatusAction
  )
  sealed trait JQNodeStatusAction extends EnumEntry
  object JQNodeStatusAction       extends Enum[JQNodeStatusAction] {
    case object AcceptNode extends JQNodeStatusAction
    case object RefuseNode extends JQNodeStatusAction
    case object DeleteNode extends JQNodeStatusAction

    def values: IndexedSeq[JQNodeStatusAction] = findValues

    override def extraNamesToValuesMap: Map[String, JQNodeStatusAction] = Map(
      "accept"   -> AcceptNode,
      "accepted" -> AcceptNode,
      "refuse"   -> RefuseNode,
      "refused"  -> RefuseNode,
      "delete"   -> DeleteNode,
      "deleted"  -> DeleteNode,
      "removed"  -> DeleteNode
    )
  }

  final case class JQNodeInherited(
      inherited: Option[Boolean]
  )

  final case class JQNodePropertyInfo(
      inherited: Boolean,
      value:     String
  )
  final case class JQNodeIdsSoftwareProperties(
      nodeIds:    Option[List[NodeId]],
      software:   Option[List[String]],
      properties: Option[List[JQNodePropertyInfo]]
  )

  final case class JQRuleCategory(
      name:        Option[String] = None,
      description: Option[String] = None,
      parent:      Option[String] = None,
      id:          Option[String] = None
  ) {

    def update(ruleCategory: RuleCategory) = {
      ruleCategory.copy(
        name = name.getOrElse(ruleCategory.name),
        description = description.getOrElse(ruleCategory.description)
      )
    }
  }

  final case class JQIncludeSystem(
      includeSystem: Option[Boolean]
  )

  final case class JQDirectiveSectionVar(
      name:  String,
      value: String
  )
  final case class JQDirectiveSection(
      name: String, // Map is necessary b/c of the "var" in {"vars": [ {"var": { "name":..., "value":... }}}

      sections: Option[
        List[Map[String, JQDirectiveSection]]
      ], // Map is necessary b/c of the "var" in {"vars": [ {"var": { "name":..., "value":... }}}

      vars:     Option[List[Map[String, JQDirectiveSectionVar]]]
  ) {
    def toSectionVal: (String, SectionVal) = {
      (
        name,
        SectionVal(
          sections.map(_.flatMap(_.get("section").map(s => s.toSectionVal)).groupMap(_._1)(_._2)).getOrElse(Map()),
          vars.map(_.flatMap(_.get("var").map(x => (x.name, x.value))).toMap).getOrElse(Map())
        )
      )
    }
  }

  // this one is a direction mapping of Directive with optionnal fields for patching
  case class PatchDirective(
      id:               Option[DirectiveId],
      techniqueVersion: Option[TechniqueVersion],
      parameters:       Option[Map[String, Seq[String]]],
      name:             Option[String],
      shortDescription: Option[String],
      policyMode:       Option[Option[PolicyMode]],
      longDescription:  Option[String],
      priority:         Option[Int],
      _isEnabled:       Option[Boolean],
      tags:             Option[Tags]
  )
  // the input query mapping
  final case class JQDirective(
      id:               Option[DirectiveId] = None,
      displayName:      Option[String] = None,
      shortDescription: Option[String] = None,
      longDescription:  Option[String] = None,
      enabled:          Option[Boolean] = None,
      parameters:       Option[Map[String, JQDirectiveSection]] = None,
      priority:         Option[Int] = None,
      techniqueName:    Option[TechniqueName] =
        None, // be careful, here we can have a revision. But it may not be enought to support the case where we are cloning a
      // directive, want to keep the main technique version, but change revision. So we have an other param to force change
      // the revision, see #techniqueRevision.

      techniqueVersion: Option[TechniqueVersion] = None,
      policyMode:       Option[Option[PolicyMode]] = None,
      tags:             Option[Tags] = None, // for clone

      source: Option[DirectiveId] = None
  ) {
    val onlyName: Boolean = displayName.isDefined &&
      shortDescription.isEmpty &&
      longDescription.isEmpty &&
      enabled.isEmpty &&
      parameters.isEmpty &&
      priority.isEmpty &&
      techniqueName.isEmpty &&
      techniqueVersion.isEmpty &&
      policyMode.isEmpty &&
      tags.isEmpty // no need to check source or reason

    def updateDirective(directive: Directive): Directive = {
      directive.patchUsing(
        PatchDirective(
          id,
          techniqueVersion,
          parameters.flatMap(_.get("section").map(s => SectionVal.toMapVariables(s.toSectionVal._2))),
          displayName,
          shortDescription,
          policyMode,
          longDescription,
          priority,
          enabled,
          tags
        )
      )
    }
  }

  final case class JQRule(
      id:                                  Option[RuleId] = None,
      displayName:                         Option[String] = None,
      @jsonAliases("category") categoryId: Option[String] = None,
      shortDescription:                    Option[String] = None,
      longDescription:                     Option[String] = None,
      directives:                          Option[Set[DirectiveId]] = None,
      targets:                             Option[Set[JRRuleTarget]] = None,
      enabled:                             Option[Boolean] = None,
      tags:                                Option[Tags] = None, // for clone

      source: Option[RuleId] = None
  ) {

    val onlyName: Boolean = displayName.isDefined &&
      categoryId.isEmpty &&
      shortDescription.isEmpty &&
      longDescription.isEmpty &&
      directives.isEmpty &&
      targets.isEmpty &&
      enabled.isEmpty &&
      tags.isEmpty // no need to check source or reason

    def updateRule(rule: Rule): Rule = {
      val updateRevision   = id.map(_.rev).getOrElse(rule.id.rev)
      val updateName       = displayName.getOrElse(rule.name)
      val updateCategory   = categoryId.map(RuleCategoryId.apply).getOrElse(rule.categoryId)
      val updateShort      = shortDescription.getOrElse(rule.shortDescription)
      val updateLong       = longDescription.getOrElse(rule.longDescription)
      val updateDirectives = directives.getOrElse(rule.directiveIds)
      val updateTargets    = targets.map(t => Set[RuleTarget](RuleTarget.merge(t.map(_.toRuleTarget)))).getOrElse(rule.targets)
      val updateEnabled    = enabled.getOrElse(rule.isEnabledStatus)
      val updateTags       = tags.getOrElse(rule.tags)
      rule.copy(
        // we can't change id, but revision
        id = RuleId(rule.id.uid, updateRevision),
        name = updateName,
        categoryId = updateCategory,
        shortDescription = updateShort,
        longDescription = updateLong,
        directiveIds = updateDirectives,
        targets = updateTargets,
        isEnabledStatus = updateEnabled,
        tags = updateTags
      )
    }
  }

  final case class JQGlobalParameter(
      id:          Option[String],
      value:       Option[ConfigValue],
      description: Option[String],
      inheritMode: Option[InheritMode]
  ) {
    def updateParameter(parameter: GlobalParameter): GlobalParameter = {
      // provider from API is force set to default.
      parameter.patch(PatchProperty(id, value, Some(PropertyProvider.defaultPropertyProvider), description, inheritMode))
    }
  }

  final case class JQGroupProperty(
      name:        String,
      rev:         Option[String],
      value:       ConfigValue,
      inheritMode: Option[InheritMode]
  ) {
    def toGroupProperty: GroupProperty = GroupProperty(
      name,
      rev.map(Revision.apply).getOrElse(GitVersion.DEFAULT_REV),
      value,
      inheritMode,
      Some(PropertyProvider.defaultPropertyProvider)
    )
  }

  final case class JQStringQuery(
      select:      Option[QueryReturnType],
      composition: Option[String],
      transform:   Option[String],
      where:       Option[List[StringCriterionLine]]
  ) {
    def toQueryString: StringQuery = {
      val c = composition.flatMap {
        case x if (x.strip().isEmpty) => None
        case x                        => Some(x)
      }

      def d = transform.flatMap {
        case x if (x.strip().isEmpty) => None
        case x                        => Some(x)
      }

      StringQuery(select.getOrElse(NodeReturnType), c, d, where.getOrElse(Nil))
    }
  }

  final case class GroupPatch(
      id:          Option[NodeGroupId],
      name:        Option[String],
      description: Option[String],
      properties:  Option[List[GroupProperty]],
      query:       Option[Option[Query]],
      isDynamic:   Option[Boolean],
      _isEnabled:  Option[Boolean]
  )

  final case class JQGroupCategory private[apidata] (
      id:          Option[NodeGroupCategoryId] = None,
      name:        Option[String] = None,
      description: Option[String] = None,
      parent:      Option[NodeGroupCategoryId] = None
  ) {
    def update(category: FullNodeGroupCategory): FullNodeGroupCategory = {
      val updateId          = id.getOrElse(category.id)
      val updateName        = name.getOrElse(category.name)
      val updateDescription = description.getOrElse(category.description)
      category.copy(
        id = updateId,
        name = updateName,
        description = updateDescription
      )
    }

    def create(defaultId: => NodeGroupCategoryId): PureResult[FullNodeGroupCategory] = {
      name match {
        case Some(name) =>
          Right(
            FullNodeGroupCategory(
              id.getOrElse(defaultId),
              name,
              description.getOrElse(""),
              Nil,
              Nil,
              isSystem = false,
              None
            )
          )
        case None       =>
          Left(Inconsistency("Could not create group Category, cause: name is not defined"))
      }
    }
  }
  object JQGroupCategory {
    private val minimalNameSize = 3
    // validation method for validating the name the group category
    private[apidata] def validate(groupCategory: JQGroupCategory): Either[String, JQGroupCategory] = {
      def validateName: Either[String, JQGroupCategory] = {
        groupCategory.name match {
          case None        => Right(groupCategory)
          case Some(value) =>
            if (value.size < minimalNameSize) {
              Left(s"Group category name '${value}' must at least have a ${minimalNameSize} character size")
            } else Right(groupCategory)
        }
      }
      validateName
    }
  }

  final case class JQGroup(
      id:                                  Option[NodeGroupId] = None,
      displayName:                         Option[String] = None,
      description:                         Option[String] = None,
      properties:                          Option[List[GroupProperty]] = None,
      query:                               Option[StringQuery] = None,
      dynamic:                             Option[Boolean] = None,
      enabled:                             Option[Boolean] = None,
      @jsonAliases("category") categoryId: Option[NodeGroupCategoryId] = None,
      source:                              Option[NodeGroupId] = None
  ) {

    val onlyName: Boolean = displayName.isDefined &&
      description.isEmpty &&
      properties.isEmpty &&
      query.isEmpty &&
      dynamic.isEmpty &&
      enabled.isEmpty &&
      categoryId.isEmpty

    def updateGroup(group: NodeGroup, queryParser: CmdbQueryParser): PureResult[NodeGroup] = {
      for {
        q        <- query match {
                      case None    => Right(None)
                      case Some(x) => queryParser.parse(x).toPureResult.map(Some(_))
                    }
        // when properties come from REST, we add hidden properties since the user should not change them.
        // Still, if the user redefines a hidden property by using the same name, we keep the user one.
        propNames = properties.getOrElse(Nil).map(_.name).toSet
        p        <- CompareProperties.updateProperties(
                      group.properties,
                      properties.map(_ ::: group.properties.filter(p => p.visibility == Hidden && !propNames.contains(p.name)))
                    )
      } yield {
        group.patchUsing(
          GroupPatch(
            // we can't change id, but revision yes
            Some(NodeGroupId(group.id.uid, id.map(_.rev).getOrElse(group.id.rev))),
            displayName,
            description,
            Some(p),
            q.map(Some(_)),
            dynamic,
            enabled
          )
        )
      }
    }
  }

  /*
   * we want to directly decode:
   * "agentKey": {
   *   "value": "-----BEGIN CERTIFICATE-----...",
   *    "status": "certified"
   * }
   * ie no duplicate { "value": { "value": ... see https://issues.rudder.io/issues/27369
   */
  final case class JQSecurityToken(sk: SecurityToken)
  object JQSecurityToken {
    implicit val decoderJQSecurityToken: JsonDecoder[JQSecurityToken] = JsonDecoder.string.mapOrFail { s =>
      SecurityToken.parseValidate(s) match {
        case Right(sk) => Right(JQSecurityToken(sk))
        case Left(err) => Left(err.fullMsg)
      }
    }
  }
  final case class JQAgentKey(
      value:  Option[JQSecurityToken],
      status: Option[KeyStatus]
  ) {
    // if agentKeyValue is present, we set both it and key status.
    // if only agentKey status is present, don't change value.
    val toKeyInfo: (Option[SecurityToken], Option[KeyStatus]) = {
      (value, status) match {
        case (None, None)       => (None, None)
        case (Some(k), None)    => (Some(k.sk), Some(CertifiedKey))
        case (None, Some(s))    => (None, Some(s))
        case (Some(k), Some(s)) => (Some(k.sk), Some(s))
      }
    }
  }
  final case class JQUpdateNode(
      properties:    Option[List[NodeProperty]],
      policyMode:    Option[Option[PolicyMode]],
      state:         Option[NodeState],
      agentKey:      Option[JQAgentKey],
      documentation: Option[String],
      reason:        Option[String]
  ) {
    val keyInfo: (Option[SecurityToken], Option[KeyStatus]) = agentKey.map(_.toKeyInfo).getOrElse((None, None))
  }

  final case class JQNodeDetailLevel(
      fields: Set[String]
  ) extends AnyVal

  object JQNodeDetailLevel {
    implicit val transformer: Transformer[JQNodeDetailLevel, NodeDetailLevel] = (jq: JQNodeDetailLevel) =>
      CustomDetailLevel(jq.fields)
  }

  final case class JQAllowedNetworks(
      allowed_networks: Option[Chunk[AllowedNetwork]]
  )
  object JQAllowedNetworks {
    def validate(v: String): PureResult[AllowedNetwork] = {
      val netWithoutSpaces = v.replaceAll("""\s""", "")
      if (netWithoutSpaces.nonEmpty) {
        if (AllowedNetwork.isValid(netWithoutSpaces)) {
          Right(AllowedNetwork(netWithoutSpaces, netWithoutSpaces))
        } else {
          Left(Unexpected(s"${netWithoutSpaces} is not a valid allowed network"))
        }
      } else {
        Left(Unexpected("Cannot pass an empty allowed network"))
      }
    }
  }

  final case class JQAllowedNetworksDiff(
      allowed_networks: Option[JQAllowedNetworkDiff]
  )
  final case class JQAllowedNetworkDiff(
      add:    Chunk[AllowedNetwork],
      delete: Chunk[AllowedNetwork]
  )

}

trait RudderJsonDecoders {
  import JsonQueryObjects.*
  import JsonResponseObjects.*
  import JsonResponseObjects.JRRuleTarget.*
  import com.normation.rudder.facts.nodes.NodeFactSerialisation.SimpleCodec.*

  implicit val deleteModeDecoder:   JsonDecoder[DeleteMode]   =
    JsonDecoder[String].mapOrFail(DeleteMode.withNameInsensitiveEither(_).left.map(_.getMessage()))
  implicit val jqDeleteModeDecoder: JsonDecoder[JQDeleteMode] = DeriveJsonDecoder.gen[JQDeleteMode]
  implicit val classesDecoder:      JsonDecoder[JQClasses]    = DeriveJsonDecoder.gen[JQClasses].orElse((_, _) => JQClasses(None))

  implicit val nodeIdDecoder:             JsonDecoder[NodeId]                      = JsonDecoder[String].map(NodeId.apply)
  implicit val nodeIdChunkDecoder:        JsonDecoder[JQNodeIdChunk]               = DeriveJsonDecoder.gen[JQNodeIdChunk]
  implicit val nodeStatusActionDecoder:   JsonDecoder[JQNodeStatusAction]          =
    JsonDecoder[String].mapOrFail(JQNodeStatusAction.withNameInsensitiveEither(_).left.map(_.getMessage()))
  implicit val nodeIdStatusDecoder:       JsonDecoder[JQNodeIdStatus]              = DeriveJsonDecoder
    .gen[JQNodeIdStatus]
    .mapOrFail(x => {
      if (x.nodeId.isEmpty) {
        Left("You must add a node id as target")
      } else {
        Right(x)
      }
    })
  implicit val nodeInheritedDecoder:      JsonDecoder[JQNodeInherited]             = DeriveJsonDecoder.gen[JQNodeInherited]
  implicit val nodestatusDecoder:         JsonDecoder[JQNodeStatus]                = DeriveJsonDecoder.gen[JQNodeStatus]
  implicit val nodePropertyInfoDecoder:   JsonDecoder[JQNodePropertyInfo]          = DeriveJsonDecoder.gen[JQNodePropertyInfo]
  implicit val nodeIdsSoftwareProperties: JsonDecoder[JQNodeIdsSoftwareProperties] =
    DeriveJsonDecoder.gen[JQNodeIdsSoftwareProperties]

  implicit val keyStatusDecoder:  JsonDecoder[KeyStatus]    =
    JsonDecoder[String].mapOrFail(KeyStatus.apply(_).left.map(_.fullMsg))
  implicit val agentKeyDecoder:   JsonDecoder[JQAgentKey]   = DeriveJsonDecoder.gen[JQAgentKey]
  implicit val updateNodeDecoder: JsonDecoder[JQUpdateNode] = DeriveJsonDecoder.gen[JQUpdateNode]

  // JRRuleTarget
  object JRRuleTargetDecoder {
    def parseString(s: String): Either[String, JRRuleTargetString] = RuleTarget.unserOne(s) match {
      case Left(err) => Left(s"'${s}' can not be decoded as a simple rule target: ${err.fullMsg}")
      case Right(x)  => Right(JRRuleTargetString(x))
    }
  }
  implicit lazy val simpleRuleTargetDecoder: JsonDecoder[JRRuleTargetString] =
    JsonDecoder[String].mapOrFail(JRRuleTargetDecoder.parseString)
  implicit lazy val composedTargetDecoder: JsonDecoder[JRRuleTargetComposed]        = DeriveJsonDecoder.gen
  implicit lazy val orRuleTargetDecoder:   JsonDecoder[JRRuleTargetComposition.or]  =
    JsonDecoder[List[JRRuleTarget]].map(JRRuleTargetComposition.or(_))
  implicit lazy val andRuleTargetDecoder:  JsonDecoder[JRRuleTargetComposition.and] =
    JsonDecoder[List[JRRuleTarget]].map(JRRuleTargetComposition.and(_))
  implicit lazy val composition:           JsonDecoder[JRRuleTargetComposition]     = DeriveJsonDecoder.gen
  implicit lazy val ruleTargetDecoder:     JsonDecoder[JRRuleTarget]                = {
    JsonDecoder.peekChar[JRRuleTarget] {
      case '"' =>
        JsonDecoder[JRRuleTargetString].widen
      case '{' =>
        JsonDecoder[JRRuleTargetComposition].widen.orElse(JsonDecoder[JRRuleTargetComposed].widen)
    }
  }
  def extractRuleTargetJson(s: String):    Either[String, JRRuleTarget]             = {
    import zio.json.*
    s.fromJson[JRRuleTarget]
      .orElse(RuleTarget.unserOne(s) match {
        case Left(err) =>
          Left(s"Error: the following string can't not be decoded as a rule target: '${s}', cause was : ${err.fullMsg}")
        case Right(x)  =>
          Right(JRRuleTargetString(x))
      })
  }

  // tags
  implicit val tagsDecoder: JsonDecoder[Tags] = JsonDecoder[List[Map[String, String]]].map(list => Tags.fromMaps(list))

  implicit val revisionDecoder: JsonDecoder[Revision] = JsonDecoder[String].map(ParseRev(_))

  // technique name/version
  implicit val techniqueNameDecoder:       JsonDecoder[TechniqueName]    = JsonDecoder[String].map(TechniqueName.apply)
  implicit val techniqueVersionDecoder:    JsonDecoder[TechniqueVersion] = JsonDecoder[String].mapOrFail(TechniqueVersion.parse(_))
  implicit lazy val ruleCategoryIdDecoder: JsonDecoder[RuleCategoryId]   = JsonDecoder[String].map(RuleCategoryId.apply)
  implicit lazy val directiveIdsDecoder:   JsonDecoder[Set[DirectiveId]] = {
    import cats.implicits.*
    JsonDecoder[List[String]].mapOrFail(list => list.traverse(x => DirectiveId.parse(x)).map(_.toSet))
  }
  implicit val ruleIdIdDecoder:            JsonDecoder[RuleId]           = JsonDecoder[String].mapOrFail(x => RuleId.parse(x))
  implicit val directiveIdDecoder:         JsonDecoder[DirectiveId]      = JsonDecoder[String].mapOrFail(x => DirectiveId.parse(x))

  // RestRule
  implicit lazy val ruleDecoder: JsonDecoder[JQRule] = DeriveJsonDecoder.gen

  implicit val ruleCategoryDecoder: JsonDecoder[JQRuleCategory] = DeriveJsonDecoder.gen

  implicit val includeSystemDecoder: JsonDecoder[JQIncludeSystem] = DeriveJsonDecoder.gen

  // RestDirective - lazy because section/sectionVar/directive are mutually recursive
  implicit lazy val sectionDecoder:   JsonDecoder[JQDirectiveSection]    = DeriveJsonDecoder.gen
  implicit lazy val variableDecoder:  JsonDecoder[JQDirectiveSectionVar] = DeriveJsonDecoder.gen
  implicit lazy val directiveDecoder: JsonDecoder[JQDirective]           = DeriveJsonDecoder.gen

  // RestGlobalParameter
  implicit val inheritModeDecoder:     JsonDecoder[InheritMode]       =
    JsonDecoder[String].mapOrFail(s => InheritMode.parseString(s).left.map(_.fullMsg))
  implicit val configValueDecoder:     JsonDecoder[ConfigValue]       = JsonDecoder[ast.Json].map(GenericProperty.fromZioJson(_))
  implicit val globalParameterDecoder: JsonDecoder[JQGlobalParameter] = DeriveJsonDecoder.gen

  // Query
  implicit val queryStringCriterionLineDecoder: JsonDecoder[StringCriterionLine] = DeriveJsonDecoder.gen
  implicit val queryReturnTypeDecoder:          JsonDecoder[QueryReturnType]     =
    JsonDecoder[String].mapOrFail(QueryReturnType.apply(_).left.map(_.fullMsg))
  implicit val queryDecoder:                    JsonDecoder[StringQuery]         = DeriveJsonDecoder.gen[JQStringQuery].map(_.toQueryString)

  // Rest group
  implicit val nodeGroupCategoryIdDecoder: JsonDecoder[NodeGroupCategoryId] = JsonDecoder[String].map(NodeGroupCategoryId.apply)
  implicit val groupPropertyDecoder:       JsonDecoder[JQGroupProperty]     = DeriveJsonDecoder.gen
  implicit val groupPropertyDecoder2:      JsonDecoder[GroupProperty]       = JsonDecoder[JQGroupProperty].map(_.toGroupProperty)
  implicit val nodeGroupIdDecoder:         JsonDecoder[NodeGroupId]         = JsonDecoder[String].mapOrFail(x => NodeGroupId.parse(x))
  implicit val groupCategoryDecoder:       JsonDecoder[JQGroupCategory]     =
    DeriveJsonDecoder.gen[JQGroupCategory].mapOrFail(JQGroupCategory.validate)
  implicit val groupDecoder:               JsonDecoder[JQGroup]             = DeriveJsonDecoder.gen

  // Settings
  implicit val allowedNetworkChunkDecoder: JsonDecoder[Chunk[AllowedNetwork]] =
    JsonDecoder.chunk[String].mapOrFail(_.accumulatePure(JQAllowedNetworks.validate).bimap(_.fullMsg, Chunk.from(_)))
  implicit val allowedNetworksDecoder:     JsonDecoder[JQAllowedNetworks]     = DeriveJsonDecoder.gen
  implicit val allowedNetworksDiffDecoder: JsonDecoder[JQAllowedNetworksDiff] = DeriveJsonDecoder.gen
  implicit val allowedNetworkDiffDecoder:  JsonDecoder[JQAllowedNetworkDiff]  = DeriveJsonDecoder.gen
}

object ZioJsonExtractor {

  /**
   * Parse request body as JSON, and decode it as type `A`.
   * This is the root method to transform a JSON query into a Rest object.
   */
  def parseJson[A](req: Req)(using decoder: JsonDecoder[A]): PureResult[A] = {
    if (req.json_?) {
      // copied from `Req.forcedBodyAsJson`
      def r  = """; *charset=(.*)""".r
      def r2 = """[^=]*$""".r
      def charset: String = req.contentType.flatMap(ct => r.findFirstIn(ct).flatMap(r2.findFirstIn)).getOrElse("UTF-8")
      // end copy

      req.body match {
        case eb: EmptyBox => Left(Unexpected((eb ?~! "error when accessing request body").messageChain))
        case Full(bytes) => decoder.decodeJson(new String(bytes, charset)).left.map(Unexpected(_))
      }
    } else {
      Left(Unexpected("Cannot parse non-JSON request as JSON; please check content-type."))
    }
  }
}

/*
 * This last class provides utility methods to get JsonQuery objects from the request.
 * We want to get ride of RestExtractorService but for now, we keep it for the parameter parts.
 */
class ZioJsonExtractor(queryParser: CmdbQueryParser & JsonQueryLexer) {
  import JsonQueryObjects.*
  import JsonResponseObjects.*
  import ZioJsonExtractor.parseJson
  import implicits.*

  /**
   * Utilities to extract values from params Map
   */
  implicit class Extract(params: Map[String, List[String]]) {
    def optGet(key: String): Option[String] = params.get(key).flatMap(_.headOption)
    def parseOne[A](key: String, decoder: String => A):                    PureResult[Option[A]] = {
      optGet(key) match {
        case None    => Right(None)
        case Some(x) => Right(Some(decoder(x)))
      }
    }
    def parse[A](key: String, decoder: JsonDecoder[A]):                    PureResult[Option[A]] = {
      optGet(key) match {
        case None    => Right(None)
        case Some(x) => decoder.decodeJson(x).map(Some(_)).left.map(Unexpected.apply)
      }
    }
    def parse2[A](key: String, decoder: String => PureResult[A]):          PureResult[Option[A]] = {
      optGet(key) match {
        case None    => Right(None)
        case Some(x) => decoder(x).map(Some(_))
      }
    }
    def parseString[A](key: String, decoder: String => Either[String, A]): PureResult[Option[A]] = {
      optGet(key) match {
        case None    => Right(None)
        case Some(x) => decoder(x).map(Some(_)).left.map(Inconsistency.apply)
      }
    }
  }

  def extractDirective(req: Req): PureResult[JQDirective] = {
    if (req.json_?) {
      parseJson[JQDirective](req)
    } else {
      extractDirectiveFromParams(req.params)
    }
  }

  def extractRule(req: Req): PureResult[JQRule] = {
    if (req.json_?) {
      parseJson[JQRule](req)
    } else {
      extractRuleFromParams(req.params)
    }
  }

  def extractRuleCategory(req: Req): PureResult[JQRuleCategory] = {
    if (req.json_?) {
      parseJson[JQRuleCategory](req)
    } else {
      extractRuleCategoryFromParam(req.params)
    }
  }

  def extractGlobalParam(req: Req): PureResult[JQGlobalParameter] = {
    if (req.json_?) {
      parseJson[JQGlobalParameter](req)
    } else {
      extractGlobalParamFromParams(req.params)
    }
  }

  def extractGroup(req: Req): PureResult[JQGroup] = {
    if (req.json_?) {
      parseJson[JQGroup](req)
    } else {
      extractGroupFromParams(req.params)
    }
  }

  def extractGroupCategory(req: Req): PureResult[JQGroupCategory] = {
    if (req.json_?) {
      parseJson[JQGroupCategory](req)
    } else {
      extractGroupCategoryFromParams(req.params)
    }
  }

  def extractDeleteMode(req: Req): PureResult[Option[DeleteMode]] = {
    if (req.json_?) {
      parseJson[JQDeleteMode](req).map(_.mode)
    } else {
      extractDeleteModeFromParams(req.params)
    }
  }

  def extractClasses(req: Req): PureResult[List[String]] = {
    if (req.json_?) {
      parseJson[JQClasses](req).map(_.classes.getOrElse(List.empty))
    } else {
      Right(extractClassesFromParams(req.params))
    }
  }

  def extractNodeIdChunk(req: Req): PureResult[Option[Chunk[NodeId]]] = {
    parseJson[JQNodeIdChunk](req).map(_.nodeId)
  }

  def extractNodeInherited(req: Req): PureResult[Option[Boolean]] = {
    parseJson[JQNodeInherited](req).map(_.inherited)
  }

  def extractNodeIdStatus(req: Req): PureResult[JQNodeIdStatus] = {
    if (req.json_?) {
      parseJson[JQNodeIdStatus](req)
    } else {
      extractNodeIdStatusFromParams(req.params)
    }
  }

  def extractNodeStatus(req: Req): PureResult[JQNodeStatus] = {
    if (req.json_?) {
      parseJson[JQNodeStatus](req)
    } else {
      extractNodeStatusFromParams(req.params)
    }
  }

  def extractNodeIdsSoftwareProperties(req: Req): PureResult[JQNodeIdsSoftwareProperties] = {
    if (req.json_?) {
      parseJson[JQNodeIdsSoftwareProperties](req)
    } else {
      extractNodeIdsSoftwarePropertiesFromParams(req.params)
    }
  }

  def extractUpdateNode(req: Req): PureResult[JQUpdateNode] = {
    if (req.json_?) {
      parseJson[JQUpdateNode](req)
    } else {
      extractUpdateNodeFromParams(req.params)
    }
  }

  def extractIncludeSystem(req: Req): PureResult[Option[Boolean]] = {
    if (req.json_?) {
      parseJson[JQIncludeSystem](req).map(_.includeSystem)
    } else {
      extractIncludeSystemFromParams(req.params)
    }
  }

  def extractAllowedNetworks(req: Req): PureResult[Option[Chunk[AllowedNetwork]]] = {
    if (req.json_?) {
      parseJson[JQAllowedNetworks](req).map(_.allowed_networks)
    } else {
      extractAllowedNetworksFromParams(req.params)
    }
  }

  def extractAllowedNetworksDiff(req: Req): PureResult[Option[JQAllowedNetworkDiff]] = {
    if (req.json_?) {
      parseJson[JQAllowedNetworksDiff](req).map(_.allowed_networks)
    } else {
      extractAllowedNetworksDiffFromParams(req.params)
    }
  }

  def extractIdsFromParams(params: Map[String, List[String]]): PureResult[Option[List[String]]] = {
    params.parseString("ids", s => Right(s.split(",").map(_.trim).toList))
  }

  def extractRuleFromParams(params: Map[String, List[String]]): PureResult[JQRule] = {
    for {
      id         <- params.parseString("id", RuleId.parse)
      enabled    <- params.parse("enabled", JsonDecoder[Boolean])
      directives <- params.parse("directives", JsonDecoder[Set[DirectiveId]])
      target     <- params.parse("targets", JsonDecoder[Set[JRRuleTarget]])
      tags       <- params.parse("tags", JsonDecoder[Tags])
      source     <- params.parseString("source", RuleId.parse)
    } yield {
      JQRule(
        id,
        params.optGet("displayName"),
        params.optGet("categoryId").orElse(params.optGet("category")),
        params.optGet("shortDescription"),
        params.optGet("longDescription"),
        directives,
        target,
        enabled,
        tags,
        source
      )
    }
  }

  def extractRuleCategoryFromParam(params: Map[String, List[String]]): PureResult[JQRuleCategory] = {
    Right(
      JQRuleCategory(
        params.optGet("name"),
        params.optGet("description"),
        params.optGet("parent"),
        params.optGet("id")
      )
    )
  }

  def extractDirectiveFromParams(params: Map[String, List[String]]): PureResult[JQDirective] = {

    for {
      id         <- params.parseString("id", DirectiveId.parse)
      enabled    <- params.parse("enabled", JsonDecoder[Boolean])
      priority   <- params.parse("priority", JsonDecoder[Int])
      parameters <- params.parse("parameters", JsonDecoder[Map[String, JQDirectiveSection]])
      policyMode <- params.parse2("policyMode", PolicyMode.parseDefault(_))
      tags       <- params.parse("tags", JsonDecoder[Tags])
      tv         <- params.parse2("techniqueVersion", TechniqueVersion.parse(_).left.map(Inconsistency(_)))
      source     <- params.parseString("source", DirectiveId.parse)
    } yield {
      JQDirective(
        id,
        params.optGet("displayName"),
        params.optGet("shortDescription"),
        params.optGet("longDescription"),
        enabled,
        parameters,
        priority,
        params.optGet("techniqueName").map(TechniqueName.apply),
        tv,
        policyMode,
        tags,
        source
      )
    }
  }

  def extractGlobalParamFromParams(params: Map[String, List[String]]): PureResult[JQGlobalParameter] = {
    for {
      value       <- params.parse2("value", GenericProperty.parseValue(_))
      inheritMode <- params.parse2("inheritMode", InheritMode.parseString(_))
    } yield {
      JQGlobalParameter(params.optGet("id"), value, params.optGet("description"), inheritMode)
    }
  }

  def extractGroupFromParams(params: Map[String, List[String]]): PureResult[JQGroup] = {
    for {
      enabled    <- params.parse("enabled", JsonDecoder[Boolean])
      dynamic    <- params.parse("dynamic", JsonDecoder[Boolean])
      query      <- params.parse2("query", queryParser.lex(_).toPureResult)
      properties <- params.parse("properties", JsonDecoder[List[GroupProperty]])
      id         <- params.parseString("id", NodeGroupId.parse)
      source     <- params.parseString("source", NodeGroupId.parse)
    } yield {
      JQGroup(
        id,
        params.optGet("displayName"),
        params.optGet("description"),
        properties,
        query,
        dynamic,
        enabled,
        params.optGet("categoryId").orElse(params.optGet("category")).map(NodeGroupCategoryId.apply),
        source
      )
    }
  }

  def extractGroupCategoryFromParams(params: Map[String, List[String]]): PureResult[JQGroupCategory] = {
    for {
      id            <- params.parse("id", JsonDecoder[NodeGroupCategoryId])
      name          <- params.parse("name", JsonDecoder[String])
      description   <- params.parse("description", JsonDecoder[String])
      parent        <- params.parse("parent", JsonDecoder[NodeGroupCategoryId])
      groupCategory <-
        JQGroupCategory
          .validate(
            JQGroupCategory(
              id,
              name,
              description,
              parent
            )
          )
          .left
          .map(Inconsistency(_))
    } yield {
      groupCategory
    }
  }

  def extractNodeDetailLevelFromParams(params: Map[String, List[String]]): PureResult[Option[JQNodeDetailLevel]] = {
    params.parseOne("include", _.split(",").toSet.transformInto[JQNodeDetailLevel])
  }

  def extractQueryFromParams(
      params: Map[String, List[String]]
  ): PureResult[Option[Query]] = {
    for {
      queryParam  <- params.parse("query", JsonDecoder[StringQuery])
      splitParams <- (for {
                       select      <- params.parseString("select", QueryReturnType.apply(_).left.map(_.fullMsg))
                       composition <- params.parseOne("composition", identity)
                       transform   <- params.parseOne("transform", identity)
                       // in RestExtractorService, we validate that this is not empty
                       where       <- params
                                        .parse("where", JsonDecoder[NonEmptyChunk[StringCriterionLine]])
                                        .chainError("Query should at least contain one criteria")
                     } yield {
                       // "where" param presence determines if there is a query
                       where.map(criteria => JQStringQuery(select, composition, transform, Some(criteria.toList)).toQueryString)
                     })
      stringQuery  = queryParam.orElse(splitParams)
      query       <- stringQuery.traverse(queryParser.parse(_).toPureResult)
    } yield {
      query
    }
  }

  def extractDeleteModeFromParams(
      params: Map[String, List[String]]
  ): PureResult[Option[DeleteMode]] = {
    params.parseString("mode", DeleteMode.withNameInsensitiveEither(_).left.map(_.getMessage()))
  }

  def extractClassesFromParams(
      params: Map[String, List[String]]
  ): List[String] = {
    params.get("classes").getOrElse(List.empty)
  }

  def extractNodeIdStatusFromParams(
      params: Map[String, List[String]]
  ): PureResult[JQNodeIdStatus] = {
    for {
      optNodeIds <- Right(params.get("nodeId").map(_.map(NodeId.apply)))
      nodeIds    <- optNodeIds.toRight(Inconsistency("You must add a node id as target"))
      status     <- extractNodeStatusFromParams(params)
    } yield {
      JQNodeIdStatus(nodeIds, status.status)
    }
  }

  def extractNodeStatusFromParams(
      params: Map[String, List[String]]
  ): PureResult[JQNodeStatus] = {
    for {
      optStatus <- params.parseString("status", JQNodeStatusAction.withNameInsensitiveEither(_).left.map(_.getMessage()))
      status    <- optStatus.toRight(Inconsistency("node status should not be empty"))
    } yield {
      JQNodeStatus(status)
    }
  }

  def extractNodeIdsSoftwarePropertiesFromParams(
      params: Map[String, List[String]]
  ): PureResult[JQNodeIdsSoftwareProperties] = {
    for {
      nodeIds    <- params.parse("nodeId", JsonDecoder[List[NodeId]])
      software   <- params.parse("software", JsonDecoder[List[String]])
      properties <- params.parse("properties", JsonDecoder[List[JQNodePropertyInfo]])
    } yield {
      JQNodeIdsSoftwareProperties(nodeIds, software, properties)
    }
  }

  def extractUpdateNodeFromParams(
      params: Map[String, List[String]]
  ): PureResult[JQUpdateNode] = {
    for {
      // parse `key=value` from the list of string
      properties <-
        params
          .get("properties")
          .traverse { props =>
            (props.traverse { prop =>
              val parts = prop.split('=')

              for {
                name <- PropertyParser.validPropertyName(parts(0))
                prop <- if (parts.size == 1) NodeProperty.parse(name, "", None, None)
                        else NodeProperty.parse(name, parts(1), None, None)
              } yield {
                prop
              }
            })
          }
      policyMode <- params.parse2("policyMode", PolicyMode.parseDefault(_))
      state      <- params.parseString("state", NodeState.parse(_))
      agentKey   <- params.parse("agentKey", JsonDecoder[JQAgentKey])
      doc         = params.optGet("documentation")
      reason      = params.optGet("reason")
    } yield {
      JQUpdateNode(properties, policyMode, state, agentKey, doc, reason)
    }
  }

  def extractIncludeSystemFromParams(params: Map[String, List[String]]): PureResult[Option[Boolean]] = {
    params.parse("includeSystem", JsonDecoder[Boolean])
  }

  def extractAllowedNetworksFromParams(params: Map[String, List[String]]): PureResult[Option[Chunk[AllowedNetwork]]] = {
    params.parse("allowed_networks", JsonDecoder[JQAllowedNetworks]).map(_.flatMap(_.allowed_networks))
  }

  def extractAllowedNetworksDiffFromParams(params: Map[String, List[String]]): PureResult[Option[JQAllowedNetworkDiff]] = {
    params.parse("allowed_networks", JsonDecoder[JQAllowedNetworkDiff])
  }
}
