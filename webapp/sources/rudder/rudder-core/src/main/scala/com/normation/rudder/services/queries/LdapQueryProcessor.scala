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

import java.util.regex.Pattern

import com.normation.inventory.domain._
import com.normation.inventory.ldap.core.LDAPConstants._
import com.normation.inventory.ldap.core._
import com.normation.ldap.sdk.BuildFilter._
import com.normation.ldap.sdk._
import com.normation.rudder.domain._
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.domain.queries._
import com.normation.rudder.repository.ldap.LDAPEntityMapper
import com.normation.rudder.services.nodes.LDAPNodeInfo
import com.normation.rudder.services.nodes.NodeInfoService
import com.normation.utils.Control.sequence
import com.normation.utils.HashcodeCaching
import com.unboundid.ldap.sdk.DereferencePolicy.NEVER
import com.unboundid.ldap.sdk.{LDAPConnection => _, SearchScope => _, _}
import net.liftweb.common._
import net.liftweb.util.Helpers
import org.slf4j.LoggerFactory
import com.normation.ldap.sdk.LdapResult._
import cats.implicits._
import com.normation.NamedZioLogger
import com.normation.box._
import com.normation.errors.RudderError
import com.normation.errors._
import scalaz.zio._
import scalaz.zio.syntax._

/*
 * We have two type of filters:
 * * success LDAP filters that can be directly translated to LDAP ones
 * * special filter, that may be almost anything, and at least
 *   need special (pre/post)-processing.
 */
sealed trait ExtendedFilter
//pur LDAP filters
final case class LDAPFilter(f:Filter) extends ExtendedFilter with HashcodeCaching

//special ones
sealed trait SpecialFilter  extends ExtendedFilter
sealed trait GeneralRegexFilter extends SpecialFilter {
  def attributeName:String
  def regex:String
}
sealed trait RegexFilter    extends GeneralRegexFilter
sealed trait NotRegexFilter extends GeneralRegexFilter
final case class SimpleRegexFilter   (attributeName:String, regex:String) extends RegexFilter    with HashcodeCaching
final case class SimpleNotRegexFilter(attributeName:String, regex:String) extends NotRegexFilter with HashcodeCaching


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
    //filter on the return type.
    //a None here means that there was no criteria on the
    //return type, only on other objects
    nodeFilters       : Option[Set[ExtendedFilter]]
    //the final composition to apply
  , composition       : CriterionComposition
    //that map MUST not contains node related filters
  , objectTypesFilters: Map[DnType, Map[String, List[SubQuery]]]
  , nodeInfoFilters   : Seq[NodeInfoMatcher]
) extends HashcodeCaching

/*
 * A subquery is something that need to be done appart from the main query to
 * get interesting information and use then to decide what the main query must do.
 * Each subquery is run independantly.
 * We try to minimize the number of subqueries.
 */
final case class SubQuery(subQueryId: String, dnType: DnType, objectTypeName: String, filters: Set[ExtendedFilter])


final case class LdapQueryProcessorResult(
    // list of entries from inventory matching the search
    entries    : Seq[LDAPEntry]
    // a post filter to run on node info
  , nodeFilters: Seq[NodeInfoMatcher]
)


case class RequestLimits (
  val subRequestTimeLimit:Int,
  val subRequestSizeLimit:Int,
  val requestTimeLimit:Int,
  val requestSizeLimit:Int
) extends HashcodeCaching

object DefaultRequestLimits extends RequestLimits(0,0,0,0)

/**
 * Processor that translates Queries into LDAP search operations
 * for accepted nodes (it also checks that the node is registered
 * in the ou=Nodes branch)
 */
