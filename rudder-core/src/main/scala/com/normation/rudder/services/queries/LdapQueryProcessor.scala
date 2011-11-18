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
import com.unboundid.ldap.sdk._
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
    processor:InternalLDAPQueryProcessor
) extends QueryProcessor with Loggable {

  
  private[this] case class QueryResult(
    nodeEntry:LDAPEntry,
    inventoryEntry:LDAPEntry
  ) extends HashcodeCaching 

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
    
    /*
     * If a list of server UUID is passed as argument, we
     * want to test ONLY them to see if the match the
     * query. 
     * So we have to prepend to all filters a test
     * AND( OR(uuid1,uuid2,...uuidN) , CalculatedFilter )
     * Of course, it's a little more complex, because
     * the attribute type to test depends on the objectType
     */
    val onlyDnTypes : Option[Map[DnType, Filter]] = serverUuids.map( buildOnlyDnFilters(_) )
    
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
     */
    val ldapObjectTypeSets : Map[DnType, Map[String,LDAPObjectType]] = 
      normalizeQuery.objectTypesFilters map { case(dnType,mapOtFilters) => 
        (dnType, (mapOtFilters map { case (ot,setFilters) => 
          val (ldapFilters, specialFilters) = unspecialiseFilters(setFilters) match {
            case Full((ldapFilters, specialFilters)) => normalizeQuery.composition match {
              case And => 
                val f = if(ldapFilters.size == 1) ldapFilters.head else AND(ldapFilters.toSeq:_*)
                ( f, specialFilters.map( ( And:CriterionComposition , _)) )
              case Or =>  
                val f = if(ldapFilters.size == 1) ldapFilters.head else  OR(ldapFilters.toSeq:_*)
                ( f,  specialFilters.map( ( Or :CriterionComposition, _)) )
            }
            case e:EmptyBox => 
              val error = e ?~! "Error when processing filters for object type %s".format(ot)
              return error
          }
          
          //for each set of filter, execute the query

          (ot, objectTypes(ot).copy(
            filter={
              onlyDnTypes match { 
                case None => AND(objectTypes(ot).filter, ldapFilters)
                case Some(map) => AND(objectTypes(ot).filter, map(dnType),ldapFilters)
              }
            },
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
          val dns = getDnsForObjectType(lot) match {
            case f@Failure(_,_,_) => return f
            case Empty => return Empty
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
            if(s.isEmpty) return Full(Seq()) //there is no need to go farther, since it will lead to anding with empty set
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
          return error
        
        case Full((ldapFilters, specialFilters)) => 
          normalizeQuery.composition match {
            case Or  => 
              val finalLdapFilter = OR( ((ldapFilters /: filterSeqSet)( _ union _ )).toSeq:_*)
              (finalLdapFilter, specialFilters.map( ( Or:CriterionComposition , _)) )
            case And => //if returnFilter is None, just and other filter, else add it. TODO : may it be empty ?
              val seqFilter = ldapFilters.toSeq ++ 
                  filterSeqSet.map(s => if(s.size > 1) OR(s.toSeq:_*) else s.head ) //s should not be empty, since we returned earlier if it was the case
              val finalLdapFilter = seqFilter.size match {
                case x if x < 1 => return Failure("We don't have any filter ?") //that case should not araise
                case 1 => seqFilter.head
                case _ => AND( seqFilter:_* ) 
              }
              (finalLdapFilter, specialFilters.map( ( And:CriterionComposition , _)) )
          }
        }
    }
   
      
    //final query, add "match only server id" filter if needed
    val rt = objectTypes(query.returnType).copy(
      filter = {
        onlyDnTypes match {
          case None => finalLdapFilter
          case Some(map) => AND(map(QueryNodeDn),finalLdapFilter)
        }
      }
    )
    
    val sr = new SearchRequest(rt.baseDn.toString, rt.scope, NEVER, 
      limits.requestSizeLimit, limits.requestTimeLimit, false,
      rt.filter, select ++ getAdditionnalAttributes(finalSpecialFilters.map(_._2)):_*
    ) //return only uuid, cn, ram, swap because we are on server only
    
    logger.debug("Execute search: %s".format(rt))
    
    for {
      con      <- ldap
      results  <- Full(con.search(sr))
      postProc <- postProcessResults(results,finalSpecialFilters)
    } yield {
      postProc
    }
  }
    
  private[this] def getDnsForObjectType(lot:LDAPObjectType) : Box[Set[DN]] = {

    val LDAPObjectType(base,scope,ldapFilters,joinType,specialFilters) = lot
    
    logger.debug("Execute sub-search: %s".format(lot))
    for {
      con         <- ldap
      results     <- Full { /*
                       * Optimization : we limit query time/size. That means that perhaps we won't have all response.
                       * That DOES not change the validity of each final answer, just we may don't find ALL valid answer.
                       * (in the case of a and, a missing result here can lead to an empty set at the end)
                       * TODO : this behaviour should be removable
                       */
                       con.search(new SearchRequest(
                           base.toString
                         , scope
                         , NEVER
                         , limits.subRequestSizeLimit
                         , limits.subRequestTimeLimit
                         , false
                         , ldapFilters
                         , joinType.selectAttribute +: getAdditionnalAttributes(specialFilters.map(_._2)).toSeq:_*
                       ))
                     }
      postResults <- postProcessResults(results,specialFilters)
    } yield {
      logger.debug("Search done")
      (postResults flatMap { e:LDAPEntry => 
        joinType match {
          case DNJoin => Some(e.dn) 
          case ParentDNJoin => Some(e.dn.getParent)
          case AttributeJoin(attr) => e(attr) map {a:String => new DN(a) }  //that's why Join Attribute must be a DN
        }
      }).toSet
    }
  }

  //TODO : in DIT ?
  private[this] def serverDnToFilter(dn:DN) : Filter = EQ(A_NODE_UUID, dn.getRDN.getAttributeValues()(0))
  private[this] def machineDnToFilter(dn:DN) : Filter = EQ(A_MACHINE_UUID, dn.getRDN.getAttributeValues()(0))
  private[this] def softwareDnToFilter(dn:DN) : Filter = EQ(A_SOFTWARE_UUID, dn.getRDN.getAttributeValues()(0))
  //end TODO
  
  /**
   * That method allows to transform a list of extended filter to
   * the corresponding list of pur LDAP filter and pur special filter.
   * 
   * That is particulary intersting for special filters that have to
   * add a "false" corresponding pur LDAP filter
   * (for example, Regex is a pur "ALL" filter + a post processing of results)
   */
  private[this] def unspecialiseFilters(filters:Set[ExtendedFilter]) : Box[(Set[Filter], Set[SpecialFilter])] = {
    val start = (Set[Filter](), Set[SpecialFilter]())
    Full((start /: filters) { 
      case (  (ldapFilters,specials), LDAPFilter(f) ) => (ldapFilters + f, specials)
      case (  (ldapFilters,specials), r:RegexFilter ) => (ldapFilters + ALL, specials + r)
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
  ) : Box[Seq[LDAPEntry]] = {
    pipeline(specialFilters.toSeq,results) { case ((composition,filter),currentResults) =>
      filter match {
        case RegexFilter(attr,regexText) => 
          val pattern = Pattern.compile(regexText) 
          /*
           * We want to match "OK" an entry if any of the values for
           * the given attribute matches the regex. 
           */
          Full( 
            currentResults.filter { entry =>
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
   * For each server id, retrieve the possible serverDn, machineDn, softwareDn
   * to look for. Build an OR filter for each set (or just and EQ if only
   * one element in the set)
   */
  private[this] def buildOnlyDnFilters(uuids:Seq[NodeId]) : Map[DnType,Filter] = {
    import collection.mutable.Buffer
    val onlyDnMap = Map(
        QueryNodeDn -> Buffer[DN](),
        QueryMachineDn -> Buffer[DN](),
        QuerySoftwareDn -> Buffer[DN]()
    )
    //execute LDAP requests
    ldap foreach { con =>
      uuids foreach { id =>
        con.get(dit.NODES.NODE.dn(id), A_CONTAINER_DN, A_SOFTWARE_DN) foreach { e =>
          onlyDnMap(QueryNodeDn) += e.dn
          onlyDnMap(QueryMachineDn) ++= e.valuesFor(A_CONTAINER_DN).map(new DN(_))
          onlyDnMap(QuerySoftwareDn)   ++= e.valuesFor(A_SOFTWARE_DN).map(new DN(_))
        }
      }
    }
    onlyDnMap map { case(k,v) =>
      if(v.isEmpty) { //build an impossible condition so that when ANDed, yield empty
        (k,NOT(EQ(A_OC,"top")))
      } else {
        val filters = v map { dn => k match {
          case QueryNodeDn => serverDnToFilter(dn)
          case QueryMachineDn => machineDnToFilter(dn)
          case QuerySoftwareDn => softwareDnToFilter(dn)
        } }
        if(filters.size == 1) (k, filters.head)
        else (k, OR(filters:_*))
      }
    }
  }
  
}
