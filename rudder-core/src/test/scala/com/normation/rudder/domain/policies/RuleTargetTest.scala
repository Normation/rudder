package com.normation.rudder.domain.policies

import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.nodes.NodeInfo
import org.joda.time.DateTime
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.nodes.NodeGroupId
import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner._
import net.liftweb.common._
import com.normation.cfclerk.domain._
import com.normation.rudder.repository.FullNodeGroupCategory
import com.normation.rudder.domain.nodes.NodeGroupCategoryId
import com.normation.rudder.reports.FullCompliance
import com.normation.rudder.reports.ReportingConfiguration

@RunWith(classOf[JUnitRunner])
class RuleTargetTest extends Specification with Loggable {

  val nodeIds = (for {
    i <- 0 to 10
  } yield {
    NodeId(s"${i}")
  }).toSet

  val allNodeIds = nodeIds + NodeId("root")
  val nodes = allNodeIds.map {
    id =>
      (
        id
      , NodeInfo (
          id, s"Node-${id}"
        , "" ,"" ,"" ,"" ,""
        , None, Nil, DateTime.now
        , "", Seq(), NodeId("root")
        , "", DateTime.now
        , false, false, false, Set()
        , ReportingConfiguration(None,None)
      )
    )
  }.toMap

  val g1 = NodeGroup (
    NodeGroupId("1"), "Empty group", "", None, false, Set(), true
  )
  val g2 = NodeGroup (
    NodeGroupId("2"), "only root", "", None, false, Set(NodeId("root")), true
  )
  val g3 = NodeGroup (
    NodeGroupId("3"), "Even nodes", "", None, false, nodeIds.filter(_.value.toInt == 2), true
  )
  val g4 = NodeGroup (
    NodeGroupId("4"), "Odd nodes", "", None, false, nodeIds.filter(_.value.toInt != 2), true
  )
  val g5 = NodeGroup (
    NodeGroupId("5"), "Nodes id divided by 3", "", None, false, nodeIds.filter(_.value.toInt == 3), true
  )
  val g6 = NodeGroup (
    NodeGroupId("6"), "Nodes id divided by 5", "", None, false, nodeIds.filter(_.value.toInt == 5), true
  )

  val groups = Set(g1, g2, g3, g4, g5, g6 )

  val groupTargets = groups.map(g => (GroupTarget(g.id),g))

  val fullRuleTargetInfos = (groupTargets.map(
    gt =>
      FullRuleTargetInfo(
          FullGroupTarget(gt._1,gt._2)
        , ""
        , ""
        , true
        , false
      )
  )).toList

  val unionTargets = groups.subsets.map{ gs =>
    val union = TargetUnion(gs.map(g => GroupTarget(g.id)))
    val serverList = (gs :\ Set[NodeId]()) {case (g,res) => g.serverList union res}
    (union,serverList)
  }
  val interTargets = groups.subsets.map{gs =>
    val inter =  TargetIntersection(gs.map(g => GroupTarget(g.id)))
    val serverList = (gs :\ allNodeIds) {case (g,res) => g.serverList intersect res}
    (inter,serverList)
  }

  val allComposite : Set [(TargetComposition,Set[NodeId])]= (unionTargets ++ interTargets).toSet

  val allTargetExclusions = {
    allComposite.flatMap { case (include,includedNodes) =>
      allComposite.map { case (exclude,excludedNodes) =>
        (TargetExclusion(include,exclude),(includedNodes -- excludedNodes))
      }
    }
  }.toSet

  val fngc = FullNodeGroupCategory (
      NodeGroupCategoryId("test_root")
    , ""
    , ""
    , Nil
    , fullRuleTargetInfos
  )

  " Nodes from Rule targets" should {
    "Be found correctly on simple rule targets" in {
      groupTargets.forall { case (gt,g) =>
        fngc.getNodeIds(Set(gt),nodes ) === g.serverList
      }
    }
   "Be found correctly on group targets union" in {
      unionTargets.forall { case (gt,g) =>
        fngc.getNodeIds(Set(gt),nodes ) === g
      }
    }
   "Be found correctly on group targets intersection" in {
      interTargets.forall { case (gt,g) =>
        fngc.getNodeIds(Set(gt),nodes ) === g
      }
    }
   "Be found correctly on group targets exclusion " in {
      allTargetExclusions.forall { case (target,resultNodes) =>
        fngc.getNodeIds(Set(target),nodes ) === resultNodes
      }
    }
  }

  val allTargets : Set[RuleTarget] = (groupTargets.map(_._1) ++ (allComposite.map(_._1)) ++ allTargetExclusions.map(_._1))
  "Rule targets" should {
    "Be correctly serialized and deserialized from their target" in {
      allTargets.forall {
        case gt => RuleTarget.unser(gt.target) match {
          case Some(unser) => unser === gt
          case None =>
            gt.target === gt
        }
      }
    }

    "Have their group target removed in composite targets" in {
      groupTargets.forall{
        case (gt,_) =>
         (allComposite ++ allTargetExclusions).forall {
           case (comp,_) => comp.removeTarget(gt) match {
             case tc : TargetComposition => tc.targets must not contain(gt)
             case TargetExclusion(inc: TargetComposition ,exc: TargetComposition ) =>
               inc.targets must not contain(gt)
               exc.targets must not contain(gt)
           }
         }
      }
    }
  }


}