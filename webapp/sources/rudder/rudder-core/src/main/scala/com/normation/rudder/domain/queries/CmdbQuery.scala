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

import cats.data.NonEmptyList
import cats.implicits.*
import com.jayway.jsonpath.JsonPath
import com.normation.errors.*
import com.normation.inventory.domain.*
import com.normation.inventory.ldap.core.LDAPConstants.*
import com.normation.ldap.sdk.*
import com.normation.ldap.sdk.BuildFilter.*
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.domain.properties.NodeProperty
import com.normation.rudder.services.queries.*
import com.normation.rudder.services.servers.InstanceId
import com.normation.utils.DateFormaterService
import com.unboundid.ldap.sdk.*
import enumeratum.Enum
import enumeratum.EnumEntry
import io.scalaland.chimney.*
import io.scalaland.chimney.syntax.*
import java.util.regex.PatternSyntaxException
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.joda.time.format.DateTimeFormat
import org.joda.time.format.DateTimeFormatter
import zio.json.*

sealed trait CriterionComparator {
  val id: String
  def hasValue: Boolean = true
}

sealed trait BaseComparator extends CriterionComparator

case object Exists    extends BaseComparator {
  override val id       = "exists"
  override def hasValue = false
}
case object NotExists extends BaseComparator {
  override val id       = "notExists"
  override def hasValue = false
}
case object Equals    extends BaseComparator { override val id = "eq"    }
case object NotEquals extends BaseComparator { override val id = "notEq" }

sealed trait OrderedComparator extends BaseComparator
case object Greater            extends OrderedComparator { override val id = "gt"   } //strictly greater
case object Lesser             extends OrderedComparator { override val id = "lt"   } //strictly lower
case object GreaterEq          extends OrderedComparator { override val id = "gteq" } //greater or equals
case object LesserEq           extends OrderedComparator { override val id = "lteq" } //lower or equals

sealed trait SpecialComparator extends BaseComparator
case object Regex              extends SpecialComparator { override val id = "regex"    }
case object NotRegex           extends SpecialComparator { override val id = "notRegex" }

sealed trait KeyValueComparator extends EnumEntry with BaseComparator

object KeyValueComparator extends Enum[KeyValueComparator] {
  case object HasKey     extends KeyValueComparator { override val id = "hasKey"     }
  case object JsonSelect extends KeyValueComparator { override val id = "jsonSelect" }

  val values: IndexedSeq[KeyValueComparator] = findValues
}

object NodePropertyMatcherUtils {

  // split k=v (v may not exists if there is no '='
  // is there is several '=', we consider they are part of the value
  def splitInput(value: String, sep: String): SplittedValue = {
    val array = value.split(sep)
    val k     = array(0) // always exists with split
    val v     = array.toList.tail
    SplittedValue(k, v, sep)
  }

  def matchJsonPath(key: String, path: PureResult[JsonPath])(p: NodeProperty): Boolean = {
    (p.name == key) && path.flatMap(JsonSelect.exists(_, p.valueAsString).toPureResult).getOrElse(false)
  }

  val regexMatcher: String => NodeInfoMatcher = (value: String) => {
    new NodeInfoMatcher {
      override val debugString = s"Prop matches '${value}'"

      override def matches(node: NodeInfo): Boolean = matchesRegex(value, node.properties)
    }
  }

  def matchesRegex(value: String, properties: Iterable[NodeProperty]): Boolean = {
    val predicat = (p: NodeProperty) => {
      try {
        value.r.pattern.matcher(s"${p.name}=${p.valueAsString}").matches()
      } catch { // malformed patterned should not be saved, but never let an exception be silent
        case ex: PatternSyntaxException => false
      }
    }
    properties.exists(predicat)
  }

}

sealed trait ComparatorList {

  def comparators:                    Seq[CriterionComparator]
  def comparatorForString(s: String): Option[CriterionComparator] =
    comparators.find(comp => s.equalsIgnoreCase(comp.id))
}

object BaseComparators extends ComparatorList {
  override def comparators: Seq[CriterionComparator] = Seq(Exists, NotExists, Equals, NotEquals, Regex, NotRegex)
}

object OrderedComparators extends ComparatorList {
  override def comparators: Seq[CriterionComparator] = BaseComparators.comparators ++ Seq(Lesser, LesserEq, Greater, GreaterEq)
}

sealed trait CriterionType extends ComparatorList {

  // Base validation, subclass only have to define validateSubCase
  def validate(value: String, compName: String): PureResult[String] = comparatorForString(compName) match {
    case Some(c) =>
      c match {
        case Exists | NotExists => Right(value) // ok, just ignored it
        case _                  => validateSubCase(value, c)
      }
    case None    => Left(Inconsistency("Unrecognized comparator name: " + compName))
  }

  protected def validateSubCase(value: String, comparator: CriterionComparator): PureResult[String]

