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

package com.normation.rudder.domain.policies

import com.normation.inventory.domain.NodeId
import com.normation.inventory.domain.ServerRole
import com.normation.rudder.domain.Constants
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.utils.Control.sequence
import com.normation.utils.HashcodeCaching
import net.liftweb.json._
import net.liftweb.json.JsonDSL._
import net.liftweb.common._

/**
 * A target is either
 * - a Group of Node (static or dynamic),
 * - a list of Node
 * - a special (system) target ("all", "policy server", etc)
 * - a specific node
 */
sealed abstract class RuleTarget {
  def target : String
  def toJson : JValue = JString(target)
}

sealed trait NonGroupRuleTarget extends RuleTarget

object GroupTarget { def r = "group:(.+)".r }
final case class GroupTarget(groupId:NodeGroupId) extends RuleTarget with HashcodeCaching {
  override def target = "group:"+groupId.value
}

//object NodeTarget { def r = "node:(.+)".r }
//case class NodeTarget(nodeId:NodeId) extends RuleTarget {
//  override def target = "node:"+nodeId.value
//}

object PolicyServerTarget { def r = "policyServer:(.+)".r }
final case class PolicyServerTarget(nodeId:NodeId) extends NonGroupRuleTarget with HashcodeCaching {
  override def target = "policyServer:"+nodeId.value
}

final case object AllTarget extends NonGroupRuleTarget {
  override def target = "special:all"
  def r = "special:all".r
}

final case object AllTargetExceptPolicyServers extends NonGroupRuleTarget {
  override def target = "special:all_exceptPolicyServers"
  def r = "special:all_exceptPolicyServers".r
}

final case object AllServersWithRole extends NonGroupRuleTarget {
  override def target = "special:all_servers_with_role"
  def r = "special:all_servers_with_role".r
}

final case object AllNodesWithoutRole extends NonGroupRuleTarget {
  override def target = "special:all_nodes_without_role"
  def r = target.r
}

/**
 * A composite target is a target composed of different target
 * This target is rendered as Json
 */
sealed trait CompositeRuleTarget extends RuleTarget {
  final def target = compact(render(toJson))

  /**
   * Removing a target is the action of erasing that target in each place where
   * it appears.
   */
  final def removeTarget(target : RuleTarget) : CompositeRuleTarget = {
    //filter a set of targets recursively
    def recRemoveTarget(targets:Set[RuleTarget]): Set[RuleTarget] = {
      targets.filterNot( _ == target).map {
        case t:CompositeRuleTarget => t.removeTarget(target)
        case x => x
      }
    }

    def removeOnJoin(t:TargetComposition): TargetComposition = {
      t match {
        case TargetUnion(t) => TargetUnion(recRemoveTarget(t))
        case TargetIntersection(t) => TargetIntersection(recRemoveTarget(t))
      }
    }

    this match {
      case t: TargetComposition => removeOnJoin(t)
      case TargetExclusion(plus, minus) => TargetExclusion(removeOnJoin(plus), removeOnJoin(minus))
    }
  }
}

/**
 * Target Composition allow you to compose multiple targets in one target
 */
sealed trait TargetComposition extends CompositeRuleTarget {
  /**
   * Targets contained in that composition
   */
  def targets : Set[RuleTarget]

  /**
   * Add a target:
   * - If the same kind of composition: merge all targets
   * - otherwise: add the target as one of the target handled by the composition
   */
  def addTarget(target : RuleTarget) : TargetComposition

}

/**
 * Union of all Targets, Should take all Nodes from these targets
 */
final case class TargetUnion(targets:Set[RuleTarget] = Set()) extends TargetComposition {
  override val toJson : JValue = {
    ( "or" -> targets.map(_.toJson))
  }
  def addTarget(target : RuleTarget) : TargetComposition = TargetUnion(targets + target)
}

/**
 * Intersection of all Targets, Should take Nodes belongings to all targets
 */
final case class TargetIntersection(targets:Set[RuleTarget] = Set()) extends TargetComposition {
  override val toJson : JValue = {
    ( "and" -> targets.map(_.toJson))
  }
  def addTarget(target : RuleTarget) : TargetComposition = TargetIntersection(targets + target)
}

/**
 * this Target take 2 composition targets as parameters:
 * - Included : Targets that should be counted in
 * - Excluded : Targets that should be removed
 * Final result should be Included set of nodes with Nodes from Excluded removed
 */
