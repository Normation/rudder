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
import net.liftweb.common._
import net.liftweb.http.SHtml
import net.liftweb.http.js._
import net.liftweb.http.SHtml.ElemAttr._
import JsCmds._
import JE._
import net.liftweb.json._
import JsonDSL._
import com.normation.exceptions.TechnicalException
import com.normation.utils.HashcodeCaching
import com.normation.rudder.services.queries.RegexFilter
import net.liftweb.common.EmptyBox

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

trait ComparatorList {
  def comparators : Seq[CriterionComparator]
  def comparatorForString(s:String) : Option[CriterionComparator] = {
    for(comp <- comparators) {
      if(comp.id == s) return Some(comp)
    }
    None
  }
}

object BaseComparators extends ComparatorList {
  override def comparators : Seq[CriterionComparator] = Seq(Exists, NotExists, Equals, NotEquals) :+ Regex
}

object OrderedComparators extends ComparatorList {
  override def comparators : Seq[CriterionComparator] = BaseComparators.comparators ++ Seq(Lesser, LesserEq, Greater, GreaterEq)
}

sealed trait CriterionType  extends ComparatorList {
  /*
   * validate the value and return a normalized one
   * for the field.
   * DO NOT FORGET TO USE attrs ! (especially 'id')
   */
  def toForm(value: String, func: String => Any, attrs: (String, String)*) : Elem = SHtml.text(value,func, attrs:_*)
  def initForm(formId:String) : JsCmd = Str("")
  //destroy form ?
  
  //Base validation, subclass only have to define validateSubCase
  def validate(value:String,compName:String) : Box[String] = comparatorForString(compName) match {
    case Some(c) => c match {
        case Exists | NotExists => Full(value) //ok, just ignored it
        case _ => validateSubCase(value,c)
      }
    case None => Failure("Unrecognized comparator name: " + compName) 
  }
  
  protected def validateSubCase(value:String,comparator:CriterionComparator) : Box[String] 
  
  //transform the given value to its LDAP string value
  def toLDAP(value:String) : Box[String]
  
  def buildRegex(attribute:String,value:String):RegexFilter = RegexFilter(attribute,value)

  //build the ldap filter for given attribute name and comparator
  def buildFilter(attributeName:String,comparator:CriterionComparator,value:String) : Filter = 
    (toLDAP(value),comparator) match {
      case (_,Exists) => HAS(attributeName)
      case (_,NotExists) => NOT(HAS(attributeName))
      case (Full(v),Equals) => EQ(attributeName,v)
      case (Full(v),NotEquals) => NOT(EQ(attributeName,v))
      case (Full(v),Greater) => AND(HAS(attributeName),NOT(LTEQ(attributeName,v)))
      case (Full(v),Lesser) => AND(HAS(attributeName),NOT(GTEQ(attributeName,v)))
      case (Full(v),GreaterEq) => GTEQ(attributeName,v)
      case (Full(v),LesserEq) => LTEQ(attributeName,v)
      case (Full(v),Regex) => HAS(attributeName) //"default, non interpreted regex
      case (f,c) => throw new TechnicalException("Can not build a filter with a non legal value for comparator '%s': %s'".format(c,f))
  }

}

//a comparator type with undefined comparators
case class BareComparator(override val comparators:CriterionComparator*) extends CriterionType with HashcodeCaching {
  override protected def validateSubCase(v:String,comparator:CriterionComparator) = Full(v)
  override def toLDAP(value:String) = Full(value)
}

trait TStringComparator extends CriterionType { 

  override protected def validateSubCase(v:String,comparator:CriterionComparator) = {
    if(null == v || v.length == 0) Failure("Empty string not allowed") else Full(v)
  }
  override def toLDAP(value:String) = Full(value)
  
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
    case _ => HAS(attributeName) //default to Exists
  }
}

