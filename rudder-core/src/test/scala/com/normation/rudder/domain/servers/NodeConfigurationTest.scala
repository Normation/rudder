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

package com.normation.rudder.domain.servers


import junit.framework.TestSuite
import org.junit.Test
import org.junit._
import org.junit.Assert._
import org.junit.runner.RunWith
import org.junit.runners.BlockJUnit4ClassRunner
import com.normation.cfclerk.domain._
import com.normation.inventory.domain.AgentType
import com.normation.cfclerk.domain._
import com.normation.rudder.domain.policies.RuleWithCf3PolicyDraft
import com.normation.rudder.domain.policies.RuleId
import net.liftweb.common._
import com.normation.inventory.domain.NodeId



@RunWith(classOf[BlockJUnit4ClassRunner])
class NodeConfigurationTest {

  def newTechnique(id: TechniqueId) = Technique(id, "tech" + id, "", Seq(), Seq(), TrackerVariableSpec(), SectionSpec("plop"), Set(), None)

  private val simplePolicy = new RuleWithCf3PolicyDraft(
      new RuleId("ruleId"),
      new Cf3PolicyDraft(new Cf3PolicyDraftId("cfcId"),
      newTechnique(new TechniqueId(TechniqueName("ppId"), TechniqueVersion("1.0"))),
      Map(), TrackerVariableSpec().toVariable(),
      priority = 0, serial = 0) // no variable
  )

  private val policyVaredOne = new RuleWithCf3PolicyDraft(
      new RuleId("ruleId1"),
      new Cf3PolicyDraft(new Cf3PolicyDraftId("cfcId1"),
      newTechnique(new TechniqueId(TechniqueName("ppId1"), TechniqueVersion("1.0"))),
      Map("one" -> InputVariable(InputVariableSpec("one", ""), Seq("one"))),
      TrackerVariableSpec().toVariable(),
      priority = 0, serial = 0)  // one variable
  )


  private val policyOtherVaredOne = new RuleWithCf3PolicyDraft(
      new RuleId("ruleId1"),
      new Cf3PolicyDraft(new Cf3PolicyDraftId("cfcId1"),
      newTechnique(new TechniqueId(TechniqueName("ppId1"), TechniqueVersion("1.0"))),
      Map("one" -> InputVariable(InputVariableSpec("one", ""), Seq("two"))),
      TrackerVariableSpec().toVariable(),
      priority = 0, serial = 0)  // one variable
  )

  private val policyNextVaredOne = new RuleWithCf3PolicyDraft(
      new RuleId("ruleId1"),
      new Cf3PolicyDraft(new Cf3PolicyDraftId("cfcId1"),
      newTechnique(new TechniqueId(TechniqueName("ppId1"), TechniqueVersion("1.0"))),
      Map("one" -> InputVariable(InputVariableSpec("one", ""), Seq("one"))),
      TrackerVariableSpec().toVariable(),
      priority = 0, serial = 1)  // next serial than policyVaredOne
  )

  private val policyVaredTwo = new RuleWithCf3PolicyDraft(
      new RuleId("ruleId2"),
      new Cf3PolicyDraft(new Cf3PolicyDraftId("cfcId2"),
      newTechnique(new TechniqueId(TechniqueName("ppId2"), TechniqueVersion("1.0"))),
      Map("two" -> InputVariable(InputVariableSpec("two", ""), Seq("two"))),
      TrackerVariableSpec().toVariable(),
      priority = 0, serial = 0) // one variable
  )

  private val minNodeConf = new MinimalNodeConfig(
      "name",
      "hostname",
      Seq(),
      "psId",
      "root"
  )


  @Test
  def simpleCreateNodeConfiguration() {
    /* Create a simple node configuration and check its configuration after */
    val newNode = new SimpleNodeConfiguration(NodeId("id"),
        Seq(),
        Seq(),
        false,
        minNodeConf,
        minNodeConf,
        None,
        Map(),
        Map(),
        Set(),
        Set())
    assertEquals(newNode.isPolicyServer, false)
    assertEquals(newNode.currentRulePolicyDrafts.size.toLong, 0L)
    assertEquals(newNode.targetRulePolicyDrafts.size.toLong, 0L)

    assertEquals(newNode.currentSystemVariables.size.toLong, 0L)
    assertEquals(newNode.targetSystemVariables.size.toLong, 0L)

    assertEquals(newNode.isModified, false)
    assertEquals(newNode.targetMinimalNodeConfig.agentsName.size.toLong, 0L)

    // Now add a policy
    newNode.addDirective(simplePolicy) match {
      case f: EmptyBox =>
        val e = f ?~! "Error when adding directive %s on node %s".format(simplePolicy.ruleId, newNode.id)
        throw new RuntimeException(e.messageChain)
      case Full(node) =>


        assertEquals(node.isModified, true)
        // Current policy don't change, but target does
        assertEquals(node.currentRulePolicyDrafts.size.toLong, 0)
        assertEquals(node.targetRulePolicyDrafts.size.toLong, 1L)

        assertEquals(node.findDirectiveByTechnique(new TechniqueId(TechniqueName("ppId"), TechniqueVersion("1.0"))).size.toLong, 1L)
        assertEquals(node.findDirectiveByTechnique(new TechniqueId(TechniqueName("ppId1"), TechniqueVersion("1.0"))).size.toLong, 0L)

        assertEquals(node.findCurrentDirectiveByTechnique(new TechniqueId(TechniqueName("ppId"), TechniqueVersion("1.0"))).size.toLong, 0L)

        assertEquals(node.getAllPoliciesNames().size.toLong, 1L)
        assertEquals(node.getAllPoliciesNames().contains(new TechniqueId(TechniqueName("ppId"), TechniqueVersion("1.0"))), true)
    }
  }

  @Test
  def completeCreateNodeConfiguration() {
    val newNode = new SimpleNodeConfiguration(NodeId("id"),
        Seq(policyVaredOne), Seq(policyVaredOne),
        false,
        minNodeConf,
        minNodeConf,
        None,
        Map(),
        Map(),
        Set(),
        Set())

    assertEquals(newNode.isPolicyServer, false)
    assertEquals(newNode.currentRulePolicyDrafts.size.toLong, 1L)
    assertEquals(newNode.targetRulePolicyDrafts.size.toLong, 1L)

    assertEquals(newNode.currentSystemVariables.size.toLong, 0L)
    assertEquals(newNode.targetSystemVariables.size.toLong, 0L)

    assertEquals(newNode.isModified, false)
    assertEquals(newNode.targetMinimalNodeConfig.agentsName.size.toLong, 0L)

    assertEquals(newNode.findDirectiveByTechnique(new TechniqueId(TechniqueName("ppId1"), TechniqueVersion("1.0"))).size.toLong, 1L)
    assertEquals(newNode.findDirectiveByTechnique(new TechniqueId(TechniqueName("ppId"), TechniqueVersion("1.0"))).size.toLong, 0L)

    assertEquals(newNode.findCurrentDirectiveByTechnique(new TechniqueId(TechniqueName("ppId1"), TechniqueVersion("1.0"))).size.toLong, 1L)


    val modified = newNode.copy(targetSystemVariables = Map("one" -> InputVariable(InputVariableSpec("one", ""), Seq("one"))))

    assertEquals(modified.isModified, false) // won't check system var
  }


}