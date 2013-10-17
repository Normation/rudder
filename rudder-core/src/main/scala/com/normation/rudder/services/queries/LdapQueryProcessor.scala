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

package com.normation.rudder.services.queries

import com.normation.rudder.repository.ldap.LDAPEntityMapper
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.domain.queries._
import com.unboundid.ldap.sdk.{ SearchScope => _, LDAPConnection => _, _ }
import DereferencePolicy.NEVER
import com.normation.ldap.sdk._
import com.normation.inventory.domain._
import com.normation.rudder.domain._
import com.normation.inventory.ldap.core._
import BuildFilter._
import LDAPConstants._
import org.slf4j.LoggerFactory
import net.liftweb.common._
import com.normation.utils.Control.{sequence,pipeline}
import com.normation.rudder.domain.RudderLDAPConstants._
import java.util.regex.Pattern
import com.normation.utils.HashcodeCaching
import net.liftweb.util.Helpers
import com.normation.rudder.services.nodes.NodeInfoService
import com.normation.rudder.services.nodes.LDAPNodeInfo
import net.liftweb.util.Helpers.tryo





/*
 * We have two type of filters:
 * * pure LDAP filters that can be directly translated to LDAP ones
 * * special filter, that may be almost anything, and at least
 *   need special (pre/post)-processing.
 */
sealed trait ExtendedFilter
//pur LDAP filters
final case class LDAPFilter(f:Filter) extends ExtendedFilter with HashcodeCaching

//special ones
sealed trait SpecialFilter extends ExtendedFilter
final case class RegexFilter(attributeName:String, regex:String) extends SpecialFilter with HashcodeCaching
final case class NotRegexFilter(attributeName:String, regex:String) extends SpecialFilter with HashcodeCaching


/*
 * An NodeQuery differ a little from a Query because it's component are sorted in two way :
 * - the server is apart with it's possible filter from criteria;
 * - other criteria are sorted by group of things that share the same dependency path to server,
 *   and the attribute on witch join are made.
 *   The attribute must be on server.
 *   For now, there is 3 groups:
 *   - Software : get their DN ;
 *   - Machine and physical element : get the Machine DN
 *   - Logical Element : get the Node DN
 *
 *   More over, we need a "DN to filter" function for the requested object type
 */
case class LDAPNodeQuery(
    //filter on the return type.
    //a None here means that there was no criteria on the
    //return type, only on other objects
    nodeFilters       : Option[Set[ExtendedFilter]]
    //the final composition to apply
  , composition       : CriterionComposition
    //that map MUST not contains node related filters
  , objectTypesFilters: Map[DnType, Map[String,Set[ExtendedFilter]]]
) extends HashcodeCaching


case class RequestLimits (
  val subRequestTimeLimit:Int,
  val subRequestSizeLimit:Int,
  val requestTimeLimit:Int,
  val requestSizeLimit:Int
) extends HashcodeCaching

object DefaultRequestLimits extends RequestLimits(10,1000,10,1000)


/**
 * Processor that translates Queries into LDAP search operations
 * for accepted nodes (it also checks that the node is registered
 * in the ou=Nodes branch)
 */
