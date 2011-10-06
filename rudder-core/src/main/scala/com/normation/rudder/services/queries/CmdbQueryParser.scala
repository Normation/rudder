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

import com.normation.rudder.domain.queries._
import net.liftweb.common._
import net.liftweb.json._
import net.liftweb.json.JsonDSL._
import JsonParser.ParseException
import com.normation.rudder.domain._
import CmdbQueryParser._

/**
 * This trait is the general interface that
 * transform Query represented as string into
 * our internal Query object. 
 * Most of the time, the query parsing will be in 
 * two times (lexing and parsing)
 * 
 */

//only string version of the query - no domain here
case class StringCriterionLine(objectType:String, attribute:String, comparator:String, value:Option[String]=None)
case class StringQuery(returnType:String,composition:Option[String],criteria:Seq[StringCriterionLine])

object CmdbQueryParser {
  //query attribute
  val TARGET = "select"
  val COMPOSITION = "composition"
  val CRITERIA = "where"
  
  //criterion attribute
  val OBJECT = "objectType"
  val ATTRIBUTE = "attribute"
  val COMPARATOR = "comparator"
  val VALUE = "value"  
}

trait QueryLexer {
  def lex(query:String) : Box[StringQuery]
  //We are awaiting for a Map(String,String) as json object,
  //with keys: objectType, attribute, comparator, value
}

trait StringQueryParser {
  def parse(query:StringQuery) : Box[Query]
}

trait CmdbQueryParser extends StringQueryParser with QueryLexer {
  def apply(query:String) : Box[Query] = for {
    sq <- lex(query)
    q <- parse(sq)
  } yield q
}


/**
 * Some default behaviour:
 * - default composition is AND
 * 
 */
trait DefaultStringQueryParser extends StringQueryParser {
  
  def criterionObjects : Map[String,ObjectCriterion]
  
  override def parse(query:StringQuery) : Box[Query] = {
  
    val comp = query.composition match {
      case None => And
      case Some(s) => CriterionComposition.parse(s).getOrElse(
          return Failure("The requested composition '%s' is not know".format(query.composition))
      )
    }
    
    val lines = ( (Full(List()):Box[List[CriterionLine]]) /: query.criteria.toList ){
      (opt,x) => opt.flatMap(l => parseLine(x).map( _::l ) ) 
    } match {
      case f@Failure(_,_,_) => return f
      case Empty => /* that should not happen, fail */
        return Failure("Parsing criteria yields an empty result, abort")
      case Full(l) => l.toSeq.reverse
    }
    
    Full(Query(query.returnType , comp , lines))
  }
  
  def parseLine(line:StringCriterionLine) : Box[CriterionLine] = {

    val objectType = criterionObjects.getOrElse(line.objectType , 
      return Failure("The object type is unknown in line '%s'".format(line))
    )
    
    val criterion = objectType.criterionForName(line.attribute).getOrElse {
      return Failure("The attribute is unknown for object type '%s' in line '%s'".format(line.objectType, line))
    }
    
    val comparator = criterion.cType.comparatorForString(line.comparator).getOrElse {
      return Failure("The comparator is unknown for attribute '%s' in line '%s'".format(line.attribute,line))
    }
    
    /*
     * Only validate the fact that if the comparator require a value, then a value is provided.
     * Providing an error when none is required is not an error 
     */
    val value = line.value match {
      case Some(x) => x
      case None => 
        if(comparator.hasValue) return Failure("Missing required value for comparator '%s' in line '%s'".format(line.comparator, line)) 
        else ""
    }
    
    Full(CriterionLine(objectType, criterion, comparator, value))
  }
  
}

/**
 * This lexer read and valid syntax of JSON query
 * 
 */
trait JsonQueryLexer extends QueryLexer {
  
  override def lex(query:String) : Box[StringQuery] = for {
    json <- jsonLex(query)
    q <- jsonParse(json)
  } yield q
  
  def jsonLex(s:String) : Box[JValue] = try {
      Full(JsonParser.parse(s))
    } catch {
      case e:ParseException => 
        Failure("Parsing failed when processing query: "+s,Full(e),Empty)
    }

