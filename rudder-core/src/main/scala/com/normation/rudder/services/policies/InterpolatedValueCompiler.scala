/*
*************************************************************************************
* Copyright 2014 Normation SAS
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

package com.normation.rudder.services.policies

import scala.annotation.migration
import scala.util.parsing.combinator.RegexParsers
import com.normation.cfclerk.domain.Variable
import com.normation.rudder.domain.policies.InterpolationContext
import com.normation.utils.Control._
import net.liftweb.common.{Failure => FailedBox, _}
import com.normation.rudder.domain.parameters.ParameterName
import com.normation.inventory.domain.NodeInventory

/**
 * A parser that handle parameterized value of
 * directive variables.
 *
 * The parameterization is to be taken in the context of
 * a rule (i.e, a directive applied to
 * a target), and in the scope of one node of the target
 * (as if you were processing one node at a time).
 *
 * The general parameterized value are of the form:
 * ${rudder.xxx}
 * were "xxx" is the parameter to lookup.
 *
 * We handle 2 kinds of parameterizations:
 * 1/ ${rudder.param.XXX}
 *    where:
 *    - XXX is a parameter configured in Rudder
 *      (for now global, but support for node-contextualised is implemented)
 *    - XXX is case sensisite
 *    - XXX's value can contains other interpolation
 * 2/ ${rudder.node.ACCESSOR}
 *    where:
 *    - "node" is a keyword ;
 *    - ACCESSOR is an accessor for that node, explained below.
 *    - the value can not contains other interpolation
 *
 * Accessor are keywords which allows to reach value in a context, exactly like
 * properties in object oriented programming.
 *
 * Accessors for parameters
 *    ${rudder.param.ACCESSOR} : replace by the value for the parameter with the name ACCESSOR
 *
 * Accessors for node
 * ------------------
 *   ${rudder.node.id} : internal ID of the node (generally an UUID)
 *   ${rudder.node.hostname} : hostname of the node
 *   ${rudder.node.admin} : login (or username) of the node administrator, or root, or at least
 *                   the login to use to run the agent
 *   ${rudder.node.policyserver.ACCESSOR} : information about the policyserver of the node.
 *                                    ACCESSORs are the same than for ${rudder.node}
 *
 *  We do have all the logistic to give access to any given inventory parameter, but for now,
 *  we still need to decide:
 *  - how we are managing it in a generic way, i.e given any addition to the inventory, having
 *    access to it without modifying that code (xpath like access to information from parameter
 *    structure)
 *  - what are the consequences in the reporting & expected reports, in particular what happen
 *    to a parameter whose value is a list (iteration, list => string, etc)
 */

trait InterpolatedValueCompiler {

  /**
   *
   * Parse a value looking for interpolation variable in it.
   *
   * Return a Box, where Full denotes a successful
   * parsing of all values, and EmptyBox. an error.
   */
  def compile(value: String): Box[InterpolationContext => Box[String]]

}


class InterpolatedValueCompilerImpl extends RegexParsers with InterpolatedValueCompiler {


  /*
   * Number of time we allows to recurse for interpolated variable
   * evaluated to other interpolated variables.
   *
   * It allows to detect cycle by brut force, but may raise false
   * positive too:
   *
   * Ex: two variable calling the other one:
   * a => b => a => b => a STOP
   *
   * Ex: a false positive:
   *
   * a => b => c => d => e => f => ... => "42"
   *
   * This is not a very big limitation, as we are not building
   * a programming language, and users can easily resolve
   * the problem by just making smaller cycle.
   *
   */
  val maxEvaluationDepth = 5



  /*
   * In our parser, whitespace are relevant,
   * we are not parsing a language here.
   */
  override val skipWhitespace = false

  /*
   * Our AST for interpolated variable:
   * A string to look for interpolation is a list of token.
   * A token can be a plain string with no variable, or something
   * to interpolate. For now, we can interpolate two kind of variables:
   * - node information (thanks to a pointed path to the intersting property)
   * - rudder parameters (only globals for now)
   */
  sealed trait Token //could be Either[CharSeq, Interpolation]
  case class CharSeq(s:String) extends Token
  sealed trait Interpolation extends Token
  //everything is expected to be lower case
  case class NodeAccessor(path:List[String]) extends Interpolation
  //everything is expected to be lower case
  case class Param(name:String) extends Interpolation



  /*
   * just call the parser on a value, and in case of successful parsing, interprete
   * the resulting AST (seq of token)
   */
  def compile(value: String): Box[InterpolationContext => Box[String]] = {
    parseAll(all, value) match {
      case NoSuccess(msg, remaining) => FailedBox(s"""Error when parsing value "${value}", error message is: ${msg}""")
      case Success(tokens, remaining) => Full(parseToken(tokens))
    }
  }


