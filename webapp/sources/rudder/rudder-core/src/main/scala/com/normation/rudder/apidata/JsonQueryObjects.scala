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

import com.normation.GitVersion
import com.normation.GitVersion.ParseRev
import com.normation.GitVersion.Revision
import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.domain.TechniqueVersion
import com.normation.errors._
import com.normation.errors.PureResult
import com.normation.errors.Unexpected
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.nodes.NodeGroupCategoryId
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.policies._
import com.normation.rudder.domain.properties.CompareProperties
import com.normation.rudder.domain.properties.GenericProperty
import com.normation.rudder.domain.properties.GlobalParameter
import com.normation.rudder.domain.properties.GroupProperty
import com.normation.rudder.domain.properties.InheritMode
import com.normation.rudder.domain.properties.PatchProperty
import com.normation.rudder.domain.properties.PropertyProvider
import com.normation.rudder.domain.queries.NodeReturnType
import com.normation.rudder.domain.queries.Query
import com.normation.rudder.domain.queries.QueryReturnType
import com.normation.rudder.rule.category.RuleCategory
import com.normation.rudder.rule.category.RuleCategoryId
import com.normation.rudder.services.queries.CmdbQueryParser
import com.normation.rudder.services.queries.JsonQueryLexer
import com.normation.rudder.services.queries.StringCriterionLine
import com.normation.rudder.services.queries.StringQuery
import com.typesafe.config.ConfigValue
import io.scalaland.chimney.dsl._
import net.liftweb.common._
import net.liftweb.http.Req
import zio.json._

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

  final case class JQRuleCategory(
      name:        Option[String] = None,
      description: Option[String] = None,
      parent:      Option[String] = None,
      id:          Option[String] = None
  ) {

    def update(ruleCategory: RuleCategory) = {
      ruleCategory.using(this).ignoreRedundantPatcherFields.patch
    }
  }

  final case class JQDirectiveSectionVar(
      name:  String,
      value: String
  )
  final case class JQDirectiveSection(
      name: String, // Map is necessary b/c of the "var" in {"vars": [ {"var": { "name":..., "value":... }}}

      sections: Option[
        List[Map[String, JQDirectiveSection]]
      ], // Map is necessary b/c of the "var" in {"vars": [ {"var": { "name":..., "value":... }}}

      vars: Option[List[Map[String, JQDirectiveSectionVar]]]
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
    val onlyName = displayName.isDefined &&
      shortDescription.isEmpty &&
      longDescription.isEmpty &&
      enabled.isEmpty &&
      parameters.isEmpty &&
      priority.isEmpty &&
      techniqueName.isEmpty &&
      techniqueVersion.isEmpty &&
      policyMode.isEmpty &&
      tags.isEmpty // no need to check source or reason

    def updateDirective(directive: Directive) = {
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
      id:               Option[RuleId] = None,
      displayName:      Option[String] = None,
      category:         Option[String] = None,
      shortDescription: Option[String] = None,
      longDescription:  Option[String] = None,
      directives:       Option[Set[DirectiveId]] = None,
      targets:          Option[Set[JRRuleTarget]] = None,
      enabled:          Option[Boolean] = None,
      tags:             Option[Tags] = None, // for clone

      source: Option[RuleId] = None
  ) {

    val onlyName = displayName.isDefined &&
      category.isEmpty &&
      shortDescription.isEmpty &&
      longDescription.isEmpty &&
      directives.isEmpty &&
      targets.isEmpty &&
      enabled.isEmpty &&
      tags.isEmpty // no need to check source or reason

    def updateRule(rule: Rule) = {
      val updateRevision   = id.map(_.rev).getOrElse(rule.id.rev)
      val updateName       = displayName.getOrElse(rule.name)
      val updateCategory   = category.map(RuleCategoryId.apply).getOrElse(rule.categoryId)
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
    def updateParameter(parameter: GlobalParameter) = {
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
    def toGroupProperty = GroupProperty(
      name,
      rev.map(Revision.apply).getOrElse(GitVersion.DEFAULT_REV),
      value,
      inheritMode,
      Some(PropertyProvider.defaultPropertyProvider)
    )
  }

  final case class JQStringQuery(
      returnType:  Option[QueryReturnType],
      composition: Option[String],
      transform:   Option[String],
      where:       Option[List[StringCriterionLine]]
  ) {
    def toQueryString = StringQuery(returnType.getOrElse(NodeReturnType), composition, transform, where.getOrElse(Nil))
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

  final case class JQGroup(
      id:          Option[NodeGroupId] = None,
      displayName: Option[String] = None,
      description: Option[String] = None,
      properties:  Option[List[GroupProperty]] = None,
      query:       Option[StringQuery] = None,
      dynamic:     Option[Boolean] = None,
      enabled:     Option[Boolean] = None,
      category:    Option[NodeGroupCategoryId] = None,
      source:      Option[NodeGroupId] = None
  ) {

    val onlyName = displayName.isDefined &&
      description.isEmpty &&
      properties.isEmpty &&
      query.isEmpty &&
      dynamic.isEmpty &&
      enabled.isEmpty &&
      category.isEmpty

    def updateGroup(group: NodeGroup, queryParser: CmdbQueryParser): PureResult[NodeGroup] = {
      for {
        q <- query match {
               case None    => Right(None)
               case Some(x) => queryParser.parse(x).toPureResult.map(Some(_))
             }
        p <- CompareProperties.updateProperties(group.properties, properties)
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

  // policy servers are serialized in their output format
}

trait RudderJsonDecoders {
  import JsonQueryObjects._
  import JsonResponseObjects._
  import JsonResponseObjects.JRRuleTarget._

  // JRRuleTarget
  object JRRuleTargetDecoder {
    def parseString(s: String) = RuleTarget.unserOne(s) match {
      case None    => Left(s"'${s}' can not be decoded as a simple rule target")
      case Some(x) => Right(JRRuleTargetString(x))
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
    import zio.json._
    s.fromJson[JRRuleTarget]
      .orElse(RuleTarget.unserOne(s) match {
        case None    => Left(s"Error: the following string can't not be decoded as a rule target: '${s}''")
        case Some(x) => Right(JRRuleTargetString(x))
      })
  }

  // tags
  implicit val tagsDecoder:       JsonDecoder[Tags]               = JsonDecoder[List[Map[String, String]]].map(list => Tags.fromMaps(list))
  // PolicyMode
  implicit val policyModeDecoder: JsonDecoder[Option[PolicyMode]] = JsonDecoder[Option[String]].mapOrFail(opt => {
    opt match {
      case None            => Right(None)
      // we need to be able to set "default", for example to reset in clone
      case Some("default") => Right(None)
      case Some(s)         => PolicyMode.parse(s).left.map(_.fullMsg).map(Some(_))
    }
  })

  implicit val revisionDecoder: JsonDecoder[Revision] = JsonDecoder[String].map(ParseRev(_))

  // technique name/version
  implicit val techniqueNameDecoder:       JsonDecoder[TechniqueName]    = JsonDecoder[String].map(TechniqueName.apply)
  implicit val techniqueVersionDecoder:    JsonDecoder[TechniqueVersion] = JsonDecoder[String].mapOrFail(TechniqueVersion.parse(_))
  implicit lazy val ruleCategoryIdDecoder: JsonDecoder[RuleCategoryId]   = JsonDecoder[String].map(RuleCategoryId.apply)
  implicit lazy val directiveIdsDecoder:   JsonDecoder[Set[DirectiveId]] = {
    import cats.implicits._
    JsonDecoder[List[String]].mapOrFail(list => list.traverse(x => DirectiveId.parse(x)).map(_.toSet))
  }
  implicit val ruleIdIdDecoder:            JsonDecoder[RuleId]           = JsonDecoder[String].mapOrFail(x => RuleId.parse(x))
  implicit val directiveIdDecoder:         JsonDecoder[DirectiveId]      = JsonDecoder[String].mapOrFail(x => DirectiveId.parse(x))

  // RestRule
  implicit lazy val ruleDecoder: JsonDecoder[JQRule] = DeriveJsonDecoder.gen

  implicit val ruleCategoryDecoder: JsonDecoder[JQRuleCategory] = DeriveJsonDecoder.gen

  // RestDirective - lazy because section/sectionVar/directive are mutually recursive
  implicit lazy val sectionDecoder:   JsonDecoder[JQDirectiveSection]    = DeriveJsonDecoder.gen
  implicit lazy val variableDecoder:  JsonDecoder[JQDirectiveSectionVar] = DeriveJsonDecoder.gen
  implicit lazy val directiveDecoder: JsonDecoder[JQDirective]           = DeriveJsonDecoder.gen

  // RestGlobalParameter
  implicit val inheritModeDecoder:     JsonDecoder[InheritMode]       =
    JsonDecoder[String].mapOrFail(s => InheritMode.parseString(s).left.map(_.fullMsg))
  implicit val configValueDecoder:     JsonDecoder[ConfigValue]       = JsonDecoder[ast.Json].map(GenericProperty.fromZioJson(_))
  implicit val globalParameterDecoder: JsonDecoder[JQGlobalParameter] = DeriveJsonDecoder.gen

  // Rest group
  implicit val nodeGroupCategoryIdDecoder:      JsonDecoder[NodeGroupCategoryId] = JsonDecoder[String].map(NodeGroupCategoryId.apply)
  implicit val queryStringCriterionLineDecoder: JsonDecoder[StringCriterionLine] = DeriveJsonDecoder.gen
  implicit val queryReturnTypeDecoder:          JsonDecoder[QueryReturnType]     = DeriveJsonDecoder.gen
  implicit val queryDecoder:                    JsonDecoder[StringQuery]         = DeriveJsonDecoder.gen[JQStringQuery].map(_.toQueryString)
  implicit val groupPropertyDecoder:            JsonDecoder[JQGroupProperty]     = DeriveJsonDecoder.gen
  implicit val groupPropertyDecoder2:           JsonDecoder[GroupProperty]       = JsonDecoder[JQGroupProperty].map(_.toGroupProperty)
  implicit val nodeGroupIdDecoder:              JsonDecoder[NodeGroupId]         = JsonDecoder[String].mapOrFail(x => NodeGroupId.parse(x))
  implicit val groupDecoder:                    JsonDecoder[JQGroup]             = DeriveJsonDecoder.gen

}

object ZioJsonExtractor {

  /**
   * Parse request body as JSON, and decode it as type `A`.
   * This is the root method to transform a JSON query into a Rest object.
   */
  def parseJson[A](req: Req)(implicit decoder: JsonDecoder[A]): PureResult[A] = {
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
class ZioJsonExtractor(queryParser: CmdbQueryParser with JsonQueryLexer) {
  import JsonQueryObjects._
  import JsonResponseObjects._
  import ZioJsonExtractor.parseJson
  import implicits._

  /**
   * Utilities to extract values from params Map
   */
  implicit class Extract(params: Map[String, List[String]]) {
    def optGet(key: String):                                               Option[String]        = params.get(key).flatMap(_.headOption)
    def parse[A](key: String, decoder: JsonDecoder[A]):                    PureResult[Option[A]] = {
      optGet(key) match {
        case None    => Right(None)
        case Some(x) => decoder.decodeJson(x).map(Some(_)).left.map(Unexpected)
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
        case Some(x) => decoder(x).map(Some(_)).left.map(Inconsistency)
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
        params.optGet("category"),
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
        params.optGet("techniqueName").map(TechniqueName),
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
        params.optGet("category").map(NodeGroupCategoryId.apply),
        source
      )
    }
  }

}
