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

package com.normation.rudder.rest

import com.github.ghik.silencer.silent
import com.normation.cfclerk.domain.Technique
import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.domain.TechniqueVersion
import com.normation.errors.PureResult
import com.normation.errors.Unexpected
import com.normation.rudder.domain.policies._
import com.normation.rudder.domain.workflows.ChangeRequestId
import com.normation.rudder.rule.category.RuleCategory
import com.normation.rudder.rule.category.RuleCategoryId
import net.liftweb.common._
import net.liftweb.http.InMemoryResponse
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req
import zio.json.DeriveJsonEncoder
import zio.json.JsonCodec._
import zio.json.{EncoderOps => _, _}
import zio.json.internal.Write
import com.normation.errors._
import com.normation.rudder.domain.nodes.InheritMode
import com.normation.rudder.domain.nodes.PropertyProvider
import com.normation.rudder.domain.parameters.GlobalParameter
import com.normation.rudder.repository.FullActiveTechnique
import com.normation.zio._
import com.normation.rudder.rest.lift.DefaultParams
import com.typesafe.config.ConfigRenderOptions
import com.typesafe.config.ConfigValue
import com.normation.rudder.domain.nodes.CompareProperties
import com.normation.rudder.domain.nodes.GenericProperty
import com.normation.rudder.domain.nodes.GroupProperty
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.nodes.NodeGroupCategoryId
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.nodes.NodePropertyHierarchy
import com.normation.rudder.domain.nodes.ParentProperty
import com.normation.rudder.domain.nodes.PatchProperty
import com.normation.rudder.domain.queries.CriterionLine
import com.normation.rudder.domain.queries.NodeReturnType
import com.normation.rudder.domain.queries.QueryReturnType
import com.normation.rudder.domain.queries.QueryTrait
import com.normation.rudder.domain.queries.ResultTransformation
import com.normation.rudder.rest.JsonResponseObjects.JRPropertyHierarchy.JRPropertyHierarchyHtml
import com.normation.rudder.rest.JsonResponseObjects.JRPropertyHierarchy.JRPropertyHierarchyJson
import com.normation.rudder.rest.lift.RenderInheritedProperties
import com.normation.rudder.services.queries.CmdbQueryParser
import com.normation.rudder.services.queries.JsonQueryLexer
import com.normation.rudder.services.queries.StringCriterionLine
import com.normation.rudder.services.queries.StringQuery
import com.softwaremill.quicklens._
import io.scalaland.chimney.dsl._

/*
 * This class deals with everything serialisation related for API.
 * Change things with care! Everything must be versionned!
 * Even changing a field name can lead to an API incompatible change and
 * so will need a new API version number (and be sure that old behavior is kept
 * for previous versions).
 */


/*
 * Rudder standard response.
 * We normalize response format to look like what is detailed here: https://docs.rudder.io/api/v/13/#section/Introduction/Response-format
 * Data are always name-spaced, so that theorically an answer can mixe several type of data. For example, for node details:
 *     "data": { "nodes": [ ... list of nodes ... ] }
 * And for globalCompliance:
 *     "data": { "globalCompliance": { ... } }
 */
object RudderJsonResponse {
  //////////////////////////// general structure of a JSON response ////////////////////////////
  // and utilities for specific objects

  final case class JsonRudderApiResponse[A](
      action      : String
    , id          : Option[String]
    , result      : String
    , data        : Option[A]
    , errorDetails: Option[String]
  )
  object JsonRudderApiResponse {
    def error(schema: ResponseSchema, message: String): JsonRudderApiResponse[Unit] =
      JsonRudderApiResponse(schema.action, None, "error", None, Some(message))

    def success[A](schema: ResponseSchema, id: Option[String], data: A): JsonRudderApiResponse[A] =
      JsonRudderApiResponse(schema.action, id, "success", Some(data), None)
  }


  //////////////////////////// Lift JSON response ////////////////////////////

  final case class RudderJsonResponse[A](json: A, prettify: Boolean, code: Int)(implicit encoder: JsonEncoder[A]) extends LiftResponse {
    def toResponse = {
      val indent = if(prettify) Some(2) else None
      val bytes = encoder.encodeJson(json, indent).toString.getBytes("UTF-8")
      InMemoryResponse(bytes, ("Content-Length", bytes.length.toString) :: ("Content-Type", "application/json; charset=utf-8") :: Nil, Nil, code)
    }
  }

  /*
   * Information about schema needed to build response
   */
  final case class ResponseSchema(
      action       : String
    , dataContainer: Option[String]
  )

  object ResponseSchema {
    def fromSchema(schema: EndpointSchema) = ResponseSchema(schema.name, schema.dataContainer)
  }

  //////////////////////////// utility methods to build responses ////////////////////////////

  object generic {
    // generic response, not in rudder normalized format - use it if you want an ad-hoc json response.
    def success[A]       (json: A)(implicit prettify: Boolean, encoder: JsonEncoder[A]) = RudderJsonResponse(json, prettify, 200)
    def internalError[A] (json: A)(implicit prettify: Boolean, encoder: JsonEncoder[A]) = RudderJsonResponse(json, prettify, 500)
    def notFoundError[A] (json: A)(implicit prettify: Boolean, encoder: JsonEncoder[A]) = RudderJsonResponse(json, prettify, 404)
    def forbiddenError[A](json: A)(implicit prettify: Boolean, encoder: JsonEncoder[A]) = RudderJsonResponse(json, prettify, 404)
  }

  trait DataContainer[A] {
    def name: String
    def data: A
  }