case object OrderedStringComparator extends TStringComparator {
  override val comparators = OrderedComparators.comparators
  
  override def buildFilter(attributeName:String,comparator:CriterionComparator,value:String) : Filter = comparator match {
    //for equals and not equals, check value for jocker
    case Equals => escapedFilter(attributeName,value)
    case NotEquals => NOT(escapedFilter(attributeName,value))
    case NotExists => NOT(HAS(attributeName))
    case Greater => AND(HAS(attributeName),NOT(LTEQ(attributeName,value)))
    case Lesser => AND(HAS(attributeName),NOT(GTEQ(attributeName,value)))
    case GreaterEq => GTEQ(attributeName,value)
    case LesserEq => LTEQ(attributeName,value)
    case Regex => HAS(attributeName) //"default, non interpreted regex
    case _ => HAS(attributeName) //default to Exists
  }
}

case object DateComparator extends CriterionType { 
  override val comparators = OrderedComparators.comparators 
  val frenchFmt = DateTimeFormat.forPattern("dd/MM/yyyy").withLocale(Locale.FRANCE)

  override protected def validateSubCase(v:String,comparator:CriterionComparator) = try {
    Full(frenchFmt.parseDateTime(v).toString)
  } catch {
    case e:Exception => 
      Failure("Invalide date: '%s'".format(v), Full(e),Empty)
  }
  //init a jquery datepicker
  override def initForm(formId:String) : JsCmd = OnLoad(JsRaw(
    """var init = $.datepicker.regional['en-GB'];
       init['showOn'] = 'both';
       $('#%s').datepicker(init);""".format(formId,formId)))
      
  override def toLDAP(value:String) = try {
    val date = frenchFmt.parseDateTime(value)
    Full(GeneralizedTime(date).toString)
  } catch {
    case e:Exception => 
      Failure("Invalide date: '%s'".format(value), Full(e),Empty)
  }
}

case object BooleanComparator extends CriterionType { 
  override val comparators = BaseComparators.comparators 
  override protected def validateSubCase(v:String,comparator:CriterionComparator) = v.toLowerCase match {
    case "t" | "f" | "true" | "false" => Full(v)
    case _ => Failure("Bad input: boolean expected, '%s' found".format(v))
  }
  override def toLDAP(v:String) = v.toLowerCase match {
    case "t" | "f" | "true" | "false" => Full(v)
    case _ => Failure("Bad input: boolean expected, '%s' found".format(v))
  }
}

case object LongComparator extends CriterionType { 
  override val comparators = OrderedComparators.comparators 
  override protected def validateSubCase(v:String,comparator:CriterionComparator) =  try {
    Full((v.toLong).toString)
  } catch {
    case e:Exception => Failure("Invalid long : '%s'".format(v))
  }
  override def toLDAP(v:String) = try {
    Full((v.toLong).toString)
  } catch {
    case e:Exception => Failure("Invalid long : '%s'".format(v))
  }
}

case object MemoryComparator extends CriterionType { 
  override val comparators = OrderedComparators.comparators 
  override protected def validateSubCase(v:String,comparator:CriterionComparator) =  try {
    if(MemorySize.parse(v).isDefined) Full(v) 
    else Failure("Invalide memory size : '%s', expecting '300 Mo', '16KB', etc".format(v))
  }
  override def toLDAP(v:String) = MemorySize.parse(v) match {
    case Some(m) => Full(m.toString)
    case None => Failure("Invalide memory size : '%s', expecting '300 Mo', '16KB', etc".format(v))
  }
}

case object OstypeComparator extends CriterionType {
  val osTypes = List("Linux","Windows","Solaris")
  override def comparators = Seq(Equals, NotEquals)
  override protected def validateSubCase(v:String,comparator:CriterionComparator) = {
    if(null == v || v.length == 0) Failure("Empty string not allowed") else Full(v)
  }
  override def toLDAP(value:String) = Full(value)

  override def buildFilter(attributeName:String,comparator:CriterionComparator,value:String) : Filter = {
    val v = value match {
      case "Windows" => OC_WINDOWS_NODE
      case "Linux" => OC_LINUX_NODE
      case "Solaris" => OC_SOLARIS_NODE
      case _ => OC_UNIX_NODE
    }
    comparator match {
      //for equals and not equals, check value for jocker
      case Equals => IS(v)
      case _ => NOT(IS(v))
    }
  }
  

  override def toForm(value: String, func: String => Any, attrs: (String, String)*) : Elem = 
    SHtml.select(
      (osTypes map (e => (e,e))).toSeq, 
      { if(osTypes.contains(value)) Full(value) else Empty}, 
      func,
      attrs:_*
    )
}

