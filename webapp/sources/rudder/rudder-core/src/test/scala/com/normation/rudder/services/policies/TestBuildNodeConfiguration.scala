/*
*************************************************************************************
* Copyright 2019 Normation SAS
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

import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.appconfig.FeatureSwitch
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.nodes.NodeGroupCategoryId
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.policies.ActiveTechniqueCategoryId
import com.normation.rudder.domain.policies.ActiveTechniqueId
import com.normation.rudder.domain.policies.FullGroupTarget
import com.normation.rudder.domain.policies.FullRuleTargetInfo
import com.normation.rudder.domain.policies.GlobalPolicyMode
import com.normation.rudder.domain.policies.GroupTarget
import com.normation.rudder.domain.policies.PolicyMode
import com.normation.rudder.domain.policies.PolicyModeOverrides
import com.normation.rudder.domain.policies.Rule
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.repository.FullActiveTechnique
import com.normation.rudder.repository.FullActiveTechniqueCategory
import com.normation.rudder.repository.FullNodeGroupCategory
import com.normation.rudder.rule.category.RuleCategoryId
import org.joda.time.DateTime
import org.junit.runner._
import org.specs2.mutable._
import org.specs2.runner._

import scala.collection.SortedMap
import scala.concurrent.duration._


/*
 * This class test the JsEngine. 6.0
 * It must works identically on Java 7 and Java 8.
 *
 */

@RunWith(classOf[JUnitRunner])
class TestBuildNodeConfiguration extends Specification {

  sequential

  val t0 = System.currentTimeMillis()

  import NodeConfigData._
  val data = new TestNodeConfiguration()
  val t0_1 = System.currentTimeMillis()
  println(s"Test node configuration   : ${t0_1-t0} ms")

  def newNode(i: Int) = node1.copy(node = node1Node.copy(id = NodeId("node"+i), name = "node"+ i), hostname = s"node$i.localhost")

  val allNodes                  = ((1 to 1000).map(newNode) :+ root).map(n => (n.id, n)).toMap
  // only one group with all nodes
  val group                     = NodeGroup (NodeGroupId("allnodes"), "allnodes", "", None, false, allNodes.keySet, true)
  val groupLib                  = FullNodeGroupCategory (
                                      NodeGroupCategoryId("test_root"), "", "", Nil
                                    , List(FullRuleTargetInfo(FullGroupTarget(GroupTarget(group.id), group), "", "", true, false))
                                  )
  val directives                = (1 to 1000).map(i => data.rpmDirective("rpm"+i, "somepkg"+i)).map(d => (d.id, d)).toMap
  val directiveLib              = FullActiveTechniqueCategory(ActiveTechniqueCategoryId("root"), "root", "", Nil, List(
                                    FullActiveTechnique(
                                        ActiveTechniqueId("common"), data.commonTechnique.id.name
                                      , SortedMap(data.commonTechnique.id.version -> DateTime.now)
                                      , SortedMap(data.commonTechnique.id.version -> data.commonTechnique), List(data.commonDirective)
                                      , true, true
                                    ),
                                    FullActiveTechnique(
                                        ActiveTechniqueId("rpmPackageInstallation"), data.rpmTechnique.id.name
                                      , SortedMap(data.rpmTechnique.id.version -> DateTime.now)
                                      , SortedMap(data.rpmTechnique.id.version -> data.rpmTechnique), directives.values.toList
                                      , true, true
                                    )
                                    )
                                    , true
                                  )

  val rule                      = Rule(RuleId("rule"), "rule", RuleCategoryId("rootcat"), Set(GroupTarget(group.id)), directiveLib.allDirectives.keySet,"","", true, true)
  val valueCompiler             = new InterpolatedValueCompilerImpl()
  val ruleValService            = new RuleValServiceImpl(valueCompiler)
  val buildContext              = new PromiseGeneration_BuildNodeContext {
                                    override def interpolatedValueCompiler: InterpolatedValueCompiler = valueCompiler
                                    override def systemVarService: SystemVariableService = data.systemVariableService
                                  }
  val globalPolicyMode          = GlobalPolicyMode(PolicyMode.Enforce, PolicyModeOverrides.Always)
  val activeNodeIds             = Set(rootId, node1Node.id, node2Node.id)
  val allNodeModes              = allNodes.map{ case (id,_) => (id, defaultModesConfig) }
  val scriptEngineEnabled       = FeatureSwitch.Disabled
  val maxParallelism            = 8
  val jsTimeout                 = FiniteDuration(5, "minutes")
  val generationContinueOnError = false

  org.slf4j.LoggerFactory.getLogger("policy.generation.timing.buildNodeConfig").asInstanceOf[ch.qos.logback.classic.Logger].setLevel(ch.qos.logback.classic.Level.TRACE)

  "build node configuration" in {

    println(s"init   : ${System.currentTimeMillis-t0} ms")

    for(i <- 0 until 10) {
      println("\n--------------------------------")
      val t1 = System.currentTimeMillis()
      val ruleVal = ruleValService.buildRuleVal(rule, directiveLib, groupLib, allNodes)
      val ruleVals = Seq(ruleVal.getOrElse(throw new RuntimeException("oups")))
      val t2 = System.currentTimeMillis()
      val nodeContexts = buildContext.getNodeContexts(allNodes.keySet, allNodes, groupLib, Nil, data.globalAgentRun, data.globalComplianceMode, globalPolicyMode).getOrElse(throw new RuntimeException("oups"))
      val t3 = System.currentTimeMillis()
      BuildNodeConfiguration.buildNodeConfigurations(activeNodeIds, ruleVals, nodeContexts, allNodeModes, scriptEngineEnabled, globalPolicyMode, maxParallelism, jsTimeout, generationContinueOnError)
      val t4 = System.currentTimeMillis()

      println(s"ruleval: ${t2-t1} ms")
      println(s"context: ${t3-t2} ms")
      println(s"config : ${t4-t3} ms")
    }
    true === true
  }
}


//object FOO {
//
//  import zio._
//  import zio.syntax._
//  import com.normation.zio._
//  import com.normation.errors._
//
//  def main(args: Array[String]): Unit = {
//
//    def nano = UIO.effectTotal(System.nanoTime)
//    def log(s: String, t1: Long, t2: Long) = UIO.effectTotal(println(s + s"${(t2-t1)/1000} µs"))
//
//    val count = 0 until 1
//    val prog =
//      ZIO.foreachParN(8)(0 until 10) { j =>
//        for {
//        ref  <- Ref.make(0L)
//        t1   <- nano
//        loop <- ZIO.foreach(count) { i => // yes only one element
//                  for {
//                    t2  <- nano
//                    res <- i.succeed
//                    t3  <- nano
//                    _   <- ref.update(t => t + t3 - t2)
//                  } yield (i)
//                }
//        t4   <- nano
//        _    <- log(s"external $j : ", t1, t4)
//        _    <- ref.get.flatMap(t => UIO.effectTotal(println(s"inner sum $j: ${t/1000} µs")))
//      } yield ()
//    }
//    ZioRuntime.unsafeRun(prog)
//
//  }
//}
