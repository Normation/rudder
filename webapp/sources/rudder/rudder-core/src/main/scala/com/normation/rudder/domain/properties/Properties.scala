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

package com.normation.rudder.domain.properties

import com.normation.GitVersion
import com.normation.GitVersion.Revision
import com.normation.errors.*
import com.normation.inventory.domain.CustomProperty
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.logger.ApplicationLogger
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.services.policies.ParameterEntry
import com.typesafe.config.*
import enumeratum.*
import java.util.regex.Pattern
import net.liftweb.json.*
import net.liftweb.json.JsonDSL.*

/*
 * A property provider is the thing responsible for that property.
 * The general rule is that only the property provider can change a given
 * property once it's created.
 * There's two special providers: `system` can always change properties, even
 * of other providers, and `default` (or when provider is unspecified) can be
 * changed by any provider.
 * So we have a lattice of prodividers with only three levels and a "can change relation":
 *                     system
 *        _______________|_______________
 *       /           /        \          \
 *   datasource   inventory  .....    other providers
 *       \___________\________/__________/
 *                       |
 *                    default
 *
 * `system` is the provider of important, system set properties which should not be changed
 * or even which should be resetted to default value as soon as possible.
 *
 * `default` corresponds to all standard operation: user setting a value through UI, script
 * using a REST request to change a property, etc.
 *
 * Notion of provider is limited to one class of object, for the same property.
 * For example an user wants to update a node property that was set by datasources => error.
 *
 * Providers don't limit inheritance nor overriding. If a global parameter has a `system`
 * provider, it can be overriden by a group or node property.
 */
final case class PropertyProvider(value: String) extends AnyVal

/*
 * An inherit mode is three chars, one for object, one for array, one for string like: 'moo'
 * The first char is for objects and can be: o = override, m = merge (default)
 * The second char is for arrays and can be: o = override (default), a = append, p = prepend
 * The third is for strings and can be:      o = override (default), a = append, p = prepend
 */
final case class InheritMode(
    forObject: InheritMode.ObjectMode,
    forArray:  InheritMode.ArrayMode,
    forString: InheritMode.StringMode
) {
  def value: String = s"${forObject.value}${forArray.value}${forString.value}"
}
object InheritMode {
  sealed trait ObjectMode extends EnumEntry {
    def value: Char
  }

  case object ObjectMode extends Enum[ObjectMode] {
    case object Override extends ObjectMode { override val value = 'o' }
    case object Merge    extends ObjectMode { override val value = 'm' }

    val values: IndexedSeq[ObjectMode] = findValues

    def parse(c: Char): Option[ObjectMode] = values.find(c == _.value)
  }

  sealed trait ArrayMode extends EnumEntry {
    def value: Char
  }

  case object ArrayMode extends Enum[ArrayMode] {
    case object Override extends ArrayMode { override val value = 'o' }
    case object Append   extends ArrayMode { override val value = 'a' }
    case object Prepend  extends ArrayMode { override val value = 'p' }

    val values: IndexedSeq[ArrayMode] = findValues

    def parse(c: Char): Option[ArrayMode] = values.find(c == _.value)
  }

  sealed trait StringMode extends EnumEntry {
    def value: Char
  }

  case object StringMode extends Enum[StringMode] {
    case object Override extends StringMode { override val value = 'o' }
    case object Append   extends StringMode { override val value = 'a' }
    case object Prepend  extends StringMode { override val value = 'p' }

    val values: IndexedSeq[StringMode] = findValues

    def parse(c: Char): Option[StringMode] = values.find(c == _.value)
  }

  def parseString(s: String): PureResult[InheritMode] = s.toList match {
    case obj :: arr :: str :: Nil =>
      (ObjectMode.parse(obj), ArrayMode.parse(arr), StringMode.parse(str)) match {
        case (Some(o), Some(a), Some(s_)) => Right(InheritMode(o, a, s_))
        case _                            =>
          Left(Inconsistency(s"Impossible to parse string as inherit mode option, expecting: [mo][oap][oap] but got: ${s}"))
      }
    case _                        =>
      Left(Inconsistency(s"Impossible to parse string as inherit mode option, expecting: [mo][oap][oap] but got: ${s}"))
  }

  val Default: InheritMode = InheritMode(ObjectMode.Merge, ArrayMode.Override, StringMode.Override)
}

object PropertyProvider {
  /*
   * System property provider. These properties should never
   * be updated/deleted by things other than rudder.
   */
  final val systemPropertyProvider:  PropertyProvider = PropertyProvider("system")
  final val defaultPropertyProvider: PropertyProvider = PropertyProvider("default")
}

/*
 * Utility class to ease update of generic property values
 */
