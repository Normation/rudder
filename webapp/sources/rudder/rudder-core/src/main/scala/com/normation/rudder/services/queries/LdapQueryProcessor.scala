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

package com.normation.rudder.services.queries

import cats.implicits.*
import com.normation.NamedZioLogger
import com.normation.errors.*
import com.normation.errors.RudderError
import com.normation.inventory.domain.*
import com.normation.inventory.ldap.core.*
import com.normation.inventory.ldap.core.LDAPConstants.*
import com.normation.ldap.sdk.*
import com.normation.ldap.sdk.BuildFilter.*
import com.normation.ldap.sdk.LDAPIOResult.*
import com.normation.ldap.sdk.syntax.*
import com.normation.rudder.domain.*
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.domain.queries.*
import com.normation.rudder.facts.nodes.CoreNodeFact
import com.normation.rudder.facts.nodes.NodeFactRepository
import com.normation.rudder.facts.nodes.QueryContext
import com.normation.rudder.facts.nodes.SelectNodeStatus
import com.normation.rudder.repository.ldap.LDAPEntityMapper
import com.normation.zio.currentTimeMillis
import com.unboundid.ldap.sdk.{LDAPConnection as _, SearchScope as _, *}
import com.unboundid.ldap.sdk.DereferencePolicy.NEVER
import java.util.regex.Pattern
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import zio.{System as _, *}
import zio.syntax.*

/*
 * We have two type of filters:
 * * success LDAP filters that can be directly translated to LDAP ones
 * * special filter, that may be almost anything, and at least
 *   need special (pre/post)-processing.
 */
sealed trait ExtendedFilter
//pur LDAP filters
final case class LDAPFilter(f: Filter) extends ExtendedFilter

//special ones
sealed trait SpecialFilter                                                  extends ExtendedFilter
sealed trait GeneralRegexFilter                                             extends SpecialFilter {
  def attributeName: String
  def regex:         String
}
sealed trait RegexFilter                                                    extends GeneralRegexFilter
sealed trait NotRegexFilter                                                 extends GeneralRegexFilter
final case class SimpleRegexFilter(attributeName: String, regex: String)    extends RegexFilter
final case class SimpleNotRegexFilter(attributeName: String, regex: String) extends NotRegexFilter

/*
 * An NodeQuery differ a little from a Query because its components are sorted in two ways:
 * - the server is apart with its possible filter from criteria
 * - other criteria are sorted by group of things that share the same dependency path to server,
 *   and the attribute on witch join are made.
 *   The attribute must be on server.
 *   For now, there is 3 groups:
 *   - Software : get their DN ;
 *   - Machine and physical element : get the Machine DN
 *   - Logical Element : get the Node DN
 *
 *   Moreover, we need a "DN to filter" function for the requested object type
 */
final case class LDAPNodeQuery(
    // filter on the return type.
    // a None here means that there was no criteria on the
    // return type, only on other objects
    nodeFilters: Option[Set[ExtendedFilter]], // the final composition to apply

    composition: CriterionComposition,
    transform:   ResultTransformation, // that map MUST not contains node related filters

    objectTypesFilters:          Map[DnType, Map[String, List[SubQuery]]],
    nodeInfoFilters:             Seq[NodeInfoMatcher],
    noFilterButTakeAllFromCache: Boolean // this is when we don't have ldapfilter, but only nodeinfo filter, so we
    // need to take eveything from cache
)

/*
 * A subquery is something that need to be done appart from the main query to
 * get interesting information and use then to decide what the main query must do.
 * Each subquery is run independantly.
 * We try to minimize the number of subqueries.
 */
final case class SubQuery(subQueryId: String, dnType: DnType, objectTypeName: String, filters: Set[ExtendedFilter])

case class RequestLimits(
    val subRequestTimeLimit: Int,
    val subRequestSizeLimit: Int,
    val requestTimeLimit:    Int,
    val requestSizeLimit:    Int
)

object DefaultRequestLimits extends RequestLimits(0, 0, 0, 0)

object InternalLDAPQueryProcessorLoggerPure extends NamedZioLogger {
  override def loggerName: String = "com.normation.rudder.services.queries.InternalLDAPQueryProcessor"
}

/**
 * This is the last step of query, where we are looking for check in NodeInfo - and complex,
 * the json path and check on node properties.
 */
