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

package com.normation.rudder.services.nodes

import org.junit.runner._
import org.specs2.mutable._
import org.specs2.runner._
import com.normation.cfclerk.domain._
import com.normation.inventory.domain.AgentType
import com.normation.cfclerk.domain._
import com.normation.rudder.domain.policies.RuleWithCf3PolicyDraft
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.servers._
import com.normation.rudder.services.servers.NodeConfigurationChangeDetectServiceImpl
import com.normation.rudder.domain.policies._
import com.normation.cfclerk.domain.TechniqueName
import net.liftweb.common._
import com.normation.cfclerk.domain.TechniqueVersion
import org.joda.time.DateTime
import com.normation.rudder.repository.RoDirectiveRepository
import com.normation.rudder.repository.CategoryWithActiveTechniques
import scala.collection.immutable.SortedMap
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.rudder.repository.FullActiveTechniqueCategory
import com.normation.inventory.domain.NodeId
import com.normation.rudder.repository.FullActiveTechnique



@RunWith(classOf[JUnitRunner])
class NodeConfigurationChangeDetectServiceTest extends Specification {

  /* Test the change in node */
  def newTechnique(id: TechniqueId) = Technique(id, "tech" + id, "", Seq(), Seq(), TrackerVariableSpec(), SectionSpec("plop"), Set(), None)

  val service = new NodeConfigurationChangeDetectServiceImpl()

  val directiveLib = FullActiveTechniqueCategory(
      id = null
    , name = "foo"
    , description = ""
    , subCategories = Nil
    , activeTechniques = (
          FullActiveTechnique(
              id = ActiveTechniqueId("at-ppId")
            , techniqueName = TechniqueName("ppId")
            , acceptationDatetimes = SortedMap(TechniqueVersion("1.0") -> new DateTime(0))
            , techniques = SortedMap(TechniqueVersion("1.0") -> null)
            , directives = Nil
            , isEnabled = true
            , isSystem = false
          )
       :: FullActiveTechnique(
              id = ActiveTechniqueId("at-ppId0")
            , techniqueName = TechniqueName("ppId1")
            , acceptationDatetimes = SortedMap(TechniqueVersion("1.0") -> new DateTime(0))
            , techniques = SortedMap(TechniqueVersion("1.0") -> null)
            , directives = Nil
            , isEnabled = true
            , isSystem = false
          )
        :: Nil
      )
    , isSystem = true
  )

