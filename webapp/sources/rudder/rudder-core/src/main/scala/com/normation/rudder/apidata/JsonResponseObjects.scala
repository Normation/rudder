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
import com.normation.GitVersion.RevisionInfo
import com.normation.cfclerk.domain.Technique
import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.domain.TechniqueVersion
import com.normation.errors._
import com.normation.inventory.domain.NodeId
import com.normation.rudder.apidata.JsonResponseObjects.JRPropertyHierarchy.JRPropertyHierarchyHtml
import com.normation.rudder.apidata.JsonResponseObjects.JRPropertyHierarchy.JRPropertyHierarchyJson
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.nodes.NodeGroupCategoryId
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.policies._
import com.normation.rudder.domain.properties.GenericProperty
import com.normation.rudder.domain.properties.GlobalParameter
import com.normation.rudder.domain.properties.GroupProperty
import com.normation.rudder.domain.properties.InheritMode
import com.normation.rudder.domain.properties.NodePropertyHierarchy
import com.normation.rudder.domain.properties.ParentProperty
import com.normation.rudder.domain.properties.PropertyProvider
import com.normation.rudder.domain.queries.CriterionLine
import com.normation.rudder.domain.queries.Query
import com.normation.rudder.domain.queries.QueryReturnType
import com.normation.rudder.domain.queries.ResultTransformation
import com.normation.rudder.domain.workflows.ChangeRequestId
import com.normation.rudder.hooks.Hooks
import com.normation.rudder.ncf.ResourceFile
import com.normation.rudder.ncf.TechniqueParameter
import com.normation.rudder.repository.FullActiveTechnique
import com.normation.rudder.repository.FullActiveTechniqueCategory
import com.normation.rudder.rule.category.RuleCategory
import com.normation.rudder.rule.category.RuleCategoryId
import com.normation.rudder.services.queries.CmdbQueryParser
import com.normation.rudder.services.queries.StringCriterionLine
import com.normation.rudder.services.queries.StringQuery
import com.normation.utils.DateFormaterService
import com.softwaremill.quicklens._
import com.typesafe.config.ConfigRenderOptions
import com.typesafe.config.ConfigValue
import io.scalaland.chimney.dsl._
import zio._
import zio.{Tag => _}
import zio.json._
import zio.json.DeriveJsonEncoder
import zio.json.internal.Write
import zio.syntax._

/*
 * This class deals with everything serialisation related for API.
 * Change things with care! Everything must be versioned!
 * Even changing a field name can lead to an API incompatible change and
 * so will need a new API version number (and be sure that old behavior is kept
 * for previous versions).
 */

// how to render parent properties in the returned json in node APIs
sealed trait RenderInheritedProperties
object RenderInheritedProperties {
  case object HTML extends RenderInheritedProperties
  case object JSON extends RenderInheritedProperties
}

// to avoid ambiguity with corresponding business objects, we use "JR" as a prfix
object JsonResponseObjects {
  final case class JRActiveTechnique(
      name:     String,
      versions: List[String]
  )

  object JRActiveTechnique {
    def fromTechnique(activeTechnique: FullActiveTechnique) = {
      JRActiveTechnique(activeTechnique.techniqueName.value, activeTechnique.techniques.map(_._1.serialize).toList)
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
      name:  String,
      value: String
  )
  final case class JRDirectiveSection(
      name: String, // we have one more "var" indirection level between a var and its details:
      // { vars":[ { "var":{ "name": .... } }, { "var": { ... }} ]

      vars: Option[
        List[Map[String, JRDirectiveSectionVar]]
      ], // we have one more "section" indirection level between a section and its details:
      // { sections":[ { "section":{ "name": .... } }, { "section": { ... }} ]

      sections: Option[List[Map[String, JRDirectiveSection]]]
  ) {

    // toMapVariable is just accumulating var by name in seq, see SectionVal.toMapVariables
    def toMapVariables: Map[String, Seq[String]] = {
      import scala.collection.mutable.Buffer
      import scala.collection.mutable.Map
      val res = Map[String, Buffer[String]]()

      def recToMap(sec: JRDirectiveSection): Unit = {
        sec.vars.foreach(_.foreach(_.foreach {
          case (_, sectionVar) =>
            res.getOrElseUpdate(sectionVar.name, Buffer()).append(sectionVar.value)
        }))
        sec.sections.foreach(_.foreach(_.foreach {
          case (_, section) =>
            recToMap(section)
        }))
      }
      recToMap(this)
      res.map { case (k, buf) => (k, buf.toSeq) }.toMap
    }
  }

