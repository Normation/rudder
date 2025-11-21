package com.normation.rudder.domain.policies

import com.normation.JsonSpecMatcher
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
import java.time.Instant
import net.liftweb.common.*
import org.junit.runner.RunWith
import org.specs2.mutable.*
import org.specs2.runner.*
import zio.json.*

@RunWith(classOf[JUnitRunner])
class RuleTargetTest extends Specification with Loggable with JsonSpecMatcher {

  val nodeIds: Set[NodeId] = (for {
    i <- 0 to 10
  } yield {
    NodeId(s"${i}")
  }).toSet

  def newNode(id: NodeId): Node = {
    Node(
      id,
      "",
      "",
      NodeState.Enabled,
      isSystem = false,
      isPolicyServer = false,
      creationDate = Instant.now(),
      nodeReportingConfiguration = ReportingConfiguration(None, None, None),
      properties = List(),
      policyMode = None,
      securityTag = None
    )
  }

  val allNodeIds: Set[NodeId]           = nodeIds + NodeId("root")
  val nodes:      Map[NodeId, NodeInfo] = allNodeIds.map { id =>
    (
      id,
      NodeInfo(
        newNode(id),
        s"Node-${id}",
        None,
        Linux(Debian, "Jessie", new Version("7.0"), None, new Version("3.2")),
        Nil,
        Instant.now(),
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

  val nodeArePolicyServers: Map[NodeId, Boolean] = nodes.map { case (id, n) => (id, n.isPolicyServer) }

  val g1: NodeGroup = NodeGroup(
    NodeGroupId(NodeGroupUid("1")),
    "Empty group",
    "",
    Nil,
    None,
    isDynamic = false,
    serverList = Set(),
    _isEnabled = true
  )
  val g2: NodeGroup = NodeGroup(
    NodeGroupId(NodeGroupUid("2")),
    "only root",
    "",
    Nil,
    None,
    isDynamic = false,
    serverList = Set(NodeId("root")),
    _isEnabled = true
  )
  val g3: NodeGroup = NodeGroup(
    NodeGroupId(NodeGroupUid("3")),
    "Even nodes",
    "",
    Nil,
    None,
    isDynamic = false,
    serverList = nodeIds.filter(_.value.toInt == 2),
    _isEnabled = true
  )
  val g4: NodeGroup = NodeGroup(
    NodeGroupId(NodeGroupUid("4")),
    "Odd nodes",
    "",
    Nil,
    None,
    isDynamic = false,
    serverList = nodeIds.filter(_.value.toInt != 2),
    _isEnabled = true
  )
  val g5: NodeGroup = NodeGroup(
    NodeGroupId(NodeGroupUid("5")),
    "Nodes id divided by 3",
    "",
    Nil,
    None,
    isDynamic = false,
    serverList = nodeIds.filter(_.value.toInt == 3),
    _isEnabled = true
  )
  val g6: NodeGroup = NodeGroup(
    NodeGroupId(NodeGroupUid("6")),
    "Nodes id divided by 5",
    "",
    Nil,
    None,
    isDynamic = false,
    serverList = nodeIds.filter(_.value.toInt == 5),
    _isEnabled = true
  )

  val groups: Set[NodeGroup] = Set(g1, g2, g3, g4, g5, g6)

  val groupTargets: Set[(GroupTarget, NodeGroup)] = groups.map(g => (GroupTarget(g.id), g))

  val fullRuleTargetInfos: List[FullRuleTargetInfo] = (groupTargets
    .map(gt => {
      FullRuleTargetInfo(
        FullGroupTarget(gt._1, gt._2),
        "",
        "",
        isEnabled = true,
        isSystem = false
      )
    }))
    .toList

  val unionTargets: Set[(TargetUnion, Set[NodeId])]        = groups
    .subsets()
    .map { gs =>
      val union      = TargetUnion(gs.map(g => GroupTarget(g.id)))
      val serverList = gs.foldLeft(Set[NodeId]()) { case (res, g) => g.serverList union res }
      (union, serverList)
    }
    .toSet
  val interTargets: Set[(TargetIntersection, Set[NodeId])] = groups
    .subsets()
    .map { gs =>
      val inter      = TargetIntersection(gs.map(g => GroupTarget(g.id)))
      val serverList = gs.foldLeft(allNodeIds) { case (res, g) => g.serverList intersect res }
      (inter, serverList)
    }
    .toSet

  val allComposite: Set[(TargetComposition, Set[NodeId])] = (unionTargets ++ interTargets).toSet

  val allTargetExclusions: Set[(TargetExclusion, Set[NodeId])] = {
    allComposite.flatMap {
      case (include, includedNodes) =>
        allComposite.map {
          case (exclude, excludedNodes) =>
            (TargetExclusion(include, exclude), (includedNodes -- excludedNodes))
        }
    }
  }.toSet

  val fngc: FullNodeGroupCategory = FullNodeGroupCategory(
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
          fngc.getNodeIds(Set(gt), nodeArePolicyServers) === g.serverList
      }
    }
    "Be found correctly on group targets union" in {
      unionTargets.forall {
        case (gt, g) =>
          fngc.getNodeIds(Set(gt), nodeArePolicyServers) === g
      }
    }
    "Be found correctly on group targets intersection" in {
      interTargets.forall {
        case (gt, g) =>
          fngc.getNodeIds(Set(gt), nodeArePolicyServers) === g
      }
    }
    "Be found correctly on group targets exclusion " in {
      allTargetExclusions.forall {
        case (target, resultNodes) =>
          fngc.getNodeIds(Set(target), nodeArePolicyServers) === resultNodes
      }
    }
  }

  "Rule targets" should {

    "unserialize an invalid target from string" in {
      RuleTarget.unser("an-invalid-target") must beLeft
    }

    "unserialize a simple target from a string" in {
      RuleTarget.unser("group:a-string-target") === NodeGroupId.parse("a-string-target").map(GroupTarget(_))
    }

    "unserialize a non group rule target" in {
      "policy server" in {
        RuleTarget.unser("policyServer:a-node-id") must beRight(PolicyServerTarget(NodeId("a-node-id")))
      }
      "other special targets" in {
        List(AllTarget, AllPolicyServers, AllTargetExceptPolicyServers).forall(t => RuleTarget.unser(t.target) must beRight(t))
      }
    }

    "unserialize a default target to union" in {
      RuleTarget.unser("{}") must beRight(TargetUnion())
    }

    "serialize a target composition of simple targets" in {
      allComposite.forall { (c, _) =>
        // groups targets are sorted
        c.target must equalsJsonSemantic(c match {
          case TargetUnion(targets)        => s"""{"or":${targets.toList.map(_.target).sorted.toJson}}"""
          case TargetIntersection(targets) => s"""{"and":${targets.toList.map(_.target).sorted.toJson}}"""
        })
      }
    }

    "Be correctly serialized and unserialized from their target" in {
      allTargets.forall(gt => RuleTarget.unser(gt.target) must beRight(gt))
    }

    /*
     * The format changed in 7.0, but we can have eventLogs with the old format.
     * It is unclear is we want to pay the big migration cost for only that, and
     * we would prefer to either amortize it with an other rewrite of eventLogs,
     * or just keep it forever (we are not sure we really want to change evenLog ever)
     */
    "Is able to correctly parse the old format and write back the new one for policy servers" in {

      val oldFormat = """{"include":{"or":["special:all_servers_with_role"]},"exclude":{"or":[]}}"""
      val newFormat = """{"include":{"or":["special:all_policyServers"]},"exclude":{"or":[]}}"""

      val p = RuleTarget.unser(oldFormat)
      p must beRight(TargetExclusion(TargetUnion(Set(AllPolicyServers)), TargetUnion()))
      p.map(_.toString) must beRight(newFormat)
    }

    "Is able to correctly parse the old format and write back for simple nodes" in {

      val oldFormat = """{"include":{"or":["special:all_nodes_without_role"]},"exclude":{"or":[]}}"""
      val newFormat = """{"include":{"or":["special:all_exceptPolicyServers"]},"exclude":{"or":[]}}"""

      val p = RuleTarget.unser(oldFormat)
      p must beRight(TargetExclusion(TargetUnion(Set(AllTargetExceptPolicyServers)), TargetUnion()))
      p.map(_.toString) must beRight(newFormat)
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
