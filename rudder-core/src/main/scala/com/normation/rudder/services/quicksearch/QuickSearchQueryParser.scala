/*
*************************************************************************************
* Copyright 2016 Normation SAS
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

package com.normation.rudder.services.quicksearch


import scala.util.parsing.combinator.RegexParsers
import net.liftweb.common.{ Failure => FailedBox, _ }
import com.normation.utils.Control.sequence

/**
 * This file contains an implementation of the query parser
 */

object QSRegexQueryParser extends RegexParsers {

  /*
   *  parse a string like:
   *
   *
   *  """/var/log/rudder/ object:directive key:attr.value"""
   *  """object:directive /var/log/rudder key:attr.value"""
   *  """object:group this is some groupe description I want key:description"""
   *
   *  But we don't want to allows a splitted "lookup value". So filters are
   *  going either at the start or at the end.
   *
   *  Space are not relevant between tokens (meaning that we will have a hard time
   *  have spaces at start or end of lookup value).
   *
   */

  /*
   * just call the parser on a value, and in case of successful parsing, interprete
   * the resulting AST (seq of token)
   */
  def parse(value: String): Box[Query] = {
    if(value.trim.isEmpty()) {
      FailedBox("You can search with an empty or whitespace only query")
    } else {
      parseAll(all, value) match {
        case NoSuccess(msg   , remaining) => FailedBox(s"""Error when parsing query "${value}", error message is: ${msg}""")
        case Success  (parsed, remaining) => interprete(parsed)
      }
    }
  }

  /*
   * The funny part that for each token add the interpretation of the token
   * by composing interpretation function.
   */
  def interprete(parsed: (QueryString, List[Filter])): Box[Query] = {

    parsed match {
      case (EmptyQuery, _) =>
        FailedBox("No query string was found (the query is only composed of whitespaces and filters)")

      case (CharSeq(query), filters) =>
        val objects    = filters.collect { case ObjectFilter   (set) => set }.flatten.toSet
        val attributes = filters.collect { case AttributeFilter(set) => set }.flatten.toSet
        for {
          objs  <- getObjects(objects)
          attrs <- getAttributes(attributes)
        } yield {
          /*
           * we need to add all attributes and objects if
           * sets are empty - the user just didn't provided any filters.
           */
          Query(
              query
            , if( objs.isEmpty) { QSObject.all    } else { objs  }
            , if(attrs.isEmpty) { QSAttribute.all } else { attrs }
          )
        }
    }
  }

  /*
   * In our parser, whitespace are relevant, but only in the query string,
   * not as a separator of different tokens.
   */
  override val skipWhitespace = true

  /*
   * Our AST for interpolated variable:
   * A string to look for interpolation is a list of token.
   * A token can be a plain string with no variable, or something
   * to interpolate. For now, we can interpolate two kind of variables:
   * - node information (thanks to a pointed path to the intersting property)
   * - rudder parameters (only globals for now)
   */
  sealed trait Token

  //the user query string to look-up
  sealed trait      QueryString       extends Token
  final case class  CharSeq(s:String) extends QueryString
  final case object EmptyQuery        extends QueryString

  //filters like object:rule,directive
  sealed trait     Filter                                   extends Token
  final case class ObjectFilter   (objects:    Set[String]) extends Filter
  final case class AttributeFilter(attributes: Set[String]) extends Filter


  //// parsing language

  //// does not work on empty/whitespace only string, forbid them before ////


  /*
   * We want to parse a string that:
   * - starts or/and ends with 0 or more filter
   * - a filter is one of an object or a key filter
   * - have a query string, which is in one piece, and is exactly parsed as it is (unicode, regex, etc)
   *   - in particular, regex must not be interpreted, because they are often use in groups
   */
  type QF = (QueryString, List[Filter])

  /////
  ///// this is the entry point /////
  /////

  private[this] def all            : Parser[QF] = ( nominal | onlyFilters )

  /////
  ///// different structure of queries
  /////