class AcceptedNodesLDAPQueryProcessor(
    nodeDit        : NodeDit
  , inventoryDit   : InventoryDit
  , processor      : InternalLDAPQueryProcessor
  , nodeInfoService: NodeInfoService
) extends QueryProcessor with Loggable {

  private[this] case class QueryResult(
      nodeEntry     : LDAPEntry
    , inventoryEntry: LDAPEntry
    , machineInfo   : Option[LDAPEntry]

  ) extends HashcodeCaching

  /**
   * only report entries that match query in also in node
   * @param query
   * @param select
   * @param limitToNodeIds
   * @return
   */
  private[this] def queryAndChekNodeId(
      query:Query,
      select:Seq[String],
      limitToNodeIds:Option[Seq[NodeId]]
  ) : Box[Seq[QueryResult]] = {

    val debugId = if(logger.isDebugEnabled) Helpers.nextNum else 0L

    for {
      res         <- processor.internalQueryProcessor(query,select,limitToNodeIds,debugId).toBox
      ldapEntries <- nodeInfoService.getLDAPNodeInfo(res.entries.flatMap(x => x(A_NODE_UUID).map(NodeId(_))).toSet, res.nodeFilters, query.composition)
    } yield {

      val inNodes = ldapEntries.map { case LDAPNodeInfo(nodeEntry, nodeInv, machineInv) =>
        QueryResult(nodeEntry, nodeInv, machineInv)
      }

      if(logger.isDebugEnabled) {
        val filtered = res.entries.map( _(A_NODE_UUID).get ).toSet -- inNodes.flatMap { case QueryResult(e, _, _) => e(A_NODE_UUID) }.toSet
        if(!filtered.isEmpty) {
            logger.debug(s"[${debugId}] [post-filter:rudderNode] ${inNodes.size} results (following nodes not in ou=Nodes,cn=rudder-configuration or not matching filters on NodeInfo: ${filtered.mkString(", ")}")
        }
      }

      //filter out Rudder server component if necessary

      query.returnType match {
        case NodeReturnType =>
            // we have a special case for the root node that always never to that group, even if some weird
            // scenario lead to the removal (or non addition) of them
          val withoutServerRole = inNodes.filterNot { case QueryResult(e, inv, _) =>  (inv.valuesFor(A_SERVER_ROLE).size>0 || e(A_NODE_UUID) == Some("root")) }
          if(logger.isDebugEnabled) {
            val filtered = (inNodes.flatMap { case QueryResult(e, _, _) => e(A_NODE_UUID) }).toSet -- withoutServerRole.flatMap { case QueryResult(e, _, _) => e(A_NODE_UUID) }
            if(!filtered.isEmpty) {
                logger.debug("[%s] [post-filter:policyServer] %s results".format(debugId, withoutServerRole.size, filtered.mkString(", ")))
            }
          }
          withoutServerRole.toSeq
        case NodeAndPolicyServerReturnType => inNodes.toSeq
      }
    }
  }

  override def process(query:Query) : Box[Seq[NodeInfo]] = {

    //only keep the one of the form Full(...)
    queryAndChekNodeId(query, NodeInfoService.nodeInfoAttributes, None).map { seq => seq.flatMap {
      case QueryResult(nodeEntry, inventoryEntry,machine) =>
        processor.ldapMapper.convertEntriesToNodeInfos(nodeEntry, inventoryEntry,machine).toBox match {
          case Full(nodeInfo) => Seq(nodeInfo)
          case e:EmptyBox =>
            logger.error((e ?~! "Ignoring entry in result set").messageChain)
            Seq()
        }
    } }
  }

}

/**
 * Processor that translates Queries into LDAP search operations
 * for pending nodes
 */