  def jsonParse(json:JValue) : Box[StringQuery] = {
  /*
   * Structure of the Query:
   * var query = { 
   *   'select' : 'server' ,  //what we are looking for at the end (servers, software...)
   *   'composition' : 'and' ,  // or 'or'
   *   'where': [ 
   *     { 'objectType' : '....' , 'attribute': '....' , 'comparator': '.....' , 'value': '....' } ,  //value is optionnal, other are mandatory
   *     { 'objectType' : '....' , 'attribute': '....' , 'comparator': '.....' , 'value': '....' } ,
   *     ...
   *     { 'objectType' : '....' , 'attribute': '....' , 'comparator': '.....' , 'value': '....' } 
   *   ]
   * }
   */

    def failureMissing(s:String) = Failure("Missing expected '%s' query parameter".format(s))
    def failureEmpty(param:String) = Failure("Parameter '%s' must be non empty in query".format(OBJECT))
    def failureBadFormat(obj:String,f:Any) = Failure("Bad query format for '%s' parameter. Expecting a string, found '%s'".format(obj,f))

    json match {
      case q@JObject(attrs) =>
        val values = q.values
        //target returned object
        val target = q.values.get(TARGET) match {
          case None => return failureMissing(TARGET)
          case Some(x:String) => if(x.length > 0) x else return failureEmpty(TARGET)
          case Some(x) => return failureBadFormat(TARGET,x)
        }
      
        //composition type
        val composition = q.values.get(COMPOSITION) match {
          case None => None
          case Some(x:String) => if(x.length > 0) Some(x) else None
          case Some(x) => return failureBadFormat(COMPOSITION,x)
        }
        
        //where close: array of criteria, is optional 
        val criteria = q.values.get(CRITERIA) match {
          case None => List[StringCriterionLine]()
          case Some(arr:List[_]) => 
            // try to parse all lines. On the first parsing error (parseCrtierion returns Failure),
            // stop and return a Failure
            // if all parsing are OK, return a Full(list(criterionLine)
            ( (Full(List[StringCriterionLine]()):Box[List[StringCriterionLine]]) /: arr){
              (opt,x) => opt.flatMap(l=> parseCriterion(x).map( _:: l))
            } match {
              case Full(l) => l.reverse
              case f@Failure(_,_,_) => return f
              case Empty => /* that should not happen, fail */ 
                return Failure("Parsing criteria yields an empty result, abort")
            }
          case Some(x) => return failureBadFormat(COMPOSITION,x)
        }
        Full(StringQuery(target,composition,criteria.toSeq))
      case x => Failure("Failed to parse the query, bad structure. Expected a JSON object, found: '%s'".format(x))
    }
  }

  def parseCriterion(json:Any) : Box[StringCriterionLine] = {
    def failureMissing(param:String,line:Map[String,String]) = Failure("Missing expected '%s' query parameter in criterion '%s'".format(OBJECT,line))
    def failureEmpty(param:String,line:Map[String,String]) = Failure("Parameter '%s' must be non empty in criterion '%s'".format(OBJECT,line))
    def failureBadParam(param:String,line:Map[String,String],x:Any) = 
      Failure("Bad query format for '%s' parameter in line '%s'. Expecting a string, found '%s'".format(OBJECT,line,x))
      
    json match {
      case l:Map[_,_] =>
        l.head match {
          case (x:String,y:String) => 
            val line = l.asInstanceOf[Map[String,String]] //is map always homogenous ?
            //First, parse the line. Then, try to bind name with object
          
            //mandatory object type, attribute, comparator ; optionnal value
            //object type
            val objectType = line.get(OBJECT) match {
              case None => return failureMissing(OBJECT,line)
              case Some(x:String) => if(x.length > 0) x else return failureEmpty(OBJECT,line)
              case Some(x) => return failureBadParam(OBJECT,line,x)
            }
          
            // attribute
            val attribute = line.get(ATTRIBUTE) match {
              case None => return failureMissing(ATTRIBUTE,line)
              case Some(x:String) => if(x.length > 0) x else return failureEmpty(ATTRIBUTE,line)
              case Some(x) => return failureBadParam(ATTRIBUTE,line,x)
            }
    
            // attribute
            val comparator = line.get(COMPARATOR) match {
              case None => return failureMissing(COMPARATOR,line)
              case Some(x:String) => if(x.length > 0) x else return failureEmpty(COMPARATOR,line)
              case Some(x) => return failureBadParam(COMPARATOR,line,x)
            }
    
            // attribute
            val value = line.get(VALUE) match {
              case None => None
              case Some(x:String) => if(x.length > 0) Some(x) else None
              case Some(x) => return failureBadParam(VALUE,line,x)
            }
    
            Full(StringCriterionLine(objectType,attribute,comparator,value))
            
          case _ => Failure("Bad query format for criterion line. Expecting an (string,string), found '%s'".format(l.head))
        }        
      case x => Failure("Bad query format for criterion line. Expecting an object, found '%s'".format(x))
    }
    
  }
}