final case class PatchProperty(
    name:        Option[String] = None,
    value:       Option[ConfigValue] = None,
    provider:    Option[PropertyProvider] = None,
    description: Option[String] = None,
    inheritMode: Option[InheritMode] = None
)

/*
 * A property is backed by an HOCON config that MUST looks like:
 * { "name": name, "value": value, "provider": "default", "description":"..." }
 * Any other format will lead to errors.
 * Only the name is mandatory, and it is alway escapped as a string.
 * Description, if not provided will return an empty string. Anything else than a string will lead to an error.
 * Provider, if not provided will return None
 * This trait provides update methods for generic processing (since we don't have case class compiler support).
 */
sealed trait GenericProperty[P <: GenericProperty[?]] {
  import GenericProperty.*

  def config: Config
  def fromConfig(v: Config): P

  final def name:  String           = config.getString(NAME)
  final def rev:   Option[Revision] = {
    if (config.hasPath(REV_ID)) Some(Revision(config.getString(REV_ID)))
    else None
  }
  final def value: ConfigValue      = {
    if (config.hasPath(VALUE)) config.getValue(VALUE)
    else ConfigValueFactory.fromAnyRef("")
  }

  final def provider: Option[PropertyProvider] = {
    // optional, default "rudder"
    if (config.hasPath(PROVIDER)) Some(PropertyProvider(config.getString(PROVIDER)))
    else None
  }

  final def description: String = {
    if (config.hasPath(GenericProperty.DESCRIPTION)) config.getString(GenericProperty.DESCRIPTION)
    else ""
  }

  final def inheritMode: Option[InheritMode] = {
    GenericProperty.getMode(config)
  }

  final def withName(name:     String):           P = patch(PatchProperty(name = Some(name)))
  final def withValue(value:   ConfigValue):      P = patch(PatchProperty(value = Some(value)))
  final def withValue(value:   String):           P = patch(PatchProperty(value = Some(value.toConfigValue)))
  final def withProvider(p:    PropertyProvider): P = patch(PatchProperty(provider = Some(p)))
  final def withDescription(d: String):           P = patch(PatchProperty(description = Some(d)))
  final def withMode(m:        InheritMode):      P = patch(PatchProperty(inheritMode = Some(m)))
  final def patch(p: PatchProperty): P      = {
    def patchOne[A](key: String, update: Option[A], toValue: A => ConfigValue)(c: Config): Config = {
      update match {
        case None    => c
        case Some(u) => c.withValue(key, toValue(u))
      }
    }

    fromConfig(
      List(
        patchOne[String](GenericProperty.NAME, p.name, _.toConfigValue)(_),
        patchOne[ConfigValue](GenericProperty.VALUE, p.value, identity)(_),
        patchOne[PropertyProvider](GenericProperty.PROVIDER, p.provider, _.value.toConfigValue)(_),
        patchOne[String](GenericProperty.DESCRIPTION, p.description, _.toConfigValue)(_),
        patchOne[InheritMode](GenericProperty.INHERIT_MODE, p.inheritMode, _.value.toConfigValue)(_)
      ).foldLeft(config) { case (c, patchStep) => patchStep(c) }
    )
  }
  override def toString:             String =
    this.getClass.getSimpleName + "(" + this.config.root.render(ConfigRenderOptions.defaults()) + ")"
}

object GenericProperty {

  val VALUE        = "value"
  val NAME         = "name"
  val REV_ID       = "revision"
  val PROVIDER     = "provider"
  val DESCRIPTION  = "description"
  val INHERIT_MODE = "inheritMode" // options: inheritance mode

  /**
   * Property name must matches that pattern
   */
  val patternName: Pattern = Pattern.compile("""[\-a-zA-Z0-9_]+""")

  def getMode(config: Config):                            Option[InheritMode] = {
    if (config.hasPath(INHERIT_MODE)) {
      InheritMode.parseString(config.getString(INHERIT_MODE)).toOption
    } else None
  }
  def setMode(config: Config, mode: Option[InheritMode]): Config              = {
    mode match {
      case Some(m) => config.withValue(INHERIT_MODE, ConfigValueFactory.fromAnyRef(m.value))
      case None    => config
    }
  }

  /**
   * Check if old property can be updated by a new one matching its provider.
   * The rules are:
   * - system provider has always the right to update,
   * - else default provider has always the right to be updated,
   * - else providers must match (there is no notion of ordering on other providers).
   *
   * Sum up: system > all other providers > none|defaults
   */
  def canBeUpdated(old: Option[PropertyProvider], newer: Option[PropertyProvider]): Boolean = {
    if (newer == Some(PropertyProvider.systemPropertyProvider)) {
      true
    } else {
      old match {
        case None                                                       =>
          true
        case Some(p1) if p1 == PropertyProvider.defaultPropertyProvider =>
          true
        case Some(p1)                                                   =>
          val res = p1 == newer.getOrElse(PropertyProvider.defaultPropertyProvider)
          res
      }
    }
  }

