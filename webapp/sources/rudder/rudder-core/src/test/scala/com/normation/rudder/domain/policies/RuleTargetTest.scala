package com.normation.rudder.domain.policies

import com.normation.inventory.domain.Debian
import com.normation.inventory.domain.Linux
import com.normation.inventory.domain.NodeId
import com.normation.inventory.domain.UndefinedKey
import com.normation.inventory.domain.Version
import com.normation.rudder.domain.nodes.Node
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.nodes.NodeGroupCategoryId
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.nodes.NodeGroupUid
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.domain.nodes.NodeState
import com.normation.rudder.reports.ReportingConfiguration
import com.normation.rudder.repository.FullNodeGroupCategory
import net.liftweb.common._
import org.joda.time.DateTime
import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner._

@RunWith(classOf[JUnitRunner])
class RuleTargetTest extends Specification with Loggable {

  val nodeIds = (for {
    i <- 0 to 10
  } yield {
    NodeId(s"${i}")
  }).toSet

  def newNode(id: NodeId) =
    Node(id, "", "", NodeState.Enabled, false, false, DateTime.now, ReportingConfiguration(None, None, None), List(), None, None)

  val allNodeIds = nodeIds + NodeId("root")
  val nodes      = allNodeIds.map { id =>
    (
      id,
      NodeInfo(
        newNode(id),
        s"Node-${id}",
        None,
        Linux(Debian, "Jessie", new Version("7.0"), None, new Version("3.2")),
        Nil,
        DateTime.now,
        UndefinedKey,
        Seq(),
        NodeId("root"),
        "",
        None,
        None,
        None
      )
    )
  }.toMap

  val g1 = NodeGroup(
    NodeGroupId(NodeGroupUid("1")),
    "Empty group",
    "",
    Nil,
    None,
    false,
    Set(),
    true
  )
  val g2 = NodeGroup(
    NodeGroupId(NodeGroupUid("2")),
    "only root",
    "",
    Nil,
    None,
    false,
    Set(NodeId("root")),
    true
  )
  val g3 = NodeGroup(
    NodeGroupId(NodeGroupUid("3")),
    "Even nodes",
    "",
    Nil,
    None,
    false,
    nodeIds.filter(_.value.toInt == 2),
    true
  )
  val g4 = NodeGroup(
    NodeGroupId(NodeGroupUid("4")),
    "Odd nodes",
    "",
    Nil,
    None,
    false,
    nodeIds.filter(_.value.toInt != 2),
    true
  )
  val g5 = NodeGroup(
    NodeGroupId(NodeGroupUid("5")),
    "Nodes id divided by 3",
    "",
    Nil,
    None,
    false,
    nodeIds.filter(_.value.toInt == 3),
    true
  )
  val g6 = NodeGroup(
    NodeGroupId(NodeGroupUid("6")),
    "Nodes id divided by 5",
    "",
    Nil,
    None,
    false,
    nodeIds.filter(_.value.toInt == 5),
    true
  )

  val groups = Set(g1, g2, g3, g4, g5, g6)

  val groupTargets = groups.map(g => (GroupTarget(g.id), g))

  val fullRuleTargetInfos = (groupTargets
    .map(gt => {
      FullRuleTargetInfo(
        FullGroupTarget(gt._1, gt._2),
        "",
        "",
        true,
        false
      )
    }))
    .toList

  val unionTargets = groups
    .subsets()
    .map { gs =>
      val union      = TargetUnion(gs.map(g => GroupTarget(g.id)))
      val serverList = gs.foldLeft(Set[NodeId]()) { case (res, g) => g.serverList union res }
      (union, serverList)
    }
    .toSet
  val interTargets = groups
    .subsets()
    .map { gs =>
      val inter      = TargetIntersection(gs.map(g => GroupTarget(g.id)))
      val serverList = gs.foldLeft(allNodeIds) { case (res, g) => g.serverList intersect res }
      (inter, serverList)
    }
    .toSet

  val allComposite: Set[(TargetComposition, Set[NodeId])] = (unionTargets ++ interTargets).toSet

  val allTargetExclusions = {
    allComposite.flatMap {
      case (include, includedNodes) =>
        allComposite.map {
          case (exclude, excludedNodes) =>
            (TargetExclusion(include, exclude), (includedNodes -- excludedNodes))
        }
    }
  }.toSet

  val fngc = FullNodeGroupCategory(
    NodeGroupCategoryId("test_root"),
    "",
    "",
    Nil,
    fullRuleTargetInfos
  )

  val allTargets: Set[RuleTarget] = (groupTargets.map(_._1) ++ (allComposite.map(_._1)) ++ allTargetExclusions.map(_._1))

  " Nodes from Rule targets" should {
    "Be found correctly on simple rule targets" in {
      groupTargets.forall {
        case (gt, g) =>
          fngc.getNodeIds(Set(gt), nodes) === g.serverList
      }
    }
    "Be found correctly on group targets union" in {
      unionTargets.forall {
        case (gt, g) =>
          fngc.getNodeIds(Set(gt), nodes) === g
      }
    }
    "Be found correctly on group targets intersection" in {
      interTargets.forall {
        case (gt, g) =>
          fngc.getNodeIds(Set(gt), nodes) === g
      }
    }
    "Be found correctly on group targets exclusion " in {
      allTargetExclusions.forall {
        case (target, resultNodes) =>
          fngc.getNodeIds(Set(target), nodes) === resultNodes
      }
    }
  }

  "Rule targets" should {
    "Be correctly serialized and deserialized from their target" in {
      allTargets.forall { gt =>
        RuleTarget.unser(gt.target) match {
          case Some(unser) => unser == gt
          // TODO: what was the meaning of this test? It makes no sense
          case None        => false // gt.target == gt
        }
      } === true
    }

    "Have their group target removed in composite targets" in {
      val allComp = (allComposite ++ allTargetExclusions)
      groupTargets.forall {
        case (gt, _) =>
          allComp.forall {
            case (comp, _) =>
              comp.removeTarget(gt) match {
                case tc: TargetComposition => !tc.targets.contains(gt)
                case TargetExclusion(inc: TargetComposition, exc: TargetComposition) =>
                  !inc.targets.contains(gt) && !exc.targets.contains(gt)
              }
          }
      } === true
    }
  }

}