case object OsNameComparator extends CriterionType { 
  import net.liftweb.http.S

  val osNames = LinuxType.allKnownTypes ::: WindowsType.allKnownTypes ::: SolarisType.allKnownTypes
  override def comparators = Seq(Equals, NotEquals)
  override protected def validateSubCase(v:String,comparator:CriterionComparator) = {
    if(null == v || v.length == 0) Failure("Empty string not allowed") else Full(v)
  }
  override def toLDAP(value:String) = Full(value)

  override def buildFilter(attributeName:String,comparator:CriterionComparator,value:String) : Filter = {
    val osName = comparator match {
      //for equals and not equals, check value for jocker
      case Equals => EQ(A_OS_NAME, value)
      case _ => NOT(EQ(A_OS_NAME, value))
    }
    AND(EQ(A_OC,OC_NODE),osName)
  }
  
  override def toForm(value: String, func: String => Any, attrs: (String, String)*) : Elem = 
    SHtml.select(
      (osNames map (e => (e.name,S.?("os.name."+e.name)))).toSeq, 
      {osNames.find(x => x.name == value).map( _.name)}, 
      func,
      attrs:_*
    )
}

case object AgentComparator extends CriterionType { 
  import com.normation.inventory.domain.InventoryConstants._
  
  val agentTypes = List(A_NOVA_AGENT,A_COMMUNITY_AGENT)
  
  override def comparators = Seq(Equals, NotEquals)
  override protected def validateSubCase(v:String,comparator:CriterionComparator) = {
    if(null == v || v.length == 0) Failure("Empty string not allowed") else Full(v)
  }
  override def toLDAP(value:String) = Full(value)

  override def buildFilter(attributeName:String,comparator:CriterionComparator,value:String) : Filter = {
    comparator match {
      //for equals and not equals, check value for jocker
      case Equals => EQ(A_AGENTS_NAME,value)
      case _ => NOT(EQ(A_AGENTS_NAME,value))
    }
  }
  
  override def toForm(value: String, func: String => Any, attrs: (String, String)*) : Elem = 
    SHtml.select(
      (agentTypes map (e => (e,e))).toSeq, 
      { if(agentTypes.contains(value)) Full(value) else Empty}, 
      func,
      attrs:_*
    )
}