  // we have one more level between a directive section and a section
  final case class JRDirectiveSectionHolder(
      section: JRDirectiveSection
  )

  object JRDirectiveSection {
    def fromSectionVal(name: String, sectionVal: SectionVal): JRDirectiveSection = {
      JRDirectiveSection(
        name = name,
        sections = sectionVal.sections.toList.sortBy(_._1) match {
          case Nil  => None
          case list => Some(list.flatMap { case (n, sections) => sections.map(s => Map("section" -> fromSectionVal(n, s))) })
        },
        vars = sectionVal.variables.toList.sortBy(_._1) match {
          case Nil  => None
          case list => Some(list.map { case (n, v) => Map("var" -> JRDirectiveSectionVar(n, v)) })
        }
      )
    }
  }
  final case class JRRevisionInfo(
      revision: String,
      date:     String,
      author:   String,
      message:  String
  )
  object JRRevisionInfo     {
    def fromRevisionInfo(r: RevisionInfo) = {
      JRRevisionInfo(r.rev.value, DateFormaterService.serialize(r.date), r.author, r.message)
    }
  }

  sealed trait JRTechnique    {
    def id:      String
    def name:    String
    def version: String
    def source:  String
  }

  final case class JRBuiltInTechnique(
      name:    String,
      id:      String,
      version: String
  ) extends JRTechnique { val source = "builtin" }

  final case class JRTechniqueParameter(
      id:          String,
      name:        String,
      description: String,
      mayBeEmpty:  Boolean
  )
  object JRTechniqueParameter {
    def from(param: TechniqueParameter) = {
      JRTechniqueParameter(
        param.id.value,
        param.name,
        param.description,
        param.mayBeEmpty
      )
    }
  }

  final case class JRTechniqueResource(
      path:  String,
      state: String
  )
  object JRTechniqueResource  {
    def from(resource: ResourceFile) = {
      JRTechniqueResource(
        resource.path,
        resource.state.value
      )
    }
  }

  final case class JRMethodCallValue(
      name:  String,
      value: String
  )
  final case class JRReportingLogic(
      name:  String,
      value: Option[String]
  )

  final case class JRDirective(
      changeRequestId: Option[String],
      id:              String, // id is in format uid+rev

      displayName:      String,
      shortDescription: String,
      longDescription:  String,
      techniqueName:    String,
      techniqueVersion: String,
      parameters:       Map[String, JRDirectiveSection],
      priority:         Int,
      enabled:          Boolean,
      system:           Boolean,
      policyMode:       String,
      tags:             List[Map[String, String]]
  ) {
    def toDirective(): IOResult[(TechniqueName, Directive)] = {
      for {
        i <- DirectiveId.parse(id).toIO
        v <- TechniqueVersion.parse(techniqueVersion).toIO
        // the Map is just for "section" -> ...
        s <- parameters.get("section").notOptional("Root section entry 'section' is missing for directive parameters")
        m <- PolicyMode.parseDefault(policyMode).toIO
      } yield {
        (
          TechniqueName(techniqueName),
          Directive(
            i,
            v,
            s.toMapVariables,
            displayName,
            shortDescription,
            m,
            longDescription,
            priority,
            enabled,
            system,
            Tags.fromMaps(tags)
          )
        )
      }
    }
  }
  object JRDirective          {
    def empty(id: String) = JRDirective(None, id, "", "", "", "", "", Map(), 5, false, false, "", List())

