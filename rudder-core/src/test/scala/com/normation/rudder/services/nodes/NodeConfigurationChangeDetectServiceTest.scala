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

import scala.collection.immutable.SortedMap
import org.junit.runner._
import org.specs2.mutable._
import org.specs2.runner._
import org.joda.time.DateTime
import com.normation.cfclerk.domain._
import com.normation.inventory.domain.COMMUNITY_AGENT
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.domain.policies.ActiveTechniqueId
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.RuleWithCf3PolicyDraft
import com.normation.rudder.repository.FullActiveTechnique
import com.normation.rudder.repository.FullActiveTechniqueCategory
import com.normation.rudder.services.policies.nodeconfig._
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.reports.FullCompliance
import com.normation.rudder.reports.ReportingConfiguration



@RunWith(classOf[JUnitRunner])
class NodeConfigurationChangeDetectServiceTest extends Specification {



  /* Test the change in node */
  def newTechnique(id: TechniqueId) = Technique(id, "tech" + id, "", Seq(), Seq(), TrackerVariableSpec(), SectionSpec("plop"), None, Set(), None)

  val service = new DetectChangeInNodeConfiguration()

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

  private val simplePolicy = RuleWithCf3PolicyDraft(
      RuleId("ruleId")
    , DirectiveId("dir")
    , newTechnique(TechniqueId(TechniqueName("ppId"), TechniqueVersion("1.0")))
    , Map()
    , TrackerVariableSpec().toVariable()
    , priority = 0
    , serial = 0
    // no variable
  )

  private val policyVaredOne = RuleWithCf3PolicyDraft(
      RuleId("ruleId1")
    , DirectiveId("dir1")
    , newTechnique(TechniqueId(TechniqueName("ppId1"), TechniqueVersion("1.0")))
    , Map("one" -> InputVariable(InputVariableSpec("one", ""), Seq("one")))
    , TrackerVariableSpec().toVariable()
    , priority = 0
    , serial = 0
    // one variable
  )

  private val policyOtherVaredOne = policyVaredOne.copy(
      cf3PolicyDraft = policyVaredOne.cf3PolicyDraft.copyWithSetVariable(
          InputVariable(InputVariableSpec("one", ""), Seq("two"))
      )
  )

  private val nextPolicyVaredOne = policyVaredOne.copy(
      cf3PolicyDraft = policyVaredOne.cf3PolicyDraft.copy(serial = 1)
  )

  private val emptyNodeReportingConfiguration = ReportingConfiguration(None,None)

  private val nodeInfo = NodeInfo(
    id            = NodeId("name")
  , name          = "name"
  , description   = ""
  , hostname      = "hostname"
  , machineType   = "vm"
  , osName        = "debian"
  , osVersion     = "5.4"
  , servicePack   = None
  , ips           = List("127.0.0.1")
  , inventoryDate = DateTime.now()
  , publicKey     = ""
  , agentsName    = Seq(COMMUNITY_AGENT)
  , policyServerId= NodeId("root")
  , localAdministratorAccountName= "root"
  , creationDate  = DateTime.now()
  , isBroken      = false
  , isSystem      = false
  , isPolicyServer= false
  , serverRoles   = Set()
  , nodeReportingConfiguration = emptyNodeReportingConfiguration
  )

  private val nodeInfo2 = nodeInfo.copy(name = "name2")


  val emptyNodeConfig = NodeConfiguration(
    nodeInfo    = nodeInfo
  , policyDrafts= Set[RuleWithCf3PolicyDraft]()
  , nodeContext = Map[String, Variable]()
  , parameters  = Set[ParameterForConfiguration]()
  , writtenDate = None
  , isRootServer= false
  )

  val simpleNodeConfig = emptyNodeConfig.copy( policyDrafts = Set(simplePolicy))
  val complexeNodeConfig = emptyNodeConfig.copy( policyDrafts = Set(policyVaredOne))

  ////////////////////////// test //////////////////////////

