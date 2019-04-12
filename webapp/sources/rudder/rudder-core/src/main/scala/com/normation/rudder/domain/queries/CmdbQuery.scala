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

package com.normation.rudder.domain.queries

import com.normation.inventory.domain._

import scala.xml._
import com.unboundid.ldap.sdk._
import com.normation.ldap.sdk._
import BuildFilter._
import com.normation.inventory.ldap.core.LDAPConstants._
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import java.util.Locale
import java.util.regex.PatternSyntaxException

import net.liftweb.common._
import net.liftweb.http.SHtml
import net.liftweb.http.js._
import net.liftweb.http.SHtml.ElemAttr._
import JsCmds._
import JE._
import net.liftweb.json._
import JsonDSL._
import com.jayway.jsonpath.JsonPath
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.domain.nodes.NodeProperty
import com.normation.rudder.domain.nodes.NodeState
import com.normation.utils.HashcodeCaching
import com.normation.rudder.services.queries._
import net.liftweb.http.SHtml.SelectableOption
import cats.implicits._

import scalaz.zio._
import scalaz.zio.syntax._
import com.normation.errors._

sealed trait CriterionComparator {
  val id:String
  def hasValue : Boolean = true
}

trait BaseComparator extends CriterionComparator

case object Exists extends BaseComparator {
  override val id = "exists"
  override def hasValue = false
}
case object NotExists extends BaseComparator {
  override val id = "notExists"
  override def hasValue = false
}
case object Equals    extends BaseComparator { override val id = "eq" }
case object NotEquals extends BaseComparator { override val id = "notEq" }

trait OrderedComparator extends BaseComparator
case object Greater   extends OrderedComparator { override val id = "gt"} //strictly greater
case object Lesser    extends OrderedComparator { override val id = "lt"} //strictly lower
case object GreaterEq extends OrderedComparator { override val id = "gteq"} //greater or equals
case object LesserEq  extends OrderedComparator { override val id = "lteq"} //lower or equals

trait SpecialComparator extends BaseComparator
case object Regex extends SpecialComparator { override val id = "regex" }
case object NotRegex extends SpecialComparator { override val id = "notRegex" }

sealed trait KeyValueComparator extends BaseComparator
object KeyValueComparator {
  final case object HasKey     extends KeyValueComparator { override val id = "hasKey"     }
  final case object JsonSelect extends KeyValueComparator { override val id = "jsonSelect" }

  def values = ca.mrvisser.sealerate.values[KeyValueComparator]
}

trait ComparatorList {


  def comparators : Seq[CriterionComparator]
  def comparatorForString(s: String) : Option[CriterionComparator] = {
    val lower = s.toLowerCase
    for(comp <- comparators) {
      if(lower == comp.id.toLowerCase) return Some(comp)
    }
    None
  }
}

object BaseComparators extends ComparatorList {
  override def comparators : Seq[CriterionComparator] = Seq(Exists, NotExists, Equals, NotEquals, Regex, NotRegex)
}

object OrderedComparators extends ComparatorList {
  override def comparators : Seq[CriterionComparator] = BaseComparators.comparators ++ Seq(Lesser, LesserEq, Greater, GreaterEq)
}

sealed trait CriterionType extends ComparatorList {
  /*
   * validate the value and returns a normalized one
   * for the field.
   * DO NOT FORGET TO USE attrs ! (especially 'id')
   */
  def toForm(value: String, func: String => Any, attrs: (String, String)*) : Elem = SHtml.text(value,func, attrs:_*)
  def initForm(formId: String) : JsCmd = Noop
  def destroyForm(formId: String) : JsCmd = {
    OnLoad(JsRaw(
      """$('#%s').datepicker( "destroy" );""".format(formId)
    ) )
  }
  //Base validation, subclass only have to define validateSubCase
  def validate(value:String,compName:String) : PureResult[String] = comparatorForString(compName) match {
    case Some(c) => c match {
        case Exists | NotExists => Right(value) //ok, just ignored it
        case _                  => validateSubCase(value,c)
      }
    case None    => Left(Unconsistancy("Unrecognized comparator name: " + compName))
  }

  protected def validateSubCase(value: String, comparator: CriterionComparator) : PureResult[String]

  protected def validateRegex(value: String): PureResult[String] = {
    try {
      val _ = java.util.regex.Pattern.compile(value) //yes, "_" is not used, side effects are fabulous! KEEP IT
      Right(value)
    } catch {
      case ex: java.util.regex.PatternSyntaxException => Left(Unconsistancy(s"The regular expression '${value}' is not valid. Expected regex syntax is the java one, documented here: http://docs.oracle.com/javase/8/docs/api/java/util/regex/Pattern.html. Exception was: ${ex.getMessage}"))
    }
  }
}


