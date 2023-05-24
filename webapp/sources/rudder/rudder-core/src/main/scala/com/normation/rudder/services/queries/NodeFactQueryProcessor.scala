/*
 *************************************************************************************
 * Copyright 2023 Normation SAS
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

package com.normation.rudder.services.queries

import com.normation.box._
import com.normation.errors.IOResult
import com.normation.inventory.domain.AcceptedInventory
import com.normation.inventory.domain.InventoryStatus
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.logger.FactQueryProcessorPure
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.nodes.NodeGroupUid
import com.normation.rudder.domain.nodes.NodeKind
import com.normation.rudder.domain.queries._
import com.normation.rudder.facts.nodes.NodeFact
import com.normation.rudder.facts.nodes.NodeFactRepository
import net.liftweb.common.Box
import zio._
import zio.stream.ZSink
import zio.syntax._

/*
 * A NodeFactMatcher is the transformation of a query into a method that is able to
 * eval a NodeFact for that Query.
 * It takes into account:
 * - the different case (node, root, invert, etc)
 * - the query criteria
 *
 * NodeFactMatcher is a group for AND and for OR
 */
final case class NodeFactMatcher(debugString: String, matches: NodeFact => IOResult[Boolean])

object NodeFactMatcher {
  val nodeAndRelayMatcher = {
    val s = "only matches node and relay"
    NodeFactMatcher(
      s,
      (n: NodeFact) => {
        for {
          res <- (n.rudderSettings.kind != NodeKind.Root).succeed
          _   <- FactQueryProcessorPure.trace(s"    [${res}] for $s on '${n.rudderSettings.kind}'")
        } yield res
      }
    )
  }
}

trait Group {
  def compose(a: NodeFactMatcher, b: NodeFactMatcher): NodeFactMatcher
  def inverse(a: NodeFactMatcher): NodeFactMatcher
  def zero: NodeFactMatcher
}
abstract class GroupImpl(override val zero: NodeFactMatcher) extends Group {
  def opString: String // for debug
  def op(a: IOResult[Boolean], b: IOResult[Boolean]): IOResult[Boolean]

  def compose(a: NodeFactMatcher, b: NodeFactMatcher) = {
    (a, b) match {
      case (`zero`, _) => b
      case (_, `zero`) => a
      case _           =>
        NodeFactMatcher(
          s"(${a.debugString}) ${opString} (${b.debugString})",
          (n: NodeFact) => op(a.matches(n), b.matches(n))
        )
    }
  }
  def inverse(a: NodeFactMatcher): NodeFactMatcher =
    NodeFactMatcher(s"!(${a.debugString})", (n: NodeFact) => a.matches(n).map(!_))
}
object GroupAnd extends GroupImpl(NodeFactMatcher("true", _ => true.succeed)) {
  override def opString:                                       String            = "&&"
  override def op(a: IOResult[Boolean], b: IOResult[Boolean]): IOResult[Boolean] = a && b
}
object GroupOr extends GroupImpl(NodeFactMatcher("false", _ => false.succeed)) {
  override def opString:                                       String            = "||"
  override def op(a: IOResult[Boolean], b: IOResult[Boolean]): IOResult[Boolean] = a || b
}

class NodeFactQueryProcessor(
    nodeFactRepo: NodeFactRepository,
    groupRepo:    SubGroupComparatorRepository,
    status:       InventoryStatus = AcceptedInventory
) extends QueryProcessor with QueryChecker {

  def process(query: Query):       Box[Seq[NodeId]] = processPure(query).toBox
  def processOnlyId(query: Query): Box[Seq[NodeId]] = processPure(query).toBox

  def check(query: Query, nodeIds: Option[Seq[NodeId]]): IOResult[Set[NodeId]] = {
    nodeIds match {
      case Some(ids) => // just add a and(nodeid) in fron of query
        for {
          m0  <- analyzeQuery(query)
          m1   = GroupAnd.compose(
                   NodeFactMatcher(s"check nodes in ${ids.map(_.value).mkString(", ")}", n => ids.contains(n.id).succeed),
                   m0
                 )
          res <- processNodeFactMatcher(m1)
        } yield res.toSet

      case None => Set().succeed
    }

  }

  def processPure(query: Query): IOResult[Chunk[NodeId]] = {
    for {
      m   <- analyzeQuery(query)
      res <- processNodeFactMatcher(m)
    } yield res
  }

  def processNodeFactMatcher(m: NodeFactMatcher): IOResult[Chunk[NodeId]] = {
    nodeFactRepo
      .getAllOn(status)
      .mapConcatChunkZIO { case node =>
        for {
          b   <- FactQueryProcessorPure.debug(m.debugString) *> processOne(m, node)
        } yield if(b) Chunk(node.id) else Chunk.empty
      }.run(ZSink.collectAll)
  }

  /*
   * transform the query into a function to apply to a NodeFact and that say "yes" or "no"
   */
  def analyzeQuery(query: Query): IOResult[NodeFactMatcher] = {
    val group = if (query.composition == And) GroupAnd else GroupOr

    for {
      // build matcher for criterion lines
      lineResult <- ZIO.foldLeft(query.criteria)(group.zero) {
                      case (matcher, criterion) =>
                        analyseCriterion(criterion).map(group.compose(matcher, _))
                    }
    } yield {
      // inverse now if needed, because we don't want to return root if not asked *even* when inverse is present
      val inv = if (query.transform == ResultTransformation.Invert) group.inverse(lineResult) else lineResult
      // finally, filter out root if need
      val res =
        if (query.returnType == NodeAndRootServerReturnType) inv else GroupAnd.compose(NodeFactMatcher.nodeAndRelayMatcher, inv)
      res
    }
  }

  def analyseCriterion(c: CriterionLine): IOResult[NodeFactMatcher] = {
    // here, we have two kind of processing:
    // - one is testing one a node by node basis: this is the main case, where we want to know if a node
    //   matches of not a bunch of criteria regarding its inventory/properties
    // - one is "on all node at once": it is when an external service is the oracle and can decide what node
    //   matches its criteria. This is the case for the node-group matcher for example.
    // For now, we have to check the criterion to know, each external service is a special case
    c.attribute.cType match {
      // not sure why I need that
      case SubGroupComparator(repo) =>
        for {
          groupNodes <- repo().getNodeIds(NodeGroupId(NodeGroupUid(c.value)))
        } yield {
          NodeFactMatcher(
            s"[${c.objectType.objectType}.${c.attribute.name} ${c.comparator.id} ${c.value}]",
            (n: NodeFact) => groupNodes.contains(n.id).succeed
          )
        }
      case _                        =>
        NodeFactMatcher(
          s"[${c.objectType.objectType}.${c.attribute.name} ${c.comparator.id} ${c.value}]",
          (n: NodeFact) => c.attribute.nodeCriterionMatcher.matches(n, c.comparator, c.value)
        ).succeed
    }
  }

  def processOne(matcher: NodeFactMatcher, n: NodeFact): IOResult[Boolean] = {
    for {
      _   <- FactQueryProcessorPure.debug(s"  --'${n.fqdn}' (${n.id.value})--")
      res <- matcher.matches(n)
      _   <- FactQueryProcessorPure.debug(s"  = [${res}] on '${n.fqdn}' (${n.id.value})")
    } yield res
  }

}