  /**
   * Serialize to hocon format, but just authorize comments (else keep json).
   * We may relaxe constraints later.
   * See `serialize` for info about how different value type are serialized.
   */
  def serializeToHocon(value: ConfigValue): String = {
    serialize(value, ConfigRenderOptions.concise().setComments(true))
  }

  /**
   * Serialize to json, loosing comments.
   * See `serialize` for info about how different value type are serialized.
   */
  def serializeToJson(value: ConfigValue): String = {
    serialize(value, ConfigRenderOptions.concise())
  }

  /**
   * Write back a value as a string. There is
   * some care to take, because simple jvalue (string, boolean, etc)
   * must be written directly as string without quote.
   */
  def serialize(value: ConfigValue, option: ConfigRenderOptions): String = {
    // special case for string: we need to remove "" for compat with agents
    if (value.valueType() == ConfigValueType.STRING) {
      value.unwrapped().toString
    } else {
      value.render(option)
    }
  }
  def serializeJson(value: JValue):                               String = {
    // special case for string: we need to remove "" for compat with agents
    value match {
      case JNothing   => ""
      case JString(s) => s
      case x          => compactRender(x)
    }
  }

  /*
   * Merge two json values, overriding or merging recursively
   */
  def mergeValues(oldValue: ConfigValue, newValue: ConfigValue, mode: InheritMode): ConfigValue = {
    import ConfigValueType.*
    import InheritMode.*

    import java.util.List as juList
    import java.util.Map as juMap
    import scala.jdk.CollectionConverters.*

    def stringPlus(a: ConfigValue, b: ConfigValue):                 ConfigValue = {
      ConfigValueFactory.fromAnyRef(a.unwrapped().asInstanceOf[String] + b.unwrapped().asInstanceOf[String])
    }
    def listPlus(a: ConfigValue, b: ConfigValue):                   ConfigValue = {
      val l = a.unwrapped().asInstanceOf[juList[Any]]
      l.addAll(b.unwrapped().asInstanceOf[juList[Any]])
      ConfigValueFactory.fromIterable(l)
    }
    def objectPlus(a: ConfigValue, b: ConfigValue, m: InheritMode): ConfigValue = {
      val o        = a.unwrapped().asInstanceOf[juMap[String, Any]].asScala
      val n        = b.unwrapped().asInstanceOf[juMap[String, Any]].asScala
      val mergeNew = n.map {
        case (k, vn) =>
          o.get(k) match {
            case None     => (k, vn)
            case Some(vo) => (k, mergeValues(ConfigValueFactory.fromAnyRef(vo), ConfigValueFactory.fromAnyRef(vn), m))
          }
      }
      ConfigValueFactory.fromMap((o ++ mergeNew).asJava)
    }

    (oldValue.valueType(), newValue.valueType()) match {
      case (STRING, STRING)                      =>
        mode.forString match {
          case StringMode.Override => newValue
          case StringMode.Prepend  => stringPlus(newValue, oldValue)
          case StringMode.Append   => stringPlus(oldValue, newValue)
        }
      case (_, NULL | STRING | NUMBER | BOOLEAN) => newValue // override in all case
      case (LIST, LIST)                          =>          // override by default
        mode.forArray match {
          case ArrayMode.Override => newValue
          case ArrayMode.Prepend  => listPlus(newValue, oldValue)
          case ArrayMode.Append   => listPlus(oldValue, newValue)
        }
      case (_, LIST)                             => newValue // override in all case
      case (OBJECT, OBJECT)                      =>          // merge by default
        mode.forObject match {
          case ObjectMode.Override => newValue
          case ObjectMode.Merge    => objectPlus(oldValue, newValue, mode)
        }
      case (_, OBJECT)                           => newValue // override
    }
  }

  /**
   * Merge two properties. newProp values will win.
   * You should have check before that "name" and "provider" are OK.
   */
  def mergeConfig(oldProp: Config, newProp: Config)(implicit defaultInheritMode: Option[InheritMode]): Config = {
    val mode           = ((GenericProperty.getMode(oldProp), GenericProperty.getMode(newProp)) match {
      case (mode, None) => mode
      case (_, mode)    => mode
    }).orElse(defaultInheritMode)
    val otherThanValue = newProp
      .withValue(VALUE, ConfigValueFactory.fromAnyRef(""))
      .withFallback(oldProp.withValue(VALUE, ConfigValueFactory.fromAnyRef("")))
    // set value
    val withValue      = otherThanValue.withValue(
      VALUE,
      mergeValues(oldProp.getValue(VALUE), newProp.getValue(VALUE), mode.getOrElse(InheritMode.Default))
    )
    // set mode
    GenericProperty.setMode(withValue, mode)
  }