  //rudder response. The "A" parameter is the business object (or list of it) in the response.
  // Success
  @silent("parameter value encoder .* is never used") // used by magnolia macro
  def successOne[A](schema: ResponseSchema, obj: A, id: String)(implicit prettify: Boolean, encoder: JsonEncoder[A]) = {
    schema.dataContainer match {
      case Some(key) =>
        implicit val enc : JsonEncoder[JsonRudderApiResponse[Map[String, List[A]]]] = DeriveJsonEncoder.gen
        generic.success(JsonRudderApiResponse.success(schema, Some(id), Map((key, List(obj)))))
      case None      => // in that case, the object is not even in a list
        implicit val enc : JsonEncoder[JsonRudderApiResponse[A]] = DeriveJsonEncoder.gen
        generic.success(JsonRudderApiResponse.success(schema, Some(id), obj))
    }
  }
  @silent("parameter value encoder .* is never used") // used by magnolia macro
  def successList[A](schema: ResponseSchema, objs: List[A])(implicit prettify: Boolean, encoder: JsonEncoder[A]) = {
    schema.dataContainer match {
      case None      =>
        implicit val enc : JsonEncoder[JsonRudderApiResponse[List[A]]] = DeriveJsonEncoder.gen
        generic.success(JsonRudderApiResponse.success(schema, None, objs))
      case Some(key) =>
        implicit val enc : JsonEncoder[JsonRudderApiResponse[Map[String, List[A]]]] = DeriveJsonEncoder.gen
        generic.success(JsonRudderApiResponse.success(schema, None, Map(key -> objs)))
    }
  }
  // errors
  implicit val nothing: JsonEncoder[Option[Unit]] = new JsonEncoder[Option[Unit]] {
    def unsafeEncode(n: Option[Unit], indent: Option[Int], out: zio.json.internal.Write): Unit = out.write("null")
    override def isNothing(a: Option[Unit]): Boolean = true
  }
  implicit val errorEncoder   : JsonEncoder[JsonRudderApiResponse[Unit]] = DeriveJsonEncoder.gen

  def internalError (schema: ResponseSchema, errorMsg: String)(implicit prettify: Boolean) = {
    generic.internalError(JsonRudderApiResponse.error(schema, errorMsg))
  }
  def notFoundError (schema: ResponseSchema, errorMsg: String)(implicit prettify: Boolean) = {
    generic.notFoundError(JsonRudderApiResponse.error(schema, errorMsg))
  }
  def forbiddenError(schema: ResponseSchema, errorMsg: String)(implicit prettify: Boolean) = {
    generic.forbiddenError(JsonRudderApiResponse.error(schema, errorMsg))
  }

  //import that to transform a class from JsonResponse into a lift response. An encoder for the JsonResponse classe
  // must be available.
  trait implicits {
    implicit class ToLiftResponseList[A](result: IOResult[Seq[A]]) {
      def toLiftResponseList(params: DefaultParams, schema: ResponseSchema)(implicit encoder: JsonEncoder[A]): LiftResponse = {
        implicit val prettify = params.prettify
        result.fold(
          err => internalError(schema, err.fullMsg)
        , seq => successList(schema, seq.toList)
        ).runNow
      }
      def toLiftResponseList(params: DefaultParams, schema: EndpointSchema)(implicit encoder: JsonEncoder[A]): LiftResponse = {
        toLiftResponseList(params, ResponseSchema.fromSchema(schema))
      }
    }
    implicit class ToLiftResponseOne[A](result: IOResult[A]) {
      def toLiftResponseOne(params: DefaultParams, schema: ResponseSchema, id: A => String)(implicit encoder: JsonEncoder[A]): LiftResponse = {
        implicit val prettify = params.prettify
        result.fold(
          err => internalError(schema, err.fullMsg)
        , one => successOne(schema, one, id(one))
        ).runNow
      }
      def toLiftResponseOne(params: DefaultParams, schema: EndpointSchema, id: A => String)(implicit encoder: JsonEncoder[A]): LiftResponse = {
        toLiftResponseOne(params, ResponseSchema.fromSchema(schema), id)
      }
      // when the computation give the response schema
      def toLiftResponseOneMap[B](params: DefaultParams, errorSchema: ResponseSchema, map: A => (ResponseSchema, B, String))(implicit encoder: JsonEncoder[B]): LiftResponse = {
        implicit val prettify = params.prettify
        result.fold(
          err => internalError(errorSchema, err.fullMsg)
        , one => {
            val (schema, x, id) = map(one)
            successOne(schema, x, id)
          }).runNow
      }
    }

  }
}

//////////////////////////// Case classes used in JSON answers ////////////////////////////

// to avoid ambiguity with corresponding business objects, we use "JR" as a prfix
object JsonResponseObjects {
  final case class JRActiveTechnique(
      name    : String
    , versions: List[String]
  )

  object JRActiveTechnique {
    def fromTechnique(activeTechnique: FullActiveTechnique) = {
      JRActiveTechnique(activeTechnique.techniqueName.value, activeTechnique.techniques.map(_._1.toString ).toList)
    }
  }

  /*
   * "sections": [
   * {
   *   "section": {
   *     "name": "File to manage",
   *     "sections": [
   *       {
   *         "section": {
   *           "name": "Enforce content by section",
   *           "vars": [
   *             {
   *               "var": {
   *                 "name": "GENERIC_FILE_CONTENT_SECTION_MANAGEMENT",
   *                 "value": "false"
   *               }
   *             },
   *             {
   *               "var": {
   *                 "name": "GENERIC_FILE_SECTION_CONTENT",
   *                 "value": ""
   *               }
   *             },
   *      ],
   *      "vars": [ .... ]
   * .....
   */
  final case class JRDirectiveSectionVar(
      name : String
    , value: String
  )
  final case class JRDirectiveSection(
      name    : String
      // we have one more "var" indirection level between a var and its details:
      // { vars":[ { "var":{ "name": .... } }, { "var": { ... }} ]
    , vars    : Option[List[Map[String, JRDirectiveSectionVar]]]
      // we have one more "section" indirection level between a section and its details:
      // { sections":[ { "section":{ "name": .... } }, { "section": { ... }} ]
    , sections: Option[List[Map[String, JRDirectiveSection]]]
  )
  // we have one more level between a directive section and a section
  final case class JRDirectiveSerctionHolder(
    section: JRDirectiveSection
  )

  object JRDirectiveSection {
    def fromSectionVal(name: String, sectionVal: SectionVal): JRDirectiveSection = {
      JRDirectiveSection(
          name     = name
        , sections = sectionVal.sections.toList.sortBy(_._1) match {
                       case Nil  => None
                       case list => Some(list.flatMap { case (n, sections) => sections.map(s => Map("section" -> fromSectionVal(n, s))) })
                     }
        , vars     = sectionVal.variables.toList.sortBy(_._1)match {
                       case Nil  => None
                       case list => Some(list.map { case (n, v) => Map("var" -> JRDirectiveSectionVar(n, v)) })
                     }
      )
    }
  }
  final case class JRDirective(
      changeRequestId  : Option[String]
    , id               : String
    , displayName      : String
    , shortDescription : String
    , longDescription  : String
    , techniqueName    : String
    , techniqueVersion : String
    , parameters       : Map[String, JRDirectiveSection]
    , priority         : Int
    , enabled          : Boolean
    , system           : Boolean
    , policyMode       : String
    , tags             : List[Map[String, String]]
  )