/*
 * There is two kinds of comparators:
 * - the ones working on inventories/LDAP data, which needs to defined how to transform
 *   the criterion into an LDAP filter,
 * - the ones working on Rudder NodeInfo, which are higher level and works on information
 *   provided by NodeInfoService
 */


// a case class that allows to precompute some parts of the NodeInfo matcher which are indep from the
// the node.
trait NodeInfoMatcher {
  def matches(node: NodeInfo): Boolean
}

object NodeInfoMatcher {
  // default builder: it will evaluated each time, sufficiant if all parts of the matcher uses NodeInfo
  def apply(f: NodeInfo => Boolean): NodeInfoMatcher = {
    new NodeInfoMatcher {
      override def matches(node: NodeInfo): Boolean = f(node)
    }
  }
}

/*
 * Below goes all NodeInfo Criterion Type
 */
sealed trait NodeCriterionType extends CriterionType {

  def matches(comparator: CriterionComparator, value: String): NodeInfoMatcher
}


case object NodeStateComparator extends NodeCriterionType {

  //this need to be lazy, else access to "S." at boot will lead to NPE.
  lazy val nodeStates = NodeState.labeledPairs.map{ case (x, label) => (x.name, label) }

  override def comparators = Seq(Equals, NotEquals)
  override protected def validateSubCase(v: String, comparator:CriterionComparator): PureResult[String] = {
    if (null == v || v.length == 0) Left(Unconsistancy("Empty string not allowed")) else Right(v)
  }

  override def matches(comparator: CriterionComparator, value: String): NodeInfoMatcher = {
    comparator match {
      case Equals => NodeInfoMatcher( (node: NodeInfo) => node.state.name == value )
      case _      => NodeInfoMatcher( (node: NodeInfo) => node.state.name != value )
    }
  }

  override def toForm(value: String, func: String => Any, attrs: (String, String)*) : Elem =
    SHtml.select(
        nodeStates
      , Box(nodeStates.find( _._1 == value).map(_._1))
      , func
      , attrs:_*
    )
}



/*
 * This comparator is used for "node properties"-like attribute, i.e:
 * - the properties has a name and a value;
 * - the name is a simple quoted string;
 * - the value is either a quoted string or a valid JSON values (we
 *   always minify json, but forcing our user to rely on that is
 *   *extremelly* brittle and we will need a real json parsing in place of
 *   that as soon as we will propose matching sub "k":"v" in "value")
 *
 *
 * The serialisation is done as follow:
 *   {("provider":"someone",)?"name":"k","value":VALUE}
 * With VALUE either a quoted string or a json:
 *   {"name":"k","value":"v"}
 *   {"name":"k","value":{ "any":"json","here":"here"}}
 *
 */
case class SplittedValue(key   : String, values: List[String]) {
  def value = values.mkString("=")
}

case class NodePropertyComparator(ldapAttr: String) extends NodeCriterionType {
  override val comparators = KeyValueComparator.values.toList ++ BaseComparators.comparators

  // split k=v (v may not exists if there is no '='
  // is there is several '=', we consider they are part of the value
  def splitInput(value: String, sep: String): SplittedValue = {
    val array = value.split(sep)
    val k = array(0) //always exists with split
    val v = array.toList.tail
    SplittedValue(k, v)
  }

  override def validateSubCase(value: String, comparator: CriterionComparator): PureResult[String] = {
    comparator match {
      case Equals | NotEquals =>
        if(value.contains("=")) {
          Right(value)
        } else {
          Left(Unconsistancy(s"When looking for 'key=value', the '=' is mandatory. The left part is a key name, and the right part is the string to look for."))
        }
      case KeyValueComparator.JsonSelect =>
        val x = value.split(":")
        if(x.size >= 1) { // remaining '=' will be considered part of the value
          Right(value)
        } else {
          Left(Unconsistancy(s"When looking for 'key=json path expression', we found zero ':', but at least one is mandatory. The left "+
                  "part is a key name, and the right part is the JSON path expression (see https://github.com/json-path/JsonPath). For example: datacenter:world.europe.[?(@.city=='Paris')]"))
        }
      case Regex | NotRegex   => validateRegex(value)
      case _                  => Right(value)
    }
  }


  def matchJsonPath(key: String, path: PureResult[JsonPath])(p: NodeProperty): Boolean = {
    (p.name == key) && path.flatMap(JsonSelect.exists(_, p.renderValue).toPureResult).getOrElse(false)
  }

  val regexMatcher = (value: String) => new NodeInfoMatcher {
                            val predicat = (p: NodeProperty) => try {
                                value.r.pattern.matcher(s"${p.name}=${p.renderValue}").matches()
                              } catch { //malformed patterned should not be saved, but never let an exception be silent
                                case ex: PatternSyntaxException => false
                              }
                            override def matches(node: NodeInfo): Boolean = node.properties.exists(predicat)
                          }

  override def matches(comparator: CriterionComparator, value: String): NodeInfoMatcher = {
    import com.normation.rudder.domain.queries.{KeyValueComparator => KVC}

    comparator match {
      // equals mean: the key is equals to kv._1 and the value is defined and the value is equals to kv._2.get
      case Equals         => {
                               val kv = splitInput(value, "=")
                               NodeInfoMatcher((node: NodeInfo) => node.properties.find(p => p.name == kv.key && p.renderValue == kv.value).isDefined)
      }
      // not equals mean: the key is not equals to kv._1 or the value is not defined or the value is defined but equals to kv._2.get
      case NotEquals      => NodeInfoMatcher((node: NodeInfo) => !matches(Equals, value).matches(node))
      case Exists         => NodeInfoMatcher((node: NodeInfo) => node.properties.size >  0)
      case NotExists      => NodeInfoMatcher((node: NodeInfo) => node.properties.size <= 0)
      case Regex          => regexMatcher(value)
      case NotRegex       => new NodeInfoMatcher {
                               val regex = regexMatcher(value)
                               override def matches(node: NodeInfo): Boolean = !regex.matches(node)
                             }
      case KVC.HasKey     => NodeInfoMatcher((node: NodeInfo) => node.properties.exists(_.name == value))
      case KVC.JsonSelect => new NodeInfoMatcher {
                               val kv = splitInput(value, ":")
                               val path = JsonSelect.compilePath(kv.value).toPureResult
                               val matcher = matchJsonPath(kv.key, path) _
                               override def matches(node: NodeInfo): Boolean = node.properties.exists(matcher)
                             }
      case _              => matches(Equals, value)
    }
  }
}