  "An empty node " should {
    "not have a change if everything is equal" in {

      service.detectChangeInNode(
          Some(NodeConfigurationCache(emptyNodeConfig))
        , emptyNodeConfig
        , directiveLib
      ) must beTheSameAs(Set())
    }

    "not have a change if the minimal are different, but there is no CR" in {
      service.detectChangeInNode(
          Some(NodeConfigurationCache(emptyNodeConfig))
        , emptyNodeConfig.copy(nodeInfo = nodeInfo2)
        , directiveLib
      ) must beTheSameAs(Set())
    }
  }


  "An node with one easy CR " should {
    "not have a change if everything is equal" in {

      service.detectChangeInNode(
          Some(NodeConfigurationCache(simpleNodeConfig))
        , simpleNodeConfig
        , directiveLib
      ) must beTheSameAs(Set())
    }

    "have its CR that changed if the minimal are different" in {
      service.detectChangeInNode(
          Some(NodeConfigurationCache(simpleNodeConfig))
        , simpleNodeConfig.copy(nodeInfo = nodeInfo2)
        , directiveLib
      ) === Set(new RuleId("ruleId"))
    }
  }

  "An node with one complex CR " should {
    "not have a change if everything is equal" in {
      service.detectChangeInNode(
          Some(NodeConfigurationCache(complexeNodeConfig))
        , complexeNodeConfig
        , directiveLib
      ) must beTheSameAs(Set())
    }

    "have a change if a variable is not equal" in {
      service.detectChangeInNode(
          Some(NodeConfigurationCache(complexeNodeConfig))
        , complexeNodeConfig.copy(policyDrafts = Set(policyOtherVaredOne))
        , directiveLib
      ) === Set(new RuleId("ruleId1"))
    }

    "have a change if serial is not equals (but same variable)" in {
      service.detectChangeInNode(
          Some(NodeConfigurationCache(complexeNodeConfig))
        , complexeNodeConfig.copy(policyDrafts = Set(nextPolicyVaredOne))
        , directiveLib
      ) === Set(new RuleId("ruleId1"))

    }

    "have a change if minimal is not equals" in {
      service.detectChangeInNode(
          Some(NodeConfigurationCache(complexeNodeConfig))
        , complexeNodeConfig.copy(nodeInfo = nodeInfo2)
        , directiveLib
      ) === Set(new RuleId("ruleId1"))
    }

    "have a change if minimal is not equals and serial different" in {
      service.detectChangeInNode(
          Some(NodeConfigurationCache(complexeNodeConfig))
        , complexeNodeConfig.copy(
              nodeInfo = nodeInfo2
            , policyDrafts = Set(nextPolicyVaredOne)
          )
        , directiveLib
      ) === Set(new RuleId("ruleId1"))
    }

    "have a change if nothing is different, but previous CR is not existant" in {
      service.detectChangeInNode(
          Some(NodeConfigurationCache(emptyNodeConfig))
        , complexeNodeConfig
        , directiveLib
      ) === Set(new RuleId("ruleId1"))
    }

    "have a change if nothing is different, but previous CR is existant and current is non existant" in {
      service.detectChangeInNode(
          Some(NodeConfigurationCache(complexeNodeConfig))
        , emptyNodeConfig
        , directiveLib
      ) === Set(new RuleId("ruleId1"))
    }

    "have a change if min is different, previous CR is existant and current is non existant" in {
      service.detectChangeInNode(
          Some(NodeConfigurationCache(complexeNodeConfig))
        , emptyNodeConfig.copy( nodeInfo = nodeInfo2 )
        , directiveLib
      ) === Set(new RuleId("ruleId1"))
    }

    "have a change if min is different, previous CR is non existant and current is existant" in {
      service.detectChangeInNode(
          Some(NodeConfigurationCache(emptyNodeConfig))
        , complexeNodeConfig.copy( nodeInfo = nodeInfo2 )
        , directiveLib
      ) === Set(new RuleId("ruleId1"))
    }
  }


}