object PostFilterNodeFromInfoService {
  val logger: Logger = LoggerFactory.getLogger("com.normation.rudder.services.queries")
  def getLDAPNodeInfo(
      foundNodeInfos: Seq[NodeInfo],
      predicates:     Seq[NodeInfoMatcher],
      composition:    CriterionComposition,
      allNodesInfos:  Seq[NodeInfo] // all the nodeinfo there is
  ): Seq[NodeId] = {
    def comp(a: Boolean, b: Boolean)                    = composition match {
      case And => a && b
      case Or  => a || b
    }
    // utility to combine predicates according to comp
    def combine(a: NodeInfoMatcher, b: NodeInfoMatcher) = new NodeInfoMatcher {
      override val debugString = {
        val c = composition match {
          case And => "&&"
          case Or  => "||"
        }
        s"(${a.debugString}) ${c} (${b.debugString})"
      }
      override def matches(node: NodeInfo): Boolean = comp(a.matches(node), b.matches(node))
    }

    // Only to be used in Or
    var foundNodeIds: Set[NodeId] = null

    // if there is no predicates (ie no specific filter on NodeInfo), we should just keep nodes from our list
    // allNodesId contains all the nodes id there is, and is defined only in case of "OR" composition
    def predicate(
        nodeInfo:    NodeInfo,
        pred:        Seq[NodeInfoMatcher],
        composition: CriterionComposition,
        allNodesId:  Option[Set[NodeId]]
    ) = {
      val contains = composition match {
        case Or  => allNodesId.map(_.contains(nodeInfo.id)).getOrElse(false) // we combine with all
        case And => true                                                     // we combine with all
      }

      if (pred.isEmpty) {
        // in that case, whatever the query composition, we can only return what was already found.
        contains
      } else {
        val combined        = predicates.reduceLeft(combine)
        val validPredicates = combined.matches(nodeInfo)
        val res             = comp(contains, validPredicates)

        if (logger.isTraceEnabled()) {
          logger.trace(s"${nodeInfo.id.value}: ${if (res) "OK" else "NOK"} for [${combined.debugString}]")
        }
        res
      }
    }

    composition match {
      // TODO: there is surely something we can do here
      case Or  =>
        foundNodeIds = foundNodeInfos.map(_.id).toSet
        allNodesInfos.filter(nodeinfo => predicate(nodeinfo, predicates, composition, Some(foundNodeIds))).map(_.id)
      case And =>
        if (predicates.isEmpty) {
          // paththru
          foundNodeInfos.map(_.id)
        } else {
          foundNodeInfos.collect { case nodeinfo if predicate(nodeinfo, predicates, composition, None) => nodeinfo.id }
        }
    }

  }
}

/**
 * Processor that translates Queries into LDAP search operations
 * for pending nodes
 */
class PendingNodesLDAPQueryChecker(
    val checker:        InternalLDAPQueryProcessor,
    nodeFactRepository: NodeFactRepository
) extends QueryChecker {

  override def check(query: Query, limitToNodeIds: Option[Seq[NodeId]])(implicit qc: QueryContext): IOResult[Set[NodeId]] = {
    if (query.criteria.isEmpty) {
      InternalLDAPQueryProcessorLoggerPure.debug(
        s"Checking a query with 0 criterium will always lead to 0 nodes: ${query}"
      ) *> Set.empty[NodeId].succeed
    } else {
      for {
        timePreCompute      <- currentTimeMillis
        // get the pending node infos we are considering
        allPendingNodeInfos <- nodeFactRepository.getAll()(qc, SelectNodeStatus.Pending)
        pendingNodeInfos     = limitToNodeIds match {
                                 case None      => allPendingNodeInfos.values.toSeq
                                 case Some(ids) =>
                                   allPendingNodeInfos.collect { case (nodeId, nodeInfo) if ids.contains(nodeId) => nodeInfo }.toSeq
                               }
        foundNodes          <- checker.internalQueryProcessor(query, Seq("1.1"), limitToNodeIds, 0, () => pendingNodeInfos.succeed)
        timePostComp        <- currentTimeMillis
        timeres              = timePostComp - timePreCompute
        _                   <- InternalLDAPQueryProcessorLoggerPure.debug(
                                 s"LDAP result: ${foundNodes.size} entries in pending nodes obtained in ${timeres}ms for query ${query.toString}"
                               )
      } yield {
        // filter out Rudder server component if necessary
        (query.returnType match {
          case NodeReturnType              =>
            // we have a special case for the root node that always never to that group, even if some weird
            // scenario lead to the removal (or non addition) of them
            foundNodes.filterNot(_.value == "root")
          case NodeAndRootServerReturnType => foundNodes
        }).toSet
      }
    }
  }
}

sealed trait QueryProcessorError {
  def msg: String
}

object QueryProcessorError {

  // IO Errors
  final case class LdapResult(msg: String, e: RudderError) extends QueryProcessorError
}

/**
 * Generic interface for LDAP query processor.
 * Must be implemented differently depending of
 * the InventoryDit (not the same behaviour for
 * accepted nodes and pending nodes)
 */
