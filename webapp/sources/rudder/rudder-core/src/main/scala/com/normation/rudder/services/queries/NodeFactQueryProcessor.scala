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

import com.normation.box.*
import com.normation.errors.IOResult
import com.normation.inventory.domain.AcceptedInventory
import com.normation.inventory.domain.InventoryStatus
import com.normation.inventory.domain.NodeId
import com.normation.inventory.domain.PendingInventory
import com.normation.inventory.domain.RemovedInventory
import com.normation.rudder.domain.logger.FactQueryProcessorLoggerPure
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.nodes.NodeGroupUid
import com.normation.rudder.domain.nodes.NodeKind
import com.normation.rudder.domain.queries.*
import com.normation.rudder.facts.nodes.CoreNodeFact
import com.normation.rudder.facts.nodes.NodeFactRepository
import com.normation.rudder.facts.nodes.QueryContext
import com.normation.rudder.facts.nodes.SelectNodeStatus
import com.normation.zio.*
import com.softwaremill.quicklens.*
import net.liftweb.common.Box
import zio.*
import zio.syntax.*

/*
 * A NodeFactMatcher is the transformation of a query into a method that is able to
 * eval a NodeFact for that Query.
 * It takes into account:
 * - the different case (node, root, invert, etc)
 * - the query criteria
 *
 * NodeFactMatcher is a group for AND and for OR
 */
final case class NodeFactMatcher(debugString: String, matches: CoreNodeFact => IOResult[Boolean])