  //degenerated case with only filters, no query string
  private[this] def onlyFilters    : Parser[QF] = ( filter.+ )                     ^^ { case f              => (EmptyQuery, f) }

  //nonimal case: zero of more filter, a query string, zero or more filter
  private[this] def nominal        : Parser[QF] = ( filter.* ~ ( case0 | case1 ) ) ^^ { case ~(f1, (q, f2)) => (check(q), f1 ::: f2) }

  //need the two following rules so that so the parsing is correctly done for filter in the end
  private[this] def case0          : Parser[QF] = ( queryInMiddle ~ filter.+ )     ^^ { case ~(q, f)        => (check(q)  , f   ) }
  private[this] def case1          : Parser[QF] = ( queryAtEnd               )     ^^ { case q              => (check(q)  , Nil ) }

  /////
  ///// simple elements: filters
  /////

  // deal with filters, either objects or attributes
  private[this] def filter         : Parser[Filter] = ( objectFilter | attributeFilter )

  // a filter, with key words
  private[this] def objectFilter   : Parser[Filter] = word("object")    ~> ":" ~> filterKeys ^^ { ObjectFilter    }
  private[this] def attributeFilter: Parser[Filter] = word("attribute") ~> ":" ~> filterKeys ^^ { AttributeFilter }
  // the keys part
  private[this] def filterKeys     : Parser[Set[String]] = filterKey ~ rep("," ~> filterKey) ^^ { case ~(h, t) => (h :: t).toSet }
  private[this] def filterKey      : Parser[String]      = """[\-\._a-zA-Z0-9]+""".r

  /////
  ///// simple elements: query string
  /////

  // we need to case, because regex are bad to look-ahead and see if there is still filter after. .+? necessary to stop at first filter
  private[this] def queryInMiddle  : Parser[QueryString] = """(?iums)(.+?(?=(object:|attribute:)))""".r ^^ { x => CharSeq(x.trim) }
  private[this] def queryAtEnd     : Parser[QueryString] = """(?iums)(.+)""".r                          ^^ { x => CharSeq(x.trim) }


  /////
  ///// utility methods
  /////

  //maches exaclty a name (case insensitive)
  //modifier: see http://docs.oracle.com/javase/7/docs/api/java/util/regex/Pattern.html
  //i: ignore case
  //u: considere unicode char for ignore case
  //s: match end of line in ".*" pattern
  //m: multi-lines mode
  //exactly quote s - no regex authorized in
  private[this] def word(s: String) = ("""(?iu)\Q""" + s + """\E""").r

  /*
   * Check that the query is not empty (else, say it is the EmptyQuery),
   * and trimed it.
   */
  private[this] def check(qs: QueryString) = qs match {
    case EmptyQuery => EmptyQuery
    case CharSeq(s) =>
      val trimed = s.trim
      if(trimed.size <= 0) {
        EmptyQuery
      } else {
        CharSeq(trimed)
      }
  }

  /**
   * Mapping between a string and actual objects.
   * We try to be kind with users: not case sensitive, not plural sensitive
   */
  private[this] val objectNameMapping = {
    QSObject.all.map { obj =>
      val n = obj.name.toLowerCase
      (n -> obj) :: ( n + "s" -> obj) :: Nil
    }.flatten.toMap
  }