  object JRDirective {
    def empty(id: String) = JRDirective(None, id, "", "", "", "", "", Map(), 5, false, false, "", List())

    def fromDirective(technique: Technique, directive: Directive, crId: Option[ChangeRequestId]): JRDirective = {
      directive.into[JRDirective]
        .enableBeanGetters
        .withFieldConst(_.changeRequestId, crId.map(_.value.toString))
        .withFieldComputed(_.id, _.id.value)
        .withFieldRenamed(_.name, _.displayName)
        .withFieldConst(_.techniqueName, technique.id.name.value)
        .withFieldComputed(_.techniqueVersion, _.techniqueVersion.toString)
        .withFieldConst(_.parameters, Map("section" -> JRDirectiveSection.fromSectionVal(SectionVal.ROOT_SECTION_NAME, SectionVal.directiveValToSectionVal(technique.rootSection, directive.parameters))))
        .withFieldComputed(_.policyMode, _.policyMode.map(_.name).getOrElse("default"))
        .withFieldComputed(_.tags, x => JRTags.fromTags(x.tags))
        .transform
    }
  }

  final case class JRDirectives(directives: List[JRDirective])

  final case class JRRule(
      changeRequestId : Option[String] = None
    , id              : String
    , displayName     : String
    , categoryId      : String
    , shortDescription: String
    , longDescription : String
    , directives      : List[String] //directives ids
    , targets         : List[JRRuleTarget]
    , enabled         : Boolean
    , system          : Boolean
    , tags            : List[Map[String, String]]
  )
  object JRRule {
    // create an empty json rule with just ID set
    def empty(id: String) = JRRule(None, id, "", "", "", "", Nil, Nil, false, false, Nil)

    // create from a rudder business rule
    def fromRule(rule: Rule, crId: Option[ChangeRequestId]): JRRule = {
      rule.into[JRRule]
        .enableBeanGetters
        .withFieldConst(_.changeRequestId, crId.map(_.value.toString))
        .withFieldRenamed(_.name, _.displayName)
        .withFieldComputed(_.categoryId, _.categoryId.value)
        .withFieldComputed(_.directives, _.directiveIds.map(_.value).toList.sorted)
        .withFieldComputed(_.targets, _.targets.toList.sortBy(_.target).map(t => JRRuleTarget(t)))
        .withFieldRenamed(_.isEnabledStatus, _.enabled)
        .withFieldComputed(_.tags, x => JRTags.fromTags(rule.tags))
        .transform
    }
  }

  object JRTags {
    def fromTags(tags: Tags): List[Map[String, String]] = {
      tags.tags.toList.sortBy(_.name.value).map ( t => Map((t.name.value, t.value.value)) )
    }
  }

  final case class JRRules(rules: List[JRRule])

  sealed trait JRRuleTarget {
    def toRuleTarget: RuleTarget
  }
  object JRRuleTarget {
    def apply(t: RuleTarget): JRRuleTarget = {
      def compose(x:TargetComposition): JRRuleTargetComposition = x match {
        case TargetUnion(targets)        => JRRuleTargetComposition.or(x.targets.toList.map(JRRuleTarget(_)))
        case TargetIntersection(targets) => JRRuleTargetComposition.and(x.targets.toList.map(JRRuleTarget(_)))
      }

      t match {
        case x:SimpleTarget      => JRRuleTargetString(x)
        case x:TargetComposition => compose(x)
        case x:TargetExclusion   => JRRuleTargetComposed(compose(x.includedTarget), compose(x.excludedTarget))
      }
    }

    final case class JRRuleTargetString(r: SimpleTarget) extends JRRuleTarget {
      override def toRuleTarget: RuleTarget = r
    }
    final case class JRRuleTargetComposed(
      include: JRRuleTargetComposition
    , exclude: JRRuleTargetComposition
    ) extends JRRuleTarget {
      override def toRuleTarget: RuleTarget = TargetExclusion(include.toRuleTarget, exclude.toRuleTarget)
    }
    sealed trait JRRuleTargetComposition extends JRRuleTarget {
      override def toRuleTarget: TargetComposition
    }
    object JRRuleTargetComposition {
      final case class or(list: List[JRRuleTarget])   extends JRRuleTargetComposition {
        override def toRuleTarget: TargetComposition = TargetUnion(list.map(_.toRuleTarget).toSet)
      }
      final case class and(list: List[JRRuleTarget]) extends JRRuleTargetComposition {
        override def toRuleTarget: TargetComposition = TargetUnion(list.map(_.toRuleTarget).toSet)
      }
    }
  }

  // CategoryKind is either JRRuleCategory or String (category id)
  // RuleKind is either JRRule or String (rule id)
  final case class JRFullRuleCategory(
      id: String
    , name: String
    , description: String
    , parent: Option[String]
    , categories: List[JRFullRuleCategory]
    , rules: List[JRRule]
  )
  object JRFullRuleCategory {
    /*
     * Prepare for json.
     * Sort field by ID to keep diff easier.
     */
    def fromCategory(cat: RuleCategory, allRules: Map[String, Seq[Rule]], parent: Option[String]): JRFullRuleCategory = {
      cat.into[JRFullRuleCategory]
        .withFieldConst(_.parent, parent)
        .withFieldComputed(_.categories, _.childs.map(c => JRFullRuleCategory.fromCategory(c, allRules, Some(cat.id.value))).sortBy(_.id))
        .withFieldConst(_.rules, allRules.get(cat.id.value).getOrElse(Nil).map(JRRule.fromRule(_, None)).toList.sortBy(_.id))
        .transform
    }
  }

  // when returning a root category, we have a "data":{"ruleCategories":{.... }}. Seems like a bug, though
  final case class JRCategoriesRootEntryFull(ruleCategories: JRFullRuleCategory)
  final case class JRCategoriesRootEntrySimple(ruleCategories: JRSimpleRuleCategory)