  private val simplePolicy = new RuleWithCf3PolicyDraft(
      new RuleId("ruleId"),
      new Cf3PolicyDraft(new Cf3PolicyDraftId("cfcId"),
      newTechnique(new TechniqueId(TechniqueName("ppId"), TechniqueVersion("1.0"))),
      Map(),
      TrackerVariableSpec().toVariable(),
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

  private val nextPolicyVaredOne = new RuleWithCf3PolicyDraft(
      new RuleId("ruleId1"),
      new Cf3PolicyDraft(new Cf3PolicyDraftId("cfcId1"),
      newTechnique(new TechniqueId(TechniqueName("ppId1"), TechniqueVersion("1.0"))),
      Map("one" -> InputVariable(InputVariableSpec("one", ""), Seq("one"))),
      TrackerVariableSpec().toVariable(),
      priority = 0, serial = 1)  // one variable
  )


  private val minNodeConf = new MinimalNodeConfig(
      "name",
      "hostname",
      Seq(),
      "psId",
      "root"
  )

  private val minNodeConf2 = new MinimalNodeConfig(
      "name2",
      "hostname",
      Seq(),
      "psId",
      "root"
  )



  "An empty node " should {
    "not have a change if everything is equal" in {
      service.detectChangeInNode(new SimpleNodeConfiguration(NodeId("id"),
                  Seq(),
                  Seq(),
                  false,
                  minNodeConf,
                  minNodeConf,
                  Some(new DateTime(1)),
                  Map(),
                  Map()), directiveLib) must beTheSameAs(Set())
    }
    "not have a change if the minimal are different, but there is no CR" in {
      service.detectChangeInNode(new SimpleNodeConfiguration(NodeId("id"),
                  Seq(),
                  Seq(),
                  false,
                  minNodeConf,
                  minNodeConf2,
                  Some(new DateTime(1)),
                  Map(),
                  Map()), directiveLib) must beTheSameAs(Set())
    }
  }


  "An node with one easy CR " should {
    "not have a change if everything is equal" in {
      service.detectChangeInNode(new SimpleNodeConfiguration(NodeId("id"),
                  Seq(simplePolicy),
                  Seq(simplePolicy),
                  false,
                  minNodeConf,
                  minNodeConf,
                  Some(new DateTime(1)),
                  Map(),
                  Map()), directiveLib) must beTheSameAs(Set())
    }

    "have its CR that changed if the minimal are different" in {
      service.detectChangeInNode(new SimpleNodeConfiguration(NodeId("id"),
                  Seq(simplePolicy),
                  Seq(simplePolicy),
                  false,
                  minNodeConf,
                  minNodeConf2,
                  Some(new DateTime(1)),
                  Map(),
                  Map()), directiveLib) === Set(new RuleId("ruleId"))
    }
  }

  "An node with one complex CR " should {
    "not have a change if everything is equal" in {
      service.detectChangeInNode(new SimpleNodeConfiguration(NodeId("id"),
                  Seq(policyVaredOne),
                  Seq(policyVaredOne),
                  false,
                  minNodeConf,
                  minNodeConf,
                  Some(new DateTime(1)),
                  Map(),
                  Map()), directiveLib) must beTheSameAs(Set())
    }

    "have a change if a variable is not equal" in {
      service.detectChangeInNode(new SimpleNodeConfiguration(NodeId("id"),
                  Seq(policyVaredOne),
                  Seq(policyOtherVaredOne),
                  false,
                  minNodeConf,
                  minNodeConf,
                  Some(new DateTime(1)),
                  Map(),
                  Map()), directiveLib) === Set(new RuleId("ruleId1"))
    }

    "have a change if serial is not equals (but same variable)" in {
      service.detectChangeInNode(new SimpleNodeConfiguration(NodeId("id"),
                  Seq(policyVaredOne),
                  Seq(nextPolicyVaredOne),
                  false,
                  minNodeConf,
                  minNodeConf,
                  Some(new DateTime(1)),
                  Map(),
                  Map()), directiveLib) === Set(new RuleId("ruleId1"))
    }

    "have a change if minimal is not equals" in {
      service.detectChangeInNode(new SimpleNodeConfiguration(NodeId("id"),
                  Seq(policyVaredOne),
                  Seq(nextPolicyVaredOne),
                  false,
                  minNodeConf,
                  minNodeConf2,
                  Some(new DateTime(1)),
                  Map(),
                  Map()), directiveLib) === Set(new RuleId("ruleId1"))
    }

    "have a change if minimal is not equals and serial different" in {
      service.detectChangeInNode(new SimpleNodeConfiguration(NodeId("id"),
                  Seq(policyVaredOne),
                  Seq(nextPolicyVaredOne),
                  false,
                  minNodeConf,
                  minNodeConf2,
                  Some(new DateTime(1)),
                  Map(),
                  Map()), directiveLib) === Set(new RuleId("ruleId1"))
    }

    "have a change if nothing is different, but previous CR is not existant" in {
      service.detectChangeInNode(new SimpleNodeConfiguration(NodeId("id"),
                  Seq(),
                  Seq(policyVaredOne),
                  false,
                  minNodeConf,
                  minNodeConf,
                  Some(new DateTime(1)),
                  Map(),
                  Map()), directiveLib) === Set(new RuleId("ruleId1"))
    }

    "have a change if nothing is different, but previous CR is existant and current is non existant" in {
      service.detectChangeInNode(new SimpleNodeConfiguration(NodeId("id"),
                  Seq(policyVaredOne),
                  Seq(),
                  false,
                  minNodeConf,
                  minNodeConf,
                  Some(new DateTime(1)),
                  Map(),
                  Map()), directiveLib) === Set(new RuleId("ruleId1"))
    }

    "have a change if min is different, previous CR is existant and current is non existant" in {
      service.detectChangeInNode(new SimpleNodeConfiguration(NodeId("id"),
                  Seq(policyVaredOne),
                  Seq(),
                  false,
                  minNodeConf,
                  minNodeConf2,
                  Some(new DateTime(1)),
                  Map(),
                  Map()), directiveLib) === Set(new RuleId("ruleId1"))
    }

    "have a change if min is different, previous CR is non existant and current is existant" in {
      service.detectChangeInNode(new SimpleNodeConfiguration(NodeId("id"),
                  Seq(),
                  Seq(policyVaredOne),
                  false,
                  minNodeConf,
                  minNodeConf2,
                  Some(new DateTime(1)),
                  Map(),
                  Map()), directiveLib) === Set(new RuleId("ruleId1"))
    }
  }


}