  /**
   * Find the first non comment char in a (multiline) string. In our convention, an
   * hocon object must starts (excluded comments and whitespaces) by a '{'.
   * Optionnally return the first char, the original string or the one without options, and option mode.
   */
  @scala.annotation.tailrec
  def firstNonCommentChar(s: String): Option[Char] = {
    val trim = s.dropWhile(c => c.isWhitespace || c.isSpaceChar)
    if (trim.isEmpty) None
    else {
      if (trim.startsWith("#") || trim.startsWith("//")) {
        firstNonCommentChar(trim.dropWhile(_ != '\n'))
      } else { // trim is non empty
        Some(trim.charAt(0))
      }
    }
  }

  /**
   * Parse a value that was correctly serialized to hocon (ie string are quoted, etc)
   */
  def parseSerialisedValue(value: String): PureResult[ConfigValue] = {
    PureResult.attempt(s"Error: value is not parsable as a property: ${value}") {
      ConfigFactory
        .parseString(
          // it's necessary to put it on its own line to avoid pb with comments/multilines
          s"""{"x":
            ${value}
            }"""
        )
        .getValue("x")
    }
  }

  /**
   * Contrary to native hocon, we only have TWO kinds of values:
   * - object, which are mandatory to start with a '{' and end by a '}'
   *   (in middle, it's whatever hocon authorize: comments, etc. But the structure must be key/value structured)
   * - arrays, which are mandatory to start with a '['
   * - strings, which must not start by '{' (you must escape it with \ if so). <- check that. Is it indempotent regarding serialisation?
   *   String can't have comments or anything, they are taken "as is".
   */
  def parseValue(value: String): PureResult[ConfigValue] = {
    // find first char that is no in a commented line nor a space
    if (value == "") Right(ConfigValueFactory.fromAnyRef(""))
    else {
      firstNonCommentChar(value) match {
        case None                              => // here, we need to return the original string, user may want to use a comment (in bash for ex) as value
          Right(ConfigValueFactory.fromAnyRef(value))
        // root can be an object or an array
        case Some(c) if (c == '{' || c == '[') =>
          parseSerialisedValue(value)
        case _                                 => // it's a string that should be understood as a string
          Right(ConfigValueFactory.fromAnyRef(value))
      }
    }
  }

  /*
   * Specify case for parameters.
   * Parameters are not saved in json so are not able to use directly provided methods
   * to parse/serialise them.
   * We want to have what is stored in parameter be what would go on the right of a json ":", ie:
   * paramValue: """{"foo":"bar"}"""  for the string {"foo":"bar"}
   * and
   * paramValue: {"foo":"bar"}  for the json {"foo":"bar"}
   * If the value is not parsable (for ex: foo without quote) it's an error.
   * And we had the compat with 6.0 which is always a string.
   */
  implicit class GlobalParameterParsing(value: String)            {
    // this method never fails. If we don't force string but don't successfully
    // parse serialized value, we log error and parse as string
    def parseGlobalParameter(name: String, forceString: Boolean): ConfigValue = {
      if (forceString) ConfigValueFactory.fromAnyRef(value)
      else {
        parseSerialisedValue(value) match {
          case Right(res) => res
          case Left(err)  =>
            ApplicationLogger.warn(
              s"Error when parsing global parameter '${name}' value from base, please update that value. It " +
              s"may be a bug, please report it. Serialized value: \n  ${value}\n  Error was: ${err.fullMsg}"
            )
            ConfigValueFactory.fromAnyRef(value)
        }
      }
    }
  }
  implicit class GlobalParameterSerialisation(value: ConfigValue) {
    // we keep quotes here
    def serializeGlobalParameter: String = value.render(ConfigRenderOptions.concise().setComments(true))
  }