  final case class JRSimpleRuleCategory(
      id: String
    , name: String
    , description: String
    , parent: String
    , categories: List[String]
    , rules: List[String]
  )
  object JRSimpleRuleCategory {
    def fromCategory(cat: RuleCategory, parent: String, rules: List[String]) = {
      cat.into[JRSimpleRuleCategory]
        .withFieldComputed(_.id, _.id.value)
        .withFieldConst(_.parent, parent)
        .withFieldComputed(_.categories, _.childs.map(_.id.value).sorted)
        .withFieldConst(_.rules, rules)
        .transform
    }
  }

  final case class JRGlobalParameter(
      changeRequestId: Option[String] = None
    , id             : String
    , value          : ConfigValue
    , description    : String
    , inheritMode    : Option[InheritMode]
    , provider       : Option[PropertyProvider]
  )

  object JRGlobalParameter {
    import com.normation.rudder.domain.nodes.GenericProperty._
    def empty(name: String) = JRGlobalParameter(None, name, "".toConfigValue, "", None, None)
    def fromGlobalParameter(p: GlobalParameter, crId: Option[ChangeRequestId]): JRGlobalParameter = {
      JRGlobalParameter(crId.map(_.value.toString), p.name, p.value, p.description, p.inheritMode, p.provider)
    }
  }

  // similar to JRGlobalParameter but s/id/name and no changeRequestId
  final case class JRProperty(
      name        : String
    , value       : ConfigValue
    , description : Option[String]
    , inheritMode : Option[InheritMode]
    , provider    : Option[PropertyProvider]
    , hierarchy   : Option[JRPropertyHierarchy]
    , origval     : Option[ConfigValue]
  )
  object JRProperty {
    def fromGroupProp(p: GroupProperty) = {
      val desc = if(p.description.trim.isEmpty) None else Some(p.description)
      JRProperty(p.name, p.value, desc, p.inheritMode, p.provider, None, None)
    }

    def fromNodePropertyHierarchy(prop: NodePropertyHierarchy, renderInHtml: RenderInheritedProperties) = {
      val (parents, origval) = prop.hierarchy.reverse match {
      case Nil  => (None, None)
      case list =>
        val parents = renderInHtml match {
          case RenderInheritedProperties.HTML =>
            JRPropertyHierarchyHtml(list.map(p =>
              s"<p>from <b>${p.displayName}</b>:<pre>${p.value.render(ConfigRenderOptions.defaults().setOriginComments(false))}</pre></p>"
            ).mkString(""))
          case RenderInheritedProperties.JSON =>
            JRPropertyHierarchyJson(list.map(JRParentProperty.fromParentProperty(_)))
        }
        (Some(parents), prop.hierarchy.headOption.map(_.value))
      }
      val desc = if(prop.prop.description.trim.isEmpty) None else Some(prop.prop.description)
      JRProperty(prop.prop.name, prop.prop.value, desc, prop.prop.inheritMode, prop.prop.provider, parents, origval)
    }
  }

  @jsonDiscriminator("kind") sealed trait JRParentProperty { def value: ConfigValue }
  object JRParentProperty {
    @jsonHint("global")
    final case class JRParentGlobal(
        value: ConfigValue
    ) extends JRParentProperty

    @jsonHint("group")
    final case class JRParentGroup(
        name : String
      , id   : String
      , value: ConfigValue
    ) extends JRParentProperty

    def fromParentProperty(p: ParentProperty) = {
      p match {
        case ParentProperty.Group(name, id, value) =>
          JRParentGroup(name, id.value, value)
        case _                                     =>
          JRParentGlobal(p.value)
      }
    }
  }

  sealed trait JRPropertyHierarchy extends Product
  object JRPropertyHierarchy {
    final case class JRPropertyHierarchyHtml(html: String) extends JRPropertyHierarchy
    final case class JRPropertyHierarchyJson(parents: List[JRParentProperty]) extends JRPropertyHierarchy
  }

  final case class JRGroupInheritedProperties(
      groupId   : String
    , properties: List[JRProperty]
  )

  object JRGroupInheritedProperties {
    def fromGroup(groupId: NodeGroupId, properties: List[NodePropertyHierarchy], renderInHtml: RenderInheritedProperties) = {
      JRGroupInheritedProperties(groupId.value, properties.sortBy(_.prop.name).map(JRProperty.fromNodePropertyHierarchy(_, renderInHtml)))
    }
  }

  final case class JRCriterium(
      objectType: String
    , attribute : String
    , comparator: String
    , value     : String
  )

  object JRCriterium {
    def fromCriterium(c: CriterionLine) = {
      c.into[JRCriterium]
        .withFieldComputed(_.objectType, _.objectType.objectType)
        .withFieldComputed(_.attribute, _.attribute.name)
        .withFieldComputed(_.comparator, _.comparator.id)
        .transform
    }
  }

  final case class JRQuery(
      select     : String
    , composition: String
    , transform  : Option[String]
    , where      : List[JRCriterium]
  )

  object JRQuery {
    def fromQuery(query: QueryTrait) = {
      JRQuery(
          query.returnType.value
        , query.composition.value
        , query.transform match {
            case ResultTransformation.Identity => None
            case x                             => Some(x.value)
          }
        , query.criteria.map(JRCriterium.fromCriterium(_))
      )
    }
  }

  final case class JRGroup(
      changeRequestId : Option[String] = None
    , id              : String
    , displayName     : String
    , description     : String
    , category        : String
    , query           : Option[JRQuery]
    , nodeIds         : List[String]
    , dynamic         : Boolean
    , enabled         : Boolean
    , groupClass      : List[String]
    , properties      : List[JRProperty]
  )
  object JRGroup {
    def empty(id: String) = JRGroup(None, id, "", "", "", None, Nil, false, false, Nil, Nil)

    def fromGroup(group: NodeGroup, catId: NodeGroupCategoryId, crId: Option[ChangeRequestId]) = {
      group.into[JRGroup]
        .enableBeanGetters
        .withFieldConst(_.changeRequestId, crId.map(_.value.toString))
        .withFieldComputed(_.id, _.id.value)
        .withFieldRenamed(_.name, _.displayName)
        .withFieldConst(_.category, catId.value)
        .withFieldComputed(_.query, _.query.map(JRQuery.fromQuery(_)))
        .withFieldComputed(_.nodeIds, _.serverList.toList.map(_.value).sorted)
        .withFieldComputed(_.groupClass, x => List(x.id.value, x.name).map(RuleTarget.toCFEngineClassName _).sorted)
        .withFieldComputed(_.properties, _.properties.map(JRProperty.fromGroupProp(_)))
        .transform
    }
  }

}
//////////////////////////// zio-json encoders ////////////////////////////

