package com.normation.rudder.services.policies

import com.normation.rudder.services.nodes.EngineOption

import com.normation.errors._
import zio._
import zio.syntax._

import PropertyParserTokens._

object PropertyParserTokens {

  /*
   * Our AST for interpolated variable:
   * A string to look for interpolation is a list of token.
   * A token can be a plain string with no variable, or something
   * to interpolate. For now, we can interpolate two kind of variables:
   * - node information (thanks to a pointed path to the interesting property)
   * - rudder parameters (only globals for now)
   */
  sealed trait Token extends Any // could be Either[CharSeq, Interpolation]

  // a string that is not part of a interpolated value
  final case class CharSeq(s: String) extends AnyVal with Token {
    def prefix(p: String) = CharSeq(p + s)
  }

  // ${} but not for rudder
  final case class NonRudderVar(s: String) extends AnyVal with Token

  // an interpolation
  sealed trait Interpolation extends Any with Token

  // everything is expected to be lower case
  final case class NodeAccessor(path: List[String])                                                    extends AnyVal with Interpolation
  // everything is expected to be lower case
  final case class Param(path: List[String])                                                           extends AnyVal with Interpolation
  // here, we keep the case as it is given
  final case class Property(path: List[String], opt: Option[PropertyOption])                           extends Interpolation
  final case class RudderEngine(engine: String, method: List[String], opt: Option[List[EngineOption]]) extends Interpolation

  // here, we have node property option
  sealed trait PropertyOption                       extends Any
  final case object InterpreteOnNode                extends PropertyOption
  final case class DefaultValue(value: List[Token]) extends AnyVal with PropertyOption

  def containsVariable(tokens: List[Token]): Boolean = {
    tokens.exists(t => t.isInstanceOf[Interpolation] || t.isInstanceOf[NonRudderVar])
  }

}

object PropertyParser {
  import fastparse._
  import fastparse.NoWhitespace._

  def parse(value: String): PureResult[List[Token]] = {
    (fastparse.parse(value, all(_)): @unchecked) match {
      case Parsed.Success(value, index)    => Right(value)
      case Parsed.Failure(label, i, extra) =>
        Left(
          Unexpected(
            s"""Error when parsing value (without ''): '${value}'. Error message is: ${extra.trace().aggregateMsg}""".stripMargin
          )
        )
    }
  }

