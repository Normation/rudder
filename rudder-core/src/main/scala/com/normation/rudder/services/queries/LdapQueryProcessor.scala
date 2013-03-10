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
import net.liftweb.util.Helpers





/*
 * We have two type of filters:
 * * pure LDAP filters that can be directly translated to LDAP ones
 * * special filter, that may be almost anything, and at least
 *   need special (pre/post)-processing.
 */
sealed trait ExtendedFilter
//pur LDAP filters
final case class LDAPFilter(f:Filter) extends ExtendedFilter

//special ones
sealed trait SpecialFilter extends ExtendedFilter
final case class RegexFilter(attributeName:String, regex:String) extends SpecialFilter

/*
 * An ServerQuery differ a little from a Query because it's component are sorted in two way :
 * - the server is apart with it's possible filter from criteria;
 * - other criteria are sorted by group of things that share the same dependency path to server,
 *   and the attribute on witch join are made.
 *   The attribute must be on server.
 *   For now, there is 3 groups:
 *   - Software : get their DN ;
 *   - Machine and physical element : get the Machine DN
 *   - Logical Element : get the Server DN
 *
 *   More over, we need a "DN to filter" function for the requested object type
 */
case class LDAPServerQuery(
    returnTypeFilters: Option[Set[ExtendedFilter]],
    composition:CriterionComposition,           //the final composition to apply
    objectTypesFilters: Map[DnType, Map[String,Set[ExtendedFilter]]] //that map MUST not contains returnType
)


case class RequestLimits (
  val subRequestTimeLimit:Int,
  val subRequestSizeLimit:Int,
  val requestTimeLimit:Int,
  val requestSizeLimit:Int
)

object DefaultRequestLimits extends RequestLimits(10,1000,10,1000)


/**
 * Processor that translates Queries into LDAP search operations
 * for accepted nodes (it also checks that the node is registered
 * in the ou=Nodes branch)
 */