trait RudderJsonEncoders {
  import JsonResponseObjects._
  import JsonResponseObjects.JRRuleTarget._

  implicit lazy val stringTargetEnc : JsonEncoder[JRRuleTargetString]          = JsonEncoder[String].contramap(_.r.target)
  implicit lazy val andTargetEnc    : JsonEncoder[JRRuleTargetComposition.or]  = JsonEncoder[List[JRRuleTarget]].contramap(_.list)
  implicit lazy val orTargetEnc     : JsonEncoder[JRRuleTargetComposition.and] = JsonEncoder[List[JRRuleTarget]].contramap(_.list)
  implicit lazy val comp1TargetEnc  : JsonEncoder[JRRuleTargetComposed]        = DeriveJsonEncoder.gen
  implicit lazy val comp2TargetEnc  : JsonEncoder[JRRuleTargetComposition]     = DeriveJsonEncoder.gen
  implicit lazy val targetEncoder   : JsonEncoder[JRRuleTarget]                = new JsonEncoder[JRRuleTarget] {
    override def unsafeEncode(a: JRRuleTarget, indent: Option[Int], out: Write): Unit = {
      a match {
        case x:JRRuleTargetString      => stringTargetEnc.unsafeEncode(x, indent, out)
        case x:JRRuleTargetComposed    => comp1TargetEnc.unsafeEncode(x, indent, out)
        case x:JRRuleTargetComposition => comp2TargetEnc.unsafeEncode(x, indent, out)
      }
    }
  }

  implicit val ruleEncoder: JsonEncoder[JRRule] = DeriveJsonEncoder.gen

  implicit val simpleCategoryEncoder: JsonEncoder[JRSimpleRuleCategory] = DeriveJsonEncoder.gen
  implicit lazy val fullCategoryEncoder: JsonEncoder[JRFullRuleCategory]   = DeriveJsonEncoder.gen
  implicit val rootCategoryEncoder1: JsonEncoder[JRCategoriesRootEntryFull] = DeriveJsonEncoder.gen
  implicit val rootCategoryEncoder2: JsonEncoder[JRCategoriesRootEntrySimple] = DeriveJsonEncoder.gen

  implicit val rulesEncoder: JsonEncoder[JRRules] = DeriveJsonEncoder.gen

  implicit val directiveSectionVarEncoder: JsonEncoder[JRDirectiveSectionVar] = DeriveJsonEncoder.gen
  implicit lazy val directiveSectionHolderEncoder: JsonEncoder[JRDirectiveSerctionHolder] = DeriveJsonEncoder.gen
  implicit lazy val directiveSectionEncoder: JsonEncoder[JRDirectiveSection] = DeriveJsonEncoder.gen
  implicit val directiveEncoder: JsonEncoder[JRDirective] = DeriveJsonEncoder.gen
  implicit val directivesEncoder: JsonEncoder[JRDirectives] = DeriveJsonEncoder.gen

  implicit val activeTechniqueEncoder: JsonEncoder[JRActiveTechnique] = DeriveJsonEncoder.gen

  implicit val configValueEncoder: JsonEncoder[ConfigValue] = new JsonEncoder[ConfigValue] {
    override def unsafeEncode(a: ConfigValue, indent: Option[Int], out: Write): Unit = {
      val options = ConfigRenderOptions.concise().setJson(true).setFormatted(indent.isDefined)
      out.write(a.render(options))
    }
  }
  implicit val propertyProviderEncoder: JsonEncoder[Option[PropertyProvider]] = JsonEncoder[Option[String]].contramap {
    case None | Some(PropertyProvider.defaultPropertyProvider) => None
    case Some(x)                                               => Some(x.value)
  }
  implicit val inheritModeEncoder: JsonEncoder[InheritMode] = JsonEncoder[String].contramap(_.value)
  implicit val globalParameterEncoder: JsonEncoder[JRGlobalParameter] = DeriveJsonEncoder.gen.contramap(g =>
    // when inheritMode or property provider are set to their default value, don't write them
    g.modify(_.inheritMode).using {
      case Some(InheritMode.Default) => None
      case x                         => x
    }.modify(_.provider).using {
      case Some(PropertyProvider.defaultPropertyProvider) => None
      case x                                              => x
    }
  )

  implicit val propertyJRParentProperty: JsonEncoder[JRParentProperty] = DeriveJsonEncoder.gen

  implicit val propertyHierarchyEncoder: JsonEncoder[JRPropertyHierarchy] = new JsonEncoder[JRPropertyHierarchy] {
    override def unsafeEncode(a: JRPropertyHierarchy, indent: Option[Int], out: Write): Unit = {
      a match {
        case JRPropertyHierarchy.JRPropertyHierarchyJson(parents) =>
          JsonEncoder[List[JRParentProperty]].unsafeEncode(parents, indent, out)
        case JRPropertyHierarchy.JRPropertyHierarchyHtml(html)    =>
          JsonEncoder[String].unsafeEncode(html, indent, out)
      }
    }
  }
  implicit val propertyEncoder: JsonEncoder[JRProperty] = DeriveJsonEncoder.gen
  implicit val criteriumEncoder: JsonEncoder[JRCriterium] = DeriveJsonEncoder.gen
  implicit val queryEncoder: JsonEncoder[JRQuery] = DeriveJsonEncoder.gen
  implicit val groupEncoder: JsonEncoder[JRGroup] = DeriveJsonEncoder.gen
  implicit val objectInheritedObjectProperties: JsonEncoder[JRGroupInheritedProperties] = DeriveJsonEncoder.gen
}



///////////////////////////// decoder ///////////////

/*
 * Object used in JSON query POST/PUT request.
 * For disambiguation, objects are prefixed by JQ
 */
object JsonQueryObjects {
  import JsonResponseObjects.JRRuleTarget

  final case class JQRuleCategory(
        name       : Option[String] = None
      , description: Option[String] = None
      , parent     : Option[String] = None
      , id         : Option[String] = None
    ) {

    def update(ruleCategory: RuleCategory) = {
      ruleCategory.using(this).ignoreRedundantPatcherFields.patch
    }
  }