/*
 * Below goes all LDAP Criterion Type
 */

sealed trait LDAPCriterionType extends CriterionType {
  //transform the given value to its LDAP string value
  def toLDAP(value:String) : PureResult[String]

  def buildRegex(attribute:String,value:String)   : PureResult[RegexFilter]    = Right(SimpleRegexFilter(attribute,value))
  def buildNotRegex(attribute:String,value:String): PureResult[NotRegexFilter] = Right(SimpleNotRegexFilter(attribute,value))

  //build the ldap filter for given attribute name and comparator
  def buildFilter(attributeName:String,comparator:CriterionComparator,value:String) : Filter = {
      (toLDAP(value),comparator) match {
        case (_,Exists)           => HAS(attributeName)
        case (_,NotExists)        => NOT(HAS(attributeName))
        case (Right(v),Equals)    => EQ(attributeName,v)
        case (Right(v),NotEquals) => NOT(EQ(attributeName,v))
        case (Right(v),Greater)   => AND(HAS(attributeName),NOT(LTEQ(attributeName,v)))
        case (Right(v),Lesser)    => AND(HAS(attributeName),NOT(GTEQ(attributeName,v)))
        case (Right(v),GreaterEq) => GTEQ(attributeName,v)
        case (Right(v),LesserEq)  => LTEQ(attributeName,v)
        case (Right(v),Regex)     => HAS(attributeName) //"default, non interpreted regex
        case (Right(v),NotRegex)  => HAS(attributeName) //"default, non interpreted regex
        case (f,c)                => throw new IllegalArgumentException(s"Can not build a filter with a non legal value for comparator '${c}': ${f}'")
    }
  }
}

//a comparator type with undefined comparators
case class BareComparator(override val comparators: CriterionComparator*) extends LDAPCriterionType with HashcodeCaching {
  override protected def validateSubCase(v:String,comparator:CriterionComparator) = Right(v)
  override def toLDAP(value:String) = Right(value)
}

trait TStringComparator extends LDAPCriterionType {

  override protected def validateSubCase(v: String, comparator: CriterionComparator) = {
    if(null == v || v.length == 0) Left(Unconsistancy("Empty string not allowed")) else {
      comparator match {
        case Regex | NotRegex => validateRegex(v)
        case x                => Right(v)
      }
    }
  }
  override def toLDAP(value:String) = Right(value)

  protected def escapedFilter(attributeName:String,value:String) : Filter = {
    BuildFilter(attributeName + "=" + Filter.encodeValue(value))
  }
}

case object StringComparator extends TStringComparator {
  override val comparators = BaseComparators.comparators

  override def buildFilter(attributeName:String,comparator:CriterionComparator,value:String) : Filter = comparator match {
    //for equals and not equals, check value for jocker
    case Equals => escapedFilter(attributeName,value)
    case NotEquals => NOT(escapedFilter(attributeName,value))
    case NotExists => NOT(HAS(attributeName))
    case Regex => HAS(attributeName) //"default, non interpreted regex
    case NotRegex => HAS(attributeName) //"default, non interpreted regex
    case _ => HAS(attributeName) //default to Exists
  }
}