  protected def validateRegex(value: String): PureResult[String] = {
    try {
      val _ = java.util.regex.Pattern.compile(value) // yes, "_" is not used, side effects are fabulous! KEEP IT
      Right(value)
    } catch {
      case ex: java.util.regex.PatternSyntaxException =>
        Left(
          Inconsistency(
            s"The regular expression '${value}' is not valid. Expected regex syntax is the java one, documented here: http://docs.oracle.com/javase/8/docs/api/java/util/regex/Pattern.html. Exception was: ${ex.getMessage}"
          )
        )
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
  def debugString: String
  def matches(node: NodeInfo): Boolean
}

object NodeInfoMatcher {
  // default builder: it will evaluated each time, sufficient if all parts of the matcher uses NodeInfo
  def apply(s: String, f: NodeInfo => Boolean): NodeInfoMatcher = {
    new NodeInfoMatcher {
      override val debugString: String = s
      override def matches(node: NodeInfo): Boolean = f(node)
    }
  }
}

/************************* old matcher logic **********************************/
// we will need to only keep the rendering part //

/*
 * Below goes all NodeInfo Criterion Type
 */
sealed trait NodeCriterionType extends CriterionType {

  def matches(comparator: CriterionComparator, value: String): NodeInfoMatcher
}

case object NodeStateComparator extends NodeCriterionType {

  override def comparators: Seq[BaseComparator] = Seq(Equals, NotEquals)

  override protected def validateSubCase(v: String, comparator: CriterionComparator): PureResult[String] = {
    if (null == v || v.isEmpty) Left(Inconsistency("Empty string not allowed")) else Right(v)
  }

  override def matches(comparator: CriterionComparator, value: String): NodeInfoMatcher = {
    comparator match {
      case Equals => NodeInfoMatcher(s"Prop equals '${value}'", (node: NodeInfo) => node.state.name == value)
      case _      => NodeInfoMatcher(s"Prop not equals '${value}'", (node: NodeInfo) => node.state.name != value)
    }
  }

}

case object NodeOstypeComparator extends NodeCriterionType {
  val osTypes:                                                                        List[String]        = List("Linux", "Windows")
  override def comparators:                                                           Seq[BaseComparator] = Seq(Equals, NotEquals)
  override protected def validateSubCase(v: String, comparator: CriterionComparator): PureResult[String]  = {
    if (null == v || v.isEmpty) Left(Inconsistency("Empty string not allowed")) else Right(v)
  }

  override def matches(comparator: CriterionComparator, value: String): NodeInfoMatcher = {
    comparator match {
      case Equals => NodeInfoMatcher(s"Prop equals '${value}'", (node: NodeInfo) => node.osDetails.os.kernelName == value)
      case _      => NodeInfoMatcher(s"Prop not equals '${value}'", (node: NodeInfo) => node.osDetails.os.kernelName != value)
    }
  }

}

case object NodeOsNameComparator extends NodeCriterionType {

  val osNames: List[OsType] = {
    LinuxType.allKnownTypes.sortBy {
      _.name
    } :::
    WindowsType.allKnownTypes
  }

  override def comparators:                                                           Seq[BaseComparator] = Seq(Equals, NotEquals)
  override protected def validateSubCase(v: String, comparator: CriterionComparator): PureResult[String]  = {
    if (null == v || v.isEmpty) Left(Inconsistency("Empty string not allowed")) else Right(v)
  }

  override def matches(comparator: CriterionComparator, value: String): NodeInfoMatcher = {
    comparator match {
      case Equals => NodeInfoMatcher(s"Prop equals '${value}'", (node: NodeInfo) => node.osDetails.os.name == value)
      case _      => NodeInfoMatcher(s"Prop not equals '${value}'", (node: NodeInfo) => node.osDetails.os.name != value)
    }
  }

}

final case class NodeStringComparator(access: NodeInfo => String) extends NodeCriterionType {
  override val comparators = BaseComparators.comparators

  override protected def validateSubCase(v: String, comparator: CriterionComparator): PureResult[String] = {
    if (null == v || v.isEmpty) Left(Inconsistency("Empty string not allowed"))
    else {
      comparator match {
        case Regex | NotRegex => validateRegex(v)
        case x                => Right(v)
      }
    }
  }

  override def matches(comparator: CriterionComparator, value: String): NodeInfoMatcher = {
    comparator match {
      case Equals    => NodeInfoMatcher(s"Prop equals '${value}'", (node: NodeInfo) => access(node) == value)
      case NotEquals => NodeInfoMatcher(s"Prop not equals '${value}'", (node: NodeInfo) => access(node) != value)
      case Regex     => NodeInfoMatcher(s"Prop matches regex '${value}'", (node: NodeInfo) => access(node).matches(value))
      case NotRegex  => NodeInfoMatcher(s"Prop matches not regex '${value}'", (node: NodeInfo) => !access(node).matches(value))
      case Exists    => NodeInfoMatcher(s"Prop exists", (node: NodeInfo) => access(node).nonEmpty)
      case NotExists => NodeInfoMatcher(s"Prop doesn't exists", (node: NodeInfo) => access(node).isEmpty)
      case _         => NodeInfoMatcher(s"Prop equals '${value}'", (node: NodeInfo) => access(node) == value)
    }
  }

}

case object NodeIpListComparator extends NodeCriterionType {
  override val comparators = BaseComparators.comparators

  override protected def validateSubCase(v: String, comparator: CriterionComparator): PureResult[String] = {
    if (null == v || v.isEmpty) Left(Inconsistency("Empty string not allowed"))
    else {
      comparator match {
        case Regex | NotRegex => validateRegex(v)
        case x                => Right(v)
      }
    }
  }

  override def matches(comparator: CriterionComparator, value: String): NodeInfoMatcher = {
    comparator match {
      case NotEquals => NodeInfoMatcher(s"Prop not equals '${value}'", (node: NodeInfo) => !node.ips.contains(value))
      case Regex     => NodeInfoMatcher(s"Prop matches regex '${value}'", (node: NodeInfo) => node.ips.exists(_.matches(value)))
      case NotRegex  =>
        NodeInfoMatcher(s"Prop matches not regex '${value}'", (node: NodeInfo) => !node.ips.exists(_.matches(value)))
      case Exists    => NodeInfoMatcher(s"Prop exists", (node: NodeInfo) => node.ips.nonEmpty)
      case NotExists => NodeInfoMatcher(s"Prop doesn't exists", (node: NodeInfo) => node.ips.isEmpty)
      case _         => NodeInfoMatcher(s"Prop equals '${value}'", (node: NodeInfo) => node.ips.contains(value))
    }
  }
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
final case class SplittedValue(key: String, values: List[String], separator: String = "=") {
  def value: String = values.mkString(separator)
}

final case class NodePropertyComparator(ldapAttr: String) extends NodeCriterionType {
  override val comparators: Seq[CriterionComparator] = KeyValueComparator.values.toList ++ BaseComparators.comparators

  override def validateSubCase(value: String, comparator: CriterionComparator): PureResult[String] = {
    comparator match {
      case Equals | NotEquals            =>
        if (value.contains("=")) {
          Right(value)
        } else {
          Left(
            Inconsistency(
              s"When looking for 'key=value', the '=' is mandatory. The left part is a key name, and the right part is the string to look for."
            )
          )
        }
      case KeyValueComparator.JsonSelect =>
        val x = value.split(":")
        if (x.nonEmpty) { // remaining '=' will be considered part of the value
          Right(value)
        } else {
          Left(
            Inconsistency(
              s"When looking for 'key=json path expression', we found zero ':', but at least one is mandatory. The left " +
              "part is a key name, and the right part is the JSON path expression (see https://github.com/json-path/JsonPath). For example: datacenter:world.europe.[?(@.city=='Paris')]"
            )
          )
        }
      case Regex | NotRegex              => validateRegex(value)
      case _                             => Right(value)
    }
  }

  override def matches(comparator: CriterionComparator, value: String): NodeInfoMatcher = {
    import com.normation.rudder.domain.queries.KeyValueComparator as KVC

    comparator match {
      // equals mean: the key is equals to kv._1 and the value is defined and the value is equals to kv._2.get
      case Equals         => {
        val kv = NodePropertyMatcherUtils.splitInput(value, "=")
        NodeInfoMatcher(
          s"Prop name=value equals'${value}'",
          (node: NodeInfo) => node.properties.find(p => p.name == kv.key && p.valueAsString == kv.value).isDefined
        )
      }
      // not equals mean: the key is not equals to kv._1 or the value is not defined or the value is defined but equals to kv._2.get
      case NotEquals      =>
        NodeInfoMatcher(s"Prop name=value not equals'${value}'", (node: NodeInfo) => !matches(Equals, value).matches(node))
      case Exists         =>
        NodeInfoMatcher(s"Prop name=value exists (at least one property)", (node: NodeInfo) => node.properties.nonEmpty)
      case NotExists      =>
        NodeInfoMatcher(s"Prop name=value not exists (empty properties)", (node: NodeInfo) => node.properties.isEmpty)
      case Regex          => NodePropertyMatcherUtils.regexMatcher(value)
      case NotRegex       =>
        new NodeInfoMatcher {
          val regex                = NodePropertyMatcherUtils.regexMatcher(value)
          override val debugString = s"Prop matches regex '${value}'"
          override def matches(node: NodeInfo): Boolean = !regex.matches(node)
        }
      case KVC.HasKey     => NodeInfoMatcher(s"Prop has key '${value}'", (node: NodeInfo) => node.properties.exists(_.name == value))
      case KVC.JsonSelect =>
        new NodeInfoMatcher {
          val kv                   = NodePropertyMatcherUtils.splitInput(value, ":")
          val path                 = JsonSelect.compilePath(kv.value).toPureResult
          val matcher              = NodePropertyMatcherUtils.matchJsonPath(kv.key, path)
          override val debugString = s"Prop json select '${value}'"
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
  // transform the given value to its LDAP string value
  protected def toLDAP(value: String): PureResult[String]

  def buildRegex(attribute:    String, value: String): PureResult[RegexFilter]    = Right(SimpleRegexFilter(attribute, value))
  def buildNotRegex(attribute: String, value: String): PureResult[NotRegexFilter] = Right(SimpleNotRegexFilter(attribute, value))

  // build the ldap filter for given attribute name and comparator
  def buildFilter(attributeName: String, comparator: CriterionComparator, value: String): Filter = {
    (toLDAP(value), comparator) match {
      case (_, Exists)           => HAS(attributeName)
      case (_, NotExists)        => NOT(HAS(attributeName))
      case (Right(v), Equals)    => EQ(attributeName, v)
      case (Right(v), NotEquals) => NOT(EQ(attributeName, v))
      case (Right(v), Greater)   => AND(HAS(attributeName), NOT(LTEQ(attributeName, v)))
      case (Right(v), Lesser)    => AND(HAS(attributeName), NOT(GTEQ(attributeName, v)))
      case (Right(v), GreaterEq) => GTEQ(attributeName, v)
      case (Right(v), LesserEq)  => LTEQ(attributeName, v)
      case (Right(v), Regex)     => HAS(attributeName) // "default, non interpreted regex
      case (Right(v), NotRegex)  => HAS(attributeName) // "default, non interpreted regex
      case (f, c)                =>
        throw new IllegalArgumentException(s"Can not build a filter with a non legal value for comparator '${c}': ${f}'")
    }
  }
}

//a comparator type with undefined comparators
final case class BareComparator(override val comparators: CriterionComparator*) extends LDAPCriterionType {
  override protected def validateSubCase(v: String, comparator: CriterionComparator): PureResult[String] = Right(v)
  override def toLDAP(value:                String): PureResult[String] = Right(value)
}

sealed trait TStringComparator extends LDAPCriterionType {

  override protected def validateSubCase(v: String, comparator: CriterionComparator): PureResult[String] = {
    if (null == v || v.isEmpty) Left(Inconsistency("Empty string not allowed"))
    else {
      comparator match {
        case Regex | NotRegex => validateRegex(v)
        case x                => Right(v)
      }
    }
  }
  override def toLDAP(value: String): PureResult[String] = Right(value)

  protected def escapedFilter(attributeName: String, value: String): Filter = {
    BuildFilter(attributeName + "=" + Filter.encodeValue(value))
  }
}

case object StringComparator extends TStringComparator {
  override val comparators = BaseComparators.comparators

  override def buildFilter(attributeName: String, comparator: CriterionComparator, value: String): Filter = comparator match {
    // for equals and not equals, check value for joker
    case Equals    => escapedFilter(attributeName, value)
    case NotEquals => NOT(escapedFilter(attributeName, value))
    case NotExists => NOT(HAS(attributeName))
    case Regex     => HAS(attributeName) // "default, non interpreted regex
    case NotRegex  => HAS(attributeName) // "default, non interpreted regex
    case _         => HAS(attributeName) // default to Exists
  }
}

case object ExactStringComparator extends TStringComparator {
  override val comparators: List[Equals.type] = Equals :: Nil

  override def buildFilter(attributeName: String, comparator: CriterionComparator, value: String): Filter = comparator match {
    // whatever the comparator it should be treated like Equals
    case _ => escapedFilter(attributeName, value)
  }
}

case object OrderedStringComparator extends TStringComparator {
  override val comparators = OrderedComparators.comparators

  override def buildFilter(attributeName: String, comparator: CriterionComparator, value: String): Filter = comparator match {
    // for equals and not equals, check value for jocker
    case Equals    => escapedFilter(attributeName, value)
    case NotEquals => NOT(escapedFilter(attributeName, value))
    case NotExists => NOT(HAS(attributeName))
    // for Greater/Lesser, the HAS attribute part is meaningful: that won't work without it.
    case Greater   => AND(HAS(attributeName), NOT(LTEQ(attributeName, value)))
    case Lesser    => AND(HAS(attributeName), NOT(GTEQ(attributeName, value)))
    case GreaterEq => GTEQ(attributeName, value)
    case LesserEq  => LTEQ(attributeName, value)
    case Regex     => HAS(attributeName) // "default, non interpreted regex
    case NotRegex  => HAS(attributeName) // "default, non interpreted regex
    case _         => HAS(attributeName) // default to Exists
  }
}

case object DateComparator extends LDAPCriterionType {
  override val comparators: Seq[CriterionComparator]        = OrderedComparators.comparators.filterNot(c => c == Regex || c == NotRegex)
  val compatFmts:           NonEmptyList[DateTimeFormatter] =
    NonEmptyList.of("dd/MM/yyyy", "yyyy/MM/dd").map(DateTimeFormat.forPattern) // format for which we need to keep compatibility
  val fmt:                                DateTimeFormatter               = DateTimeFormat.forPattern("yyyy-MM-dd")
  val allFmts:                            NonEmptyList[DateTimeFormatter] = (fmt :: compatFmts)
  def error(value: String, e: Exception): Inconsistency                   = Inconsistency(
    s"Invalid date: '${value}', expected format is: 'yyyy-MM-dd'. Error was: ${e.getMessage}"
  )

  override protected def validateSubCase(v: String, comparator: CriterionComparator): PureResult[String] = {
    parseDate(v).orElse(parseDateTime(v)).as(v)
  }

  override protected def toLDAP(value: String): Either[RudderError, String] = Left(
    Unexpected(s"The LDAP filter for the date ${value} wasn't taken into account. This is a developer error (see buildFilter).")
  )

  private def parseDate(value: String): PureResult[DateTime] = {
    allFmts
      .map(f => Either.catchOnly[Exception](f.parseDateTime(value).withZone(DateTimeZone.UTC)))
      .reduceLeft(_ orElse _)
      .leftMap(error(value, _))
  }

  private def parseDateTime(value: String): PureResult[DateTime] = {
    DateFormaterService.parseDate(value)
  }

  /*
   * Date comparison are not trivial, because we don't want to take care of the
   * time, but the time exists.
   */
  override def buildFilter(attributeName: String, comparator: CriterionComparator, value: String): Filter = {
    val parsedDate = parseDate(value)
    lazy val date  = parsedDate
      .orElse(parseDateTime(value))
      .getOrElse(
        throw new IllegalArgumentException(
          s"The date format was not recognized: '${value}' (expected 'yyyy-MM-dd') or an ISO8601 datetime"
        )
      )
    def dateString = GeneralizedTime(date).toString
    def date0000   = GeneralizedTime(date.withTimeAtStartOfDay).toString
    def date2359   = GeneralizedTime(date.withTime(23, 59, 59, 999)).toString

    def eqDate     = AND(GTEQ(attributeName, date0000), LTEQ(attributeName, date2359))
    def eqDateTime = AND(GTEQ(attributeName, dateString), LTEQ(attributeName, dateString))

    // make distinction when it's not a date but a datetime
    val isDateOnly = parsedDate.isRight
    (isDateOnly, comparator) match {
      // for equals and not equals, check value for jocker
      case (true, Equals)     => eqDate
      case (false, Equals)    => eqDateTime
      case (true, NotEquals)  => NOT(eqDate)
      case (false, NotEquals) => NOT(eqDateTime)
      case (_, NotExists)     => NOT(HAS(attributeName))
      case (true, Greater)    => AND(HAS(attributeName), NOT(LTEQ(attributeName, date2359)))
      case (false, Greater)   => AND(HAS(attributeName), NOT(LTEQ(attributeName, dateString)))
      case (true, Lesser)     => AND(HAS(attributeName), NOT(GTEQ(attributeName, date0000)))
      case (false, Lesser)    => AND(HAS(attributeName), NOT(GTEQ(attributeName, dateString)))
      case (true, GreaterEq)  => GTEQ(attributeName, date0000)
      case (false, GreaterEq) => GTEQ(attributeName, dateString)
      case (true, LesserEq)   => LTEQ(attributeName, date2359)
      case (false, LesserEq)  => LTEQ(attributeName, dateString)
//      case Regex => HAS(attributeName) //"default, non interpreted regex
      case _                  => HAS(attributeName) // default to Exists
    }
  }

}

case object BooleanComparator extends LDAPCriterionType {
  override val comparators = BaseComparators.comparators
  override protected def validateSubCase(v: String, comparator: CriterionComparator): PureResult[String] = v.toLowerCase match {
    case "t" | "f" | "true" | "false" => Right(v)
    case _                            => Left(Inconsistency(s"Bad input: boolean expected, '${v}' found"))
  }
  override def toLDAP(v: String):                                                     PureResult[String] = v.toLowerCase match {
    case "t" | "f" | "true" | "false" => Right(v)
    case _                            => Left(Inconsistency(s"Bad input: boolean expected, '${v}' found"))
  }
}

case object LongComparator extends LDAPCriterionType {
  override val comparators = OrderedComparators.comparators
  override protected def validateSubCase(v: String, comparator: CriterionComparator): PureResult[String] = try {
    Right((v.toLong).toString)
  } catch {
    case e: Exception => Left(Inconsistency(s"Invalid long : '${v}'"))
  }
  override def toLDAP(v: String):                                                     PureResult[String] = try {
    Right((v.toLong).toString)
  } catch {
    case e: Exception => Left(Inconsistency(s"Invalid long : '${v}'"))
  }
}

case object MemoryComparator extends LDAPCriterionType {
  override val comparators = OrderedComparators.comparators
  override protected def validateSubCase(v: String, comparator: CriterionComparator): PureResult[String] = {
    comparator match {
      case Regex | NotRegex => validateRegex(v)
      case _                =>
        if (MemorySize.parse(v).isDefined) Right(v)
        else Left(Inconsistency(s"Invalid memory size : '${v}', expecting '300 M', '16KB', etc"))
    }
  }

  override def toLDAP(v: String): PureResult[String] = MemorySize.parse(v) match {
    case Some(m) => Right(m.toString)
    case None    => Left(Inconsistency(s"Invalid memory size : '${v}', expecting '300 M', '16KB', etc"))
  }
}

case object MachineComparator extends LDAPCriterionType {

  val machineTypes: List[String] = "Virtual" :: "Physical" :: Nil

  override def comparators:                                                           Seq[BaseComparator] = Seq(Equals, NotEquals)
  override protected def validateSubCase(v: String, comparator: CriterionComparator): PureResult[String]  = {
    if (null == v || v.isEmpty) Left(Inconsistency("Empty string not allowed")) else Right(v)
  }

  override def toLDAP(value: String): PureResult[String] = Right(value)

  override def buildFilter(attributeName: String, comparator: CriterionComparator, value: String): Filter = {
    val v = value match {
      // the machine can't belong to another type
      case "Virtual"  => OC_VM
      case "Physical" => OC_PM
    }
    comparator match {
      case Equals => IS(v)
      case _      => NOT(IS(v))
    }
  }
}

case object VmTypeComparator extends LDAPCriterionType {
  final case class vm(obj: VmType, ldapClass: String, displayName: String)
  val vmTypes: List[(String, String)] = List(
    (OC_VM_HYPERV, VmType.HyperV.name),
    (OC_VM_LXC, VmType.LXC.name),
    (OC_VM_OPENVZ, VmType.OpenVZ.name),
    (OC_VM_QEMU, VmType.QEmu.name),
    (OC_VM_VIRTUALBOX, VmType.VirtualBox.name),
    (OC_VM_VIRTUOZZO, VmType.Virtuozzo.name),
    (OC_VM_VMWARE, VmType.VMWare.name),
    (OC_VM_XEN, VmType.Xen.name)
  )

  override def comparators: Seq[BaseComparator] = Seq(Equals, NotEquals)

  override protected def validateSubCase(v: String, comparator: CriterionComparator): PureResult[String] = {
    if (null == v || v.isEmpty) Left(Inconsistency("Empty string not allowed")) else Right(v)
  }

  override def toLDAP(value: String): PureResult[String] = Right(value)

  override def buildFilter(attributeName: String, comparator: CriterionComparator, value: String): Filter = {
    val v = vmTypes.collectFirst { case (ldap, text) if (ldap == value) => ldap }.getOrElse(OC_VM)
    comparator match {
      case Equals => IS(v)
      case _      => NOT(IS(v))
    }
  }
}

/*
 * Agent comparator is kind of scpecial, because it needs to accomodate to the following cases:
 * - historically, agent names were only "Community" (understood "cfengine", of course)
 * - then, we changed in 4.2 to normalized "cfengine-community" (plus "dsc")
 *   (but old agent are still "Community"
 * - and we want a subcase "anything cfengine based" (because it is important for generation, for
 *   group "haspolicyserver-*" and rule "inventory-all")
 *
 *   So we do actually need a special agent type "cfengine", and hand craft the buildFilter for it.
 */
case object AgentComparator extends LDAPCriterionType {

  val ANY_CFENGINE          = "cfengine"
  val (cfeTypes, cfeAgents) =
    ((ANY_CFENGINE, "Any CFEngine based agent"), (ANY_CFENGINE, AgentType.CfeCommunity :: Nil))
  private val allAgents     = AgentType.values.toList

  val agentTypes: List[(String, String)]       = (cfeTypes :: allAgents.map(a => (a.oldShortName, (a.displayName)))).sortBy(_._2)
  val agentMap:   Map[String, List[AgentType]] = (cfeAgents :: allAgents.map(a => (a.oldShortName, a :: Nil))).toMap

  override def comparators:                                                           Seq[BaseComparator] = Seq(Equals, NotEquals)
  override protected def validateSubCase(v: String, comparator: CriterionComparator): PureResult[String]  = {
    if (null == v || v.isEmpty) Left(Inconsistency("Empty string not allowed")) else Right(v)
  }
  override def toLDAP(value: String): PureResult[String] = Right(value)

  /*
   * We need compatibility for < 4.2 inventory
   * 4.2+: a json is stored in AGENTS_NAME, so we need to check if it contains the valid agentType attribute
   * 4.1: a json is stored in AGENTS_NAME, but not with the same value than 4.2 (oldshortName instead of id is stored as agentType ...)
   * <4.1: AGENTS_NAME only contains the name of the agent (but a value that is different form the id, oldShortName)
   */
  private def filterAgent(agent: AgentType) = {
    SUB(A_AGENT_NAME, null, Array(s""""agentType":"${agent.id}""""), null) ::           // 4.2+
    SUB(A_AGENT_NAME, null, Array(s""""agentType":"${agent.oldShortName}""""), null) :: // 4.1
    EQ(A_AGENT_NAME, agent.oldShortName) ::                                             // 3.1 ( < 4.1 in fact)
    Nil
  }

  override def buildFilter(attributeName: String, comparator: CriterionComparator, value: String): Filter = {

    val filters = for {
      agents <- agentMap.get(value).toList
      agent  <- agents
      filter <- filterAgent(agent)
    } yield {
      filter
    }
    comparator match {
      // for equals and not equals, check value for joker
      case Equals =>
        OR(filters*)
      case _      => // actually, this is meant to be "not equals"
        NOT(OR(filters*))
    }
  }
}

case object EditorComparator extends LDAPCriterionType {
  val editors: List[String] = List("Microsoft", "RedHat", "Debian", "Adobe", "Macromedia")
  override val comparators = BaseComparators.comparators
  override protected def validateSubCase(v: String, comparator: CriterionComparator): PureResult[String] =
    if (editors.contains(v)) Right(v) else Left(Inconsistency(s"Invalide editor : '${v}'"))

  override def toLDAP(value: String): PureResult[String] = Right(value)
}

/**
 * A type for comparators that do not require the value to be encoded into LDAP
 */
sealed trait NonLdapCriterionType extends CriterionType

case class InstanceIdComparator(instanceId: InstanceId) extends NonLdapCriterionType with TStringComparator {
  override def comparators:                                    Seq[BaseComparator] = Equals :: NotEquals :: Nil
  def matches(value: String, comparator: CriterionComparator): PureResult[Boolean] = {
    comparator match {
      case Equals    => Right(value.equalsIgnoreCase(instanceId.value))
      case NotEquals => Right(!value.equalsIgnoreCase(instanceId.value))
      case _         => Left(Unexpected(s"Instance ID comparator only supports : ${comparators.map(_.id).mkString(",")}"))
    }
  }
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
final case class JsonFixedKeyComparator(ldapAttr: String, jsonKey: String, quoteValue: Boolean) extends TStringComparator {
  override val comparators = BaseComparators.comparators

  def format(attribute: String, value: String):              String                  = {
    val v = if (quoteValue) s""""$value"""" else value
    s""""${attribute}":${v}"""
  }
  def regex(attribute: String, value: String):               String                  = {
    s".*${format(attribute, value)}.*"
  }
  override def buildRegex(attribute: String, value: String): PureResult[RegexFilter] = {
    Right(SimpleRegexFilter(ldapAttr, regex(attribute, value)))
  }

  override def buildNotRegex(attribute: String, value: String): PureResult[NotRegexFilter] = {
    Right(SimpleNotRegexFilter(ldapAttr, regex(attribute, value)))
  }

  override def buildFilter(key: String, comparator: CriterionComparator, value: String): Filter = {
    import KeyValueComparator.HasKey
    val sub = SUB(ldapAttr, null, Array(format(key, value).getBytes("UTF-8")), null)
    comparator match {
      case Equals    => sub
      case NotEquals => NOT(sub)
      case NotExists => NOT(HAS(ldapAttr))
      case Regex     => HAS(ldapAttr) // default, non interpreted regex
      case NotRegex  => HAS(ldapAttr) // default, non interpreted regex
      case HasKey    => sub
      case _         => HAS(key)      // default to Exists
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
final case class NameValueComparator(ldapAttr: String) extends TStringComparator {
  import KeyValueComparator.HasKey
  override val comparators: Seq[CriterionComparator] = HasKey +: BaseComparators.comparators

  // split k=v (v may not exists if there is no '='
  // is there is several '=', we consider they are part of the value
  def splitInput(value: String): (String, Option[String]) = {
    val SplittedValue(k, l, s) = NodePropertyMatcherUtils.splitInput(value, "=")
    val v                      = l match {
      case Nil => None
      case t   => Some(t.mkString(s))
    }
    (k, v)
  }

  // produce the correct "serialized" JSON to look for
  def formatKV(kv: (String, Option[String])): String = {
    // no englobing {} to allow use in regex
    s""""name":"${kv._1}","value":"${kv._2.getOrElse("")}""""
  }

  // the first arg is "name.value", not interesting here
  override def buildRegex(_x: String, value: String): PureResult[RegexFilter] = {
    Right(SimpleRegexFilter(ldapAttr, """\{""" + formatKV(splitInput(value)) + """\}"""))
  }

  // the first arg is "name.value", not interesting here
  override def buildNotRegex(_x: String, value: String): PureResult[NotRegexFilter] = {
    Right(SimpleNotRegexFilter(ldapAttr, """\{""" + formatKV(splitInput(value)) + """\}"""))
  }

  // the first arg is "name.value", not interesting here
  override def buildFilter(_x: String, comparator: CriterionComparator, value: String): Filter = {
    val kv  = splitInput(value)
    val sub = SUB(ldapAttr, ("{" + formatKV(kv)).getBytes("UTF-8"), null, null)
    comparator match {
      case Equals    => sub
      case NotEquals => NOT(sub)
      case NotExists => NOT(HAS(ldapAttr))
      case Regex     => HAS(ldapAttr) // default, non interpreted regex
      case NotRegex  => HAS(ldapAttr) // default, non interpreted regex
      case HasKey    => SUB(ldapAttr, s"""{"name":"${kv._1}"""".getBytes("UTF-8"), null, null)
      case _         => HAS(ldapAttr) // default to Exists
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

// we must use `() => IOResult[...]` to avoid cyclic reference
final case class SubGroupComparator(subGroupComparatorRepo: () => SubGroupComparatorRepository) extends TStringComparator {
  override val comparators: List[Equals.type] = Equals :: Nil

  override def buildFilter(attributeName: String, comparator: CriterionComparator, value: String): Filter = comparator match {
    // whatever the comparator it should be treated like Equals
    case _ => escapedFilter(attributeName, value)
  }
}

/**
 * Create a new criterion for the given attribute `name`, and `cType` comparator.
 * Optionally, you can provide an override to signal that that criterion is not
 * on an inventory (or successfully on an inventory) property but on a RudderNode property.
 * In that case, give the predicat that the node must follows.
 */
final case class Criterion(
    val name:             String,
    val cType:            CriterionType,
    nodeCriterionMatcher: NodeCriterionMatcher,
    overrideObjectType:   Option[String] = None
) {
  require(name != null && name.nonEmpty, "Criterion name must be defined")
  require(cType != null, "Criterion Type must be defined")
}

case class ObjectCriterion(val objectType: String, val criteria: Seq[Criterion]) {
  require(objectType.nonEmpty, "Unique identifier for line must be defined")
  require(criteria.nonEmpty, "You must at least have one criterion for the line")

  // optionally retrieve the criterion from a "string" attribute
  def criterionForName(name: String): (Option[Criterion]) =
    criteria.find(c => name.equalsIgnoreCase(c.name))

  def criterionComparatorForName(name: String, comparator: String): (Option[Criterion], Option[CriterionComparator]) = {
    criterionForName(name) match {
      case ab @ Some(x) => (ab, x.cType.comparatorForString(comparator))
      case _            => (None, None)
    }
  }
}

final case class CriterionLine(
    objectType: ObjectCriterion,
    attribute:  Criterion,
    comparator: CriterionComparator,
    value:      String = ""
)

object CriterionLine {
  implicit val transformCriterionLine: Transformer[CriterionLine, JsonCriterionLine] = {
    Transformer
      .define[CriterionLine, JsonCriterionLine]
      .withFieldComputed(_.objectType, _.objectType.objectType)
      .withFieldComputed(_.attribute, _.attribute.name)
      .withFieldComputed(_.comparator, _.comparator.id)
      .buildTransformer
  }

}

final case class JsonCriterionLine(
    objectType: String,
    attribute:  String,
    comparator: String,
    value:      String
)

object JsonCriterionLine {
  implicit val encoderJsonCriterionLine: JsonEncoder[JsonCriterionLine] = DeriveJsonEncoder.gen
  implicit val decoderJsonCriterionLine: JsonDecoder[JsonCriterionLine] = DeriveJsonDecoder.gen
}

sealed abstract class CriterionComposition { def value: String }

object CriterionComposition {

  case object And extends CriterionComposition { val value = "and" }
  case object Or  extends CriterionComposition { val value = "or"  }

  def parse(s: String): PureResult[CriterionComposition] = {
    s.toLowerCase match {
      case "and" => Right(And)
      case "or"  => Right(Or)
      case x     => Left(Inconsistency(s"The requested composition '${x}' is unknown"))
    }
  }

  implicit val encoderCriterionComposition: JsonEncoder[CriterionComposition] = JsonEncoder.string.contramap(_.value)
  implicit val decoderCriterionComposition: JsonDecoder[CriterionComposition] =
    JsonDecoder.string.mapOrFail(parse(_).left.map(_.fullMsg))
}

sealed trait QueryReturnType {
  def value: String
}

object QueryReturnType {
  case object NodeReturnType              extends QueryReturnType {
    override val value = "node"
  }
  case object NodeAndRootServerReturnType extends QueryReturnType {
    override val value = "nodeAndPolicyServer"
  }

  def apply(value: String): Either[Inconsistency, QueryReturnType] = {
    value match {
      case NodeReturnType.value              => Right(NodeReturnType)
      case NodeAndRootServerReturnType.value => Right(NodeAndRootServerReturnType)
      case _                                 => Left(Inconsistency(s"Query return type '${value}' is not valid"))
    }
  }

  implicit val encoderQueryReturnType: JsonEncoder[QueryReturnType] = JsonEncoder.string.contramap(_.value)
  implicit val decoderQueryReturnType: JsonDecoder[QueryReturnType] = JsonDecoder.string.mapOrFail(apply(_).left.map(_.fullMsg))
}

sealed trait ResultTransformation extends EnumEntry {
  def value: String
}

object ResultTransformation extends Enum[ResultTransformation] {
  // no result transformation
  case object Identity extends ResultTransformation { val value = "identity" }
  // invert result: substract from "all nodes" the one matching that query
  case object Invert   extends ResultTransformation { val value = "invert"   }

  val values: IndexedSeq[ResultTransformation] = findValues

  def parse(value: String): PureResult[ResultTransformation] = {
    value.toLowerCase match {
      case "none" | Identity.value => Right(Identity)
      case Invert.value            => Right(Invert)
      case _                       =>
        Left(
          Inconsistency(
            s"Can not parse '${value}' as a result transformation; expecting: ${values.map(_.value).mkString("'", "', '", "'")}"
          )
        )
    }
  }

  implicit val encoderResultTransformation: JsonEncoder[ResultTransformation] = JsonEncoder.string.contramap(_.value)
  implicit val decoderResultTransformation: JsonDecoder[ResultTransformation] =
    JsonDecoder.string.mapOrFail(parse(_).left.map(_.fullMsg))
}

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
final case class JsonQuery(
    select:      QueryReturnType,
    composition: CriterionComposition,
    transform:   Option[ResultTransformation],
    where:       List[JsonCriterionLine]
)

object JsonQuery {
  implicit val encoderJsonQuery: JsonEncoder[JsonQuery] = DeriveJsonEncoder.gen
  implicit val decoderJsonQuery: JsonDecoder[JsonQuery] = DeriveJsonDecoder.gen
}

object Query {
  /*
   * Compare two queries without taking into account the order of criterions
   */
  def equalsUnsorted(q1: Query, q2: Query): Boolean = {
    // we don't care if the cardinal of equals criterion is not the same on the two,
    // ie [c1,c2,c1] == [c2,c2,c1] yields true
    q1.returnType == q2.returnType &&
    q1.composition == q2.composition &&
    q1.transform == q2.transform &&
    q1.criteria.size == q2.criteria.size &&
    q1.criteria.forall(c1 => q2.criteria.exists(c2 => c1 == c2))
  }

  implicit val transformQuery: Transformer[Query, JsonQuery] = {
    Transformer
      .define[Query, JsonQuery]
      .withFieldComputed(
        _.transform,
        _.transform match {
          case ResultTransformation.Identity => None
          case x                             => Some(x)
        }
      )
      .withFieldRenamed(_.returnType, _.select)
      .withFieldRenamed(_.criteria, _.where)
      .buildTransformer
  }

  /*
   *  { "select":"...", "composition":"...", "where": [
   *      { "objectType":"...", "attribute":"...", "comparator":"..", "value":"..." }
   *      ...
   *    ]}
   *
   * Make "transform" optional: don't display it if it's identity
   */
  implicit val encoderQuery: JsonEncoder[Query] = JsonEncoder[JsonQuery].contramap(_.transformInto[JsonQuery])
}

final case class Query(
    returnType:  QueryReturnType,    // "node" or "node and policy servers"
    composition: CriterionComposition,
    transform:   ResultTransformation,
    criteria:    List[CriterionLine] // list of all criteria to be matched by returned values
) {

  override def toString(): String = {
    s"{ returnType:'${returnType.value}' (${transform.value}) with '${composition.toString}' criteria [${criteria.map { x =>
        s"${x.objectType.objectType}.${x.attribute.name} ${x.comparator.id} ${x.value}"
      }.mkString(" ; ")}] }"
  }

}