  final case class JQDirectiveSectionVar(
      name : String
    , value: String
  )
  final case class JQDirectiveSection(
      name    : String
      // Map is necessary b/c of the "var" in {"vars": [ {"var": { "name":..., "value":... }}}
    , sections: Option[List[Map[String, JQDirectiveSection]]]
      // Map is necessary b/c of the "var" in {"vars": [ {"var": { "name":..., "value":... }}}
    , vars    : Option[List[Map[String, JQDirectiveSectionVar]]]
  ) {
    def toSectionVal: (String, SectionVal) = {
      ( name
      , SectionVal(
            sections.map(_.flatMap(_.get("section").map(s => s.toSectionVal)).groupMap(_._1)(_._2)).getOrElse(Map())
          , vars.map(_.flatMap(_.get("var").map(x => (x.name, x.value))).toMap).getOrElse(Map())
        )
      )
    }
  }

  // this one is a direction mapping of Directive with optionnal fields for patching
  case class PatchDirective(
      id              : Option[DirectiveId]
    , techniqueVersion: Option[TechniqueVersion]
    , parameters      : Option[Map[String, Seq[String]]]
    , name            : Option[String]
    , shortDescription: Option[String]
    , policyMode      : Option[Option[PolicyMode]]
    , longDescription : Option[String]
    , priority        : Option[Int]
    , _isEnabled      : Option[Boolean]
    , tags            : Option[Tags]
  )
  // the input query mapping
  final case class JQDirective(
      id               : Option[String]                           = None
    , displayName      : Option[String]                           = None
    , shortDescription : Option[String]                           = None
    , longDescription  : Option[String]                           = None
    , enabled          : Option[Boolean]                          = None
    , parameters       : Option[Map[String, JQDirectiveSection]]  = None
    , priority         : Option[Int]                              = None
    , techniqueName    : Option[TechniqueName]                    = None
    , techniqueVersion : Option[TechniqueVersion]                 = None
    , policyMode       : Option[Option[PolicyMode]]               = None
    , tags             : Option[Tags]                             = None
      //for clone
    , source           : Option[String]                           = None
  ) {
    val onlyName = displayName.isDefined    &&
                   shortDescription.isEmpty &&
                   longDescription.isEmpty  &&
                   enabled.isEmpty          &&
                   parameters.isEmpty       &&
                   priority.isEmpty         &&
                   techniqueName.isEmpty    &&
                   techniqueVersion.isEmpty &&
                   policyMode.isEmpty       &&
                   tags.isEmpty // no need to check source or reason



    def updateDirective(directive: Directive) = {
      directive.patchUsing(PatchDirective(
          id.map(DirectiveId)
        , techniqueVersion
        , parameters.flatMap(_.get("section").map(s => SectionVal.toMapVariables(s.toSectionVal._2)))
        , displayName
        , shortDescription
        , policyMode
        , longDescription
        , priority
        , enabled
        , tags
      ))
    }
  }

  final case class JQRule(
      id               : Option[String]              = None
    , displayName      : Option[String]              = None
    , category         : Option[String]              = None
    , shortDescription : Option[String]              = None
    , longDescription  : Option[String]              = None
    , directives       : Option[Set[DirectiveId]]    = None
    , targets          : Option[Set[JRRuleTarget]]   = None
    , enabled          : Option[Boolean]             = None
    , tags             : Option[Tags]                = None
      //for clone
    , source           : Option[String]              = None
  ) {

    val onlyName = displayName.isDefined    &&
                   category.isEmpty         &&
                   shortDescription.isEmpty &&
                   longDescription.isEmpty  &&
                   directives.isEmpty       &&
                   targets.isEmpty          &&
                   enabled.isEmpty          &&
                   tags.isEmpty  // no need to check source or reason

    def updateRule(rule: Rule) = {
      val updateName       = displayName.getOrElse(rule.name)
      val updateCategory   = category.map(RuleCategoryId).getOrElse(rule.categoryId)
      val updateShort      = shortDescription.getOrElse(rule.shortDescription)
      val updateLong       = longDescription.getOrElse(rule.longDescription)
      val updateDirectives = directives.getOrElse(rule.directiveIds)
      val updateTargets    = targets.map(t => Set[RuleTarget](RuleTarget.merge(t.map(_.toRuleTarget)))).getOrElse(rule.targets)
      val updateEnabled    = enabled.getOrElse(rule.isEnabledStatus)
      val updateTags       = tags.getOrElse(rule.tags)
      rule.copy(
          name             = updateName
        , categoryId       = updateCategory
        , shortDescription = updateShort
        , longDescription  = updateLong
        , directiveIds     = updateDirectives
        , targets          = updateTargets
        , isEnabledStatus  = updateEnabled
        , tags             = updateTags
      )
    }
  }


  final case class JQGlobalParameter(
      id         : Option[String]
    , value      : Option[ConfigValue]
    , description: Option[String]
    , inheritMode: Option[InheritMode]
  ) {
    def updateParameter(parameter: GlobalParameter) = {
      // provider from API is force set to default.
      parameter.patch(PatchProperty(id, value, Some(PropertyProvider.defaultPropertyProvider), description, inheritMode))
    }
  }

  final case class JQGroupProperty(
      name       : String
    , value      : ConfigValue
    , inheritMode: Option[InheritMode]
  ) {
    def toGroupProperty = GroupProperty(name, value, inheritMode, Some(PropertyProvider.defaultPropertyProvider))
  }

  final case class JQStringQuery(
      returnType : Option[QueryReturnType]
    , composition: Option[String]
    , transform  : Option[String]
    , where      : Option[List[StringCriterionLine]]
  ) {
    def toQueryString = StringQuery(returnType.getOrElse(NodeReturnType), composition, transform, where.getOrElse(Nil))
  }

  final case class GroupPatch(
      id         : Option[NodeGroupId]
    , name       : Option[String]
    , description: Option[String]
    , properties : Option[List[GroupProperty]]
    , query      : Option[Option[QueryTrait]]
    , isDynamic  : Option[Boolean]
    , _isEnabled : Option[Boolean]
  )