    def fromDirective(technique: Technique, directive: Directive, crId: Option[ChangeRequestId]): JRDirective = {
      directive
        .into[JRDirective]
        .enableBeanGetters
        .withFieldConst(_.changeRequestId, crId.map(_.value.toString))
        .withFieldComputed(_.id, _.id.serialize)
        .withFieldRenamed(_.name, _.displayName)
        .withFieldConst(_.techniqueName, technique.id.name.value)
        .withFieldComputed(_.techniqueVersion, _.techniqueVersion.serialize)
        .withFieldConst(
          _.parameters,
          Map(
            "section" -> JRDirectiveSection.fromSectionVal(
              SectionVal.ROOT_SECTION_NAME,
              SectionVal.directiveValToSectionVal(technique.rootSection, directive.parameters)
            )
          )
        )
        .withFieldComputed(_.policyMode, _.policyMode.map(_.name).getOrElse("default"))
        .withFieldComputed(_.tags, x => JRTags.fromTags(x.tags))
        .transform
    }
  }

  final case class JRDirectives(directives: List[JRDirective])

  final case class JRDirectiveTreeCategory(
      name:          String,
      description:   String,
      subCategories: List[JRDirectiveTreeCategory],
      techniques:    List[JRDirectiveTreeTechnique]
  )
  object JRDirectiveTreeCategory  {
    def fromActiveTechniqueCategory(technique: FullActiveTechniqueCategory): JRDirectiveTreeCategory = {
      JRDirectiveTreeCategory(
        technique.name,
        technique.description,
        technique.subCategories.map(fromActiveTechniqueCategory),
        technique.activeTechniques.map(JRDirectiveTreeTechnique.fromActiveTechnique)
      )
    }
  }

  final case class JRDirectiveTreeTechnique(
      id:         String,
      name:       String,
      directives: List[JRDirective]
  )
  object JRDirectiveTreeTechnique {
    def fromActiveTechnique(technique: FullActiveTechnique): JRDirectiveTreeTechnique = {
      JRDirectiveTreeTechnique(
        technique.techniqueName.value,
        technique.newestAvailableTechnique.map(_.name).getOrElse(technique.techniqueName.value),
        technique.directives.flatMap { d =>
          technique.techniques.get(d.techniqueVersion).map(t => JRDirective.fromDirective(t, d, None))
        }
      )
    }
  }
  final case class JRApplicationStatus(
      value:   String,
      details: Option[String]
  )
  final case class JRRule(
      changeRequestId: Option[String] = None,
      id:              String, // id is in format uid+rev

      displayName:      String,
      categoryId:       String,
      shortDescription: String,
      longDescription:  String,
      directives:       List[String], // directives ids

      targets:    List[JRRuleTarget],
      enabled:    Boolean,
      system:     Boolean,
      tags:       List[Map[String, String]],
      policyMode: Option[String],
      status:     Option[JRApplicationStatus]
  ) {
    def toRule(): IOResult[Rule] = {
      for {
        i <- RuleId.parse(id).toIO
        d <- ZIO.foreach(directives)(DirectiveId.parse(_).toIO)
      } yield Rule(
        i,
        displayName,
        RuleCategoryId(categoryId),
        targets.map(_.toRuleTarget).toSet,
        d.toSet,
        shortDescription,
        longDescription,
        enabled,
        system,
        Tags.fromMaps(tags)
      )
    }
  }

  object JRRule {
    // create an empty json rule with just ID set
    def empty(id: String) = JRRule(None, id, "", "", "", "", Nil, Nil, false, false, Nil, None, None)

    // create from a rudder business rule
    def fromRule(
        rule:       Rule,
        crId:       Option[ChangeRequestId],
        policyMode: Option[String],
        status:     Option[(String, Option[String])]
    ): JRRule = {
      rule
        .into[JRRule]
        .enableBeanGetters
        .withFieldConst(_.changeRequestId, crId.map(_.value.toString))
        .withFieldComputed(_.id, _.id.serialize)
        .withFieldRenamed(_.name, _.displayName)
        .withFieldComputed(_.categoryId, _.categoryId.value)
        .withFieldComputed(_.directives, _.directiveIds.map(_.serialize).toList.sorted)
        .withFieldComputed(_.targets, _.targets.toList.sortBy(_.target).map(t => JRRuleTarget(t)))
        .withFieldRenamed(_.isEnabledStatus, _.enabled)
        .withFieldComputed(_.tags, x => JRTags.fromTags(rule.tags))
        .withFieldConst(_.policyMode, policyMode)
        .withFieldConst(_.status, status.map(s => JRApplicationStatus(s._1, s._2)))
        .transform
    }
  }