case object ExactStringComparator extends TStringComparator {
  override val comparators = Equals :: Nil

  override def buildFilter(attributeName:String,comparator:CriterionComparator,value:String) : Filter = comparator match {
    // whatever the comparator it should be treated like Equals
    case _ => escapedFilter(attributeName,value)
  }
}

case object OrderedStringComparator extends TStringComparator {
  override val comparators = OrderedComparators.comparators

  override def buildFilter(attributeName:String,comparator:CriterionComparator,value:String) : Filter = comparator match {
    //for equals and not equals, check value for jocker
    case Equals => escapedFilter(attributeName,value)
    case NotEquals => NOT(escapedFilter(attributeName,value))
    case NotExists => NOT(HAS(attributeName))
    //for Greater/Lesser, the HAS attribute part is meaningful: that won't work without it.
    case Greater => AND(HAS(attributeName),NOT(LTEQ(attributeName,value)))
    case Lesser => AND(HAS(attributeName),NOT(GTEQ(attributeName,value)))
    case GreaterEq => GTEQ(attributeName,value)
    case LesserEq => LTEQ(attributeName,value)
    case Regex => HAS(attributeName) //"default, non interpreted regex
    case NotRegex => HAS(attributeName) //"default, non interpreted regex
    case _ => HAS(attributeName) //default to Exists
  }
}

case object DateComparator extends LDAPCriterionType {
  override val comparators = OrderedComparators.comparators.filterNot( c => c == Regex || c == NotRegex)
  val fmt = "dd/MM/yyyy"
  val frenchFmt = DateTimeFormat.forPattern(fmt).withLocale(Locale.FRANCE)

  override protected def validateSubCase(v:String,comparator:CriterionComparator) = try {
    Right(frenchFmt.parseDateTime(v).toString)
  } catch {
    case e:Exception =>
      Left(Unconsistancy(s"Invalide date: '${v}'. Error was: ${e.getMessage}"))
  }
  //init a jquery datepicker
  override def initForm(formId:String) : JsCmd = OnLoad(JsRaw(
    """var init = $.datepicker.regional['en-GB'];
       init['showOn'] = 'focus';
       $('#%s').datepicker(init);
       """.format(formId)))
  override def destroyForm(formId:String) : JsCmd = OnLoad(JsRaw(
    """$('#%s').datepicker( "destroy" );""".format(formId)))
  override def toLDAP(value:String) = parseDate(value).map( GeneralizedTime( _ ).toString )

  private[this] def parseDate(value: String) : PureResult[DateTime] = try {
    val date = frenchFmt.parseDateTime(value)
    Right(date)
  } catch {
    case e:Exception =>
      Left(Unconsistancy(s"Invalide date: '${value}'. Error was: ${e.getMessage}"))
  }

  /*
   * Date comparison are not trivial, because we don't want to take care of the
   * time, but the time exists.
   */
  override def buildFilter(attributeName:String,comparator:CriterionComparator,value:String) : Filter = {

    val date = parseDate(value).getOrElse(throw new IllegalArgumentException("The date format was not recognized: '%s', expected '%s'".format(value, fmt)))

    val date0000 = GeneralizedTime(date.withTimeAtStartOfDay).toString
    val date2359 = GeneralizedTime(date.withTime(23, 59, 59, 999)).toString

    val eq = AND(GTEQ(attributeName, date0000), LTEQ(attributeName, date2359))

    comparator match {
      //for equals and not equals, check value for jocker
      case Equals => eq
      case NotEquals => NOT(eq)
      case NotExists => NOT(HAS(attributeName))
      case Greater => AND(HAS(attributeName),NOT(LTEQ(attributeName,date2359)))
      case Lesser => AND(HAS(attributeName),NOT(GTEQ(attributeName,date0000)))
      case GreaterEq => GTEQ(attributeName,date0000)
      case LesserEq => LTEQ(attributeName,date2359)
//      case Regex => HAS(attributeName) //"default, non interpreted regex
      case _ => HAS(attributeName) //default to Exists
    }
  }

}

case object BooleanComparator extends LDAPCriterionType {
  override val comparators = BaseComparators.comparators
  override protected def validateSubCase(v:String,comparator:CriterionComparator) = v.toLowerCase match {
    case "t" | "f" | "true" | "false" => Right(v)
    case _ => Left(Unconsistancy(s"Bad input: boolean expected, '${v}' found"))
  }
  override def toLDAP(v:String) = v.toLowerCase match {
    case "t" | "f" | "true" | "false" => Right(v)
    case _ => Left(Unconsistancy(s"Bad input: boolean expected, '${v}' found"))
  }
}