  /**
   * Parse a JSON JValue to ConfigValue. It always succeeds.
   */
  def fromJsonValue(value: JValue):          ConfigValue = {
    import scala.jdk.CollectionConverters.*
    value match {
      case JNothing | JNull => ConfigValueFactory.fromAnyRef("")
      case JString(s)       => ConfigValueFactory.fromAnyRef(s)
      case JDouble(d)       => ConfigValueFactory.fromAnyRef(d)
      case JInt(num)        => ConfigValueFactory.fromAnyRef(num)
      case JBool(b)         => ConfigValueFactory.fromAnyRef(b)
      case JObject(arr)     => {
        val m = new java.util.HashMap[String, ConfigValue]()
        arr.foreach(f => m.put(f.name, fromJsonValue(f.value)))
        ConfigValueFactory.fromMap(m)
      }
      case JArray(arr)      => ConfigValueFactory.fromIterable(arr.map(x => fromJsonValue(x)).asJava)
    }
  }
  def fromZioJson(value: zio.json.ast.Json): ConfigValue = {
    import zio.json.ast.Json.*

    import scala.jdk.CollectionConverters.*
    value match {
      case Null     => ConfigValueFactory.fromAnyRef("")
      case Str(s)   =>
        ConfigValueFactory.fromAnyRef(s)
      case Num(num) =>
        // check if we need to use long or double
        // yes, that may be not exact, but config only support double.
        // We really need one `fromAnyRef` for each case to have the correct type
        try {
          ConfigValueFactory.fromAnyRef(num.longValueExact())
        } catch {
          case ex: ArithmeticException =>
            ConfigValueFactory.fromAnyRef(num.doubleValue())
        }
      case Bool(b)  => ConfigValueFactory.fromAnyRef(b)
      case Obj(arr) => {
        // key insertion order is not kept, no need to try to use LinkedHashMap
        val m = new java.util.HashMap[String, ConfigValue]()
        arr.foreach(f => m.put(f._1, fromZioJson(f._2)))
        ConfigValueFactory.fromMap(m)
      }
      case Arr(arr) => ConfigValueFactory.fromIterable(arr.map(x => fromZioJson(x)).asJava)
    }
  }

  def toJsonValue(value: ConfigValue): JValue = {
    value.valueType() match {
      case ConfigValueType.NULL    => JNothing
      case ConfigValueType.BOOLEAN => JBool(value.unwrapped().asInstanceOf[Boolean])
      case ConfigValueType.NUMBER  =>
        value.unwrapped() match {
          case f: java.lang.Float   => JDouble(f.doubleValue())
          case d: java.lang.Double  => JDouble(d)
          case i: java.lang.Integer => JInt(BigInt(i))
          case l: java.lang.Long    => JInt(BigInt(l))
          case error =>
            throw new IllegalArgumentException(
              s"Error with config value '${value}': it says it is a NUMBER but it is: ${value.unwrapped()}. Please report the bug."
            )
        }
      case ConfigValueType.STRING  => JString(value.unwrapped().asInstanceOf[String])
      // the only safe and compatible way for array/object seems to be to render and then parse
      case _                       => parse(value.render(ConfigRenderOptions.concise()))
    }
  }

  /**
   * Parse a name, provider, description and value as a config object. It can fail if `value` doesn't fulfill our requirements:
   * either starts by a `{` and is well formatted hocon property string, or is a string.
   */
  def parseConfig(
      name:        String,
      rev:         Revision,
      value:       String,
      mode:        Option[InheritMode],
      provider:    Option[PropertyProvider],
      description: Option[String],
      options:     ConfigParseOptions = ConfigParseOptions.defaults()
  ): PureResult[Config] = {
    parseValue(value).map(v => toConfig(name, rev, v, mode, provider, description, options))
  }

  /**
   * Transform a name, provider, description, and value (as a ConfigValue) into a config.
   */
  def toConfig(
      name:        String,
      rev:         Revision,
      value:       ConfigValue,
      mode:        Option[InheritMode],
      provider:    Option[PropertyProvider],
      description: Option[String],
      options:     ConfigParseOptions = ConfigParseOptions.defaults()
  ): Config = {
    val m = new java.util.HashMap[String, ConfigValue]()
    m.put(NAME, ConfigValueFactory.fromAnyRef(name))
    rev match {
      case GitVersion.DEFAULT_REV => // nothing
      case x                      => m.put(REV_ID, ConfigValueFactory.fromAnyRef(x.value))
    }
    provider.foreach(x => m.put(PROVIDER, ConfigValueFactory.fromAnyRef(x.value)))
    description.foreach(x => m.put(DESCRIPTION, ConfigValueFactory.fromAnyRef(x)))
    m.put(VALUE, value)
    mode.foreach(x => m.put(INHERIT_MODE, ConfigValueFactory.fromAnyRef(x.value)))
    ConfigFactory.parseMap(m)
  }

  def valueToConfig(value: ConfigValue): Config = {
    ConfigFactory.empty().withValue(VALUE, value)
  }

  /**
   * Parse a string a hocon config object
   */
  def parseConfig(json: String): PureResult[Config] = {
    PureResult.attemptZIO(s"Error when parsing data as a property: ${json}") {
      val cfg = ConfigFactory.parseString(json)
      if (cfg.hasPath(NAME) && cfg.hasPath(VALUE)) Right(cfg)
      else Left(Inconsistency(s"Error when parsing data as a property: it misses required field 'name' or 'value': ${json}"))
    }
  }