  object JRTags {
    def fromTags(tags: Tags): List[Map[String, String]] = {
      tags.tags.toList.sortBy(_.name.value).map(t => Map((t.name.value, t.value.value)))
    }
  }

  final case class JRRules(rules: List[JRRule])

  sealed trait JRRuleTarget {
    def toRuleTarget: RuleTarget
  }
  object JRRuleTarget       {
    def apply(t: RuleTarget): JRRuleTarget = {
      def compose(x: TargetComposition): JRRuleTargetComposition = x match {
        case TargetUnion(targets)        => JRRuleTargetComposition.or(x.targets.toList.map(JRRuleTarget(_)))
        case TargetIntersection(targets) => JRRuleTargetComposition.and(x.targets.toList.map(JRRuleTarget(_)))
      }

      t match {
        case x: SimpleTarget      => JRRuleTargetString(x)
        case x: TargetComposition => compose(x)
        case x: TargetExclusion   => JRRuleTargetComposed(compose(x.includedTarget), compose(x.excludedTarget))
      }
    }

    final case class JRRuleTargetString(r: SimpleTarget) extends JRRuleTarget {
      override def toRuleTarget: RuleTarget = r
    }
    final case class JRRuleTargetComposed(
        include: JRRuleTargetComposition,
        exclude: JRRuleTargetComposition
    ) extends JRRuleTarget {
      override def toRuleTarget: RuleTarget = TargetExclusion(include.toRuleTarget, exclude.toRuleTarget)
    }
    sealed trait JRRuleTargetComposition                 extends JRRuleTarget {
      override def toRuleTarget: TargetComposition
    }
    object JRRuleTargetComposition {
      final case class or(list: List[JRRuleTarget])  extends JRRuleTargetComposition {
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
      id:          String,
      name:        String,
      description: String,
      parent:      Option[String],
      categories:  List[JRFullRuleCategory],
      rules:       List[JRRule]
  )
  object JRFullRuleCategory {
    /*
     * Prepare for json.
     * Sort field by ID to keep diff easier.
     */
    def fromCategory(
        cat:      RuleCategory,
        allRules: Map[String, Seq[(Rule, Option[String], Option[(String, Option[String])])]],
        parent:   Option[String]
    ): JRFullRuleCategory = {
      cat
        .into[JRFullRuleCategory]
        .withFieldConst(_.parent, parent)
        .withFieldComputed(
          _.categories,
          _.childs.map(c => JRFullRuleCategory.fromCategory(c, allRules, Some(cat.id.value))).sortBy(_.id)
        )
        .withFieldConst(
          _.rules,
          allRules.get(cat.id.value).getOrElse(Nil).map { case (r, p, s) => JRRule.fromRule(r, None, p, s) }.toList.sortBy(_.id)
        )
        .transform
    }
  }

  // when returning a root category, we have a "data":{"ruleCategories":{.... }}. Seems like a bug, though
  final case class JRCategoriesRootEntryFull(ruleCategories: JRFullRuleCategory)
  final case class JRCategoriesRootEntrySimple(ruleCategories: JRSimpleRuleCategory)

  final case class JRSimpleRuleCategory(
      id:          String,
      name:        String,
      description: String,
      parent:      String,
      categories:  List[String],
      rules:       List[String]
  )
  object JRSimpleRuleCategory {
    def fromCategory(cat: RuleCategory, parent: String, rules: List[String]) = {
      cat
        .into[JRSimpleRuleCategory]
        .withFieldComputed(_.id, _.id.value)
        .withFieldConst(_.parent, parent)
        .withFieldComputed(_.categories, _.childs.map(_.id.value).sorted)
        .withFieldConst(_.rules, rules)
        .transform
    }
  }