case object LongComparator extends LDAPCriterionType {
  override val comparators = OrderedComparators.comparators
  override protected def validateSubCase(v:String,comparator:CriterionComparator) =  try {
    Right((v.toLong).toString)
  } catch {
    case e:Exception => Left(Unconsistancy(s"Invalid long : '${v}'"))
  }
  override def toLDAP(v:String) = try {
    Right((v.toLong).toString)
  } catch {
    case e:Exception => Left(Unconsistancy(s"Invalid long : '${v}'"))
  }
}

case object MemoryComparator extends LDAPCriterionType {
  override val comparators = OrderedComparators.comparators
  override protected def validateSubCase(v: String, comparator: CriterionComparator) = {
    comparator match {
      case Regex | NotRegex => validateRegex(v)
      case _ =>
        if(MemorySize.parse(v).isDefined) Right(v)
        else Left(Unconsistancy(s"Invalid memory size : '${v}', expecting '300 Mo', '16KB', etc"))
    }
  }

  override def toLDAP(v:String) = MemorySize.parse(v) match {
    case Some(m) => Right(m.toString)
    case None => Left(Unconsistancy(s"Invalid memory size : '${v}', expecting '300 Mo', '16KB', etc"))
  }
}


case object MachineComparator extends LDAPCriterionType {

  val machineTypes = "Virtual" ::  "Physical" :: Nil

  override def comparators = Seq(Equals, NotEquals)
  override protected def validateSubCase(v: String, comparator:CriterionComparator) = {
    if (null == v || v.length == 0) Left(Unconsistancy("Empty string not allowed")) else Right(v)
  }

  override def toLDAP(value: String) = Right(value)

  override def buildFilter(attributeName: String, comparator: CriterionComparator, value: String): Filter = {
    val v = value match {
      // the machine can't belong to another type
      case "Virtual" => OC_VM
      case "Physical" => OC_PM
    }
    comparator match {
      case Equals => IS(v)
      case _ => NOT(IS(v))
    }
  }

  override def toForm(value: String, func: String => Any, attrs: (String, String)*) : Elem =
    SHtml.select(
      (machineTypes map (e => (e,e))).toSeq
      , { if(machineTypes.contains(value)) Full(value) else Empty}
      , func
      , attrs:_*
    )
}

case object OstypeComparator extends LDAPCriterionType {
  val osTypes = List("AIX", "BSD", "Linux", "Solaris", "Windows")
  override def comparators = Seq(Equals, NotEquals)
  override protected def validateSubCase(v:String,comparator:CriterionComparator) = {
    if(null == v || v.length == 0) Left(Unconsistancy("Empty string not allowed")) else Right(v)
  }
  override def toLDAP(value:String) = Right(value)

  override def buildFilter(attributeName:String,comparator:CriterionComparator,value:String) : Filter = {
    val v = value match {
      case "Windows" => OC_WINDOWS_NODE
      case "Linux"   => OC_LINUX_NODE
      case "Solaris" => OC_SOLARIS_NODE
      case "AIX"     => OC_AIX_NODE
      case "BSD"     => OC_BSD_NODE
      case _         => OC_UNIX_NODE
    }
    comparator match {
      //for equals and not equals, check value for jocker
      case Equals => IS(v)
      case _ => NOT(IS(v))
    }
  }

  override def toForm(value: String, func: String => Any, attrs: (String, String)*) : Elem =
    SHtml.select(
        (osTypes map (e => (e,e))).toSeq
      , { if(osTypes.contains(value)) Full(value) else Empty}
      , func
      , attrs:_*
    )
}

case object OsNameComparator extends LDAPCriterionType {
  import net.liftweb.http.S

  val osNames = AixOS ::
                BsdType.allKnownTypes.sortBy { _.name } :::
                LinuxType.allKnownTypes.sortBy { _.name } :::
                (SolarisOS :: Nil) :::
                WindowsType.allKnownTypes

  override def comparators = Seq(Equals, NotEquals)
  override protected def validateSubCase(v:String,comparator:CriterionComparator) = {
    if(null == v || v.length == 0) Left(Unconsistancy("Empty string not allowed")) else Right(v)
  }
  override def toLDAP(value:String) = Right(value)

  override def buildFilter(attributeName:String,comparator:CriterionComparator,value:String) : Filter = {
    val osName = comparator match {
      //for equals and not equals, check value for jocker
      case Equals => EQ(A_OS_NAME, value)
      case _ => NOT(EQ(A_OS_NAME, value))
    }
    AND(EQ(A_OC,OC_NODE),osName)
  }

  private[this] def distribName(x: OsType): String = {
    x match {
      //add linux: for linux
      case _: LinuxType   => "Linux - " + S.?("os.name."+x.name)
      case _: BsdType     => "BSD - " + S.?("os.name."+x.name)
      //nothing special for windows, Aix and Solaris
      case _              => S.?("os.name."+x.name)
    }
  }

  override def toForm(value: String, func: String => Any, attrs: (String, String)*) : Elem =
    SHtml.select(
      osNames.map(e => (e.name,distribName(e))).toSeq,
      {osNames.find(x => x.name == value).map( _.name)},
      func,
      attrs:_*
    )
}