  /*
   * The funny part that for each token add the interpretation of the token
   * by composing interpretation function.
   */
  def parseToken(tokens:List[Token]): InterpolationContext => Box[String] = {
    def build(context: InterpolationContext) = {
      val init: Box[String] = Full("")
      ( init /: tokens){
        case (eb:EmptyBox, _ ) => eb
        case (Full(str), token) => analyse(context, token) match {
          case eb:EmptyBox => eb
          case Full(s) => Full(str + s)
        }
      }
    }

    build _
  }

  /*
   * The three following methods analyse token one by one and
   * given the token, build the function to execute to get
   * the final string (that may not succeed at run time, because of
   * unknown parameter, etc)
   */
  def analyse(context: InterpolationContext, token:Token): Box[String] = {
    token match {
      case CharSeq(s) => Full(s)
      case NodeAccessor(path) => checkNodeAccessor(context, path)
      case Param(name) => checkParam(context, ParameterName(name))
    }
  }

  def checkParam(context: InterpolationContext, paramName: ParameterName): Box[String] = {
    context.parameters.get(paramName) match {
      case Some(value) =>
        if(context.depth >= maxEvaluationDepth) {
          FailedBox(s"""Can not evaluted global parameter "${paramName.value}" because it uses an interpolation variable that depends upon """
           + s"""other interpolated variables in a stack more than ${maxEvaluationDepth} in depth. We fear it's a circular dependancy.""")
        } else value(context.copy(depth = context.depth+1))
      case _ => FailedBox(s"Error when trying to interpolate a variable: Rudder parameter not found: '${paramName.value}'")
    }
  }

  def checkNodeAccessor(context: InterpolationContext, path: List[String]): Box[String] = {
    def environmentVariable(node: NodeInventory, envVarName: String): String = {
      node.environmentVariables.find( _.name == envVarName) match {
        case None => ""
        case Some(v) => v.value.getOrElse("")
      }
    }

    val error = FailedBox(s"Unknow interpolated variable $${node.${path.mkString(".")}}" )
    path match {
      case Nil => FailedBox("In node interpolated variable, at least one accessor must be provided")
      case access :: tail => access.toLowerCase :: tail match {
        case "id" :: Nil => Full(context.nodeInfo.id.value)
        case "hostname" :: Nil => Full(context.nodeInfo.hostname)
        case "admin" :: Nil => Full(context.nodeInfo.localAdministratorAccountName)
        case "policyserver" :: tail2 => tail2 match {
          case "id" :: Nil => Full(context.policyServerInfo.id.value)
          case "hostname" :: Nil => Full(context.policyServerInfo.hostname)
          case "admin" :: Nil => Full(context.policyServerInfo.localAdministratorAccountName)
          case _ => error
        }
        case seq => error
      }
    }
  }

  //// parsing language

  //make a case insensitive regex from the parameter
  //modifier: see http://docs.oracle.com/javase/7/docs/api/java/util/regex/Pattern.html
  //i: ignore case
  //u: considere unicode char for ignore case
  //s: match end of line in ".*" pattern
  //m: multi-lines mode
  //exactly quote s - no regex authorized in
  def id(s: String) = ("""(?iu)\Q""" + s + """\E""").r

  def all: Parser[List[Token]] = (rep1(interpol | plainString) | emptyVar)

  //empty string is a special case that must be look appart from plain string.
  //also parse full blank string, because no need to look for more case for them.
  def emptyVar: Parser[List[CharSeq]] = """(?iums)(\s)*""".r ^^ { x => List(CharSeq(x)) }

  def plainString: Parser[CharSeq] = ("""(?iums)((?!\Q${rudder.\E).)+""").r  ^^ { CharSeq(_) }

  //identifier for step in the path or param names
  def propId: Parser[String] = """[\-_a-zA-Z0-9]+""".r

  //an interpolated variable looks like: ${rudder.XXX}, or ${RuDder.xXx}
  def interpol: Parser[Interpolation] = "${" ~> id("rudder") ~> "." ~> (nodeProp | parameter ) <~ "}"

  //a node path looks like: ${rudder.node.HERE.PATH}
  def nodeProp: Parser[Interpolation] = {
    id("node") ~> "." ~> repsep(propId, ".") ^^ { seq => NodeAccessor(seq) }
  }

  //a parameter looks like: ${rudder.PARAM_NAME}
  def parameter: Parser[Interpolation] = {
    id("param") ~> "." ~> propId ^^ { p => Param(p) }
  }


}