class PendingNodesLDAPQueryChecker(
    val checker:InternalLDAPQueryProcessor
) extends QueryChecker {

  override def check(query:Query, limitToNodeIds:Seq[NodeId]) : Box[Seq[NodeId]] = {
    if(query.criteria.isEmpty) {
      LoggerFactory.getILoggerFactory.getLogger(Logger.loggerNameFor(classOf[InternalLDAPQueryProcessor])).debug(
        s"Checking a query with 0 criterium will always lead to 0 nodes: ${query}"
      )
      Full(Seq.empty[NodeId])
    } else {
      for {
        res <- checker.internalQueryProcessor(query, Seq("1.1"), Some(limitToNodeIds)).toBox
        ids <- sequence(res.entries) { entry =>
          checker.ldapMapper.nodeDn2OptNodeId(entry.dn).toBox ?~! "Can not get node ID from dn %s".format(entry.dn)
        }
      } yield {
        ids
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
    ldap           : LDAPConnectionProvider[RoLDAPConnection]
  , dit            : InventoryDit
  , nodeDit        : NodeDit
  , ditQueryData   : DitQueryData
  , val ldapMapper : LDAPEntityMapper //for LDAP attribute for nodes
  , limits         : RequestLimits = DefaultRequestLimits
) extends NamedZioLogger {

  import ditQueryData._
  override def loggerName: String = this.getClass.getName

  /**
   *
   * The high level query processor, with all the
   * relevant logics.
   * Sub classes should call that method to
   * implement process&check method
   */
  def internalQueryProcessor(
      query         : Query
    , select        : Seq[String] = Seq()
    , limitToNodeIds: Option[Seq[NodeId]] = None
    , debugId       : Long = 0L
  ) : IOResult[LdapQueryProcessorResult] = {



    //normalize the query: remove duplicates, order elements (last one server)
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
     */
    def ldapObjectTypeSets(normalizedQuery: LDAPNodeQuery) = createLDAPObjects(normalizedQuery, debugId)

    //then, actually execute queries
    def dnMapMapSets(normalizedQuery: LDAPNodeQuery, ldapObjectTypeSets: Map[DnType, Map[String, LDAPObjectType]]): IOResult[Map[DnType, Map[String,Set[DN]]]] = {
      ZIO.foreach(ldapObjectTypeSets) { case (dnType, mapLot) =>
        ZIO.foreach(mapLot) { case (ot, lot) =>
          //for each set of filter, execute the query
          getDnsForObjectType(lot, normalizedQuery.composition, debugId).map(dns => (ot, dns) ).chainError(s"[${debugId}] `-> stop query due to error")
        }.map(x => (dnType, x.toMap))
      }.map(_.toMap)
    }

    //now, groups resulting set of same DnType according to composition
    def dnMapSets(normalizedQuery: LDAPNodeQuery, dnMapMapSets: Map[DnType, Map[String,Set[DN]]]) : Map[DnType, Set[DN]] = {
      dnMapMapSets map { case (dnType, dnMapSet) =>
        val dnSet:Set[DN] = normalizedQuery.composition match {
          case Or  => (Set[DN]() /: dnMapSet)( _ union _._2 )
          case And =>
            val s = if(dnMapSet.isEmpty) Set[DN]() else (dnMapSet.head._2 /: dnMapSet)( _ intersect _._2 )
//            if(s.isEmpty) {
//              logger.debug(s"[${debugId}] `-> early stop query (empty sub-query)"))
//              return Full(LdapQueryProcessorResult(Nil, Nil)) //there is no need to go farther, since it will lead to ending with empty set
//            }
//            else s
            s
        }
        (dnType, dnSet)
      }
    }


    //transform all the DNs we get to filters for the targeted object type
    //here, we are objectType dependent: we use a mapping that is saying
    //"for that objectType, that dnType is transformed into a filter like that"
    def filterSeqSet(dnMapSets: Map[DnType, Set[DN]]): Seq[Set[Filter]] =
      (dnMapSets map { case (dnType, dnMapSet) =>
        dnMapSet map { dn => nodeJoinFilters(dnType)(dn) }
      }).toSeq

    //now, build last filter depending on comparator :
    //or : just OR everything
    //and: and in Set, or between them so that: Set(a,b), Set(c,d) => OR( (a and c), (a and d), (b and c), (b and d) )
    //     or simpler: AND( (a or b), (c or d) )
    def buildLastFilter(normalizedQuery: LDAPNodeQuery, filterSeqSet: Seq[Set[Filter]]): IOResult[(Option[Filter], Set[SpecialFilter])] = {
      unspecialiseFilters(normalizedQuery.nodeFilters.getOrElse(Set[ExtendedFilter]())).toIO.catchAll { err =>
        val error = Chained("Error when processing final objet filters", err)
        logPure.debug(s"[${debugId}] `-> stop query due to error: ${error.fullMsg}") *>
        error.fail
      }.map { case (ldapFilters, specialFilters) =>
        val finalLdapFilter = normalizedQuery.composition match {
          case Or  =>
            Some(OR( ((ldapFilters /: filterSeqSet)( _ union _ )).toSeq:_*))
          case And => //if returnFilter is None, just and other filter, else add it. TODO : may it be empty ?
            val seqFilter = ldapFilters.toSeq ++
                filterSeqSet.map(s => if(s.size > 1) OR(s.toSeq:_*) else s.head ) //s should not be empty, since we returned earlier if it was the case
            seqFilter.size match {
              case x if x < 1 => None
              case 1 => Some(seqFilter.head)
              case _ => Some(AND( seqFilter:_* ))
            }
        }
        (finalLdapFilter, specialFilters )
      }
    }

    for {
      //log start query
      _        <- logPure.debug(s"[${debugId}] Start search for ${query.toString}")
      nq       <- normalizedQuery
      lots     <- ldapObjectTypeSets(nq)
      dmms     <- dnMapMapSets(nq, lots)
      dms      =  dnMapSets(nq, dmms)
      _        <- logPure.ifTraceEnabled {
                    ZIO.foreach(dms) { case (dnType, dns) =>
                     logPure.trace(s"/// ${dnType} ==> ${dns.map( _.getRDN).mkString(", ")}")
                    }
                  }
      fss      =  filterSeqSet(dms)
      blf      <- buildLastFilter(nq, fss)

      // for convenience
      (finalLdapFilter, finalSpecialFilters) = blf

      //final query, add "match only server id" filter if needed
      rt       =  nodeObjectTypes.copy(filter = finalLdapFilter)
      _        <- logPure.debug(s"[${debugId}] |- (final query) ${rt}")
      entries  <- (for {
                con      <- ldap
                results  <- executeQuery(rt.baseDn.toString, rt.scope, nodeObjectTypes.objectFilter, rt.filter, finalSpecialFilters, select.toSet, nq.composition, debugId)
              } yield {
                postFilterNode(results.groupBy( _.dn ).map( _._2.head ).toSeq, query.returnType, limitToNodeIds)
              }).foldM(
                err =>
                  logPure.debug(s"[${debugId}] `-> error: ${err.fullMsg}") *>
                   err.fail
              , seq =>
                  logPure.debug(s"[${debugId}] `-> ${seq.size} results") *>
                  seq.succeed
              )
    } yield {
      LdapQueryProcessorResult(entries, nq.nodeInfoFilters)
    }
  }

  /**
   * That method allows to post-process a list of nodes based on
   * the resultType.
   * - step1: filter out policy server if we only want "simple" nodes
   * - step2: filter out nodes based on a given list of acceptable entries
   */
  private[this] def postFilterNode(entries: Seq[LDAPEntry], returnType: QueryReturnType, limitToNodeIds:Option[Seq[NodeId]]) : Seq[LDAPEntry] = {

    val step1 = returnType match {
                  //actually, we are able at that point to know if we have a policy server,
                  //so we don't post-process anything.
                  case NodeReturnType => entries
                  case NodeAndPolicyServerReturnType => entries
                }
    val step2 = limitToNodeIds match {
                 case None => step1
                 case Some(seq) => step1.filter(e =>
                                     seq.exists(nodeId => nodeId.value == e(A_NODE_UUID).getOrElse("Missing attribute %s in node entry, that case is not supported.").format(A_NODE_UUID))
                                   )
               }

    step2
  }

  /*
   * From the list of DN to query with their filters, build a list of LDAPObjectType
   */
  /*
   * From the list of DN to query with their filters, build a list of LDAPObjectType
   */
  private[this] def createLDAPObjects(query: LDAPNodeQuery, debugId: Long) : IOResult[Map[DnType, Map[String, LDAPObjectType]]] = {
    ZIO.foreach(query.objectTypesFilters) { case(dnType, mapOtSubQueries) =>
      val sq = ZIO.foreach(mapOtSubQueries) { case (ot, listSubQueries) =>

        val subqueries = ZIO.foreach(listSubQueries) { case SubQuery(subQueryId, dnType, objectTypeName, filters) =>
          (unspecialiseFilters(filters) match {
            case Right((ldapFilters, specialFilters)) =>
              val f = ldapFilters.size match {
                case 0 => None
                case 1 => Some(ldapFilters.head)
                case n =>
                  query.composition match {
                    case And => Some(AND(ldapFilters.toSeq:_*))
                    case Or  => Some(OR(ldapFilters.toSeq:_*))
                  }
              }

              (query.composition match {
                case And => (f, specialFilters.map( ( And:CriterionComposition , _)) )
                case Or  => (f, specialFilters.map( ( Or :CriterionComposition , _)) )
              }).succeed

            case Left(e) =>
              val error = Chained(s"Error when processing filters for object type '${ot}'", e)
              logPure.debug(s"[${debugId}] `-> stop query due to error: ${error.fullMsg}") *>
              error.fail
          }).map { case (ldapFilters, specialFilters) =>

            //for each set of filter, build the query
            val f = ldapFilters match {
              case None    => objectTypes(ot).filter
              case Some(x) =>
                objectTypes(ot).filter match {
                  case Some(y) => Some(AND(y, x))
                  case None => Some(x)
                }
            }

            (subQueryId, objectTypes(ot).copy(
                filter         = f
              , join           = joinAttributes(ot)
              , specialFilters = specialFilters
            ))
          }
        }
        subqueries
      }

      // it's ok to List[Map[k, ot] to Map[k, ot] b/c k are unique,
      // we had sorted by objectTye
      sq.map(x => (dnType, x.flatten.toMap))

    }.map( _.toMap)
  }

  //execute a query with special filter based on the composition
  private[this] def executeQuery(base: DN, scope: SearchScope, objectFilter: LDAPObjectTypeFilter, filter: Option[Filter], specialFilters: Set[SpecialFilter], attributes:Set[String], composition: CriterionComposition, debugId: Long) : IOResult[Seq[LDAPEntry]] = {

    def buildSearchRequest(addedSpecialFilters:Set[SpecialFilter]) : IOResult[SearchRequest] = {
      //special filter can modify the filter and the attributes to get
      for {
        params      <- ZIO.foldLeft(addedSpecialFilters)((filter,attributes)) {
                         case ( (f, currentAttributes), r ) =>
                           val filterToApply = composition match {
                             case Or => Some(ALL)
                             case And => f.orElse(Some(ALL))             }

                           (filterToApply, currentAttributes ++ getAdditionnalAttributes(Set(r))).succeed

                         case (_, sf) =>
                           Unconsistancy("Unknow special filter, can not build a request with it: " + sf).fail

                       }
        finalFilter <- params._1 match {
                        case None    => Unconsistancy("No filter (neither standard nor special) for request, can not process!").fail
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
             base.toString
           , scope
           , NEVER
           , limits.subRequestSizeLimit
           , limits.subRequestTimeLimit
           , false
           , finalFilter
           , params._2.toSeq:_*
        )
      }
    }

    def baseQuery(con:RoLDAPConnection, addedSpecialFilters:Set[SpecialFilter]) : IOResult[Seq[LDAPEntry]] = {
      //special filter can modify the filter and the attributes to get

      for {
        sr      <- buildSearchRequest(addedSpecialFilters)
        _       <- logPure.debug(s"[${debugId}] |--- ${sr}")
        entries <- con.search(sr)
        _       <- logPure.debug(s"[${debugId}] |---- after ldap search request ${entries.size} result(s)")
        post    <- postProcessQueryResults(entries, addedSpecialFilters.map( (composition,_) ), debugId)
        _       <- logPure.debug(s"[${debugId}] |---- after post-processing: ${post.size} result(s)")
      } yield {
        post
      }
    }

    def andQuery(con:RoLDAPConnection) : IOResult[Seq[LDAPEntry]] = {
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
    def orQuery(con:RoLDAPConnection) : IOResult[Seq[LDAPEntry]] = {
      //optimisation: we can group regex filter to post process them all in one pass

      val sf = specialFilters.groupBy {
        case r:RegexFilter => "regex"
        case r:NotRegexFilter => "notregex"
        case _ => "other"
      }

      for {
        entries  <- filter match {
                      case Some(f) => baseQuery(con, Set())
                      //we are in a case of only special filter
                      case None    => Seq().succeed
                    }
                 //now, each filter individually
        _        <- logPure.debug("[%s] |--- or (base filter): %s".format(debugId, entries.size))
        _        <- logPure.trace("[%s] |--- or (base filter): %s".format(debugId, entries.map( _.dn.getRDN  ).mkString(", ")))
        specials <- ZIO.foreach(sf){ case (k, filters) => baseQuery(con, filters) }
        sFlat    =  specials.flatten
        _        <- logPure.debug("[%s] |--- or (special filter): %s".format(debugId, sFlat.size))
        _        <- logPure.trace("[%s] |--- or (special filter): %s".format(debugId, sFlat.map( _.dn.getRDN  ).mkString(", ")))
        total    =  (entries ++ sFlat).distinct
        _        <- logPure.debug(s"[${debugId}] |--- or (total): ${total.size}")
        _        <- logPure.trace(s"[${debugId}] |--- or (total): ${total.map( _.dn.getRDN  ).mkString(", ")}")
      } yield {
        total
      }
    }

    //actual implementation for buildSearchRequest

    for {
      con     <- ldap
      results <- composition match {
                     case Or  => orQuery(con)
                     case And => andQuery(con)
                   }
      _       <- logPure.debug(s"[${debugId}] |--- results are:")
      _       <- logPure.ifDebugEnabled(ZIO.foreach(results)(r => logPure.debug(s"[${debugId}] |--- ${r}")))
    } yield {
      results
    }
  }

  private[this] def getDnsForObjectType(lot:LDAPObjectType, composition: CriterionComposition, debugId: Long): IOResult[Set[DN]] = {
    val LDAPObjectType(base,scope, objectFilter, ldapFilters,joinType,specialFilters) = lot

    //log sub-query with two "-"
    logPure.debug("[%s] |-- %s".format(debugId, lot)) *>
    executeQuery(base, scope, objectFilter, ldapFilters, specialFilters.map( _._2), Set(joinType.selectAttribute), composition, debugId) >>= { results =>
      val res = (results.flatMap { e:LDAPEntry =>
        joinType match {
          case DNJoin => Some(e.dn)
          case ParentDNJoin => Some(e.dn.getParent)
          case NodeDnJoin => e.valuesFor("nodeId").map(nodeDit.NODES.NODE.dn )
        }
      }).toSet

      logPure.debug("[%s] |-- %s sub-results (merged)".format(debugId, res.size)) *>
      logPure.trace("[%s] |-- ObjectType: %s; ids: %s".format(debugId, lot.baseDn, res.map( _.getRDN).mkString(", "))) *>
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
  private[this] def unspecialiseFilters(filters:Set[ExtendedFilter]) : PureResult[(Set[Filter], Set[SpecialFilter])] = {
    val start = Right((Set(), Set())): Either[RudderError, (Set[Filter], Set[SpecialFilter])]
    filters.foldLeft(start) {
      case (  Right((ldapFilters, specials)), LDAPFilter(f)        ) => Right((ldapFilters + f, specials))
      case (  Right((ldapFilters, specials)), r:GeneralRegexFilter ) => Right((ldapFilters, specials + r))
      case (  x                             , f                    ) => Left(Unconsistancy(s"Can not handle filter type: '${f}', abort"))
    }
  }

  /**
   * Special filters may need some attributes to work.
   * That method allows to get them
   */
  private[this] def getAdditionnalAttributes(filters:Set[SpecialFilter]) : Set[String] = {
    filters.flatMap {
      case f:GeneralRegexFilter => Set(f.attributeName)
    }
  }

  private[this] def postProcessQueryResults(
      results:Seq[LDAPEntry]
    , specialFilters:Set[(CriterionComposition,SpecialFilter)]
    , debugId: Long
  ) : IOResult[Seq[LDAPEntry]] = {
    def applyFilter(specialFilter:SpecialFilter, entries:Seq[LDAPEntry]) : IOResult[Seq[LDAPEntry]] = {
      import java.util.regex.PatternSyntaxException
      def getRegex(regexText: String): IOResult[Pattern] = {
        IOResult.effect(Pattern.compile(regexText)).catchAll {
          case ex: PatternSyntaxException => SystemError(s"The regular expression '${regexText}' is not valid. Expected regex syntax is the java one, documented here: http://docs.oracle.com/javase/8/docs/api/java/util/regex/Pattern.html", ex).fail
        }
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
                case Some(v) => pattern.matcher( v ).matches
                case _       => false
              }
            }
            logPure.trace("[%5s] for regex check '%s' on attribute %s of entry: %s:%s".format(res, regexText, attr, entry.dn,entry.valuesFor(attr).mkString(",")))
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
            logPure.trace("Filtering with regex not matching '%s' entry: %s:%s".format(regexText,entry.dn,entry.valuesFor(attr).mkString(",")))
            val res = entry.valuesFor(attr).forall { value =>
              valueFormatter(value) match {
                case Some(v) => !pattern.matcher( v ).matches
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

        case x => Unconsistancy(s"Don't know how to post process query results for filter '${x}'").fail
      }
    }

    if(specialFilters.isEmpty) {
      results.succeed
    } else {
      val filterSeq = specialFilters.toSeq


      //we only know how to process homogeneous CriterionComposition. Different one are an error
      for {
        _           <- logPure.debug(s"[${debugId}] |---- post-process with filters: ${filterSeq.mkString("[", "]  [", "]")}")
        composition <- ZIO.foldLeft(filterSeq)(filterSeq.head._1) {
                         case ( baseComp, (newComp, _) ) if(newComp == baseComp) => baseComp.succeed
                         case _ => s"Composition of special filters are not homogeneous, can not processed them. Special filters: ${specialFilters.toString}".fail
                       }
        results     <- composition match {
                         case And => //each step of filtering is the input of the next => pipeline
                           ZIO.foldLeft(filterSeq)(results) { case (currentResults,(_,filter)) =>
                             applyFilter(filter, currentResults)
                           }
                         case Or => //each step of the filtering take all entries as input, and at the end, all are merged
                           ZIO.foldLeft(filterSeq)(Seq[LDAPEntry]()) { case (currentResults, (_,filter)) =>
                             applyFilter(filter, results).map( r => (Set() ++ r ++ currentResults).toSeq)
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
  private def normalize(query:Query) : PureResult[LDAPNodeQuery] = {
    sealed trait QueryFilter
    object QueryFilter {
      final case class Ldap    (dnType: DnType, objectType: String, filter: ExtendedFilter)                   extends QueryFilter
      // A filter that must be used in a nodeinfo
      final case class NodeInfo(criterion: NodeCriterionType, comparator: CriterionComparator, value: String) extends QueryFilter
    }

    /*
     * Create subqueries for each object type by merging adequatly
     */
    def groupFilterByLdapRequest(composition: CriterionComposition, ldapFilters: Seq[QueryFilter.Ldap]): Map[DnType, Map[String, List[SubQuery]]] = {
          // group 'filter'(_3) by objectType (_1), then by LDAPObjectType (_2)
      ldapFilters.groupBy(_.dnType).map { case (dnType, byDnTypes) =>
        val byOt = byDnTypes.groupBy(_.objectType).map { case(objectType, byObjectType) =>
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
            case ("group", And) => uniqueFilters.map(f => SubQuery("group:"+f.toString, dnType, objectType, Set(f))).to[List]
            case _              => SubQuery(objectType, dnType, objectType, uniqueFilters) :: Nil
          }
          (objectType, subQueries)
        }
        (dnType, byOt)
      }
    }

    def checkAndSplitFilterType(q: Query): PureResult[Seq[QueryFilter]] = {
      // Compute in one go all data for a filter, fails if one filter fails to build
      q.criteria.traverse { case crit@CriterionLine(ot, a, comp, value) =>
        // objectType may be overriden in the attribute (for node state).
        val objectType =  a.overrideObjectType.getOrElse(ot.objectType)

        // Validate that for each object type in criteria, we know it
        if(objectTypes.isDefinedAt(objectType)) {
          val tpe = if(objectType == "nodeAndPolicyServer") "node" else objectType

          //now, check if we have an LDAP filter or a Node filter
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
                               QueryFilter.Ldap(objectDnTypes(objectType),tpe, filter)
                             }

                           case node: NodeCriterionType =>
                             Right(QueryFilter.NodeInfo(node, comp, value))
                         }
          } yield {
            res
          }
        } else {
          Left(Unconsistancy(s"The object type '${objectType}' is not know in criteria ${crit}"))
        }
      }
    }

    for {
      // Validate that we know the requested object type
      okQueryType      <- if (objectTypes.isDefinedAt(query.returnType.value)) Right("ok")
                          else Left(Unconsistancy(s"The requested type '${query.returnType}' is not known. This is likely a bug. Please report it"))
      buildFilters     <- checkAndSplitFilterType(query)
      ldapFilters      =  buildFilters.collect { case x:QueryFilter.Ldap     => x }
      nodeInfoFilters  =  buildFilters.collect { case x:QueryFilter.NodeInfo => x }
      groupedSetFilter =  groupFilterByLdapRequest(query.composition, ldapFilters)
      // Get only filters applied to nodes (NodeDn and 'node' objectType)
      nodeFilters      =  groupedSetFilter.get(QueryNodeDn).flatMap ( _.get("node") ).map( _.flatMap(_.filters).toSet)
      // Get the other filters, by only removing those with 'node' objectType ... maybe we could do a partition here, or even do it above
      subQueries       =  groupedSetFilter.mapValues(_.filterKeys { _ != "node" }).filterNot( _._2.isEmpty)
    } yield {
      // at that point, it may happen that nodeFilters and otherFilters are empty. In that case, we add a
      // "get all nodes" query and all filters will be done in node info.

      val mainFilters = if(groupedSetFilter.isEmpty) { Some(Set[ExtendedFilter](LDAPFilter(BuildFilter.ALL))) } else { nodeFilters }

      val nodeInfos = nodeInfoFilters.map { case QueryFilter.NodeInfo(c, comp, value) => c.matches(comp, value)}
      LDAPNodeQuery(mainFilters, query.composition, subQueries, nodeInfos)
    }
  }

}