  final case class JRGlobalParameter(
      changeRequestId: Option[String] = None,
      id:              String,
      value:           ConfigValue,
      description:     String,
      inheritMode:     Option[InheritMode],
      provider:        Option[PropertyProvider]
  )

  object JRGlobalParameter {
    import GenericProperty._
    def empty(name: String) = JRGlobalParameter(None, name, "".toConfigValue, "", None, None)
    def fromGlobalParameter(p: GlobalParameter, crId: Option[ChangeRequestId]): JRGlobalParameter = {
      JRGlobalParameter(crId.map(_.value.toString), p.name, p.value, p.description, p.inheritMode, p.provider)
    }
  }

  // similar to JRGlobalParameter but s/id/name and no changeRequestId
  final case class JRProperty(
      name:        String,
      value:       ConfigValue,
      description: Option[String],
      inheritMode: Option[InheritMode],
      provider:    Option[PropertyProvider],
      hierarchy:   Option[JRPropertyHierarchy],
      origval:     Option[ConfigValue]
  )
  object JRProperty {
    def fromGroupProp(p: GroupProperty) = {
      val desc = if (p.description.trim.isEmpty) None else Some(p.description)
      JRProperty(p.name, p.value, desc, p.inheritMode, p.provider, None, None)
    }

    def fromNodePropertyHierarchy(prop: NodePropertyHierarchy, renderInHtml: RenderInheritedProperties) = {
      val (parents, origval) = prop.hierarchy.reverse match {
        case Nil  => (None, None)
        case list =>
          val parents = renderInHtml match {
            case RenderInheritedProperties.HTML =>
              JRPropertyHierarchyHtml(
                list
                  .map(p =>
                    s"<p>from <b>${p.displayName}</b>:<pre>${p.value.render(ConfigRenderOptions.defaults().setOriginComments(false))}</pre></p>"
                  )
                  .mkString("")
              )
            case RenderInheritedProperties.JSON =>
              JRPropertyHierarchyJson(list.map(JRParentProperty.fromParentProperty(_)))
          }
          (Some(parents), prop.hierarchy.headOption.map(_.value))
      }
      val desc               = if (prop.prop.description.trim.isEmpty) None else Some(prop.prop.description)
      JRProperty(prop.prop.name, prop.prop.value, desc, prop.prop.inheritMode, prop.prop.provider, parents, origval)
    }
  }

  @jsonDiscriminator("kind") sealed trait JRParentProperty { def value: ConfigValue }
  object JRParentProperty                                  {
    @jsonHint("global")
    final case class JRParentGlobal(
        value: ConfigValue
    ) extends JRParentProperty

    @jsonHint("group")
    final case class JRParentGroup(
        name:  String,
        id:    String,
        value: ConfigValue
    ) extends JRParentProperty

    def fromParentProperty(p: ParentProperty) = {
      p match {
        case ParentProperty.Group(name, id, value) =>
          JRParentGroup(name, id.serialize, value)
        case _                                     =>
          JRParentGlobal(p.value)
      }
    }
  }

  sealed trait JRPropertyHierarchy extends Product
  object JRPropertyHierarchy {
    final case class JRPropertyHierarchyHtml(html: String)                    extends JRPropertyHierarchy
    final case class JRPropertyHierarchyJson(parents: List[JRParentProperty]) extends JRPropertyHierarchy
  }

  final case class JRGroupInheritedProperties(
      groupId:    String,
      properties: List[JRProperty]
  )

  object JRGroupInheritedProperties {
    def fromGroup(groupId: NodeGroupId, properties: List[NodePropertyHierarchy], renderInHtml: RenderInheritedProperties) = {
      JRGroupInheritedProperties(
        groupId.serialize,
        properties.sortBy(_.prop.name).map(JRProperty.fromNodePropertyHierarchy(_, renderInHtml))
      )
    }
  }

  final case class JRCriterium(
      objectType: String,
      attribute:  String,
      comparator: String,
      value:      String
  ) {
    def toStringCriterionLine = StringCriterionLine(objectType, attribute, comparator, Some(value))
  }

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
      select:      String,
      composition: String,
      transform:   Option[String],
      where:       List[JRCriterium]
  )