case object EditorComparator extends CriterionType {
  val editors = List("Microsoft", "RedHat", "Debian", "Adobe", "Macromedia")
  override val comparators = BaseComparators.comparators 
  override protected def validateSubCase(v:String,comparator:CriterionComparator) =  
    if(editors.contains(v)) Full(v) else Failure("Invalide editor : '%s'".format(v))
  override def toForm(value: String, func: String => Any, attrs: (String, String)*) : Elem = 
    SHtml.select(
      (editors map (e => (e,e))).toSeq, 
      { if(editors.contains(value)) Full(value) else Empty}, 
      func,
      attrs:_*
    )
  override def toLDAP(value:String) = Full(value)
}
/*
case object GroupOfDnsComparator extends CriterionType {
  import bootstrap.liftweb.LiftSpringApplicationContext.inject
  import com.normation.inventory.ldap.core._

  lazy val ldap = inject[LDAPConnectionProvider]
  lazy val acceptedNodesDit = inject[Dit]("acceptedNodesDit")
  /*
   * Find group names from the LDAP
   * Groups are denoted by map (name,dispayName)
   */
  def groups:Map[String,String] = ldap map { con =>
    con.searchSub(acceptedNodesDit.GROUPS.dn, EQ(A_OC,OC_GROUP_OF_DNS),A_NAME).map{ e => 
      val name = e(A_NAME).getOrElse("") 
      (name -> toDisplayName(name,e.dn)) 
    }.toMap
  } match {
    case Failure(_,_,_) => Map()
    case Empty => Map()
    case Full(map) => map.filter(x => !( x._1 == ""))
  }
  
  private def toDisplayName(name:String,dn:DN) : String = {
    val base : List[RDN] = acceptedNodesDit.GROUPS.dn
    val parents = (dn.getParent:List[RDN]).filter(x => !base.contains(x)).
      map(rdn => rdn.getAttributeValues()(0))
    (name :: parents).reverse.mkString(" \u2192 ") // a nice ->
  }
  
  override val comparators = Seq(Equals)
  override protected def validateSubCase(v:String,comparator:CriterionComparator) =  
    if(groups.isDefinedAt(v)) Full(v) else Failure("Invalide editor : '%s'".format(v))
  override def toForm(value: String, func: String => Any, attrs: (String, String)*) : Elem = 
    SHtml.select(
      groups.toSeq, 
      { if(groups.isDefinedAt(value)) Full(value) else Empty}, 
      func,
      attrs:_*
    )
    
  override def toLDAP(value:String) = Full(value)
}
*/
case class JsonComparator(key:String,splitter:String = "",numericvalue:Boolean = false) extends TStringComparator {
  override val comparators = BaseComparators.comparators  
  
  def splitJson(attribute:String,value:String) = {
   val (splittedvalue,splittedattribute) =
     if (splitter!="")
       (value.split(splitter),attribute.split('.'))
     else
       (Array(value),Array(attribute))
  if (splittedvalue.size==splittedattribute.size){
    val keyvalue = (splittedattribute.toList,splittedvalue.toList).zipped map( (attribute,value) => (attribute,value))
    Full(keyvalue.map(attval => if (numericvalue) "\"%s\":%s".format(attval._1,attval._2) else
      "\"%s\":\"%s\"".format(attval._1,attval._2)))
    }
  else {
    Failure("not enough argument")
  }
  }

  override def buildRegex(attribute:String,value:String) : RegexFilter = {
  val regexp = ".*%s.*".format(splitJson(attribute,value).getOrElse(Nil).mkString(".*"))
  RegexFilter(key,regexp)
  }

  override def buildFilter(attributeName:String,comparator:CriterionComparator,value:String) : Filter = {
  def JsonQueryfromkeyvalues (attributeName:String,value:String): Filter = {
      splitJson(attributeName,value) match {
      case e:EmptyBox => HAS(key)
      case x => SUB(key,null,x.get.toArray ,null)
      }
    }
  comparator match {
    case Equals    => JsonQueryfromkeyvalues(attributeName,value)
    case NotEquals => NOT(JsonQueryfromkeyvalues(attributeName,value))
    case NotExists => NOT(HAS(key))
    case Regex => HAS(key) //default, non interpreted regex
    case _ => HAS(key) //default to Exists
  }
 }
}


case class Criterion(val name:String, val cType:CriterionType) extends HashcodeCaching {
  require(name != null && name.length > 0, "Criterion name must be defined")
  require(cType != null, "Criterion Type must be defined")
  
  def buildRegex(attribute:String,value:String) = cType.buildRegex(attribute,value)

  def buildFilter(comp:CriterionComparator,value:String) = cType.buildFilter(name,comp,value)
}


case class ObjectCriterion(val objectType:String, val criteria:Seq[Criterion]) extends HashcodeCaching {
  require(objectType.length > 0, "Unique identifier for line must be defined")
  require(criteria.size > 0, "You must at least have one criterion for the line")

  //optionnaly retrieve the criterion from a "string" attribute
  def criterionForName(name:String) : (Option[Criterion]) = {
    for(c <- criteria) {
      if(c.name == name) return Some(c)
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
case object NodeReturnType extends QueryReturnType{
  override val value = "node"
}
case object NodeAndPolicyServerReturnType extends QueryReturnType{
  override val value = "nodeAndPolicyServer"
}

case class Query(
    val returnType:QueryReturnType,  //only "node" for now
    val composition:CriterionComposition,
    val criteria:Seq[CriterionLine] //list of all criteria to be matched by returned values
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

    lazy val toJSONString = compact(render(toJSON))

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