/*
 * Agent comparator is kind of scpecial, because it needs to accomodate to the following cases:
 * - historically, agent names were only "Nova" and "Community" (understood "cfengine", of course)
 * - then, we changed in 4.2 to normalized "cfengine-community" and "cfengine-nova" (plus "dsc")
 *   (but old agent are still "Nova" and "Community"
 * - and we want a subcase "anything cfengine based" (because it is important for generation, for
 *   group "haspolicyserver-*" and rule "inventory-all")
 *
 *   So we do actually need a special agent type "cfengine", and hand craft the buildFilter for it.
 */
case object AgentComparator extends LDAPCriterionType {

  val ANY_CFENGINE = "cfengine"
  val (cfeTypes, cfeAgents) = ((ANY_CFENGINE, "Any CFEngine based agent"),(ANY_CFENGINE, AgentType.CfeCommunity :: AgentType.CfeEnterprise :: Nil))
  val allAgents = AgentType.allValues.toList

  val agentTypes = ( cfeTypes  :: allAgents.map(a => (a.oldShortName, (a.displayName)))).sortBy( _._2 )
  val agentMap   = ( cfeAgents :: allAgents.map(a => (a.oldShortName, a :: Nil))).toMap

  override def comparators = Seq(Equals, NotEquals)
  override protected def validateSubCase(v:String,comparator:CriterionComparator) = {
    if(null == v || v.length == 0) Left(Unconsistancy("Empty string not allowed")) else Right(v)
  }
  override def toLDAP(value:String) = Right(value)

  /*
   * We need compatibility for < 4.2 inventory
   * 4.2+: a json is stored in AGENTS_NAME, so we need to check if it contains the valid agentType attribute
   * 4.1: a json is stored in AGENTS_NAME, but not with the same value than 4.2 (oldshortName instead of id is stored as agentType ...)
   * <4.1: AGENTS_NAME only contains the name of the agent (but a value that is different form the id, oldShortName)
   */
  private[this] def filterAgent(agent: AgentType) =
      SUB(A_AGENTS_NAME, null, Array(s""""agentType":"${agent.id}""""), null) :: // 4.2+
      SUB(A_AGENTS_NAME, null, Array(s""""agentType":"${agent.oldShortName}""""), null) :: // 4.1
      EQ(A_AGENTS_NAME, agent.oldShortName) :: // 3.1 ( < 4.1 in fact)
      Nil

  override def buildFilter(attributeName:String,comparator:CriterionComparator,value:String) : Filter = {

    val filters = for {
      agents <- agentMap.get(value).toList
      agent <- agents
      filter <- filterAgent(agent)
    } yield {
      filter
    }
    comparator match {
      //for equals and not equals, check value for joker
      case Equals =>
        OR(filters:_*)
      case _ => //actually, this is meant to be "not equals"
        NOT(OR(filters:_*))
    }
  }

  override def toForm(value: String, func: String => Any, attrs: (String, String)*) : Elem =
    SHtml.select(
      agentTypes,
      Box(agentTypes.find( _._1 == value )).map( _._1),
      func,
      attrs:_*
    )
}

case object EditorComparator extends LDAPCriterionType {
  val editors = List("Microsoft", "RedHat", "Debian", "Adobe", "Macromedia")
  override val comparators = BaseComparators.comparators
  override protected def validateSubCase(v:String,comparator:CriterionComparator) =
    if(editors.contains(v)) Right(v) else Left(Unconsistancy(s"Invalide editor : '${v}'"))
  override def toForm(value: String, func: String => Any, attrs: (String, String)*) : Elem =
    SHtml.select(
      (editors map (e => (e,e))).toSeq,
      { if(editors.contains(value)) Full(value) else Empty},
      func,
      attrs:_*
    )
  override def toLDAP(value:String) = Right(value)
}

/*
 * This comparator is used to look for a specific key=value in a json value, where key is known
 * before hand. Typically, that comparator is used when you serialized several field in one json,
 * and you need to lookup one of these field's value.
 * Ex:
 * LDAP attritude and value:
 *    my_serialized_data: {"key1": "value1", "key2": "value2" }
 *
 * JsonFixedKeyComparator("my_serialized_data", "key2", false) => will search for value in "value2"
 *
 * Used for "process" attribute
 */
case class JsonFixedKeyComparator(ldapAttr:String, jsonKey: String, quoteValue: Boolean) extends TStringComparator with Loggable {
  override val comparators = BaseComparators.comparators

  def format(attribute:String, value:String) = {
    val v = if (quoteValue) s""""$value"""" else value
    s""""${attribute}":${v}"""
  }
  def regex(attribute: String, value: String) = {
    s".*${format(attribute, value)}.*"
  }
  override def buildRegex(attribute:String,value:String) : PureResult[RegexFilter] = {
    Right(SimpleRegexFilter(ldapAttr,regex(attribute, value)))
  }

  override def buildNotRegex(attribute:String,value:String) : PureResult[NotRegexFilter] = {
    Right(SimpleNotRegexFilter(ldapAttr,regex(attribute, value)))
  }

  override def buildFilter(key: String, comparator:CriterionComparator,value: String) : Filter = {
    import KeyValueComparator.HasKey
    val sub = SUB(ldapAttr, null, Array(format(key, value).getBytes("UTF-8")), null)
    comparator match {
      case Equals    => sub
      case NotEquals => NOT(sub)
      case NotExists => NOT(HAS(ldapAttr))
      case Regex     => HAS(ldapAttr) //default, non interpreted regex
      case NotRegex  => HAS(ldapAttr) //default, non interpreted regex
      case HasKey    => sub
      case _         => HAS(key) //default to Exists
    }
  }
}

/*
 * This JSON comparator is defined for the subcase where a "k=v" business property is
 * serialized using JSON towards:
 *   { "name":"k", "value":"v" }
 *
 *  So the user can specify in the field input with the format: k=v
 *  We then split on '=' on look for each parts.
 *  We have a comparator specific only to check existence of a specific key
 *  (but can actually be replaced by: "k=.*" in regex.)
 *
 *  Used for environmentVariable
 */
case class NameValueComparator(ldapAttr: String) extends TStringComparator with Loggable {
  import KeyValueComparator.HasKey
  override val comparators = HasKey +: BaseComparators.comparators

  // split k=v (v may not exists if there is no '='
  // is there is several '=', we consider they are part of the value
  def splitInput(value: String): (String, Option[String]) = {
    val array = value.split('=')
    val k = array(0) //always exists with split
    val v = array.toList.tail match {
      case Nil => None
      case t   => Some(t.mkString("="))
    }
    (k, v)
  }

  // produce the correct "serialized" JSON to look for
  def formatKV(kv: (String, Option[String])): String = {
    //no englobing {} to allow use in regex
    s""""name":"${kv._1}","value":"${kv._2.getOrElse("")}""""
  }

  //the first arg is "name.value", not interesting here
  override def buildRegex(_x: String, value: String) : PureResult[RegexFilter] = {
    Right(SimpleRegexFilter(ldapAttr,"""\{"""+formatKV(splitInput(value))+"""\}""" ))
  }

  //the first arg is "name.value", not interesting here
  override def buildNotRegex(_x: String, value: String) : PureResult[NotRegexFilter] = {
    Right(SimpleNotRegexFilter(ldapAttr,"""\{"""+formatKV(splitInput(value))+"""\}"""))
  }

  //the first arg is "name.value", not interesting here
  override def buildFilter(_x:String, comparator:CriterionComparator, value:String) : Filter = {
    val kv = splitInput(value)
    val sub = SUB(ldapAttr, ("{"+formatKV(kv)).getBytes("UTF-8"), null, null)
    comparator match {
      case Equals    => sub
      case NotEquals => NOT(sub)
      case NotExists => NOT(HAS(ldapAttr))
      case Regex     => HAS(ldapAttr) //default, non interpreted regex
      case NotRegex  => HAS(ldapAttr) //default, non interpreted regex
      case HasKey    => SUB(ldapAttr, s"""{"name":"${kv._1}"""".getBytes("UTF-8"), null, null)
      case _         => HAS(ldapAttr) //default to Exists
    }
  }
}

/**
 * A comparator that is used to build subgroup.
 * The actual comparison is on the sub-group ID, and we only
 * authorize an "equal" comparison on it.
 *
 * But for the displaying, we present a dropdown with the list
 * of nodes.
 */
final case class SubGroupChoice(id: NodeGroupId, name: String)
class SubGroupComparator(getGroups: () => PureResult[Seq[SubGroupChoice]]) extends TStringComparator with Loggable {
  override val comparators = Equals :: Nil

  override def buildFilter(attributeName:String,comparator:CriterionComparator,value:String) : Filter = comparator match {
    // whatever the comparator it should be treated like Equals
    case _ => escapedFilter(attributeName,value)
  }

  override def toForm(value: String, func: String => Any, attrs: (String, String)*) : Elem = {
    // we need to query for the list of groups here
    val subGroups: Seq[SelectableOption[String]] = {
      (for {
        res <- getGroups()
      } yield {
        val g = res.map { case SubGroupChoice(id, name) => SelectableOption(id.value, name) }
        // if current value is defined but not in the list, add it with a "missing group" label
        if(value != "") {
          g.find( _.value == value ) match {
            case None    => SelectableOption(value, "Missing group") +: g
            case Some(_) => g
          }
        } else {
          g
        }
      }) match {
        case Right(list) => list.sortBy( _.label )
        case Left(error) => //if an error occure, log and display the error in place of the label
          logger.error(s"An error happens when trying to find the list of groups to use in sub-groups: ${error.fullMsg}")
          SelectableOption(value, "Error when looking for available groups") :: Nil
      }
    }

    SHtml.selectObj[String](
        subGroups
      , Box(subGroups.find( _.value == value).map( _.value))
      , func
      , attrs:_*
    )
  }
}


/**
 * Create a new criterion for the given attribute `name`, and `cType` comparator.
 * Optionnaly, you can provide an override to signal that that criterion is not
 * on an inventory (or successlly on an inventory) property but on a RudderNode property.
 * In that case, give the predicat that the node must follows.
 */
case class Criterion(val name:String, val cType: CriterionType, overrideObjectType: Option[String] = None) extends HashcodeCaching {
  require(name != null && name.length > 0, "Criterion name must be defined")
  require(cType != null, "Criterion Type must be defined")
}

case class ObjectCriterion(val objectType:String, val criteria:Seq[Criterion]) extends HashcodeCaching {
  require(objectType.length > 0, "Unique identifier for line must be defined")
  require(criteria.size > 0, "You must at least have one criterion for the line")

  //optionnaly retrieve the criterion from a "string" attribute
  def criterionForName(name:String) : (Option[Criterion]) = {
    val lower = name.toLowerCase
    for(c <- criteria) {
      if(lower == c.name.toLowerCase) return Some(c)
    }
    None
  }

  def criterionComparatorForName(name:String, comparator:String) : (Option[Criterion],Option[CriterionComparator]) = {
    criterionForName(name) match {
      case ab@Some(x) => (ab, x.cType.comparatorForString(comparator))
      case _ => (None,None)
    }
  }
}

case class CriterionLine(objectType:ObjectCriterion, attribute:Criterion, comparator:CriterionComparator, value:String="") extends HashcodeCaching

sealed abstract class CriterionComposition
case object And extends CriterionComposition
case object Or extends CriterionComposition
object CriterionComposition {
    def parse(s:String) : Option[CriterionComposition] = {
    s.toLowerCase match {
      case "and" => Some(And)
      case "or" => Some(Or)
      case _ => None
    }
  }
}

sealed trait QueryReturnType {
  def value : String
}

case object QueryReturnType {
  def apply(value : String) = {
    value match {
      case NodeReturnType.value => Right(NodeReturnType)
      case NodeAndPolicyServerReturnType.value => Right(NodeAndPolicyServerReturnType)
      case _ => Left(Unconsistancy(s"Query return type '${value}' is not valid"))
    }
  }
}
case object NodeReturnType extends QueryReturnType{
  override val value = "node"
}
case object NodeAndPolicyServerReturnType extends QueryReturnType{
  override val value = "nodeAndPolicyServer"
}

case class Query(
    val returnType:QueryReturnType,  //"node" or "node and policy servers"
    val composition:CriterionComposition,
    val criteria: List[CriterionLine] //list of all criteria to be matched by returned values
) {
    override def toString() = "{ returnType:'%s' with '%s' criteria [%s] }".format(returnType, composition,
          criteria.map{x => "%s.%s %s %s".format(x.objectType.objectType, x.attribute.name, x.comparator.id, x.value)}.mkString(" ; "))

     /*
       *  { "select":"...", "composition":"...", "where": [
       *      { "objectType":"...", "attribute":"...", "comparator":"..", "value":"..." }
       *      ...
       *    ]}
       */
     lazy val toJSON =
              ("select" -> returnType.value) ~
              ("composition" -> composition.toString) ~
              ("where" -> criteria.map( c =>
                ("objectType" -> c.objectType.objectType) ~
                ("attribute" -> c.attribute.name) ~
                ("comparator" -> c.comparator.id) ~
                ("value" -> c.value)
              ) )

    lazy val toJSONString = compactRender(toJSON)

    override def equals(other:Any) : Boolean = {
      other match {
        case Query(rt,comp,crit) => //criteria order does not matter
          this.returnType == rt &&
          this.composition == comp &&
          this.criteria.size == crit.size &&
          this.criteria.forall(c1 => crit.exists(c2 => c1 == c2))
          //we don't care if the cardinal of equals criterion is not the same on the two,
          //ie [c1,c2,c1] == [c2,c2,c1] yields true
        case _ => false
      }
    }

    override def hashCode() = returnType.hashCode * 17 + composition.hashCode * 3 + criteria.toSet.hashCode * 7
}