final case class TargetExclusion(
    includedTarget : TargetComposition
  , excludedTarget : TargetComposition
) extends CompositeRuleTarget {

  /**
   * Json value of a composition:
   * { "include" -> target composition, "kind" -> target composition }
   */
  override val toJson : JValue = {
    ( "include" -> includedTarget.toJson ) ~
    ( "exclude" -> excludedTarget.toJson )
  }

  override def toString = {
    target
  }

  /**
   * Add a target to the included target
   */
  def updateInclude (target : RuleTarget) = {
    val newIncluded = includedTarget addTarget target
    copy(newIncluded)
  }

  /**
   * Add a target to the excluded target
   */
  def updateExclude (target : RuleTarget) = {
    val newExcluded = excludedTarget addTarget target
    copy(includedTarget,newExcluded)
  }

}

object RuleTarget extends Loggable {

  /**
   * Return all node ids that match the set of target.
   * allNodes pair is: (isPolicyServer, serverRoles)
   */
  def getNodeIds(
      targets : Set[RuleTarget]
    , allNodes: Map[NodeId, (Boolean /* isPolicyServer */, Set[ServerRole])]
    , groups  : Map[NodeGroupId, Set[NodeId]]
  ) : Set[NodeId] = {

    /* A Node with a server role is either:
     *   * A node with a role (easy)
     *   * A node that is a policy Server
     *   * A node with the root id (in fact: Constants.ROOT_POLICY_SERVER_ID), the root policy server
     */
    def isANodeWithRole (nodeId : NodeId, isPolicyServer : Boolean ,serverRoles : Set[ServerRole]) : Boolean = {
      serverRoles.size>0 || isPolicyServer || nodeId == Constants.ROOT_POLICY_SERVER_ID
    }
    (Set[NodeId]() /: targets) { case (nodes , target) => target match {
      case AllTarget => return allNodes.keySet
      case AllTargetExceptPolicyServers => nodes ++ allNodes.collect { case(k,n) if(!n._1) => k }
      case PolicyServerTarget(nodeId) => nodes + nodeId
      case AllServersWithRole =>
        // All nodes with server roles or policyServer (ie relay)
        nodes ++ allNodes.collect {
          case (nodeId,(isPolicyServer, roles)) if isANodeWithRole(nodeId,isPolicyServer,roles) =>
            nodeId
         }
      case AllNodesWithoutRole =>
        // All nodes with no server roles and not a policy server/relay)
        nodes ++ allNodes.collect {
          case (nodeId,(isPolicyServer, roles)) if !(isANodeWithRole(nodeId,isPolicyServer,roles)) =>
            nodeId
         }

      //here, if we don't find the group, we consider it's an error in the
      //target recording, but don't fail, just log it.
      case GroupTarget(groupId) =>
        nodes ++ groups.getOrElse(groupId, Set())

      case TargetIntersection(targets) =>
        val nodeSets = targets.map(t => getNodeIds(Set(t), allNodes, groups))
        // Compute the intersection of the sets of Nodes
        val intersection = (allNodes.keySet/: nodeSets) {
          case (currentIntersection, nodes) => currentIntersection.intersect(nodes)
        }
        nodes ++ intersection

      case TargetUnion(targets) =>
        val nodeSets = targets.map(t => getNodeIds(Set(t), allNodes, groups))
        // Compute the union of the sets of Nodes
        val union = (Set[NodeId]()/: nodeSets) {
          case (currentUnion, nodes) => currentUnion.union(nodes)
        }
        nodes ++ union

      case TargetExclusion(included,excluded) =>
        // Compute the included Nodes
        val includedNodes = getNodeIds(Set(included), allNodes, groups)
        // Compute the excluded Nodes
        val excludedNodes = getNodeIds(Set(excluded), allNodes, groups)
        // Remove excluded nodes from included nodes
        val result = includedNodes -- excludedNodes
        nodes ++ result
    } }
  }