  object JRQuery {
    def fromQuery(query: Query) = {
      JRQuery(
        query.returnType.value,
        query.composition.value,
        query.transform match {
          case ResultTransformation.Identity => None
          case x                             => Some(x.value)
        },
        query.criteria.map(JRCriterium.fromCriterium(_))
      )
    }
  }

  final case class JRGroup(
      changeRequestId: Option[String] = None,
      id:              String,
      displayName:     String,
      description:     String,
      category:        String,
      query:           Option[JRQuery],
      nodeIds:         List[String],
      dynamic:         Boolean,
      enabled:         Boolean,
      groupClass:      List[String],
      properties:      List[JRProperty],
      target:          String,
      system:          Boolean
  ) {
    def toGroup(queryParser: CmdbQueryParser): IOResult[(NodeGroupCategoryId, NodeGroup)] = {
      for {
        i <- NodeGroupId.parse(id).toIO
        q <- query match {
               case None    => None.succeed
               case Some(q) =>
                 for {
                   t <- QueryReturnType(q.select).toIO
                   x <- queryParser
                          .parse(StringQuery(t, Some(q.composition), q.transform, q.where.map(_.toStringCriterionLine)))
                          .toIO
                 } yield Some(x)
             }
      } yield {
        (
          NodeGroupCategoryId(category),
          NodeGroup(
            i,
            displayName,
            description,
            properties.map(p => GroupProperty(p.name, GitVersion.DEFAULT_REV, p.value, p.inheritMode, p.provider)),
            q,
            dynamic,
            nodeIds.map(NodeId(_)).toSet,
            enabled,
            system
          )
        )
      }
    }
  }

  object JRGroup {
    def empty(id: String) = JRGroup(None, id, "", "", "", None, Nil, false, false, Nil, Nil, "", false)

    def fromGroup(group: NodeGroup, catId: NodeGroupCategoryId, crId: Option[ChangeRequestId]) = {
      group
        .into[JRGroup]
        .enableBeanGetters
        .withFieldConst(_.changeRequestId, crId.map(_.value.toString))
        .withFieldComputed(_.id, _.id.serialize)
        .withFieldRenamed(_.name, _.displayName)
        .withFieldConst(_.category, catId.value)
        .withFieldComputed(_.query, _.query.map(JRQuery.fromQuery(_)))
        .withFieldComputed(_.nodeIds, _.serverList.toList.map(_.value).sorted)
        .withFieldComputed(_.groupClass, x => List(x.id.serialize, x.name).map(RuleTarget.toCFEngineClassName _).sorted)
        .withFieldComputed(_.properties, _.properties.map(JRProperty.fromGroupProp(_)))
        .withFieldComputed(_.target, x => GroupTarget(x.id).target)
        .withFieldComputed(_.system, _.isSystem)
        .transform
    }
  }

  final case class JRRuleNodesDirectives(
      id: String, // id is in format uid+rev

      numberOfNodes:      Int,
      numberOfDirectives: Int
  )

  object JRRuleNodesDirectives {
    // create an empty json rule with just ID set
    def empty(id: String) = JRRuleNodesDirectives(id, 0, 0)

    // create from a rudder business rule
    def fromData(ruleId: RuleId, nodesCount: Int, directivesCount: Int): JRRuleNodesDirectives = {
      JRRuleNodesDirectives(ruleId.serialize, nodesCount, directivesCount)
    }
  }

  final case class JRHooks(
      basePath:  String,
      hooksFile: List[String]
  )

  object JRHooks {
    def fromHook(hook: Hooks) = {
      hook
        .into[JRHooks]
        .withFieldConst(_.basePath, hook.basePath)
        .withFieldConst(_.hooksFile, hook.hooksFile.map(_._1))
        .transform
    }
  }
}
//////////////////////////// zio-json encoders ////////////////////////////

trait RudderJsonEncoders {
  import JsonResponseObjects._
  import JsonResponseObjects.JRRuleTarget._