class InternalLDAPQueryProcessor(
    ldap:           LDAPConnectionProvider[RoLDAPConnection],
    dit:            InventoryDit,
    nodeDit:        NodeDit,
    ditQueryData:   DitQueryData,
    val ldapMapper: LDAPEntityMapper, // for LDAP attribute for nodes

    limits: RequestLimits = DefaultRequestLimits
) {

  import ditQueryData.*
  val logPure = InternalLDAPQueryProcessorLoggerPure

  /**
   *
   * The high level query processor, with all the relevant logics.
   * It looks in LDAP for infos that are only there
   * and in the NodeInfos for eveything else
   */
  def internalQueryProcessor(
      query:              Query,
      select:             Seq[String] = Seq(),
      limitToNodeIds:     Option[Seq[NodeId]] = None,
      debugId:            Long = 0L,
      lambdaAllNodeInfos: () => IOResult[Seq[CoreNodeFact]] // this is hackish, to have the list of all node if
      // only if necessary, to avoid the overall cost of looking for it

  ): IOResult[Seq[NodeId]] = {

    // normalize the query: remove duplicates, order elements (last one server)
    val normalizedQuery = normalize(query).toIO.chainError("Can not normalize query. This is likely a bug, please report it.")

    /*
     * We have an LDAPQuery:
     * - a composition type (and/or)
     * - a target object type to return with its own filters (not combined)
     * - criteria as a map of (dn type -->  map(object type name --> filters ))
     *   (for each dnType, we have all the attributes that should return that dnType and their own filter not composed)
     */

    /*
     * First step: forall criteria, combined filters according to composition
     * (the result is given as an LDAPObjectType, so we have everything to process the query)
     * We have one request by LDAPObjectType
     *
     * We have to make separated requests for special filter,
     * and we need to have one by filter. So these one are in separated requests.
     *
     * => getMapDn
     */

    for {
      // log start query
      _             <- logPure.debug(s"[${debugId}] Start search for ${query.toString}")
      timeStart     <- currentTimeMillis
      // Construct & normalize the data
      nq            <- normalizedQuery
      // special case: no query, but we create a dummy one,
      // identified by noFilterButTakeAllFromCache = true
      // in this case, we skip all the ldap part
      optdms        <- if (nq.noFilterButTakeAllFromCache) {
                         None.succeed
                       } else {
                         getMapDn(nq, debugId)
                       }

      // Fetching all node infos if necessary
      // This is an optimisation procedue, as it is a bit costly to fetch it, so we want to
      // have it only if the query is an OR, and Invertion, or and AND and there ae
      // no LDAP criteria
      allNodesInfos <- query.composition match {
                         case Or                                                    => lambdaAllNodeInfos()
                         case And if nq.noFilterButTakeAllFromCache                 => lambdaAllNodeInfos()
                         case And if query.transform == ResultTransformation.Invert => lambdaAllNodeInfos()
                         case And if optdms.isDefined                               => lambdaAllNodeInfos()

                         case _ => Seq[CoreNodeFact]().succeed
                       }
      timefetch     <- currentTimeMillis
      _             <-
        logPure.debug(
          s"[${debugId}] LDAP result: fetching if necessary all nodesInfos  (${allNodesInfos.size} entries) in nodes obtained in ${timefetch - timeStart} ms for query ${query.toString}."
        )

      // If dnMapSets returns a None, then it means that we are ANDing composition with an empty value
      // so we skip the last query
      // It needs to returns a Seq because a Set of NodeInfo is really expensive to compute
      results       <- optdms match {
                         case None if nq.noFilterButTakeAllFromCache =>
                           allNodesInfos.succeed
                         case None                                   =>
                           Seq[CoreNodeFact]().succeed
                         case Some(dms)                              =>
                           for {
                             ids <- executeLdapQueries(dms, nq, select, debugId)
                             _   <- logPure.trace(
                                      s"[${debugId}] Found ${ids.size} entries ; filtering with ${allNodesInfos.size} accepted nodes"
                                    )
                           } yield {
                             allNodesInfos.filter(nodeInfo => ids.contains(nodeInfo.id))
                           }
                       }
      // No more LDAP query is required here
      // Do the filtering about non LDAP data here
      timeldap      <- currentTimeMillis
      _             <-
        logPure.debug(
          s"[${debugId}] LDAP result: ${results.size} entries in nodes obtained in ${timeldap - timeStart} ms for query ${query.toString}"
        )

      nodeIdFiltered = query.composition match {
                         case And if results.isEmpty =>
                           // And and nothing returns nothing
                           Seq[NodeId]()
                         case And                    =>
                           // If i'm doing and AND, there is no need for the allNodes here
                           PostFilterNodeFromInfoService.getLDAPNodeInfo(
                             results.map(_.toNodeInfo),
                             nq.nodeInfoFilters,
                             query.composition,
                             Seq()
                           )
                         case Or                     =>
                           // Here we need the list of all nodes
                           PostFilterNodeFromInfoService.getLDAPNodeInfo(
                             results.map(_.toNodeInfo),
                             nq.nodeInfoFilters,
                             query.composition,
                             allNodesInfos.map(_.toNodeInfo)
                           )
                       }
      timefilter    <- currentTimeMillis
      _             <-
        logPure.debug(
          s"[post-filter:rudderNode] Found ${nodeIdFiltered.size} nodes when filtering for info service existence and properties (${timefilter - timeldap} ms)"
        )
      _             <- logPure.ifDebugEnabled {
                         val filtered = results.map(x => x.id.value).diff(nodeIdFiltered.map(x => x.value))
                         if (filtered.nonEmpty) {
                           logPure.debug(
                             s"[${debugId}] [post-filter:rudderNode] ${nodeIdFiltered.size} results (following nodes not in ou=Nodes,cn=rudder-configuration or not matching filters on NodeInfo: ${filtered
                                 .mkString(", ")}"
                           )
                         } else {
                           logPure.debug(
                             s"[${debugId}] [post-filter:rudderNode] ${nodeIdFiltered.size} results (following nodes not in ou=Nodes,cn=rudder-configuration or not matching filters on NodeInfo: ${filtered
                                 .mkString(", ")}"
                           )

                         }
                       }

      inverted     = query.transform match {
                       case ResultTransformation.Identity => nodeIdFiltered
                       case ResultTransformation.Invert   =>
                         logPure.logEffect.debug(s"[${debugId}] |- (need to get all nodeIds for inversion) ")
                         val res = allNodesInfos.map(_.id).diff(nodeIdFiltered)
                         logPure.logEffect.debug(s"[${debugId}] |- (invert) entries after inversion: ${res.size}")
                         res
                     }
      postFiltered = postFilterNode(inverted, query.returnType, limitToNodeIds)
    } yield {
      postFiltered
    }
  }

  /*
   * A raw execution without log, special optimisation case, invert, etc
   */
  def rawInternalQueryProcessor(query: Query, debugId: Long = 0L): IOResult[Seq[NodeId]] = {
    if (query.criteria.isEmpty) Seq().succeed
    else {
      for {
        nq  <- normalize(query).toIO.chainError("Error when normalizing LDAP query")
        ids <- getMapDn(nq, debugId).flatMap {
                 case None      => Seq().succeed
                 case Some(dns) => executeLdapQueries(dns, nq, Seq("1.1"), debugId)
               }
      } yield ids
    }
  }

  def getMapDn(nq: LDAPNodeQuery, debugId: Long): IOResult[Option[Map[DnType, Set[DN]]]] = {
    // then, actually execute queries
    def dnMapMapSets(
        normalizedQuery:    LDAPNodeQuery,
        ldapObjectTypeSets: Map[DnType, Map[String, LDAPObjectType]],
        debugId:            Long
    ): IOResult[Map[DnType, Map[String, Set[DN]]]] = {
      ZIO
        .foreach(ldapObjectTypeSets) {
          case (dnType, mapLot) =>
            ZIO
              .foreach(mapLot) {
                case (ot, lot) =>
                  // for each set of filter, execute the query
                  getDnsForObjectType(lot, normalizedQuery.composition, debugId)
                    .map(dns => (ot, dns))
                    .chainError(s"[${debugId}] `-> stop query due to error")
              }
              .map(x => (dnType, x.toMap))
        }
        .map(_.toMap)
    }

    // Now, groups resulting set of same DnType according to composition
    // Returns None if the resulting composition (using AND) leads to an empty set of Nodes, Some filtering otherwise
    def dnMapSets(
        normalizedQuery: LDAPNodeQuery,
        dnMapMapSets:    Map[DnType, Map[String, Set[DN]]]
    ): Option[Map[DnType, Set[DN]]] = {
      dnMapMapSets.foldLeft(Map[DnType, Set[DN]]().some) {
        // We got a None we can skip further folds
        case (None, _)                       => None
        case (Some(map), (dnType, dnMapSet)) =>
          // Compute composition of all dn lists as an option, so that we can have the empty case as a None (see And branch)
          val dnSet: Option[Set[DN]] = normalizedQuery.composition match {
            case Or  =>
              dnMapSet.foldLeft(Set[DN]().some) {
                case (Some(a), b) => Some(a union b._2)
                case (None, b)    => Some(b._2)
              }
            case And =>
              if (dnMapSet.isEmpty) {
                None
              } else {
                val ((_, head), tail) = (dnMapSet.head, dnMapSet.tail)
                val zero              = if (head.isEmpty) None else head.some
                tail.foldLeft(zero) {
                  case (None, _)         =>
                    None
                  case (Some(a), (_, b)) =>
                    if (b.isEmpty) {
                      None
                    } else {
                      // DNs may not be the same, we may have on one side ou=nodes and the other ou=accepted inventory
                      if (a.head.getParent == b.head.getParent) {
                        val intersect = a intersect b
                        if (intersect.isEmpty) None else Some(intersect)
                      } else {
                        val mapA = a.map(x => (x.getRDN, x)).toMap
                        val mapB = b.map(x => (x.getRDN, x)).toMap

                        val intersect = mapA.keySet intersect mapB.keySet

                        if (intersect.isEmpty) None else Some(intersect.map(mapA(_)))
                      }
                    }
                }
              }
          }
          dnSet.map(dn => map + ((dnType, dn)))
      }
    }

    for {
      lots <- createLDAPObjects(nq, debugId)
      dmms <- dnMapMapSets(nq, lots, debugId)
    } yield {
      dnMapSets(nq, dmms)
    }
  }

  def executeLdapQueries(
      dms:     Map[DnType, Set[DN]],
      nq:      LDAPNodeQuery,
      select:  Seq[String],
      debugId: Long
  ): IOResult[Seq[NodeId]] = {
    // transform all the DNs we get to filters for the targeted object type
    // here, we are objectType dependent: we use a mapping that is saying
    // "for that objectType, that dnType is transformed into a filter like that"
    def filterSeqSet(dnMapSets: Map[DnType, Set[DN]]): Seq[Set[Filter]] = {
      (
        dnMapSets map {
          case (dnType, dnMapSet) =>
            dnMapSet map { dn => nodeJoinFilters(dnType)(dn) }
        }
      ).toSeq
    }

    // now, build last filter depending on comparator :
    // or : just OR everything
    // and: and in Set, or between them so that: Set(a,b), Set(c,d) => OR( (a and c), (a and d), (b and c), (b and d) )
    //     or simpler: AND( (a or b), (c or d) )
    def buildLastFilter(
        normalizedQuery: LDAPNodeQuery,
        filterSeqSet:    Seq[Set[Filter]]
    ): IOResult[(Option[Filter], Set[SpecialFilter])] = {
      unspecialiseFilters(normalizedQuery.nodeFilters.getOrElse(Set[ExtendedFilter]())).toIO.catchAll { err =>
        val error = Chained("Error when processing final objet filters", err)
        logPure.debug(s"[${debugId}] `-> stop query due to error: ${error.fullMsg}") *>
        error.fail
      }.map {
        case (ldapFilters, specialFilters) =>
          val finalLdapFilter = normalizedQuery.composition match {
            case Or  =>
              if (filterSeqSet.size + ldapFilters.size <= 0) {
                Some(
                  NOT(HAS(A_OC))
                ) // means that we don't have any ldap filter, only extended one, so select no nodes, see #19538
              } else Some(OR((filterSeqSet.foldLeft(ldapFilters)(_ union _)).toSeq*))
            case And => // if returnFilter is None, just and other filter, else add it. TODO : may it be empty ?
              val seqFilter = ldapFilters.toSeq ++ filterSeqSet.flatMap(s => {
                s.size match {
                  case n if n < 1 => None
                  case 1          => Some(s.head)
                  case _          => Some(OR(s.toSeq*))
                }
              })

              seqFilter.size match {
                case x if x < 1 => None
                case 1          => Some(seqFilter.head)
                case _          => Some(AND(seqFilter*))
              }
          }
          (finalLdapFilter, specialFilters)
      }
    }

    (
      for {
        // Ok, do the computation here
        // still rely on LDAP here
        _   <- logPure.ifTraceEnabled {
                 ZIO.foreachDiscard(dms) {
                   case (dnType, dns) =>
                     logPure.trace(s"/// ${dnType} ==> ${dns.map(_.getRDN).mkString(", ")}")
                 }
               }
        fss  = filterSeqSet(dms)
        blf <- buildLastFilter(nq, fss)

        // for convenience
        (finalLdapFilter, finalSpecialFilters) = blf

        // final query, add "match only server id" filter if needed
        rt       = nodeObjectTypes.copy(filter = finalLdapFilter)
        _       <- logPure.debug(s"[${debugId}] |- (final query) ${rt}")
        entries <- executeQuery(
                     rt.baseDn,
                     rt.scope,
                     nodeObjectTypes.objectFilter,
                     rt.filter,
                     finalSpecialFilters,
                     select.toSet,
                     nq.composition,
                     debugId
                   )
      } yield entries.flatMap(x => x(A_NODE_UUID).map(NodeId(_)))
    )
      .tapError(err => logPure.debug(s"[${debugId}] `-> error: ${err.fullMsg}"))
      .tap(seq => logPure.debug(s"[${debugId}] `-> ${seq.size} results"))
  }

  /**
   * That method allows to post-process a list of NodeInfo based on
   * the resultType.
   * - step1: ~filter out policy server if we only want "simple" nodes~ => no, we need to do
   *   that in `queryAndChekNodeId` where we know about server roles
   * - step2: filter out nodes based on a given list of acceptable entries
   */
  private[this] def postFilterNode(
      entries:        Seq[NodeId],
      returnType:     QueryReturnType,
      limitToNodeIds: Option[Seq[NodeId]]
  ): Seq[NodeId] = {
    limitToNodeIds match {
      case None      => entries
      case Some(seq) => entries.filter(id => seq.exists(nodeId => nodeId.value == id.value))
    }
  }

  /*
   * From the list of DN to query with their filters, build a list of LDAPObjectType
   */
  private[this] def createLDAPObjects(query: LDAPNodeQuery, debugId: Long): IOResult[Map[DnType, Map[String, LDAPObjectType]]] = {
    ZIO
      .foreach(query.objectTypesFilters) {
        case (dnType, mapOtSubQueries) =>
          val sq = ZIO.foreach(mapOtSubQueries.toSeq) {
            case (ot, listSubQueries) =>
              val subqueries = ZIO.foreach(listSubQueries) {
                case SubQuery(subQueryId, dnType, objectTypeName, filters) =>
                  (unspecialiseFilters(filters) match {
                    case Right((ldapFilters, specialFilters)) =>
                      val f = ldapFilters.size match {
                        case 0 => None
                        case 1 => Some(ldapFilters.head)
                        case n =>
                          query.composition match {
                            case And => Some(AND(ldapFilters.toSeq*))
                            case Or  => Some(OR(ldapFilters.toSeq*))
                          }
                      }

                      (query.composition match {
                        case And => (f, specialFilters.map((And: CriterionComposition, _)))
                        case Or  => (f, specialFilters.map((Or: CriterionComposition, _)))
                      }).succeed

                    case Left(e) =>
                      val error = Chained(s"Error when processing filters for object type '${ot}'", e)
                      logPure.debug(s"[${debugId}] `-> stop query due to error: ${error.fullMsg}") *>
                      error.fail
                  }).map {
                    case (ldapFilters, specialFilters) =>
                      // for each set of filter, build the query
                      val f = ldapFilters match {
                        case None    => objectTypes(ot).filter
                        case Some(x) =>
                          objectTypes(ot).filter match {
                            case Some(y) => Some(AND(y, x))
                            case None    => Some(x)
                          }
                      }

                      (
                        subQueryId,
                        objectTypes(ot).copy(
                          filter = f,
                          join = joinAttributes(ot),
                          specialFilters = specialFilters
                        )
                      )
                  }
              }
              subqueries
          }

          // it's ok to List[Map[k, ot] to Map[k, ot] b/c k are unique,
          // we had sorted by objectTye
          sq.map(x => (dnType, x.flatten.toMap))

      }
      .map(_.toMap)
  }

  // execute a query with special filter based on the composition
  private[this] def executeQuery(
      base:           DN,
      scope:          SearchScope,
      objectFilter:   LDAPObjectTypeFilter,
      filter:         Option[Filter],
      specialFilters: Set[SpecialFilter],
      attributes:     Set[String],
      composition:    CriterionComposition,
      debugId:        Long
  ): IOResult[Seq[LDAPEntry]] = {

    def buildSearchRequest(addedSpecialFilters: Set[SpecialFilter]): IOResult[SearchRequest] = {
      // special filter can modify the filter and the attributes to get
      for {
        params      <- ZIO.foldLeft(addedSpecialFilters)((filter, attributes)) {
                         case ((f, currentAttributes), r) =>
                           val filterToApply = composition match {
                             case Or  => Some(ALL)
                             case And => f.orElse(Some(ALL))
                           }

                           (filterToApply, currentAttributes ++ getAdditionnalAttributes(Set(r))).succeed

                         case (_, sf) =>
                           Inconsistency("Unknow special filter, can not build a request with it: " + sf).fail

                       }
        finalFilter <- params._1 match {
                         case None    => Inconsistency("No filter (neither standard nor special) for request, can not process!").fail
                         case Some(x) => AND(objectFilter.value, x).succeed
                       }
      } yield {
        /*
         * Optimization : we limit query time/size. That means that perhaps we won't have all response.
         * That DOES not change the validity of each final answer, just we may don't find ALL valid answers.
         * (in the case of a and, a missing result here can lead to an empty set at the end)
         * TODO : this behavior should be removable
         */
        new SearchRequest(
          base.toString,
          scope.toUnboundid,
          NEVER,
          limits.subRequestSizeLimit,
          limits.subRequestTimeLimit,
          false,
          finalFilter,
          params._2.toSeq*
        )
      }
    }

    def baseQuery(con: RoLDAPConnection, addedSpecialFilters: Set[SpecialFilter]): IOResult[Seq[LDAPEntry]] = {
      // special filter can modify the filter and the attributes to get

      for {
        sr      <- buildSearchRequest(addedSpecialFilters)
        _       <- logPure.debug(s"[${debugId}] |--- ${sr}")
        entries <- con.search(sr)
        _       <- logPure.debug(s"[${debugId}] |---- after ldap search request ${entries.size} result(s)")
        post    <- postProcessQueryResults(entries, addedSpecialFilters.map((composition, _)), debugId)
        _       <- logPure.debug(s"[${debugId}] |---- after post-processing: ${post.size} result(s)")
      } yield {
        post
      }
    }

    def andQuery(con: RoLDAPConnection): IOResult[Seq[LDAPEntry]] = {
      baseQuery(con, specialFilters)
    }

    /*
     * if the composition is "OR", we have to process in two steps:
     * - a first step to get entries thanks to "normal" LDAP filter
     * - a second step with one request for each special filter, because
     *   their post-processing doesn't compose. For example, the REGEX
     *   special filter will actually perform a search that return EVERYTHING
     *   and post-process it with regex. With only one step, only entries matching
     *   that filter would be returned, even if they match other part of the OR.
     */
    def orQuery(con: RoLDAPConnection): IOResult[Seq[LDAPEntry]] = {
      // optimisation: we can group regex filter to post process them all in one pass

      val sf = specialFilters.groupBy {
        case r: RegexFilter    => "regex"
        case r: NotRegexFilter => "notregex"
        case _ => "other"
      }

      for {
        entries  <- filter match {
                      case Some(f) => baseQuery(con, Set())
                      // we are in a case of only special filter
                      case None    => Seq().succeed
                    }
        // now, each filter individually
        _        <- logPure.debug("[%s] |--- or (base filter): %s".format(debugId, entries.size))
        _        <- logPure.trace("[%s] |--- or (base filter): %s".format(debugId, entries.map(_.dn.getRDN).mkString(", ")))
        specials <- ZIO.foreach(sf: Iterable[(String, Set[SpecialFilter])]) {
                      case (k, specialFilters) =>
                        logPure.trace("[%s] |--- or (special filter '%s': %s".format(debugId, k, specialFilters)) *>
                        baseQuery(con, specialFilters)
                    }
        sFlat     = specials.flatten
        _        <- logPure.debug("[%s] |--- or (special filter): %s".format(debugId, sFlat.size))
        _        <- logPure.trace("[%s] |--- or (special filter): %s".format(debugId, sFlat.map(_.dn.getRDN).mkString(", ")))
        total     = (entries ++ sFlat).distinct
        _        <- logPure.debug(s"[${debugId}] |--- or (total): ${total.size}")
        _        <- logPure.trace(s"[${debugId}] |--- or (total): ${total.map(_.dn.getRDN).mkString(", ")}")
      } yield {
        total
      }
    }

    // actual implementation for buildSearchRequest

    for {
      con     <- ldap
      results <- composition match {
                   case Or  => orQuery(con)
                   case And => andQuery(con)
                 }
      _       <- logPure.trace(s"[${debugId}] |--- results are:")
      _       <- logPure.ifTraceEnabled(ZIO.foreach(results)(r => logPure.trace(s"[${debugId}] |--- ${r}")))
    } yield {
      results
    }
  }

  private[this] def getDnsForObjectType(
      lot:         LDAPObjectType,
      composition: CriterionComposition,
      debugId:     Long
  ): IOResult[Set[DN]] = {
    val LDAPObjectType(base, scope, objectFilter, ldapFilters, joinType, specialFilters) = lot

    // log sub-query with two "-"
    logPure.debug(s"[${debugId}] |-- ${lot}") *>
    executeQuery(
      base,
      scope,
      objectFilter,
      ldapFilters,
      specialFilters.map(_._2),
      Set(joinType.selectAttribute),
      composition,
      debugId
    ).flatMap { results =>
      val res = (results.flatMap { (e: LDAPEntry) =>
        joinType match {
          case DNJoin       => Some(e.dn)
          case ParentDNJoin => Some(e.dn.getParent)
          case NodeDnJoin   => e.valuesFor("nodeId").map(nodeDit.NODES.NODE.dn)
        }
      }).toSet

      logPure.debug(s"[${debugId}] |-- ${res.size} sub-results (merged)") *>
      logPure.trace(s"[${debugId}] |-- ObjectType: ${lot.baseDn}; ids: ${res.map(_.getRDN).mkString(", ")}") *>
      res.succeed
    }
  }

  /**
   * That method allows to transform a list of extended filter to
   * the corresponding list of success LDAP filter and success special filter.
   *
   * That method absolutly NOT modify standard filter with added filters
   * from special filter.
   * Special filter are handled in their own place
   */
  private[this] def unspecialiseFilters(filters: Set[ExtendedFilter]): PureResult[(Set[Filter], Set[SpecialFilter])] = {
    val start = Right((Set(), Set())): Either[RudderError, (Set[Filter], Set[SpecialFilter])]
    filters.foldLeft(start) {
      case (Right((ldapFilters, specials)), LDAPFilter(f))         => Right((ldapFilters + f, specials))
      case (Right((ldapFilters, specials)), r: GeneralRegexFilter) => Right((ldapFilters, specials + r))
      case (x, f)                                                  => Left(Inconsistency(s"Can not handle filter type: '${f}', abort"))
    }
  }

  /**
   * Special filters may need some attributes to work.
   * That method allows to get them
   */
  private[this] def getAdditionnalAttributes(filters: Set[SpecialFilter]): Set[String] = {
    filters.flatMap { case f: GeneralRegexFilter => Set(f.attributeName) }
  }

  private[this] def postProcessQueryResults(
      results:        Seq[LDAPEntry],
      specialFilters: Set[(CriterionComposition, SpecialFilter)],
      debugId:        Long
  ): IOResult[Seq[LDAPEntry]] = {
    def applyFilter(specialFilter: SpecialFilter, entries: Seq[LDAPEntry]): IOResult[Seq[LDAPEntry]] = {
      def getRegex(regexText: String): IOResult[Pattern] = {
        IOResult.attempt(
          s"The regular expression '${regexText}' is not valid. Expected regex syntax is the java " +
          s"one, documented here: http://docs.oracle.com/javase/8/docs/api/java/util/regex/Pattern.html"
        )(Pattern.compile(regexText))
      }

      /*
       * Apply the regex match filter on entries-> attribute after applying the valueFormatter function
       * (which can be used to normalized the value)
       */
      def regexMatch(attr: String, regexText: String, entries: Seq[LDAPEntry], valueFormatter: String => Option[String]) = {
        for {
          pattern <- getRegex(regexText)
        } yield {
          /*
           * We want to match "OK" an entry if any of the values for
           * the given attribute matches the regex.
           */
          entries.filter { entry =>
            val res = entry.valuesFor(attr).exists { value =>
              valueFormatter(value) match {
                case Some(v) => pattern.matcher(v).matches
                case _       => false
              }
            }
            logPure.trace(
              "[%5s] for regex check '%s' on attribute %s of entry: %s:%s".format(
                res,
                regexText,
                attr,
                entry.dn,
                entry.valuesFor(attr).mkString(",")
              )
            )
            res
          }
        }
      }

      /*
       * Apply the regex NOT match filter on entries-> attribute after applying the valueFormatter function
       * (which can be used to normalized the value)
       */
      def regexNotMatch(attr: String, regexText: String, entries: Seq[LDAPEntry], valueFormatter: String => Option[String]) = {
        for {
          pattern <- getRegex(regexText)
        } yield {
          /*
           * We want to match "OK" an entry if the entry does not
           * have the attribute or NONE of the value matches the regex.
           */
          entries.filter { entry =>
            logPure.trace(
              "Filtering with regex not matching '%s' entry: %s:%s".format(
                regexText,
                entry.dn,
                entry.valuesFor(attr).mkString(",")
              )
            )
            val res = entry.valuesFor(attr).forall { value =>
              valueFormatter(value) match {
                case Some(v) => !pattern.matcher(v).matches
                case _       => false
              }
            }
            logPure.trace("Entry matches: " + res)
            res
          }
        }
      }

      specialFilter match {
        case SimpleRegexFilter(attr, regexText) =>
          regexMatch(attr, regexText, entries, x => Some(x))

        case SimpleNotRegexFilter(attr, regexText) =>
          regexNotMatch(attr, regexText, entries, x => Some(x))

        case x => Inconsistency(s"Don't know how to post process query results for filter '${x}'").fail
      }
    }

    if (specialFilters.isEmpty) {
      results.succeed
    } else {
      val filterSeq = specialFilters.toSeq

      // we only know how to process homogeneous CriterionComposition. Different one are an error
      for {
        _           <- logPure.debug(s"[${debugId}] |---- post-process with filters: ${filterSeq.mkString("[", "]  [", "]")}")
        composition <- ZIO.foldLeft(filterSeq)(filterSeq.head._1) {
                         case (baseComp, (newComp, _)) if (newComp == baseComp) => baseComp.succeed
                         case _                                                 =>
                           s"Composition of special filters are not homogeneous, can not processed them. Special filters: ${specialFilters.toString}".fail
                       }
        results     <- composition match {
                         case And => // each step of filtering is the input of the next => pipeline
                           ZIO.foldLeft(filterSeq)(results) {
                             case (currentResults, (_, filter)) =>
                               applyFilter(filter, currentResults)
                           }
                         case Or  => // each step of the filtering take all entries as input, and at the end, all are merged
                           ZIO.foldLeft(filterSeq)(Seq[LDAPEntry]()) {
                             case (currentResults, (_, filter)) =>
                               applyFilter(filter, results).map(r => (Set() ++ r ++ currentResults).toSeq)
                           }
                       }
        _           <- logPure.debug(s"[${debugId}] |---- results (post-process): ${results.size}")
      } yield {
        results
      }
    }
  }

  /*
   * Transform a Query into an LDAPNodeQuery:
   * - check that the query only contains known LDAP Objects.
   * - group criteria that target objects under the same 'root'
   *   together. We have three roots:
   *   - nodes,
   *   - software,
   *   - machines.
   *
   * It is also here that we handle the return type.
   * After that point, we only know how to handle NODES, but
   * perhaps
   */
  private def normalize(query: Query): PureResult[LDAPNodeQuery] = {
    sealed trait QueryFilter
    object QueryFilter {
      final case class Ldap(dnType: DnType, objectType: String, filter: ExtendedFilter)                       extends QueryFilter
      // A filter that must be used in a nodeinfo
      final case class NodeInfo(criterion: NodeCriterionType, comparator: CriterionComparator, value: String) extends QueryFilter
    }

    /*
     * Create subqueries for each object type by merging adequatly
     */
    def groupFilterByLdapRequest(
        composition: CriterionComposition,
        ldapFilters: Seq[QueryFilter.Ldap]
    ): Map[DnType, Map[String, List[SubQuery]]] = {
      // group 'filter'(_3) by objectType (_1), then by LDAPObjectType (_2)
      ldapFilters.groupBy(_.dnType).map {
        case (dnType, byDnTypes) =>
          val byOt = byDnTypes.groupBy(_.objectType).map {
            case (objectType, byObjectType) =>
              val uniqueFilters = byObjectType.map(_.filter).toSet
              // here, we need to know if for the given object type, we are allows to merge several filter on the same
              // object in one request. This is generally the case, for example:
              // node.id == xxx AND node.hostname == yyy => one request with filter &(id=xxx)(hostname=yyy).
              // But for requests that are done on SET of elements, it does not compose for AND. For ex, for sub-groups
              // query, if we want (node in group1) AND (node in group2), we can't translate that into:
              // &(nodeGroupId=group1)(nodeGroupId=group2). We must do two sub requests, and intersec. For OR,
              // one request is OK.

              // only group is a special case with AND
              // use objectType as ID safe for group, use group filter value
              val subQueries = (objectType, composition) match {
                case ("group", And) => uniqueFilters.map(f => SubQuery("group:" + f.toString, dnType, objectType, Set(f))).toList
                case _              => SubQuery(objectType, dnType, objectType, uniqueFilters) :: Nil
              }
              (objectType, subQueries)
          }
          (dnType, byOt)
      }
    }

    def checkAndSplitFilterType(q: Query): PureResult[Seq[QueryFilter]] = {
      // Compute in one go all data for a filter, fails if one filter fails to build
      q.criteria.traverse {
        case crit @ CriterionLine(ot, a, comp, value) =>
          // objectType may be overriden in the attribute (for node state).
          val objectType = a.overrideObjectType.getOrElse(ot.objectType)

          // Validate that for each object type in criteria, we know it
          if (objectTypes.isDefinedAt(objectType)) {
            val tpe = if (objectType == "nodeAndPolicyServer") "node" else objectType

            // now, check if we have an LDAP filter or a Node filter
            for {
              validated <- a.cType.validate(value, comp.id)
              res       <- a.cType match {
                             case ldap: LDAPCriterionType =>
                               for {
                                 filter <- comp match {
                                             case Regex    => ldap.buildRegex(a.name, value)
                                             case NotRegex => ldap.buildNotRegex(a.name, value)
                                             case _        => Right(LDAPFilter(ldap.buildFilter(a.name, comp, value)))
                                           }
                               } yield {
                                 QueryFilter.Ldap(objectDnTypes(objectType), tpe, filter)
                               }

                             case node: NodeCriterionType =>
                               Right(QueryFilter.NodeInfo(node, comp, value))
                           }
            } yield {
              res
            }
          } else {
            Left(Inconsistency(s"The object type '${objectType}' is not know in criteria ${crit}"))
          }
      }
    }

    for {
      // Validate that we know the requested object type
      okQueryType     <- if (objectTypes.isDefinedAt(query.returnType.value)) Right("ok")
                         else {
                           Left(
                             Inconsistency(
                               s"The requested type '${query.returnType}' is not known. This is likely a bug. Please report it"
                             )
                           )
                         }
      buildFilters    <- checkAndSplitFilterType(query)
      ldapFilters      = buildFilters.collect { case x: QueryFilter.Ldap => x }
      nodeInfoFilters  = buildFilters.collect { case x: QueryFilter.NodeInfo => x }
      groupedSetFilter = groupFilterByLdapRequest(query.composition, ldapFilters)
      // Get only filters applied to nodes (NodeDn and 'node' objectType)
      nodeFilters      = groupedSetFilter.get(QueryNodeDn).flatMap(_.get("node")).map(_.flatMap(_.filters).toSet)
      // Get the other filters, by only removing those with 'node' objectType ... maybe we could do a partition here, or even do it above
      subQueries       = groupedSetFilter.view.mapValues(_.view.filterKeys(_ != "node").toMap).filterNot(_._2.isEmpty).toMap
    } yield {
      // at that point, it may happen that nodeFilters and otherFilters are empty
      val (mainFilters, andAndEmpty) = if (groupedSetFilter.isEmpty) {
        query.composition match {
          // In that case, we add a "get all nodes" query and all filters will be done in node info.
          // we should have a specific case here, saying: it's empty, we are AND, so we AND with all the cache
//          case And => (None, true)

          case And => (Some(Set[ExtendedFilter](LDAPFilter(BuildFilter.ALL))), true)
          // In that case, We should return no Nodes from this query and we will only query nodes with filters from node info.
          case Or  => (None, false)
        }
      } else { (nodeFilters, false) }

      val nodeInfos = nodeInfoFilters.map { case QueryFilter.NodeInfo(c, comp, value) => c.matches(comp, value) }
      LDAPNodeQuery(mainFilters, query.composition, query.transform, subQueries, nodeInfos, andAndEmpty)
    }
  }
}