  /*
   * For attributes, we want to be a little more lenient than for objects, and have:
   * - several names mapping to the same attribute. Ex: both (id, nodeid) map to NodeId.
   * - a name mapping to several attributes. Ex. description map to (Description, LongDescription, ShortDescription)
   * - for all name, also have the plural
   */
  private[this] val attributeNameMapping = {
    import QSAttribute._
    //set of names by attribute
    val descriptions = Set(Description, LongDescription, ShortDescription).map( _.name ) ++ Set("descriptions")
    val attributeNames = QSAttribute.all.map { a => a match {
      case Name              => (a, Set(Name.name, "name") )
      case Description       => (a, descriptions)
      case ShortDescription  => (a, descriptions)
      case LongDescription   => (a, descriptions)
      case IsEnabled         => (a, Set(IsEnabled.name, "enabled") )
      case NodeId            => (a, Set(NodeId.name, "nodeid") )
      case Fqdn              => (a, Set(Fqdn.name, "hostname") )
      case OsType            => (a, Set(OsType.name, "ostype", "os"))
      case OsName            => (a, Set(OsName.name, "osname", "os") ) //not also full name because osFullname contains osName, not the reverse
      case OsVersion         => (a, Set(OsVersion.name, "osversion", "os") )
      case OsFullName        => (a, Set(OsFullName.name, "osfullname", OsName.name, "osname", "os") )
      case OsKernelVersion   => (a, Set(OsKernelVersion.name, "oskernelversion", "oskernel", "kernel", "version", "os") )
      case OsServicePack     => (a, Set(OsServicePack.name, "osservicepack", "ossp", "sp", "servicepack", "os") )
      case Arch              => (a, Set(Arch.name, "architecture", "arch") )
      case Ram               => (a, Set(Ram.name, "memory") )
      case IpAddresses       => (a, Set(IpAddresses.name, "ip", "ips", "networkips") )
      case PolicyServerId    => (a, Set(PolicyServerId.name, "policyserver") )
      case Properties        => (a, Set(Properties.name, "node.props", "nodeprops", "node.properties", "nodeproperties") )
      case RudderRoles       => (a, Set(RudderRoles.name, "serverrole", "serverroles", "role", "roles") )
      case GroupId           => (a, Set(GroupId.name, "groupid") )
      case IsDynamic         => (a, Set(IsDynamic.name, "isdynamic") )
      case DirectiveId       => (a, Set(DirectiveId.name, "directiveid") )
      case DirectiveVarName  => (a, Set(DirectiveVarName.name, "directivevar", "parameter", "parameters", "variable", "variables") )
      case DirectiveVarValue => (a, Set(DirectiveVarValue.name,"directivevalue", "parameter", "parameters", "variable", "variables") )
      case TechniqueName     => (a, Set(TechniqueName.name, "technique", "techniqueid") )
      case TechniqueVersion  => (a, Set(TechniqueVersion.name, "technique", "techniqueid", "version") )
      case ParameterName     => (a, Set(ParameterName.name, "parametername", "paramname", "name", "parameter", "param") )
      case ParameterValue    => (a, Set(ParameterValue.name, "parametervalue", "paramvalue", "value", "parameter", "param") )
      case RuleId            => (a, Set(RuleId.name, "ruleid") )
      case DirectiveIds      => (a, Set(DirectiveIds.name, "directiveids", "id", "ids") )
      case Targets           => (a, Set(Targets.name, "target", "group", "groups") )
    } }

    //given that mapping, build the map of name -> Set(attribute)
    val byNames: Map[String, Set[(String, QSAttribute)]] = attributeNames.flatMap { case(a, names) => names.map( n => (n.toLowerCase,a) ) }.groupBy(_._1)
    byNames.mapValues( _.map(_._2))
  }

  private[this] def getMapping[T](names: Set[String], map: Map[String, T]): Box[Set[T]] = {
    sequence(names.toSeq) { name =>
      map.get(name.toLowerCase) match {
        case Some(obj) => Full(obj)
        case None      => FailedBox(s"Requested type '${name}' is not known. Please choose among '${map.keys.toSeq.sorted.mkString("', '")}'")
      }
    }.map( _.toSet )
  }

  /**
   * Mapping between a string and actual object.
   * We try to be kind with users: not case sensitive, not plural sensitive
   */
  private[this] def getObjects(names: Set[String]): Box[Set[QSObject]] = {
    getMapping(names, objectNameMapping)
  }

  /**
   * Mapping between a string and actual attributes.
   * We try to be kind with users: not case sensitive, not plural sensitive
   */
  private[this] def getAttributes(names: Set[String]): Box[Set[QSAttribute]] = {
    getMapping(names, attributeNameMapping).map( _.flatten )
  }
}