  final case class JQGroup(
      id          : Option[String] = None
    , displayName : Option[String] = None
    , description : Option[String] = None
    , properties  : Option[List[GroupProperty]]
    , query       : Option[StringQuery] = None
    , dynamic     : Option[Boolean] = None
    , enabled     : Option[Boolean] = None
    , category    : Option[NodeGroupCategoryId] = None
    , source      : Option[String] = None
  ) {

    val onlyName = displayName.isDefined &&
                   description.isEmpty   &&
                   properties.isEmpty    &&
                   query.isEmpty         &&
                   dynamic.isEmpty       &&
                   enabled.isEmpty       &&
                   category.isEmpty

    def updateGroup(group: NodeGroup, queryParser: CmdbQueryParser): PureResult[NodeGroup] = {
      for {
        q <- query match {
               case None    => Right(None)
               case Some(x) => queryParser.parse(x).toPureResult.map(Some(_))
             }
        p <- CompareProperties.updateProperties(group.properties, properties)
      } yield {
        group.patchUsing(GroupPatch(
            id.map(NodeGroupId)
          , displayName
          , description
          , Some(p)
          , q.map(Some(_))
          , dynamic
          , enabled
        ))
      }
    }
  }
}

trait RudderJsonDecoders {
  import JsonResponseObjects._
  import JsonResponseObjects.JRRuleTarget._
  import JsonQueryObjects._

  // JRRuleTarget
  object JRRuleTargetDecoder {
    def parseString(s: String) = RuleTarget.unserOne(s) match {
      case None    => Left(s"'${s}' can not be decoded as a simple rule target")
      case Some(x) => Right(JRRuleTargetString(x))
    }
  }
  implicit lazy val simpleRuleTargetDecoder: JsonDecoder[JRRuleTargetString]       = JsonDecoder[String].mapOrFail(JRRuleTargetDecoder.parseString)
  implicit lazy val composedTargetDecoder: JsonDecoder[JRRuleTargetComposed]       = DeriveJsonDecoder.gen
  implicit lazy val orRuleTargetDecoder : JsonDecoder[JRRuleTargetComposition.or]  = JsonDecoder[List[JRRuleTarget]].map(JRRuleTargetComposition.or(_))
  implicit lazy val andRuleTargetDecoder: JsonDecoder[JRRuleTargetComposition.and] = JsonDecoder[List[JRRuleTarget]].map(JRRuleTargetComposition.and(_))
  implicit lazy val composition: JsonDecoder[JRRuleTargetComposition]              = DeriveJsonDecoder.gen
  implicit lazy val ruleTargetDecoder: JsonDecoder[JRRuleTarget]                   = {
    JsonDecoder.peekChar[JRRuleTarget] {
      case '"' =>
        JsonDecoder[JRRuleTargetString].widen
      case '{' =>
        JsonDecoder[JRRuleTargetComposition].widen.
          orElse(JsonDecoder[JRRuleTargetComposed].widen)
    }
  }
  def extractRuleTargetJson(s: String): Either[String, JRRuleTarget] = {
    import zio.json._
    s.fromJson[JRRuleTarget].orElse(RuleTarget.unserOne(s) match {
      case None    => Left(s"Error: the following string can't not be decoded as a rule target: '${s}''")
      case Some(x) => Right(JRRuleTargetString(x))
    })
  }

  // tags
  implicit val tagsDecoder: JsonDecoder[Tags] = JsonDecoder[List[Map[String, String]]].map { list =>
    val res = list.flatMap { _.map {
      case (k, v) => com.normation.rudder.domain.policies.Tag(TagName(k), TagValue(v))
    } }
    Tags(res.toSet)
  }
  // PolicyMode
  implicit val policyModeDecoder: JsonDecoder[Option[PolicyMode]] = JsonDecoder[Option[String]].mapOrFail(opt => opt match {
    case None            => Right(None)
    //we need to be able to set "default", for example to reset in clone
    case Some("default") => Right(None)
    case Some(s)         => PolicyMode.parse(s).left.map(_.fullMsg).map(Some(_))
  })
  // technique name/version
  implicit val techniqueNameDecoder: JsonDecoder[TechniqueName] = JsonDecoder[String].map(TechniqueName)
  implicit val techniqueVersionDecoder: JsonDecoder[TechniqueVersion] = JsonDecoder[String].mapOrFail(TechniqueVersion.parse(_))
  implicit lazy val ruleCategoryIdDecoder: JsonDecoder[RuleCategoryId] = JsonDecoder[String].map(RuleCategoryId)
  implicit lazy val directiveIdsDecoder: JsonDecoder[Set[DirectiveId]] = JsonDecoder[List[String]].map(_.map(DirectiveId).toSet)

  // RestRule
  implicit lazy val ruleDecoder: JsonDecoder[JQRule] = DeriveJsonDecoder.gen

  implicit val ruleCategoryDecoder: JsonDecoder[JQRuleCategory] = DeriveJsonDecoder.gen


  // RestDirective - lazy because section/sectionVar/directive are mutually recursive
  implicit lazy val sectionDecoder: JsonDecoder[JQDirectiveSection] = DeriveJsonDecoder.gen
  implicit lazy val variableDecoder: JsonDecoder[JQDirectiveSectionVar] = DeriveJsonDecoder.gen
  implicit lazy val directiveDecoder: JsonDecoder[JQDirective] = DeriveJsonDecoder.gen

  // RestGlobalParameter
  implicit val inheritModeDecoder: JsonDecoder[InheritMode] = JsonDecoder[String].mapOrFail(s =>
    InheritMode.parseString(s).left.map(_.fullMsg)
  )
  implicit val configValueDecoder: JsonDecoder[ConfigValue] = JsonDecoder[ast.Json].map(GenericProperty.fromZioJson(_))
  implicit val globalParameterDecoder: JsonDecoder[JQGlobalParameter] = DeriveJsonDecoder.gen

  // Rest group
  implicit val nodeGroupCategoryIdDecoder: JsonDecoder[NodeGroupCategoryId] = JsonDecoder[String].map(NodeGroupCategoryId)
  implicit val queryStringCriterionLineDecoder: JsonDecoder[StringCriterionLine] = DeriveJsonDecoder.gen
  implicit val queryReturnTypeDecoder: JsonDecoder[QueryReturnType] = DeriveJsonDecoder.gen
  implicit val queryDecoder: JsonDecoder[StringQuery] = DeriveJsonDecoder.gen[JQStringQuery].map(_.toQueryString)
  implicit val groupPropertyDecoder: JsonDecoder[JQGroupProperty] = DeriveJsonDecoder.gen
  implicit val groupPropertyDecoder2: JsonDecoder[GroupProperty] = JsonDecoder[JQGroupProperty].map(_.toGroupProperty)
  implicit val groupDecoder: JsonDecoder[JQGroup] = DeriveJsonDecoder.gen
}

