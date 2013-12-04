/*
*************************************************************************************
* Copyright 2011 Normation SAS
*************************************************************************************
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Affero General Public License as
* published by the Free Software Foundation, either version 3 of the
* License, or (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU Affero GPL v3, the copyright holders add the following
* Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU Affero GPL v3
* licence, when you create a Related Module, this Related Module is
* not considered as a part of the work and may be distributed under the
* license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Affero General Public License for more details.
*
* You should have received a copy of the GNU Affero General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/agpl.html>.
*
*************************************************************************************
*/
package com.normation.rudder.services.policies

import org.junit.runner._
import org.specs2.runner._
import org.specs2.mutable._
import com.normation.cfclerk.domain._
import net.liftweb.common._
import Box._
import com.normation.rudder.repository.FullActiveTechnique
import com.normation.rudder.domain.policies.ActiveTechniqueId
import com.normation.rudder.repository.FullActiveTechniqueCategory
import com.normation.rudder.domain.policies.ActiveTechniqueCategoryId
import scala.collection.SortedMap
import org.joda.time.DateTime
import com.normation.rudder.domain.policies.Directive
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.Rule
import com.normation.rudder.domain.policies.GroupTarget
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.services.reports.ComputeCardinalityOfDirectiveVal

/**
 * Test how RuleVal and DirectiveVal are constructed, and if they
 * returns what is expected for meta techinques
 */
@RunWith(classOf[JUnitRunner])
class RuleValServiceTest extends Specification {

  /**
   * Instanciate the services
   */
  val ruleValService = new RuleValServiceImpl(new VariableBuilderServiceImpl())
  val computeCardinality = new ComputeCardinalityOfDirectiveVal

  /**
   * Create the objects for tests
   */
    val techniqueId = TechniqueId(
        TechniqueName("techniqueName")
      , TechniqueVersion("1.0")
    )
  val directiveId = DirectiveId("dirId")
  val ruleId = RuleId("ruleId")

  /* create representation of meta techniques */
  def makePredefinedSectionSpec(name: String, providedValues: (String, Seq[String])) =
    PredefinedValuesVariableSpec(
        reportKeysVariableName(name)
      , "description"
      , providedValues
    )
  def makeComponentSectionSpec(name:String) =
    SectionSpec(
        name
      , true
      , true
      , Some(reportKeysVariableName(name))
      , HighDisplayPriority
      , ""
      , Seq(makePredefinedSectionSpec(name, ("variable_"+name, Seq("variable_"+name+"one", "variable_"+name+"two"))))
    )

  def makeRootSectionSpec() =
    SectionSpec(
        "root section"
      , false
      , false
      , None
      , HighDisplayPriority
      , ""
      , Seq(
            makeComponentSectionSpec("component1")
          , makeComponentSectionSpec("component2")
        )
    )

  def makeMetaTechnique(id: TechniqueId) =
    Technique(
        id
      , "meta" + id
      , ""
      , Seq()
      , Seq()
      , TrackerVariableSpec(None)
      , makeRootSectionSpec
      , Set()
      , None
      , false
      , ""
      , false
      , true
    )

    val technique = makeMetaTechnique(techniqueId)

    val directive = Directive(
        directiveId
      , techniqueId.version
      , Map()
      , "MyDirective"
      , "shortDescription"
      , "longDescription"
      , 5
      , true
      , false
    )

    val rule = Rule(
          ruleId
        , "Rule Name"
        , 55
        , Set(GroupTarget(NodeGroupId("nodeGroupId")))
        , Set(directiveId)
        , ""
        , ""
        , true
        , false
    )

    val fullActiveTechnique =
      FullActiveTechnique(
          ActiveTechniqueId("activeTechId")
        , techniqueId.name
        , SortedMap((techniqueId.version, new DateTime()))
        , SortedMap((techniqueId.version, technique))
        , List(directive)
        , true
        , false
      )

    // create the ActiveTechniqueCategory
    val fullActiveTechniqueCategory =
      FullActiveTechniqueCategory(
           ActiveTechniqueCategoryId("id")
         , "name"
         , "description"
         , Nil
         , List(fullActiveTechnique)
         , false
      )

    // Ok, now I can test
    "The RuleValService, with one directive, one Meta-technique " should {

      val ruleVal = ruleValService.buildRuleVal(rule, fullActiveTechniqueCategory)

      "return a Full(RuleVal)" in {
        ruleVal.isDefined == true
      }

      val directivesVals = ruleVal.open_!.directiveVals

      "the ruleval should have only one directiveVal" in {
        directivesVals.size == 1
      }

      "the directive val should have three predefined variables: the two corresponding to the two component, and the trackingkey" in {
        directivesVals.head.originalVariables.size == 3
      }

      "the directive val should have two variables: the two corresponding to the two components" in {
        directivesVals.head.variables.size == 2
      }

      val variables = directivesVals.head.variables
      "one variable should be reportKeysVariableName(component1) -> (variable_component1 :: (variable_component1one, variable_component1two))" in {
        val var1 = variables.get(reportKeysVariableName("component1"))
        var1 match {
          case None => failure(s"Excepted variable variable_component1, but got nothing. The variables are ${variables}")
          case Some(vars) =>
            vars.values.size == 3 &&
            vars.values === Seq("variable_component1", "variable_component1one", "variable_component1two")
        }
      }

      "the ruleVal should convert to one policyDraft" in {
        ruleVal.open_!.toPolicyDrafts.size == 1
      }
    }

    "The cardinality computed " should {
      val ruleVal = ruleValService.buildRuleVal(rule, fullActiveTechniqueCategory)
      val directivesVals = ruleVal.open_!.directiveVals

      val cardinality = computeCardinality.getCardinality(directivesVals.head)

      "return a seq of two components" in {
        cardinality.size == 2
      }

      "first component should have 3 values" in {
        cardinality.head.x._2.size == 3 && cardinality.head.x._3.size == 3
      }

      "components component1 should have values variable_component1, variable_component1one, variable_component1two) " in {

        cardinality.filter(x => x._1 == "component1").head._2 === Seq("variable_component1", "variable_component1one", "variable_component1two")
      }
    }

}