class AccepetedNodesLDAPQueryProcessor(
    nodeDit:NodeDit,
    inventoryDit:InventoryDit,
    processor:InternalLDAPQueryProcessor,
    nodeInfoService: NodeInfoService
) extends QueryProcessor with Loggable {


  private[this] case class QueryResult(
      nodeEntry:LDAPEntry
    , inventoryEntry:LDAPEntry
    , machineObjectClass:Option[Seq[String]]
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
      select:Seq[String] = Seq(),
      limitToNodeIds:Option[Seq[NodeId]] = None
  ) : Box[Seq[QueryResult]] = {

    val debugId = if(logger.isDebugEnabled) Helpers.nextNum else 0L

    for {
      inventoryEntries <- processor.internalQueryProcessor(query,select,limitToNodeIds,debugId)
    } yield {
      val inNodes = (for {
        inventoryEntry <- inventoryEntries
        rdn <- inventoryEntry(A_NODE_UUID)
        LDAPNodeInfo(nodeEntry, nodeInv, machineInv) <- nodeInfoService.getLDAPNodeInfo(NodeId(rdn))
      } yield {
        QueryResult(nodeEntry,nodeInv, machineInv)
      })

      if(logger.isDebugEnabled) {
        val filtered = inventoryEntries.map( _(A_NODE_UUID).get ).toSet -- inNodes.flatMap { case QueryResult(e, _, _) => e(A_NODE_UUID) }.toSet
        if(!filtered.isEmpty) {
            logger.debug("[%s] [post-filter:rudderNode] %s results (%s not in ou=Nodes,cn=rudder-configuration)".format(debugId, inNodes.size, filtered.mkString(", ")))
        }
      }

      //filter out policy server if necessary

      query.returnType match {
        case NodeReturnType =>
          val withoutPolicyServer = inNodes.filterNot { case QueryResult(e, _, _) => e.isA(OC_POLICY_SERVER_NODE)}
          if(logger.isDebugEnabled) {
            val filtered = (inNodes.flatMap { case QueryResult(e, _, _) => e(A_NODE_UUID) }).toSet -- withoutPolicyServer.flatMap { case QueryResult(e, _, _) => e(A_NODE_UUID) }
            if(!filtered.isEmpty) {
                logger.debug("[%s] [post-filter:policyServer] %s results".format(debugId, withoutPolicyServer.size, filtered.mkString(", ")))
            }
          }
          withoutPolicyServer
        case NodeAndPolicyServerReturnType => inNodes
      }
    }
  }


  override def process(query:Query) : Box[Seq[NodeInfo]] = {
    //only keep the one of the form Full(...)
    queryAndChekNodeId(query, processor.ldapMapper.nodeInfoAttributes, None).map { seq => seq.flatMap {
      case QueryResult(nodeEntry, inventoryEntry,machine) =>
        processor.ldapMapper.convertEntriesToNodeInfos(nodeEntry, inventoryEntry,machine) match {
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
    for {
      entries <- checker.internalQueryProcessor(query, Seq("1.1"), Some(limitToNodeIds))
      ids <- sequence(entries) { entry =>
        checker.ldapMapper.nodeDn2OptNodeId(entry.dn) ?~! "Can not get node ID from dn %s".format(entry.dn)
      }
    } yield {
      ids
    }
  }
}


/**
 * Generic interface for LDAP query processor.
 * Must be implemented differently depending of
 * the InventoryDit (not the same behaviour for
 * accepted nodes and pending nodes)
 */
class InternalLDAPQueryProcessor(
  val ldap:LDAPConnectionProvider[RoLDAPConnection],
  val dit:InventoryDit,
  val ditQueryData:DitQueryData,
  val ldapMapper:LDAPEntityMapper, //for LDAP attribute for nodes
  val limits:RequestLimits = DefaultRequestLimits
) extends Loggable {

  import ditQueryData._

  /**
   *
   * The high level query processor, with all the
   * relevant logics.
   * Sub classes should call that method to
   * implement process&check method
   *
   * TODO: there is a lot of room to be smarter here.
   */
  def internalQueryProcessor(
      query:Query,
      select:Seq[String] = Seq(),
      limitToNodeIds:Option[Seq[NodeId]] = None, //
      debugId: Long = 0L
  ) : Box[Seq[LDAPEntry]] = {

    //normalize the query: remove duplicates, order elements (last one server)
    val normalizedQuery = normalize(query) match {
      case Full(q) => q
      case f:Failure => return f
      case Empty => return Failure("Can not normalize query")
    }

    //log start query
    logger.debug("[%s] Start search for %s".format(debugId, query.toString))


    //TODO : with AND and empty set, we could return early

    /*
     * We have an LDAPQuery:
     * - a composition type (and/or)
     * - a target object type to return with its own filters (not combined)
     * - criteria as a map of (dn type -->  map(object type name --> filters ))
     *   (for each dnType, we have all the attribute that should return that dnType and their own filter not composed)
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
    val ldapObjectTypeSets = { createLDAPObjects(normalizedQuery, debugId) match {
      case Full(x) => x
      case e:EmptyBox => return e
    } }


    //then, actually execute queries
    val dnMapMapSets : Map[DnType, Map[String,Set[DN]]] =
      ldapObjectTypeSets map { case(dnType,mapLot) =>
        (dnType, (mapLot map { case (ot,lot) =>
        //for each set of filter, execute the query
          val dns = getDnsForObjectType(lot, normalizedQuery.composition, debugId) match {
            case eb:EmptyBox =>
              logger.debug("[%s] `-> stop query due to error: %s".format(debugId,eb))
              return eb
            case Full(s) => s
          }
          (ot, dns)
        })
        )
      }

    //now, groups resulting set of same DnType according to composition
    val dnMapSets : Map[DnType, Set[DN]] =
      dnMapMapSets map { case (dnType, dnMapSet) =>
        val dnSet:Set[DN] = normalizedQuery.composition match {
          case Or  => (Set[DN]() /: dnMapSet)( _ union _._2 )
          case And =>
            val s = if(dnMapSet.isEmpty) Set[DN]() else (dnMapSet.head._2 /: dnMapSet)( _ intersect _._2 )
            if(s.isEmpty) {
              logger.debug("[%s] `-> early stop query (empty sub-query)".format(debugId))
              return Full(Seq()) //there is no need to go farther, since it will lead to anding with empty set
            }
            else s
        }
        (dnType,dnSet)
      }

    if(logger.isTraceEnabled) {

      dnMapSets.foreach { case (dnType, dns) =>
        logger.trace("/// %s ==> %s".format(dnType, dns.map( _.getRDN).mkString(", ") ))
      }

    }

    //transform all the DNs we get to filters for the targeted object type
    //here, we are objectType dependent: we use a mapping that is saying
    // "for that objectType, that dnType is transformed into a filter like that"
    val filterSeqSet : Seq[Set[Filter]] =
      (dnMapSets map { case (dnType, dnMapSet) =>
        dnMapSet map { dn => nodeJoinFilters(dnType)(dn) }
      }).toSeq


    //now, build last filter depending on comparator :
    //or : just OR everything
    //and: and in Set, or between them so that: Set(a,b), Set(c,d) => OR( (a and c), (a and d), (b and c), (b and d) )
    //     or simpler: AND( (a or b), (c or d) )
    val (finalLdapFilter, finalSpecialFilters) = {
      unspecialiseFilters(normalizedQuery.nodeFilters.getOrElse(Set[ExtendedFilter]())) match {
        case e:EmptyBox =>
          val error = e ?~! "Error when processing final objet filters"
          logger.debug("[%s] `-> stop query due to error: %s".format(debugId,error))
          return error

        case Full((ldapFilters, specialFilters)) =>
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



    //final query, add "match only server id" filter if needed
    val rt = nodeObjectTypes.copy(filter = finalLdapFilter)

    logger.debug("[%s] |- (final query) %s".format(debugId, rt))

    val res = for {
      con      <- ldap
      results  <- executeQuery(rt.baseDn.toString, rt.scope, nodeObjectTypes.objectFilter, rt.filter, finalSpecialFilters, select.toSet, normalizedQuery.composition, debugId)
    } yield {
      postFilterNode(results.groupBy( _.dn ).map( _._2.head ).toSeq, query.returnType, limitToNodeIds)
    }

    if(logger.isDebugEnabled) {
      res match {
        case eb:EmptyBox => logger.debug("[%s] `-> error: %s".format(debugId, eb))
        case Full(seq) => logger.debug("[%s] `-> %s results".format(debugId, seq.size))
      }
    }

    res
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
  private[this] def createLDAPObjects(query: LDAPNodeQuery, debugId: Long) : Box[Map[DnType, Map[String,LDAPObjectType]]] = {
      Full(query.objectTypesFilters map { case(dnType,mapOtFilters) =>
        (dnType, (mapOtFilters map { case (ot,setFilters) =>
          val (ldapFilters, specialFilters) = unspecialiseFilters(setFilters) match {
            case Full((ldapFilters, specialFilters)) =>
              val f = ldapFilters.size match {
                case 0 => None
                case 1 => Some(ldapFilters.head)
                case n =>
                  query.composition match {
                    case And => Some(AND(ldapFilters.toSeq:_*))
                    case Or  => Some(OR(ldapFilters.toSeq:_*))
                  }
              }

              query.composition match {
                case And => (f, specialFilters.map( ( And:CriterionComposition , _)) )
                case Or  => (f, specialFilters.map( ( Or :CriterionComposition , _)) )
              }

            case e:EmptyBox =>
              val error = e ?~! "Error when processing filters for object type %s".format(ot)
              logger.debug("[%s] `-> stop query due to error: %s".format(debugId,error))
              return error
          }

          //for each set of filter, build the query
          val f = ldapFilters match {
            case None    => objectTypes(ot).filter
            case Some(x) =>
              objectTypes(ot).filter match {
                case Some(y) => Some(AND(y, x))
                case None => Some(x)
              }
          }

          (ot, objectTypes(ot).copy(
            filter=f,
            join=joinAttributes(ot),
            specialFilters=specialFilters
          ))
        })
        )
      })
    }

  //execute a query with special filter based on the composition
  private[this] def executeQuery(base: DN, scope: SearchScope, objectFilter: LDAPObjectTypeFilter, filter: Option[Filter], specialFilters: Set[SpecialFilter], attributes:Set[String], composition: CriterionComposition, debugId: Long) : Box[Seq[LDAPEntry]] = {

    def buildSearchRequest(addedSpecialFilters:Set[SpecialFilter]) : Box[SearchRequest] = {

      //special filter can modify the filter and the attributes to get
      val params = ( (filter,attributes) /: addedSpecialFilters) {
            case ( (f, currentAttributes), r:RegexFilter) =>
              val filterToApply = composition match {
                case Or => Some(ALL)
                case And => f.orElse(Some(ALL))             }

              (filterToApply, currentAttributes ++ getAdditionnalAttributes(Set(r)))

            case ( (f, currentAttributes), r:NotRegexFilter) =>
              val filterToApply = composition match {
                case Or => Some(ALL)
                case And => f.orElse(Some(ALL))             }

              (filterToApply, currentAttributes ++ getAdditionnalAttributes(Set(r)))

            case (_, sf) => return Failure("Unknow special filter, can not build a request with it: " + sf)
      }

      val finalFilter = params._1 match {
        case None => return Failure("No filter (neither standard nor special) for request, can not process!")
        case Some(x) => AND(objectFilter.value, x)
      }

       /*
        * Optimization : we limit query time/size. That means that perhaps we won't have all response.
        * That DOES not change the validity of each final answer, just we may don't find ALL valid answer.
        * (in the case of a and, a missing result here can lead to an empty set at the end)
        * TODO : this behaviour should be removable
        */
      Full(new SearchRequest(
           base.toString
         , scope
         , NEVER
         , limits.subRequestSizeLimit
         , limits.subRequestTimeLimit
         , false
         , finalFilter
         , params._2.toSeq:_*
       ))
    }

    def baseQuery(con:RoLDAPConnection, addedSpecialFilters:Set[SpecialFilter]) : Box[Seq[LDAPEntry]] = {
      //special filter can modify the filter and the attributes to get

      for {
        sr      <- buildSearchRequest(addedSpecialFilters)
        _       <- { logger.debug("[%s] |--- %s".format(debugId, sr)) ; Full({}) }
        entries <- Full(con.search(sr))
        _       <- { logger.debug("[%s] |---- %s result(s)".format(debugId, entries.size)) ; Full({}) }
        post    <- postProcessQueryResults(entries, addedSpecialFilters.map( (composition,_) ), debugId)
      } yield {
        post
      }
    }

    def andQuery(con:RoLDAPConnection) : Box[Seq[LDAPEntry]] = {
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
    def orQuery(con:RoLDAPConnection) : Box[Seq[LDAPEntry]] = {
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
                      case None    => Full(Seq())
                    }
                 //now, each filter individually
        _        <- { logger.debug("[%s] |--- or (base filter): %s".format(debugId, entries.size)) ; Full({}) }
        _        <- { logger.trace("[%s] |--- or (base filter): %s".format(debugId, entries.map( _.dn.getRDN  ).mkString(", "))) ; Full({}) }
        specials <- sequence(sf.toSeq){ case (k, filters) => baseQuery(con, filters) }
        sFlat    =  specials.flatten
        _        <- { logger.debug("[%s] |--- or (special filter): %s".format(debugId, sFlat.size)) ; Full({}) }
        _        <- { logger.trace("[%s] |--- or (special filter): %s".format(debugId, sFlat.map( _.dn.getRDN  ).mkString(", "))) ; Full({}) }
      } yield {
        val total = (entries ++ sFlat).distinct
        logger.debug("[%s] |--- or (total): %s".format(debugId, total.size))
        logger.trace("[%s] |--- or (total): %s".format(debugId, total.map( _.dn.getRDN  ).mkString(", ")))
        total
      }
    }

    //actual implementation for buildSearchRequest

    for {
      con         <- ldap
      results     <- composition match {
                       case Or  => orQuery(con)
                       case And => andQuery(con)
                     }
    } yield {
      results
    }
  }

  private[this] def getDnsForObjectType(lot:LDAPObjectType, composition: CriterionComposition, debugId: Long) : Box[Set[DN]] = {
    val LDAPObjectType(base,scope, objectFilter, ldapFilters,joinType,specialFilters) = lot

    //log sub-query with two "-"
    logger.debug("[%s] |-- %s".format(debugId, lot))
    for {
      results <- executeQuery(base, scope, objectFilter, ldapFilters, specialFilters.map( _._2), Set(joinType.selectAttribute), composition, debugId)
    } yield {
      val res = (results flatMap { e:LDAPEntry =>
        joinType match {
          case DNJoin => Some(e.dn)
          case ParentDNJoin => Some(e.dn.getParent)
          case AttributeJoin(attr) => e(attr) map {a:String => new DN(a) }  //that's why Join Attribute must be a DN
        }
      }).toSet
      logger.debug("[%s] |-- %s sub-results (merged)".format(debugId, res.size))
      logger.trace("[%s] |-- ObjectType: %s; ids: %s".format(debugId, lot.baseDn, res.map( _.getRDN).mkString(", ")))
      res
    }
  }


  /**
   * That method allows to transform a list of extended filter to
   * the corresponding list of pure LDAP filter and pure special filter.
   *
   * That method absolutly NOT modify standard filter with added filters
   * from special filter.
   * Special filter are handled in their own place
   */
  private[this] def unspecialiseFilters(filters:Set[ExtendedFilter]) : Box[(Set[Filter], Set[SpecialFilter])] = {
    val start = (Set[Filter](), Set[SpecialFilter]())
    Full((start /: filters) {
      case (  (ldapFilters,specials), LDAPFilter(f) ) => (ldapFilters + f, specials)
      case (  (ldapFilters,specials), r:RegexFilter ) => (ldapFilters, specials + r)
      case (  (ldapFilters,specials), r:NotRegexFilter ) => (ldapFilters, specials + r)
      case (x, f) => return Failure("Can not handle filter type: '%s', abort".format(f))
    })
  }

  /**
   * Special filters may need some attributes to work.
   * That method allows to get them
   */
  private[this] def getAdditionnalAttributes(filters:Set[SpecialFilter]) : Set[String] = {
    filters.flatMap {
      case RegexFilter(attr,v) => Set(attr)
      case NotRegexFilter(attr,v) => Set(attr)
    }
  }

  private[this] def postProcessQueryResults(
      results:Seq[LDAPEntry]
    , specialFilters:Set[(CriterionComposition,SpecialFilter)]
    , debugId: Long
  ) : Box[Seq[LDAPEntry]] = {
    def applyFilter(specialFilter:SpecialFilter, entries:Seq[LDAPEntry]) : Box[Seq[LDAPEntry]] = {
      specialFilter match {
        case RegexFilter(attr,regexText) =>
           for {
            pattern <- tryo { Pattern.compile(regexText) }
          } yield {
            /*
             * We want to match "OK" an entry if any of the values for
             * the given attribute matches the regex.
             */
            entries.filter { entry =>
              val res = entry.valuesFor(attr).exists { value =>
                pattern.matcher( value ).matches
              }
              logger.trace("[%5s] for regex check '%s' on attribute %s of entry: %s:%s".format(res, regexText, attr, entry.dn,entry.valuesFor(attr).mkString(",")))
              res
            }
          }
        case NotRegexFilter(attr,regexText) =>
          for {
            pattern<- tryo { Pattern.compile(regexText) }
          } yield {
            /*
             * We want to match "OK" an entry if the entry does not
             * have the attribute or NONE of the value matches the regex.
             */
            entries.filter { entry =>
              logger.trace("Filtering with regex not matching '%s' entry: %s:%s".format(regexText,entry.dn,entry.valuesFor(attr).mkString(",")))
              val res = entry.valuesFor(attr).forall { value =>
                !pattern.matcher( value ).matches
              }
              logger.trace("Entry matches: " + res)
              res
            }
          }
        case x => Failure("Don't know how to post process query results for filter '%s'".format(x))
      }
    }



    if(specialFilters.isEmpty) {
      Full(results)
    } else {
      val filterSeq = specialFilters.toSeq
      logger.debug("[%s] |---- post-process with filters: %s".format(debugId, filterSeq.mkString("[", "]  [", "]")))

      //we only know how to process homogeneous CriterionComposition. Different one are an error
      for {
        composition <- pipeline(filterSeq, filterSeq.head._1) {
                         case ( (newComp,_) ,  baseComp ) if(newComp == baseComp) => Full(baseComp)
                         case _ => Failure("Composition of special filters are not homogeneous, can not processed them. Special filters: " + specialFilters.toString)
                       }
        results     <- composition match {
                         case And => //each step of filtering is the input of the next => pipeline
                           pipeline(filterSeq,results) { case ((_,filter),currentResults) =>
                             applyFilter(filter, currentResults)
                           }
                         case Or => //each step of the filtering take all entries as input, and at the end, all are merged
                           pipeline(filterSeq,Seq[LDAPEntry]()) { case ((_,filter),currentResults) =>
                             applyFilter(filter, results).map( r => (Set() ++ r ++ currentResults).toSeq)
                           }
                       }
        _           <- { logger.debug("[%s] |---- results (post-process): %s".format(debugId, results.size)) ; Full({})}
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
  private def normalize(query:Query) : Box[LDAPNodeQuery] = {

    //validate that we knows the requested object type
    if(!objectTypes.isDefinedAt(query.returnType.value))
      return Failure("The requested type '%s' is not know".format(query.returnType))

    //validate that for each object type in criteria, we know it
    query.criteria foreach { cl =>
      if(!objectTypes.isDefinedAt(cl.objectType.objectType))
        return Failure("The object type '%s' is not know in criteria %s".format(cl.objectType.objectType,cl))
    }

    //group criteria by objectType, and map value to LDAPObjectType
    val groupedSetFilter: Map[DnType, Map[String,Set[ExtendedFilter]]] = {
      query.criteria.groupBy(c => c.objectType.objectType) map { case (objectType,seq) =>

        //here, we have a special case for "node" and "nodeAndPolicyServer" that
        //are in fact the same "objectType".
        //Question: should it just be a "normal" post-process not at that level,
        //but higher ? Why is it a type ?
        val tpe = if(objectType == "nodeAndPolicyServer") "node" else objectType

        (tpe , (seq map { case CriterionLine(ot,a,comp,value) =>
          (comp match {
            case Regex => a.buildRegex(a.name,value)
            case NotRegex => a.buildNotRegex(a.name,value)
            case _ => LDAPFilter(a.buildFilter(comp,value))
          }) : ExtendedFilter
        }).toSet)
      }
    }.groupBy { case (objectType, v) => objectDnTypes(objectType) }


    val nodeFilters = groupedSetFilter.get(QueryNodeDn).flatMap( _.get("node").map( _.toSet) )
    val otherFilters = groupedSetFilter.flatMap { case(dn, map) =>
      val setFilter = map.flatMap { case (objectType, x) =>
        if(objectType == "node") None
        else Some(objectType -> x)
      }
      if(setFilter.isEmpty) None
      else Some( dn -> setFilter )
    }

    Full(LDAPNodeQuery(nodeFilters, query.composition, otherFilters))
  }

}