class AccepetedNodesLDAPQueryProcessor(
    nodeDit:NodeDit,
    processor:InternalLDAPQueryProcessor
) extends QueryProcessor with Loggable {


  private[this] case class QueryResult(
    nodeEntry:LDAPEntry,
    inventoryEntry:LDAPEntry
  )

  /**
   * only report entries that match query in also in node
   * @param query
   * @param select
   * @param serverUuids
   * @return
   */
  private[this] def queryAndChekNodeId(
      query:Query,
      select:Seq[String] = Seq(),
      serverUuids:Option[Seq[NodeId]] = None
  ) : Box[Seq[QueryResult]] = {

    for {
      inventoryEntries <- processor.internalQueryProcessor(query,select,serverUuids)
    } yield {
      for {
        inventoryEntry <- inventoryEntries
        rdn <- inventoryEntry(A_NODE_UUID)
        con <- processor.ldap
        nodeEntry <- con.get(nodeDit.NODES.NODE.dn(rdn), Seq(SearchRequest.ALL_USER_ATTRIBUTES, A_OBJECT_CREATION_DATE):_*)
      } yield {
        QueryResult(nodeEntry,inventoryEntry)
      }
    }
  }


  override def process(query:Query) : Box[Seq[NodeInfo]] = {
    //only keep the one of the form Full(...)
    queryAndChekNodeId(query, processor.ldapMapper.nodeInfoAttributes, None).map { seq => seq.flatMap {
      case QueryResult(nodeEntry, inventoryEntry) =>
        processor.ldapMapper.convertEntriesToNodeInfos(nodeEntry, inventoryEntry) match {
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


  override def check(query:Query, serverUuids:Seq[NodeId]) : Box[Seq[NodeId]] = {
    for {
      entries <- checker.internalQueryProcessor(query, Seq("1.1"), Some(serverUuids))
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
  val ldap:LDAPConnectionProvider,
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

  /*
   * TODO séparer le processus de query en deux parties:
   * 1/  requete sur le type d'objet retourné par la requete
   *     (donc pour nous, les serveurs)
   *     Ca nous permet d'obtenir une liste de serverDn, softwareDn,
   *     machineDn à tester.
   *     A partir de là, on crée les filtres qui vont bien pour
   *     chaque type, à ANDer en tête de chaque autre requete.
   *     PROBLEME: on peut se retrouver avec tout l'annuaire en "et".
   * 2/  requete des différents autres types, avec les bon filter.
   *
   * Avantage : tester si une machine vérifie une requete revient
   * simplement à ajouter la ligne "uuid=truc" pour les requetes ET.
   *
   * En fait, c'est une sous requete...
   * AND ( OR(uuid = x1, uuid = x2, ..., uuid = xN),
   *    ( Autres parametres )
   */
  def internalQueryProcessor(
      query:Query,
      select:Seq[String] = Seq(),
      serverUuids:Option[Seq[NodeId]] = None
  ) : Box[Seq[LDAPEntry]] = {
    //normalize the query: remove duplicates, order elements (last one server)
    val normalizeQuery = normalize(query) match {
      case Full(q) => q
      case f:Failure => return f
      case Empty => return Failure("Can not normalize query")
    }

    //log start query
    val debugId = if(logger.isDebugEnabled) Helpers.nextNum else 0L
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
     *
     * We have to make separated requests for special filter,
     * and we need to have one by filter. So these one are in separated requests.
     *
     */
    val ldapObjectTypeSets : Map[DnType, Map[String,LDAPObjectType]] =
      normalizeQuery.objectTypesFilters map { case(dnType,mapOtFilters) =>
        (dnType, (mapOtFilters map { case (ot,setFilters) =>
          val (ldapFilters, specialFilters) = unspecialiseFilters(setFilters) match {
            case Full((ldapFilters, specialFilters)) =>
              val f = ldapFilters.size match {
                case 0 => None
                case 1 => Some(ldapFilters.head)
                case n =>
                  normalizeQuery.composition match {
                    case And => Some(AND(ldapFilters.toSeq:_*))
                    case Or  => Some(OR(ldapFilters.toSeq:_*))
                  }
              }

              normalizeQuery.composition match {
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
      }

    //then, actually execute queries
    val dnMapMapSets : Map[DnType, Map[String,Set[DN]]] =
      ldapObjectTypeSets map { case(dnType,mapLot) =>
        (dnType, (mapLot map { case (ot,lot) =>
        //for each set of filter, execute the query
          val dns = getDnsForObjectType(lot, normalizeQuery.composition, debugId) match {
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
        val dnSet:Set[DN] = normalizeQuery.composition match {
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

    //transform all the DNs we get to filters for the targeted object type
    //here, we are objectType dependent: we need a mapping that is saying
    // "for that objectType, that dnType is transformed into a filter like that"
    val filterSeqSet : Seq[Set[Filter]] =
      (dnMapSets map { case (dnType, dnMapSet) =>
        dnMapSet map { dn => serverJoinFilters(dnType)(dn) }
      }).toSeq


    //now, build last filter depending on comparator :
    //or : just OR everything
    //and: and in Set, or between them so that: Set(a,b), Set(c,d) => OR( (a and c), (a and d), (b and c), (b and d) )
    //     or simpler: AND( (a or b), (c or d) )
    val (finalLdapFilter, finalSpecialFilters) = {
      unspecialiseFilters(normalizeQuery.returnTypeFilters.getOrElse(Set[ExtendedFilter]())) match {
        case e:EmptyBox =>
          val error = e ?~! "Error when processing final objet filters"
          logger.debug("[%s] `-> stop query due to error: %s".format(debugId,error))
          return error

        case Full((ldapFilters, specialFilters)) =>
          val finalLdapFilter = normalizeQuery.composition match {
            case Or  =>
              if(ldapFilters.isEmpty) None else Some(OR( ((ldapFilters /: filterSeqSet)( _ union _ )).toSeq:_*))
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
    val rt = objectTypes(query.returnType).copy(
      filter = {
        serverUuids match {
          case None => finalLdapFilter
          case Some(seq) if(seq.size < 1) => finalLdapFilter
          case Some(seq) if(seq.size >= 1) =>
            finalLdapFilter match {
              case None => Some(buildOnlyDnFilters(seq))
              case Some(x) => Some(AND(x , buildOnlyDnFilters(seq)))
            }
        }
      }
    )

    logger.debug("[%s] |- %s".format(debugId, rt))

    val res = for {
      con      <- ldap
      results  <- executeQuery(rt.baseDn.toString, rt.scope, rt.filter, finalSpecialFilters, select.toSet, normalizeQuery.composition, debugId)
    } yield {
      results.groupBy( _.dn ).map( _._2.head ).toSeq
    }

    if(logger.isDebugEnabled) {
      res match {
        case eb:EmptyBox => logger.debug("[%s] `-> error: %s".format(debugId, eb))
        case Full(seq) => logger.debug("[%s] `-> %s results".format(debugId, seq.size))
      }
    }

    res
  }

  //execute a query with special filter based on the composition
  private[this] def executeQuery(base: DN, scope: SearchScope, filter: Option[Filter], specialFilters: Set[SpecialFilter], attributes:Set[String], composition: CriterionComposition, debugId: Long) : Box[Seq[LDAPEntry]] = {

    def buildSearchRequest(addedSpecialFilters:Set[SpecialFilter]) : Box[SearchRequest] = {

      //special filter can modify the filter and the attributes to get
      val params = ( (filter,attributes) /: addedSpecialFilters) {
            case ( (f, currentAttributes), r:RegexFilter) =>
              val filterToApply = composition match {
                case Or => Some(ALL)
                case And => f.orElse(Some(ALL))             }

              (filterToApply, currentAttributes ++ getAdditionnalAttributes(Set(r)))
            case (_, sf) => return Failure("Unknow special filter, can not build a request with it: " + sf)
      }

      val finalFilter = params._1 match {
        case None => return Failure("No filter (neither standard nor special) for request, can not process!")
        case Some(x) => x
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

    def baseQuery(con:LDAPConnection, addedSpecialFilters:Set[SpecialFilter]) : Box[Seq[LDAPEntry]] = {
      //special filter can modify the filter and the attributes to get

      for {
        sr      <- buildSearchRequest(addedSpecialFilters)
        _       <- { logger.debug("[%s] |--- %s".format(debugId, sr)) ; Full({}) }
        entries <- Full(con.search(sr))
        _       <- { logger.debug("[%s] |---- %s result(s)".format(debugId, entries.size)) ; Full({}) }
        post    <- postProcessResults(entries, addedSpecialFilters.map( (composition,_) ), debugId)
      } yield {
        post
      }
    }

    def andQuery(con:LDAPConnection) : Box[Seq[LDAPEntry]] = {
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
    def orQuery(con:LDAPConnection) : Box[Seq[LDAPEntry]] = {
      //optimisation: we can group regex filter to post process them all in one pass

      val sf = specialFilters.groupBy {
        case r:RegexFilter => "regex"
        case _ => "other"
      }

      for {
        entries  <- filter match {
                      case Some(f) => baseQuery(con, Set())
                      //we are in a case of only special filter
                      case None    => Full(Seq())
                    }
                 //now, each filter individually
        _        <- { logger.debug("[%s] |-- or (base filter): %s".format(debugId, entries.size)) ; Full({}) }
        specials <- sequence(sf.toSeq){ case (k, filters) => baseQuery(con, filters) }
        sFlat    =  specials.flatten
        _        <- { logger.debug("[%s] |-- or (special filter): %s".format(debugId, sFlat.size)) ; Full({}) }
      } yield {
        entries ++ sFlat
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
    val LDAPObjectType(base,scope,ldapFilters,joinType,specialFilters) = lot

    //log sub-query with two "-"
    logger.debug("[%s] |-- %s".format(debugId, lot))
    for {
      results <- executeQuery(base, scope, ldapFilters, specialFilters.map( _._2), Set(joinType.selectAttribute), composition, debugId)
    } yield {
      val res = (results flatMap { e:LDAPEntry =>
        joinType match {
          case DNJoin => Some(e.dn)
          case ParentDNJoin => Some(e.dn.getParent)
          case AttributeJoin(attr) => e(attr) map {a:String => new DN(a) }  //that's why Join Attribute must be a DN
        }
      }).toSet
      logger.debug("[%s] |-- %s sub-results".format(debugId, res.size))
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
    }
  }

  private[this] def postProcessResults(
      results:Seq[LDAPEntry]
    , specialFilters:Set[(CriterionComposition,SpecialFilter)]
    , debugId: Long
  ) : Box[Seq[LDAPEntry]] = {
    def applyFilter(specialFilter:SpecialFilter, entries:Seq[LDAPEntry]) : Box[Seq[LDAPEntry]] = {
      specialFilter match {
        case RegexFilter(attr,regexText) =>
          val pattern = Pattern.compile(regexText)
          /*
           * We want to match "OK" an entry if any of the values for
           * the given attribute matches the regex.
           */
          Full(
            entries.filter { entry =>
              logger.trace("Filtering with regex '%s' entry: %s:%s".format(regexText,entry.dn,entry.valuesFor(attr).mkString(",")))
              val res = entry.valuesFor(attr).exists { value =>
                pattern.matcher( value ).matches
              }
              logger.trace("Entry matches: " + res)
              res
            }
          )
        case x => Failure("Don't know how to post process query results for filter '%s'".format(x))
      }
    }



    if(specialFilters.isEmpty) {
      Full(results)
    } else {
      val filterSeq = specialFilters.toSeq
      logger.debug("[%s] |---- post-process with filters: %s".format(debugId, filterSeq))

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

  private def normalize(query:Query) : Box[LDAPServerQuery] = {

    //validate that we knows the requested object type
    if(!objectTypes.isDefinedAt(query.returnType))
      return Failure("The requested type '%s' is not know".format(query.returnType))

    //validate that for each object type in criteria, we know it
    query.criteria foreach { cl =>
      if(!objectTypes.isDefinedAt(cl.objectType.objectType))
        return Failure("The object type '%s' is not know in criteria %s".format(cl.objectType.objectType,cl))
    }

    //group criteria by objectType, and map value to LDAPObjectType
    var groupedSetFilter : Map[String,Set[ExtendedFilter]] =
      query.criteria.groupBy(c => c.objectType.objectType) map { case (key,seq) =>
        (key , (seq map { case CriterionLine(ot,a,comp,value) =>
          (comp match {
            case Regex => RegexFilter(a.name,value)
            case _ => LDAPFilter(a.buildFilter(comp,value))
          }) : ExtendedFilter
        }).toSet)
      }

    val requestedTypeSetFilter = groupedSetFilter.get(query.returnType) match {
      case None => None
      case s@Some(setFilter) => //remove it from further processing
        groupedSetFilter -= query.returnType
        s
    }

    Full(LDAPServerQuery(requestedTypeSetFilter, query.composition, groupedSetFilter.groupBy( kv => objectDnTypes(kv._1))))
  }


  /**
   * A filter on node id, when we are looking for specific one.
   * NOTE: that SHOULD be optimized so that we actually only query
   * on these node if we have the information, and not only post-filter results,
   * because perhaps we did a thousand requests for nothing here.
   */
  private[this] def buildOnlyDnFilters(uuids:Seq[NodeId]) : Filter = {
    OR(uuids.map { uuid => EQ(A_NODE_UUID, uuid.value) }:_*)
  }

}