  implicit lazy val stringTargetEnc: JsonEncoder[JRRuleTargetString]          = JsonEncoder[String].contramap(_.r.target)
  implicit lazy val andTargetEnc:    JsonEncoder[JRRuleTargetComposition.or]  = JsonEncoder[List[JRRuleTarget]].contramap(_.list)
  implicit lazy val orTargetEnc:     JsonEncoder[JRRuleTargetComposition.and] = JsonEncoder[List[JRRuleTarget]].contramap(_.list)
  implicit lazy val comp1TargetEnc:  JsonEncoder[JRRuleTargetComposed]        = DeriveJsonEncoder.gen
  implicit lazy val comp2TargetEnc:  JsonEncoder[JRRuleTargetComposition]     = DeriveJsonEncoder.gen
  implicit lazy val targetEncoder:   JsonEncoder[JRRuleTarget]                = new JsonEncoder[JRRuleTarget] {
    override def unsafeEncode(a: JRRuleTarget, indent: Option[Int], out: Write): Unit = {
      a match {
        case x: JRRuleTargetString      => stringTargetEnc.unsafeEncode(x, indent, out)
        case x: JRRuleTargetComposed    => comp1TargetEnc.unsafeEncode(x, indent, out)
        case x: JRRuleTargetComposition => comp2TargetEnc.unsafeEncode(x, indent, out)
      }
    }
  }

  implicit val ruleIdEncoder: JsonEncoder[RuleId] = JsonEncoder[String].contramap(_.serialize)

  implicit val applicationStatusEncoder: JsonEncoder[JRApplicationStatus] = DeriveJsonEncoder.gen

  implicit val ruleEncoder: JsonEncoder[JRRule]  = DeriveJsonEncoder.gen
  implicit val hookEncoder: JsonEncoder[JRHooks] = DeriveJsonEncoder.gen

  implicit val ruleNodesDirectiveEncoder: JsonEncoder[JRRuleNodesDirectives] = DeriveJsonEncoder.gen

  implicit val simpleCategoryEncoder:    JsonEncoder[JRSimpleRuleCategory]        = DeriveJsonEncoder.gen
  implicit lazy val fullCategoryEncoder: JsonEncoder[JRFullRuleCategory]          = DeriveJsonEncoder.gen
  implicit val rootCategoryEncoder1:     JsonEncoder[JRCategoriesRootEntryFull]   = DeriveJsonEncoder.gen
  implicit val rootCategoryEncoder2:     JsonEncoder[JRCategoriesRootEntrySimple] = DeriveJsonEncoder.gen

  implicit val rulesEncoder: JsonEncoder[JRRules] = DeriveJsonEncoder.gen

  implicit val directiveSectionVarEncoder:         JsonEncoder[JRDirectiveSectionVar]    = DeriveJsonEncoder.gen
  implicit lazy val directiveSectionHolderEncoder: JsonEncoder[JRDirectiveSectionHolder] = DeriveJsonEncoder.gen
  implicit lazy val directiveSectionEncoder:       JsonEncoder[JRDirectiveSection]       = DeriveJsonEncoder.gen
  implicit val directiveEncoder:                   JsonEncoder[JRDirective]              = DeriveJsonEncoder.gen
  implicit val directivesEncoder:                  JsonEncoder[JRDirectives]             = DeriveJsonEncoder.gen
  implicit val directiveTreeTechniqueEncoder:      JsonEncoder[JRDirectiveTreeTechnique] = DeriveJsonEncoder.gen
  implicit val directiveTreeEncoder:               JsonEncoder[JRDirectiveTreeCategory]  = DeriveJsonEncoder.gen

  implicit val activeTechniqueEncoder: JsonEncoder[JRActiveTechnique] = DeriveJsonEncoder.gen