  /*
   * Defines what is accepted as a valid property character name.
   */
  final val invalidPropertyChar = Set('"', '$', '{', '}', '[', ']')
  def validPropertyNameChar(c: Char):  Boolean            = {
    !(c.isControl || c.isSpaceChar || c.isWhitespace || invalidPropertyChar.contains(c))
  }
  def validPropertyName(name: String): PureResult[String] = {
    if (name.forall(validPropertyNameChar)) Right(name)
    else {
      Left(
        Inconsistency(
          s"Property name is invalid: it must contains only non space, non control chars and different from: '${invalidPropertyChar
              .mkString("', '")}'"
        )
      )
    }
  }

  def all[A: P]: P[List[Token]] =
    P(Start ~ ((noVariableStart | variable | ("${" ~ noVariableEnd.map(_.prefix("${")))).rep(1) | empty) ~ End).map(_.toList)

  // empty string is a special case that must be look appart from plain string.
  def empty[A: P] = P("").map(_ => CharSeq("") :: Nil)
  def space[A: P] = P(CharsWhile(_.isWhitespace, 0))
  // plain string must not match our identifier, ${rudder.* and ${node.properties.*}
  // here we defined a function to build them
  def noVariableStart[A: P]: P[CharSeq] = P((!"${" ~ AnyChar).rep(1).!).map(CharSeq(_))
  def noVariableEnd[A: P]:   P[CharSeq] = P((!"}" ~ AnyChar).rep(1).!).map(CharSeq(_))

  def variable[A: P] = P("${" ~ space ~ variableType ~ space ~ "}")

  def variableType[A: P] = P(interpolatedVariable | otherVariable)
  def variableId[A: P]: P[String] = P(CharIn("""\-_a-zA-Z0-9""").rep(1).!)
  def propertyId[A: P]: P[String] = P(CharsWhile(validPropertyNameChar).!)

  // other cases of ${}: cfengine variables, etc
  def otherVariable[A: P]: P[NonRudderVar] = P((variableId ~ ".").rep(0) ~ variableId).map {
    case (begin, end) => NonRudderVar((begin :+ end).mkString("."))
  }

  def interpolatedVariable[A: P]: P[Interpolation] = P(rudderVariable | nodeProperty | rudderEngine)
  // identifier for step in the path or param names

  // an interpolated variable looks like: ${rudder.XXX}, or ${RuDder.xXx}
  // after "${rudder." there is no backtracking to an "otherProp" or string possible.
  def rudderVariable[A: P]: P[Interpolation] = P(
    IgnoreCase("rudder") ~ space ~ "." ~ space ~/ (rudderNode | parameters | oldParameter)
  )

  def rudderEngine[A: P]: P[Interpolation] = P(
    (IgnoreCase("data") | IgnoreCase("rudder-data")) ~ space ~ "." ~ space ~/ (rudderEngineFormat)
  )

  // a node path looks like: ${rudder.node.HERE.PATH}
  def rudderNode[A: P]: P[Interpolation] = {
    P(IgnoreCase("node") ~/ space ~ "." ~ space ~/ variableId.rep(sep = space ~ "." ~ space)).map { seq =>
      NodeAccessor(seq.toList)
    }
  }

  // ${data.name[val][val2] | option1 = xxx | option2 = xxx}
  // ${rudder-data.name[val][val2] | option1 = xxx | option2 = xxx}
  def rudderEngineFormat[A: P]: P[Interpolation] = P(propertyId ~/ arrayNames ~/ engineOption.?).map {
    case (name, methods, opt) => RudderEngine(name, methods, opt)
  }

  // a parameter old syntax looks like: ${rudder.param.PARAM_NAME}
  def oldParameter[A: P]: P[Interpolation] = P(IgnoreCase("param") ~ space ~ "." ~/ space ~/ variableId).map { p =>
    Param(p :: Nil)
  }

  // a parameter new syntax looks like: ${rudder.parameters[PARAM_NAME][SUB_NAME]}
  def parameters[A: P]: P[Interpolation] = P(IgnoreCase("parameters") ~/ arrayNames).map(p => Param(p.toList))

  // a node property looks like: ${node.properties[.... Cut after "properties".
  def nodeProperty[A: P]: P[Interpolation] = (IgnoreCase("node") ~ space ~ "." ~ space ~ IgnoreCase("properties") ~/ arrayNames ~/
    nodePropertyOption.?).map { case (path, opt) => Property(path.toList, opt) }

  // parse an array of property names: `[name1][name2]..` (for parameter/node properties)
  def arrayNames[A: P]: P[List[String]] = P((space ~ "[" ~ space ~ propertyId ~ space ~ "]").rep(1)).map(_.toList)

  // here, the number of " must be strictly decreasing - ie. triple quote before
  def nodePropertyOption[A: P]: P[PropertyOption] = P(space ~ "|" ~/ space ~ (onNodeOption | defaultOption))

  def engineOption[A: P]: P[List[EngineOption]] = {
    P((space ~ "|" ~/ space ~ propertyId ~ space ~ "=" ~/ space ~/ propertyId).rep(1)).map { opts =>
      opts.toList.map(o => EngineOption(o._1, o._2))
    }
  }

  def defaultOption[A: P]: P[DefaultValue]          = P(
    IgnoreCase("default") ~/ space ~ "=" ~/ space ~/ (P(string("\"") | string("\"\"\"") | emptyString | variable.map(_ :: Nil)))
  ).map(DefaultValue(_))
  def onNodeOption[A: P]:  P[InterpreteOnNode.type] = P(IgnoreCase("node")).map(_ => InterpreteOnNode)

  def emptyString[A: P]: P[List[Token]] = P("\"\"\"\"\"\"" | "\"\"").map(_ => CharSeq("") :: Nil)

  // string must be simple or triple quoted string

  def string[A: P](quote: String):                P[List[Token]] = P(
    quote ~ (noVariableStartString(quote) | variable | ("${" ~ noVariableEndString(quote)).map(_.prefix("${"))).rep(1) ~ quote
  ).map { case x => x.toList }
  def noVariableStartString[A: P](quote: String): P[CharSeq]     = P((!"${" ~ (!quote ~ AnyChar)).rep(1).!).map(CharSeq(_))
  def noVariableEndString[A: P](quote: String):   P[CharSeq]     = P((!"}" ~ (!quote ~ AnyChar)).rep(1).!).map(CharSeq(_))
}