  /**
   * Unserialize RuleTarget from Json
   */
  def unserJson(json : JValue) : Box[RuleTarget] = {

    def unserComposition(json : JValue) : Box[TargetComposition] = {
      json match {
        case JObject(Nil) =>
          Full(TargetUnion())
        case JObject(JField("or",JArray(content)) :: Nil) =>
          for {
            targets <- sequence(content)(unserJson)
          } yield {
            TargetUnion(targets.toSet)
          }
        case JObject(JField("and",JArray(content)) :: Nil) =>
          for {
            targets <- sequence(content)(unserJson)
          } yield {
            TargetIntersection(targets.toSet)
          }
      case _ =>
        Failure(s"'${compact(render(json))}' is not a valid rule target")
      }
    }

    json match {
      case JString(s) =>
        unser(s)
      //we want to be able to have field in both order, but I don't know how to do it in an other way
      case JObject(fields) =>
        //look for include and exclude. We accept to not have each one,
        //and if several are given, just take one

        val includedJson = fields.collect {
          case JField("include", inc) => inc
        }.headOption
        val excludedJson = fields.collect {
          case JField("exclude", inc) => inc
        }.headOption

        (includedJson,excludedJson) match {
          case (None, None) => unserComposition(json)
          case (x,y) =>  //at least one of include/exclude was present, so we really want to do a composite
            for {
              includeTargets <- unserComposition(x.getOrElse(JObject(Nil)))
              excludeTargets <- unserComposition(y.getOrElse(JObject(Nil)))
            } yield {
              TargetExclusion(includeTargets,excludeTargets)
            }
        }
      case _ => // not a JObject ?
        unserComposition(json)
    }
  }

  def unser(s:String) : Option[RuleTarget] = {
    s match {
      case GroupTarget.r(g) =>
        Some(GroupTarget(NodeGroupId(g)))
      case PolicyServerTarget.r(s) =>
        Some(PolicyServerTarget(NodeId(s)))
      case AllTarget.r() =>
        Some(AllTarget)
      case AllTargetExceptPolicyServers.r() =>
        Some(AllTargetExceptPolicyServers)
      case AllServersWithRole.r() =>
        Some(AllServersWithRole)
      case AllNodesWithoutRole.r() =>
        Some(AllNodesWithoutRole)
      case _ =>
        try {
          unserJson(parse(s))
        } catch {
          case e : Exception =>
            logger.error(s"Error when trying to read the following serialized Rule target as a composite target (other case where not relevant): '${s}'. Reported parsing error cause was: ${e.getMessage}")
            None
        }
    }
  }

  /**
   * Create a targetExclusion from a Set of RuleTarget
   * If the set contains only a TargetExclusion, use it
   * else put all targets into a new target Exclusion using TargetUnion as composition
   */
  def merge(targets : Set[RuleTarget]) : TargetExclusion = {
    targets.toSeq match {
      case Seq(t:TargetExclusion) => t
      case _ =>
        val start = TargetExclusion(TargetUnion(Set()),TargetUnion(Set()))
        val res = (start /: targets) {
          case (res,e:TargetExclusion) =>
           res.updateInclude(e.includedTarget).updateExclude(e.excludedTarget)
          case (res,t) => res.updateInclude(t)
          }
        res
    }
  }

  /**
   * Transform a rule target string "id" to
   * a cfengine class compatible string,
   * prefixed by "group_"
   */
  def toCFEngineClassName(target: String) = {
    //normalisation process:
    // 1) to asccii
    // 2) cfengine normalisation, replacing all non [alphanum-]
    ///   char by _

    // from http://stackoverflow.com/a/2413228/436331 and the precision
    // in http://stackoverflow.com/a/5697575/436331
    def toAscii(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("""[\p{Mn}\p{Me}]+""", "")

    "group_" + toAscii(target).toLowerCase.replaceAll("""[^\p{Alnum}]""", "_")

  }

}

/** common information on a target */

case class RuleTargetInfo(
    target     : RuleTarget
  , name       : String
  , description: String
  , isEnabled  : Boolean
  , isSystem   : Boolean
) extends HashcodeCaching

///// the full version with all information /////

sealed trait FullRuleTarget {
  def target: RuleTarget
}

final case class FullGroupTarget(
    target   : GroupTarget
  , nodeGroup: NodeGroup
) extends FullRuleTarget

final case class FullCompositeRuleTarget(
    target: CompositeRuleTarget
) extends FullRuleTarget

final case class FullOtherTarget(
    target: NonGroupRuleTarget
) extends FullRuleTarget

final case class FullRuleTargetInfo(
    target     : FullRuleTarget
  , name       : String
  , description: String
  , isEnabled  : Boolean
  , isSystem   : Boolean
) extends HashcodeCaching {

  def toTargetInfo = RuleTargetInfo(
      target = target.target
    , name = name
    , description = description
    , isEnabled = isEnabled
    , isSystem = isSystem
  )
}