  implicit class RenderProperty(val p: GenericProperty[?]) extends AnyVal {
    // get the json string for the property, what you likely want
    def valueAsString:      String = GenericProperty.serializeToJson(p.value)
    // get the Hocon string, with comments if any
    def valueAsDebugString: String = GenericProperty.serializeToHocon(p.value)
    // get value as a JValue
    def jsonValue:          JValue = toJsonValue(p.value)
  }

  /*
   * Implicit class to change values to ConfigValue
   */
  implicit class StringToConfigValue(val x: String)           extends AnyVal {
    def toConfigValue: ConfigValue = ConfigValueFactory.fromAnyRef(x)
  }
  implicit class AnyValToConfigValue[T <: AnyVal](val x: T)   extends AnyVal {
    def toConfigValue: ConfigValue = ConfigValueFactory.fromAnyRef(x)
  }
  implicit class BigIntToConfigValue(val x: BigInt)           extends AnyVal {
    def toConfigValue: ConfigValue = ConfigValueFactory.fromAnyRef(x)
  }
  implicit class InheritModeToConfigValue(val x: InheritMode) extends AnyVal {
    def toConfigValue: ConfigValue = ConfigValueFactory.fromAnyRef(x.value)
  }
  implicit class IterableToConfig[T](val x: Iterable[T])      extends AnyVal {
    import scala.jdk.CollectionConverters.*
    def toConfigValue: ConfigValue = ConfigValueFactory.fromIterable(x.asJava)
  }
  implicit class MapToConfig[T](val x: Map[String, T])        extends AnyVal {
    import scala.jdk.CollectionConverters.*
    def toConfigValue: ConfigValue = ConfigValueFactory.fromMap(x.asJava)
  }

  /*
   * Implicit class to render properties to JSON
   */
  implicit class PropertyToJson(val x: GenericProperty[?]) extends AnyVal {
    def toJson: JObject = (
      ("name"            -> x.name)
        ~ ("value"       -> parse(x.value.render(ConfigRenderOptions.concise())))
        ~ ("provider"    -> x.provider.map(_.value))
        ~ ("inheritMode" -> x.inheritMode.map(_.value))
    )

    def toData: String = x.config.root().render(ConfigRenderOptions.concise().setComments(true))
  }

  implicit class JsonProperties(val props: Seq[GenericProperty[?]]) extends AnyVal {
    implicit def formats: DefaultFormats.type = DefaultFormats

    def toApiJson: JArray = {
      JArray(props.map(_.toJson).toList)
    }

    def toDataJson: JObject = {
      props.map(x => JField(x.name, x.jsonValue)).toList.sortBy(_.name)
    }
  }
}

/**
 * A node property is a key/value pair + metadata.
 * For now, only metadata available is:
 * - the provider of the property. By default Rudder.
 *
 * Only the provider of a property can modify it.
 */
final case class NodeProperty(config: Config) extends GenericProperty[NodeProperty] {
  override def fromConfig(c: Config): NodeProperty = NodeProperty(c)
}

object NodeProperty {

  // the provider that manages inventory custom properties
  val customPropertyProvider: PropertyProvider = PropertyProvider("inventory")

  /**
   * A builder with the logic to handle the value part.
   *
   * For compatibity reason, we want to be able to process
   * empty (JNothing) and primitive types, especially string, specificaly as
   * a JString *but* a string representing an actual JSON should be
   * used as json.
   */
  def parse(
      name:     String,
      value:    String,
      mode:     Option[InheritMode],
      provider: Option[PropertyProvider]
  ): PureResult[NodeProperty] = {
    GenericProperty.parseConfig(name, GitVersion.DEFAULT_REV, value, mode, provider, None).map(c => new NodeProperty(c))
  }
  def apply(name: String, value: ConfigValue, mode: Option[InheritMode], provider: Option[PropertyProvider]): NodeProperty = {
    new NodeProperty(GenericProperty.toConfig(name, GitVersion.DEFAULT_REV, value, mode, provider, None))
  }

  def unserializeLdapNodeProperty(json: String): PureResult[NodeProperty] = {
    GenericProperty.parseConfig(json).map(new NodeProperty(_))
  }

  def fromInventory(prop: CustomProperty): NodeProperty =
    apply(prop.name, GenericProperty.fromJsonValue(prop.value), None, Some(NodeProperty.customPropertyProvider))
}

final case class GroupProperty(config: Config) extends GenericProperty[GroupProperty] {
  override def fromConfig(c: Config): GroupProperty = GroupProperty(c)
}

object GroupProperty {

