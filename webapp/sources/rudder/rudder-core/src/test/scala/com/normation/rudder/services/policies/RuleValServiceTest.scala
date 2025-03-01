/*
 *************************************************************************************
 * Copyright 2011 Normation SAS
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
package com.normation.rudder.services.policies

import com.normation.GitVersion
import com.normation.cfclerk.domain.*
import com.normation.inventory.domain.AgentType
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.nodes.NodeGroupUid
import com.normation.rudder.domain.policies.ActiveTechniqueCategoryId
import com.normation.rudder.domain.policies.ActiveTechniqueId
import com.normation.rudder.domain.policies.Directive
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.DirectiveUid
import com.normation.rudder.domain.policies.GroupTarget
import com.normation.rudder.domain.policies.PolicyTypes
import com.normation.rudder.domain.policies.Rule
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.RuleUid
import com.normation.rudder.repository.FullActiveTechnique
import com.normation.rudder.repository.FullActiveTechniqueCategory
import com.normation.rudder.rule.category.RuleCategoryId
import com.normation.rudder.services.nodes.PropertyEngineServiceImpl
import com.normation.zio.*
import org.joda.time.DateTime
import org.junit.runner.*
import org.specs2.mutable.*
import org.specs2.runner.*
import scala.collection.MapView
import scala.collection.SortedMap

/**
 * Test how RuleVal and ParsedPolicyDraft are constructed, and if they
 * returns what is expected for meta techinques
 */
@RunWith(classOf[JUnitRunner])
class RuleValServiceTest extends Specification {

  /**
   * Instanciate the services
   */
  val propertyEngineService = new PropertyEngineServiceImpl(List.empty)
  val ruleValService        = new RuleValServiceImpl(new InterpolatedValueCompilerImpl(propertyEngineService))

  /**
   * Create the objects for tests
   */
  val techniqueId: TechniqueId  = TechniqueId(
    TechniqueName("techniqueName"),
    TechniqueVersionHelper("1.0")
  )
  val directiveId: DirectiveUid = DirectiveUid("dirId")
  val ruleId:      RuleId       = RuleId(RuleUid("ruleId"))

  /* create representation of meta techniques */
  def makePredefinedSectionSpec(name: String, providedValues: (String, Seq[String])): PredefinedValuesVariableSpec = {
    PredefinedValuesVariableSpec(
      reportKeysVariableName(name),
      "description",
      None,
      providedValues,
      id = None
    )
  }
  def makeComponentSectionSpec(name: String):                                         SectionSpec                  = {
    SectionSpec(
      name = name,
      isMultivalued = true,
      isComponent = true,
      componentKey = Some(reportKeysVariableName(name)),
      displayPriority = HighDisplayPriority,
      description = "",
      children =
        Seq(makePredefinedSectionSpec(name, ("variable_" + name, Seq("variable_" + name + "one", "variable_" + name + "two"))))
    )
  }

  def makeRootSectionSpec(): SectionSpec = {
    SectionSpec(
      name = "root section",
      isMultivalued = false,
      isComponent = false,
      componentKey = None,
      displayPriority = HighDisplayPriority,
      description = "",
      children = Seq(
        makeComponentSectionSpec("component1"),
        makeComponentSectionSpec("component2")
      )
    )
  }

  def makeMetaTechnique(id: TechniqueId): Technique = {
    Technique(
      id,
      name = "meta" + id,
      description = "",
      agentConfigs = AgentConfig(AgentType.CfeCommunity, Nil, Nil, Nil, Nil) :: Nil,
      trackerVariableSpec = TrackerVariableSpec(None, None),
      rootSection = makeRootSectionSpec(),
      deprecrationInfo = None,
      systemVariableSpecs = Set(),
      isMultiInstance = false,
      longDescription = "",
      PolicyTypes.rudderBase
    )
  }

  val technique: Technique = makeMetaTechnique(techniqueId)

  val directive: Directive = Directive(
    DirectiveId(directiveId, GitVersion.DEFAULT_REV),
    techniqueId.version,
    parameters = Map(),
    name = "MyDirective",
    shortDescription = "shortDescription",
    policyMode = None,
    longDescription = "longDescription",
    priority = 5,
    _isEnabled = true,
    isSystem = false
  )