object implicits extends RudderJsonDecoders with RudderJsonEncoders with RudderJsonResponse.implicits


/*
 * This last class provides utility methods to get JsonQuery objects from the request.
 * We want to get ride of RestExtractorService but for now, we keep it for the parameter parts.
 */
class ZioJsonExtractor(queryParser: CmdbQueryParser with JsonQueryLexer) {
  import JsonResponseObjects._
  import JsonQueryObjects._
  import implicits._

  /**
   * Parse request body as JSON, and decode it as type `A`.
   * This is the root method to transform a JSON query into a Rest object.
   */
  def parseJson[A](req: Req)(implicit decoder: JsonDecoder[A]): PureResult[A] = {
    if(req.json_?) {
    // copied from `Req.forcedBodyAsJson`
    def r = """; *charset=(.*)""".r
    def r2 = """[^=]*$""".r
      def charset: String = req.contentType.flatMap(ct => r.findFirstIn(ct).flatMap(r2.findFirstIn)).getOrElse("UTF-8")
    // end copy

      req.body match {
        case eb: EmptyBox => Left(Unexpected((eb ?~! "error when accessing request body").messageChain))
        case Full(bytes)  => decoder.decodeJson(new String(bytes, charset)).left.map(Unexpected(_))
      }
    } else {
      Left(Unexpected("Cannot parse non-JSON request as JSON; please check content-type."))
    }
  }

  /**
   * Utilities to extract values from params Map
   */
  implicit class Extract(params: Map[String,List[String]]) {
    def optGet(key: String): Option[String] = params.get(key).flatMap(_.headOption)
    def parse[A](key: String, decoder: JsonDecoder[A]): PureResult[Option[A]] = {
      optGet(key) match {
        case None    => Right(None)
        case Some(x) => decoder.decodeJson(x).map(Some(_)).left.map(Unexpected)
      }
    }
    def parse2[A](key: String, decoder: String => PureResult[A]): PureResult[Option[A]] = {
      optGet(key) match {
        case None    => Right(None)
        case Some(x) => decoder(x).map(Some(_))
      }
    }
  }


  def extractDirective(req: Req): PureResult[JQDirective] = {
    if(req.json_?) {
      parseJson[JQDirective](req)
    } else {
      extractDirectiveFromParams(req.params)
    }
  }

  def extractRule(req: Req): PureResult[JQRule] = {
    if(req.json_?) {
      parseJson[JQRule](req)
    } else {
      extractRuleFromParams(req.params)
    }
  }

  def extractRuleCategory(req: Req): PureResult[JQRuleCategory] = {
    if(req.json_?) {
      parseJson[JQRuleCategory](req)
    } else {
      extractRuleCategoryFromParam(req.params)
    }
  }

  def extractGlobalParam(req: Req): PureResult[JQGlobalParameter] = {
    if(req.json_?) {
      parseJson[JQGlobalParameter](req)
    } else {
      extractGlobalParamFromParams(req.params)
    }
  }

  def extractGroup(req: Req): PureResult[JQGroup] = {
    if(req.json_?) {
      parseJson[JQGroup](req)
    } else {
      extractGroupFromParams(req.params)
    }
  }

  def extractRuleFromParams(params: Map[String,List[String]]) : PureResult[JQRule] = {
    for {
      enabled          <- params.parse("enabled"   , JsonDecoder[Boolean])
      directives       <- params.parse("directives", JsonDecoder[Set[DirectiveId]])
      target           <- params.parse("targets"   , JsonDecoder[Set[JRRuleTarget]])
      tags             <- params.parse("tags"      , JsonDecoder[Tags])
    } yield {
      JQRule(
          params.optGet("id")
        , params.optGet("displayName")
        , params.optGet("category")
        , params.optGet("shortDescription")
        , params.optGet("longDescription")
        , directives
        , target
        , enabled
        , tags
        , params.optGet("source")
      )
    }
  }

  def extractRuleCategoryFromParam(params: Map[String,List[String]]): PureResult[JQRuleCategory] = {
    Right(JQRuleCategory(
        params.optGet("name")
      , params.optGet("description")
      , params.optGet("parent")
      , params.optGet("id")
    ))
  }


  def extractDirectiveFromParams(params: Map[String,List[String]]) : PureResult[JQDirective] = {

    for {
      enabled    <- params.parse("enabled"    , JsonDecoder[Boolean])
      priority   <- params.parse("priority"   , JsonDecoder[Int])
      parameters <- params.parse("parameters" , JsonDecoder[Map[String, JQDirectiveSection]])
      policyMode <- params.parse2("policyMode", PolicyMode.parseDefault(_))
      tags       <- params.parse("tags"       , JsonDecoder[Tags])
    } yield {
      JQDirective(
          params.optGet("id")
        , params.optGet("displayName")
        , params.optGet("shortDescription")
        , params.optGet("longDescription")
        , enabled
        , parameters
        , priority
        , params.optGet("techniqueName").map(TechniqueName)
        , params.optGet("techniqueVersion").map(TechniqueVersion(_))
        , policyMode
        , tags
        , params.optGet("source")
      )
    }
  }

  def extractGlobalParamFromParams(params: Map[String,List[String]]) : PureResult[JQGlobalParameter] = {
    for {
      value       <- params.parse2("value"      , GenericProperty.parseValue(_))
      inheritMode <- params.parse2("inheritMode", InheritMode.parseString(_))
    } yield {
      JQGlobalParameter(params.optGet("id"), value, params.optGet("description"), inheritMode)
    }
  }

  def extractGroupFromParams(params : Map[String,List[String]]) : PureResult[JQGroup] = {
    for {
      enabled     <- params.parse ("enabled"   , JsonDecoder[Boolean])
      dynamic     <- params.parse ("dynamic"   , JsonDecoder[Boolean])
      query       <- params.parse2("query"     , queryParser.lex(_).toPureResult)
      properties  <- params.parse ("properties", JsonDecoder[List[GroupProperty]])
    } yield {
      JQGroup(
          params.optGet("id")
        , params.optGet("displayName")
        , params.optGet("description")
        , properties
        , query
        , dynamic
        , enabled
        , params.optGet("category").map(NodeGroupCategoryId)
        , params.optGet("source")
      )
    }
  }

}