  /**
   * A builder with the logic to handle the value part.
   *
   * For compatibity reason, we want to be able to process
   * empty (JNothing) and primitive types, especially string, specificaly as
   * a JString *but* a string representing an actual JSON should be
   * used as json.
   */
  def parse(
      name:     String,
      rev:      Revision,
      value:    String,
      mode:     Option[InheritMode],
      provider: Option[PropertyProvider]
  ): PureResult[GroupProperty] = {
    GenericProperty.parseConfig(name, rev, value, mode, provider, None).map(c => new GroupProperty(c))
  }
  def apply(
      name:     String,
      rev:      Revision,
      value:    ConfigValue,
      mode:     Option[InheritMode],
      provider: Option[PropertyProvider]
  ): GroupProperty = {
    new GroupProperty(GenericProperty.toConfig(name, rev, value, mode, provider, None))
  }

  def unserializeLdapGroupProperty(json: String): PureResult[GroupProperty] = {
    GenericProperty.parseConfig(json).map(new GroupProperty(_))
  }
}

object CompareProperties {
  import cats.implicits.*

  /**
   * Update a set of properties with the map:
   * - if a key of the map matches a property name,
   *   use the map value for the key as value for
   *   the property
   * - if the value is the emtpy string, remove
   *   the property
   *
   * Each time, we have to check the provider of the update to see if it's compatible.
   * Node that in read-write mode, the provider is the last who wrote the property.
   *
   * A "none" provider actually means Rudder system one.
   */
  def updateProperties[P <: GenericProperty[P]](oldProps: List[P], optNewProps: Option[List[P]]): PureResult[List[P]] = {

    // check if the prop should be removed or updated
    def isDelete(prop: P): Boolean = (
      !prop.config.hasPath(GenericProperty.VALUE)
        || prop.value.valueType() == ConfigValueType.NULL
        || (prop.value.valueType() == ConfigValueType.STRING && prop.value.unwrapped().asInstanceOf[String] == "")
    )

    optNewProps match {
      case None           => Right(oldProps)
      case Some(newProps) =>
        val oldPropsMap = oldProps.map(p => (p.name, p)).toMap

        // update only according to rights - we get a seq of option[either[remove, update]]
        for {
          updated <- newProps.toList.traverse { newProp =>
                       oldPropsMap.get(newProp.name) match {
                         case None          =>
                           if (isDelete(newProp)) {
                             Right(Left(newProp.name))
                           } else {
                             Right(Right(newProp))
                           }
                         case Some(oldProp) =>
                           if (GenericProperty.canBeUpdated(old = oldProp.provider, newer = newProp.provider)) {
                             if (isDelete(newProp)) {
                               Right(Left(newProp.name))
                             } else {
                               Right(Right(newProp))
                             }
                           } else {
                             val old     = oldProp.provider.getOrElse(PropertyProvider.defaultPropertyProvider).value
                             val current = newProp.provider.getOrElse(PropertyProvider.defaultPropertyProvider).value
                             Left(
                               Inconsistency(
                                 s"You can not update property '${oldProp.name}' which is owned by provider '${old}' thanks to provider '${current}'"
                               )
                             )
                           }
                       }
                     }
        } yield {
          val toRemove = updated.collect { case Left(name) => name }.toSet
          val toUpdate = updated.collect { case Right(prop) => (prop.name, prop) }.toMap
          // merge properties
          (oldPropsMap.view.filterKeys(k => !toRemove.contains(k)).toMap ++ toUpdate).map(_._2).toList
        }
    }
  }

}

/*
 * A node property with all its inheritance context.
 * - key / provider
 * - resulting value on node
 * - list of diff:
 *   - name of the diff provider: group/target name, global parameter
 */
sealed trait ParentProperty {
  def displayName: String // human readable information about the parent providing prop
  def value:       ConfigValue
}

/**
 * A node property with its ohneritance/overriding context.
 */
final case class NodePropertyHierarchy(prop: NodeProperty, hierarchy: List[ParentProperty])

object ParentProperty {
  final case class Node(name: String, id: NodeId, value: ConfigValue)       extends ParentProperty {
    override def displayName: String = s"${name} (${id.value})"
  }
  final case class Group(name: String, id: NodeGroupId, value: ConfigValue) extends ParentProperty {
    override def displayName: String = s"${name} (${id.serialize})"
  }
  // a global parameter has the same name as property so no need to be specific for name
  final case class Global(value: ConfigValue)                               extends ParentProperty {
    val displayName = "Global Parameter"
  }