  val rule: Rule = Rule(
    ruleId,
    name = "Rule Name",
    categoryId = RuleCategoryId("cat1"),
    targets = Set(GroupTarget(NodeGroupId(NodeGroupUid("nodeGroupId")))),
    directiveIds = Set(DirectiveId(directiveId)),
    shortDescription = "",
    longDescription = "",
    isEnabledStatus = true,
    isSystem = false
  )

  val fullActiveTechnique: FullActiveTechnique = {
    FullActiveTechnique(
      ActiveTechniqueId("activeTechId"),
      techniqueId.name,
      acceptationDatetimes = SortedMap((techniqueId.version, new DateTime())),
      techniques = SortedMap((techniqueId.version, technique)),
      directives = List(directive),
      isEnabled = true,
      policyTypes = PolicyTypes.rudderBase
    )
  }

  // create the ActiveTechniqueCategory
  val fullActiveTechniqueCategory: FullActiveTechniqueCategory = {
    FullActiveTechniqueCategory(
      ActiveTechniqueCategoryId("id"),
      name = "name",
      description = "description",
      subCategories = Nil,
      activeTechniques = List(fullActiveTechnique),
      isSystem = false
    )
  }

  // Ok, now I can test
  "The RuleValService, with one directive, one Meta-technique " should {

    val ruleVal = ruleValService.buildRuleVal(rule, fullActiveTechniqueCategory, NodeConfigData.groupLib, MapView())

    "return a Full(RuleVal)" in {
      ruleVal.isDefined == true
    }

    val directivesVals = ruleVal.openOrThrowException("Should have been full for test").parsedPolicyDrafts

    "the ruleval should have only one directiveVal" in {
      directivesVals.size == 1
    }

    "the directive val should have three predefined variables: the two corresponding to the two component, and the trackingkey" in {
      directivesVals.head.originalVariables.size == 3
    }

    "the directive val should have two variables: the two corresponding to the two components" in {
      directivesVals.head.variables(null).either.runNow match {
        case Left(err)   => ko(s"Error when getting variables: " + err.fullMsg)
        case Right(vars) => vars.size === 2
      }
    }

    val variables = directivesVals.head.variables
    "one variable should be reportKeysVariableName(component1) -> (variable_component1 :: (variable_component1one, variable_component1two))" in {
      variables(null).either.runNow match {
        case Left(_)     => ko("Error when parsing variable")
        case Right(vars) =>
          vars.get(ComponentId(reportKeysVariableName("component1"), "component1" :: "root section" :: Nil, None)) match {
            case None           => ko(s"Excepted variable variable_component1, but got nothing. The variables are ${variables}")
            case Some(variable) =>
              variable.values.size === 3
              variable.values === Seq("variable_component1", "variable_component1one", "variable_component1two")
          }
      }
    }

  }

  "The cardinality computed " should {
    val ruleVal = ruleValService.buildRuleVal(rule, fullActiveTechniqueCategory, NodeConfigData.groupLib, MapView())
    val draft   = ruleVal.openOrThrowException("Should have been full for test").parsedPolicyDrafts.head
    // false PolicyVars for that draft
    val vars    = PolicyVars(draft.id, draft.policyMode, draft.originalVariables, draft.originalVariables, draft.trackerVariable)

    val pt         = PolicyTechnique
      .forAgent(draft.technique, AgentType.CfeCommunity)
      .getOrElse(throw new RuntimeException("Test must not throws"))
    val components = RuleExpectedReportBuilder.componentsFromVariables(pt, draft.id.directiveId, vars)

    "return a seq of two components" in {
      components.size === 2
    }

    /* "first component should have 3 values" in {
        components.head.componentsValues.size === 3
      }

      "components component1 should have values variable_component1, variable_component1one, variable_component1two) " in {
        components.filter( _.componentName == "component1").head.componentsValues === List("variable_component1", "variable_component1one", "variable_component1two")
      }*/
  }

}