object NodeFactMatcher {
  val nodeAndRelayMatcher: NodeFactMatcher = {
    val s = "only matches node and relay"
    NodeFactMatcher(
      s,
      (n: CoreNodeFact) => {
        for {
          res <- (n.rudderSettings.kind != NodeKind.Root).succeed
          _   <- FactQueryProcessorLoggerPure.trace(s"    [${res}] for $s on '${n.rudderSettings.kind}'")
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
object GroupAnd extends Group {
  def compose(a: NodeFactMatcher, b: NodeFactMatcher): NodeFactMatcher = {
    (a, b) match {
      case (`zero`, _) => b
      case (_, `zero`) => a
      case _           => NodeFactMatcher(s"(${a.debugString}) && (${b.debugString})", (n: CoreNodeFact) => (a.matches(n) && b.matches(n)))
    }
  }

  def inverse(a: NodeFactMatcher): NodeFactMatcher =
    NodeFactMatcher(s"!(${a.debugString})", (n: CoreNodeFact) => a.matches(n).map(!_))

  val zero: NodeFactMatcher = NodeFactMatcher("and0_true", _ => true.succeed)
}

object GroupOr extends Group {
  def compose(a: NodeFactMatcher, b: NodeFactMatcher): NodeFactMatcher = {
    (a, b) match {
      case (`zero`, _) => b
      case (_, `zero`) => a
      case _           => NodeFactMatcher(s"(${a.debugString}) || (${b.debugString})", (n: CoreNodeFact) => (a.matches(n) || b.matches(n)))
    }
  }

  def inverse(a: NodeFactMatcher): NodeFactMatcher =
    NodeFactMatcher(s"!(${a.debugString})", (n: CoreNodeFact) => a.matches(n).map(!_))

  val zero: NodeFactMatcher = NodeFactMatcher("or0_false", _ => false.succeed)
}

/*
 * A case class to sort a list of criterion lines into the different kind we know about:
 * - core node fact
 * - sub group
 * - ldap (historical)
 */
final case class CriterionLines(
    coreNodeFact: Chunk[CriterionLine],
    subGroup:     Chunk[CriterionLine],
    ldap:         Chunk[CriterionLine]
)
object CriterionLines {
  def empty: CriterionLines = CriterionLines(Chunk.empty, Chunk.empty, Chunk.empty)
}

class NodeFactQueryProcessor(
    nodeFactRepo:  NodeFactRepository,
    groupRepo:     SubGroupComparatorRepository,
    ldapQueryProc: InternalLDAPQueryProcessor,
    status:        InventoryStatus = AcceptedInventory
) extends QueryProcessor with QueryChecker {

  def process(query:       Query)(implicit qc: QueryContext): Box[Seq[NodeId]] = processPure(query).map(_.toList.map(_.id)).toBox
  def processOnlyId(query: Query)(implicit qc: QueryContext): Box[Seq[NodeId]] = processPure(query).map(_.toList.map(_.id)).toBox

  def check(query: Query, nodeIds: Option[Seq[NodeId]])(implicit qc: QueryContext): IOResult[Set[NodeId]] = {
    // make a 0 criteria request raise an error like LDAP would do,
    // see: https://www.rudder-project.org/redmine/issues/12338
    if (query.criteria.isEmpty) {
      InternalLDAPQueryProcessorLoggerPure.debug(
        s"Checking a query with 0 criterium will always lead to 0 nodes: ${query}"
      ) *> Set.empty[NodeId].succeed
    } else {
      nodeIds match {
        case None      =>
          processPure(query).map(nodeFacts => nodeFacts.map(_.id).toSet)
        case Some(ids) =>
          processPure(query).map(nodeFacts => ids.toSet.intersect(nodeFacts.map(_.id).toSet))
      }

    }
  }

  def processPure(query: Query)(implicit qc: QueryContext): IOResult[Chunk[CoreNodeFact]] = {

    // Since we are just iterating on an array of facts, we want to keep a "by cpu" filling.
    // Heuristic will need to be more fine tailored, for now:
    // - it looks like it's worthy as early as 1000
    // - it looks like split/merge cost does not pay for itself above nb-core - 2
    // That was only checked on one t14s AMD Ryzen 7 PRO 6850U with test data, it *needs* more tailoring.
    def parallelism(sizeFacts: Int): Int = {
      if (sizeFacts < 1000) 1
      else {
        Math.max(1, java.lang.Runtime.getRuntime().availableProcessors() - 2)
      }
    }

    def process(s: SelectNodeStatus) = {
      for {
        t0  <- currentTimeMillis
        m   <- analyzeQuery(query)
        t1  <- currentTimeMillis
        _   <- FactQueryProcessorLoggerPure.Metrics.debug(s"Analyse query in ${t1 - t0} ms")
        res <- nodeFactRepo
                 .getAll()(qc, s)
                 .flatMap { all =>
                   ZIO
                     .filterPar(all.values)(node => FactQueryProcessorLoggerPure.debug(m.debugString) *> processOne(m, node))
                     .withParallelism(parallelism(all.size))
                 }
                 .map(Chunk.fromIterable)
        t2  <- currentTimeMillis
        _   <- FactQueryProcessorLoggerPure.Metrics.debug(s"Run query in ${t2 - t1} ms")
        _   <- FactQueryProcessorLoggerPure.debug(s"Found ${res.size} results")
        _   <- FactQueryProcessorLoggerPure.trace(s"Matching nodes: '${res.map(_.id.value).mkString("', '")}'")
      } yield res
    }

    status match {
      case AcceptedInventory => process(SelectNodeStatus.Accepted)
      case PendingInventory  => process(SelectNodeStatus.Pending)
      case RemovedInventory  => Chunk.empty.succeed
    }
  }

  /*
   * transform the query into a function to apply to a NodeFact and that say "yes" or "no"
   *
   * We have different "query-er":
   * - CoreNodeFact (we have info in cache)
   * - SubGroupQuery (we want to do only one query to external service for each line)
   * - LdapQuery (we want to have all lines of that kind grouped in a new query)
   */
  def analyzeQuery(query: Query)(implicit qc: QueryContext): IOResult[NodeFactMatcher] = {
    val group = if (query.composition == CriterionComposition.And) GroupAnd else GroupOr

    // we need a better pattern matching (extensible would be better) in place of `isInstanceOf`
    // we prefer coreNodeFact matcher on top of LDAP, since the former is quick in cache, the latter needs IO
    val sortedLinesIO = ZIO.foldLeft(query.criteria)(CriterionLines.empty) {
      case (lines, l) =>
        l match {
          // if possible, always use that one for performance reason
          case _ if l.attribute.nodeCriterionMatcher != UnsupportedByNodeMinimalApi =>
            lines.modify(_.coreNodeFact).using(_.appended(l)).succeed
          case _ if l.attribute.cType.isInstanceOf[SubGroupComparator]              =>
            lines.modify(_.subGroup).using(_.appended(l)).succeed
          case _ if l.attribute.cType.isInstanceOf[LDAPCriterionType]               =>
            lines.modify(_.ldap).using(_.appended(l)).succeed
          case _                                                                    =>
            // ??? Should not happen, perhaps log ?
            FactQueryProcessorLoggerPure.warn(
              s"The criterion line with attribute '${l.attribute.name}' of type ${l.attribute.cType} can't be handled. Please report that error to maintainers"
            ) *>
            lines.succeed
        }
    }

    for {
      // build matcher for criterion lines
      sortedLines  <- sortedLinesIO
      subgroupLines = subGroupMatcher(sortedLines.subGroup, groupRepo)
      ldapLines     = ldapMatcher(sortedLines.ldap, query)
      nodeLines     = nodeFactMatcher(sortedLines.coreNodeFact)
      lineResult   <- ZIO.foldLeft(subgroupLines ++ ldapLines ++ nodeLines)(group.zero) {
                        case (matcher, line) =>
                          line.map(group.compose(matcher, _))
                      }
    } yield {
      // inverse now if needed, because we don't want to return root if not asked *even* when inverse is present
      val inv = if (query.transform == ResultTransformation.Invert) group.inverse(lineResult) else lineResult
      // finally, filter out root if need
      val res = {
        if (query.returnType == QueryReturnType.NodeAndRootServerReturnType) inv
        else GroupAnd.compose(NodeFactMatcher.nodeAndRelayMatcher, inv)
      }
      res
    }
  }

  // here, we have two kinds of processing:
  // - one is testing on a node by node basis: this is the main case, where we want to know if a node
  //   matches of not a bunch of criteria regarding its inventory/properties
  // - one is "on all node at once": it is when an external service is the oracle and can decide what node
  //   matches its criteria. This is the case for the node-group matcher for example.
  // For now, we have to check the criterion to know, each external service is a special case
  def ldapMatcher(lines: Seq[CriterionLine], q: Query): Seq[IOResult[NodeFactMatcher]] = {
    // for LDAP, we rebuild a false query with only these lines and no transformation
    if (lines.nonEmpty) {
      val ldapQuery = q.modify(_.transform).setTo(ResultTransformation.Identity).modify(_.criteria).setTo(lines.toList)
      Seq(for {
        t0      <- currentTimeMillis
        nodeIds <- ldapQueryProc.rawInternalQueryProcessor(ldapQuery)
        t1      <- currentTimeMillis
        _       <- FactQueryProcessorLoggerPure.Metrics.debug(s"Analyse/exec ldap query in ${t1 - t0} ms")
      } yield {
        NodeFactMatcher(
          s"[sub-ldap query ${ldapQuery.toString()}]",
          (n: CoreNodeFact) => nodeIds.contains(n.id).succeed
        )
      })
    } else Seq()
  }

  def nodeFactMatcher(lines: Seq[CriterionLine]): Seq[IOResult[NodeFactMatcher]] = {
    lines.map { c =>
      NodeFactMatcher(
        s"[${c.objectType.objectType}.${c.attribute.name} ${c.comparator.id} ${c.value}]",
        (n: CoreNodeFact) => c.attribute.nodeCriterionMatcher.matches(n, c.comparator, c.value)
      ).succeed
    }
  }

  def subGroupMatcher(
      lines:     Seq[CriterionLine],
      groupRepo: SubGroupComparatorRepository
  )(implicit qc: QueryContext): Seq[IOResult[NodeFactMatcher]] = {
    lines.map { c =>
      for {
        groupNodes <- groupRepo.getNodeIds(NodeGroupId(NodeGroupUid(c.value)))
      } yield {
        NodeFactMatcher(
          s"[${c.objectType.objectType}.${c.attribute.name} ${c.comparator.id} ${c.value}]",
          (n: CoreNodeFact) => groupNodes.contains(n.id).succeed
        )
      }
    }
  }

  def processOne(matcher: NodeFactMatcher, n: CoreNodeFact): IOResult[Boolean] = {
    for {
      _   <- FactQueryProcessorLoggerPure.debug(s"  --'${n.fqdn}' (${n.id.value})--")
      res <- matcher.matches(n)
      _   <- FactQueryProcessorLoggerPure.debug(s"  = [${res}] on '${n.fqdn}' (${n.id.value})")
    } yield res
  }

}