  implicit val configValueEncoder:      JsonEncoder[ConfigValue]              = new JsonEncoder[ConfigValue] {
    override def unsafeEncode(a: ConfigValue, indent: Option[Int], out: Write): Unit = {
      val options = ConfigRenderOptions.concise().setJson(true).setFormatted(indent.isDefined)
      out.write(a.render(options))
    }
  }
  implicit val propertyProviderEncoder: JsonEncoder[Option[PropertyProvider]] = JsonEncoder[Option[String]].contramap {
    case None | Some(PropertyProvider.defaultPropertyProvider) => None
    case Some(x)                                               => Some(x.value)
  }
  implicit val inheritModeEncoder:      JsonEncoder[InheritMode]              = JsonEncoder[String].contramap(_.value)
  implicit val globalParameterEncoder:  JsonEncoder[JRGlobalParameter]        = DeriveJsonEncoder.gen.contramap(g => {
    // when inheritMode or property provider are set to their default value, don't write them
    g.modify(_.inheritMode)
      .using {
        case Some(InheritMode.Default) => None
        case x                         => x
      }
      .modify(_.provider)
      .using {
        case Some(PropertyProvider.defaultPropertyProvider) => None
        case x                                              => x
      }
  })

  implicit val propertyJRParentProperty: JsonEncoder[JRParentProperty] = DeriveJsonEncoder.gen

  implicit val propertyHierarchyEncoder:        JsonEncoder[JRPropertyHierarchy]        = new JsonEncoder[JRPropertyHierarchy] {
    override def unsafeEncode(a: JRPropertyHierarchy, indent: Option[Int], out: Write): Unit = {
      a match {
        case JRPropertyHierarchy.JRPropertyHierarchyJson(parents) =>
          JsonEncoder[List[JRParentProperty]].unsafeEncode(parents, indent, out)
        case JRPropertyHierarchy.JRPropertyHierarchyHtml(html)    =>
          JsonEncoder[String].unsafeEncode(html, indent, out)
      }
    }
  }
  implicit val propertyEncoder:                 JsonEncoder[JRProperty]                 = DeriveJsonEncoder.gen
  implicit val criteriumEncoder:                JsonEncoder[JRCriterium]                = DeriveJsonEncoder.gen
  implicit val queryEncoder:                    JsonEncoder[JRQuery]                    = DeriveJsonEncoder.gen
  implicit val groupEncoder:                    JsonEncoder[JRGroup]                    = DeriveJsonEncoder.gen
  implicit val objectInheritedObjectProperties: JsonEncoder[JRGroupInheritedProperties] = DeriveJsonEncoder.gen

  implicit val revisionInfoEncoder: JsonEncoder[JRRevisionInfo] = DeriveJsonEncoder.gen
}

/*
 * Decoders for JsonResponse object, when you need to read back something that they serialized.
 */
object JsonResponseObjectDecodes extends RudderJsonDecoders {
  import JsonResponseObjects._

  implicit lazy val decodeJRParentProperty:    JsonDecoder[JRParentProperty]    = DeriveJsonDecoder.gen
  implicit lazy val decodeJRPropertyHierarchy: JsonDecoder[JRPropertyHierarchy] = DeriveJsonDecoder.gen
  implicit lazy val decodePropertyProvider:    JsonDecoder[PropertyProvider]    = JsonDecoder.string.map(s => PropertyProvider(s))
  implicit lazy val decodeJRProperty:          JsonDecoder[JRProperty]          = DeriveJsonDecoder.gen

  implicit lazy val decodeJRCriterium:           JsonDecoder[JRCriterium]           = DeriveJsonDecoder.gen
  implicit lazy val decodeJRDirectiveSectionVar: JsonDecoder[JRDirectiveSectionVar] = DeriveJsonDecoder.gen

  implicit lazy val decodeJRApplicationStatus: JsonDecoder[JRApplicationStatus] = DeriveJsonDecoder.gen
  implicit lazy val decodeJRQuery:             JsonDecoder[JRQuery]             = DeriveJsonDecoder.gen
  implicit lazy val decodeJRDirectiveSection:  JsonDecoder[JRDirectiveSection]  = DeriveJsonDecoder.gen
  implicit lazy val decodeJRRule:              JsonDecoder[JRRule]              = DeriveJsonDecoder.gen
  implicit lazy val decodeJRGroup:             JsonDecoder[JRGroup]             = DeriveJsonDecoder.gen
  implicit lazy val decodeJRDirective:         JsonDecoder[JRDirective]         = DeriveJsonDecoder.gen

}