  // The hierarchy of node properties from top to bottom : Global, Group, Node
  implicit val ordering: Ordering[ParentProperty] = new Ordering[ParentProperty] {
    override def compare(x: ParentProperty, y: ParentProperty): Int = {
      (x, y) match {
        case (Global(_), Global(_))           => 0
        case (Global(_), _)                   => -1
        case (_, Global(_))                   => 1
        case (Group(_, _, _), Group(_, _, _)) => 0
        case (Group(_, _, _), _)              => -1
        case (_, Group(_, _, _))              => 1
        case (Node(_, _, _), Node(_, _, _))   => 0
      }
    }
  }
}

/**
 * The part dealing with JsonSerialisation of node related
 * attributes (especially properties) and parameters
 */
object JsonPropertySerialisation {

  import net.liftweb.json.*
  import net.liftweb.json.JsonDSL.*

  implicit class ParentPropertyToJSon(val p: ParentProperty) extends AnyVal {
    def toJson: JValue = {
      p match {
        case ParentProperty.Global(value)          =>
          (
            ("kind"    -> "global")
            ~ ("value" -> GenericProperty.toJsonValue(value))
          )
        case ParentProperty.Group(name, id, value) =>
          (
            ("kind"    -> "group")
            ~ ("name"  -> name)
            ~ ("id"    -> id.serialize)
            ~ ("value" -> GenericProperty.toJsonValue(value))
          )
        case _                                     => JNothing
      }
    }
  }

  implicit class JsonNodePropertyHierarchy(val prop: NodePropertyHierarchy) extends AnyVal {
    implicit def formats: DefaultFormats.type = DefaultFormats

    private def buildHierarchy(displayParents: List[ParentProperty] => JValue): JObject = {
      val (parents, origval) = prop.hierarchy match {
        case Nil  => (None, None)
        case list =>
          (
            Some(displayParents(list)),
            prop.hierarchy.headOption.map(v => GenericProperty.toJsonValue(v.value))
          )
      }

      prop.prop.toJson ~ ("hierarchy" -> parents) ~ ("origval" -> origval)

    }

    def toApiJson: JObject = {
      buildHierarchy(list => list.reverse.map(_.toJson))
    }

    def toApiJsonRenderParents: JObject = {
      buildHierarchy(list => {
        list.reverse
          .map(p => {
            s"<p>from <b>${p.displayName}</b>:<pre>${xml.Utility
                .escape(p.value.render(ConfigRenderOptions.defaults().setOriginComments(false)))}</pre></p>"
          })
          .mkString("")
      })
    }

  }

  implicit class JsonNodePropertiesHierarchy(val props: List[NodePropertyHierarchy]) extends AnyVal {
    implicit def formats: DefaultFormats.type = DefaultFormats

    def toApiJson: JArray = {
      JArray(props.sortBy(_.prop.name).map(p => p.toApiJson))
    }

    def toApiJsonRenderParents: JArray = {
      JArray(props.sortBy(_.prop.name).map(p => p.toApiJsonRenderParents))
    }

  }

  implicit class JsonParameter(val x: ParameterEntry) extends AnyVal {
    def toJson: JObject = (
      ("name"      -> x.parameterName)
        ~ ("value" -> x.escapedValue)
    )
  }

  implicit class JsonParameters(val parameters: Set[ParameterEntry]) extends AnyVal {
    implicit def formats: DefaultFormats.type = DefaultFormats

    def dataJson(x: ParameterEntry): JField = {
      JField(x.parameterName, x.escapedValue)
    }

    def toDataJson: JObject = {
      parameters.map(dataJson(_)).toList.sortBy(_.name)
    }
  }

}

/**
 * A Global Parameter is a parameter globally defined, that may be overriden
 */
final case class GlobalParameter(config: Config) extends GenericProperty[GlobalParameter] {
  override def fromConfig(c: Config): GlobalParameter = GlobalParameter(c)
}

object GlobalParameter {

  /**
   * A builder with the logic to handle the value part.
   *,
   * For compatibity reason, we want to be able to process
   * empty (JNothing) and primitive types, especially string, specificaly as
   * a JString *but* a string representing an actual JSON should be
   * used as json.
   */
  def parse(
      name:        String,
      rev:         Revision,
      value:       String,
      mode:        Option[InheritMode],
      description: String,
      provider:    Option[PropertyProvider]
  ): PureResult[GlobalParameter] = {
    GenericProperty.parseConfig(name, rev, value, mode, provider, Some(description)).map(c => new GlobalParameter(c))
  }
  def apply(
      name:        String,
      rev:         Revision,
      value:       ConfigValue,
      mode:        Option[InheritMode],
      description: String,
      provider:    Option[PropertyProvider]
  ): GlobalParameter = {
    new GlobalParameter(GenericProperty.toConfig(name, rev, value, mode, provider, Some(description)))
  }

}
