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

package com.normation.rudder.domain.nodes

import java.util.regex.Pattern

import com.normation.errors._
import com.normation.rudder.services.policies.ParameterEntry
import com.typesafe.config._
import net.liftweb.json._
import net.liftweb.json.JsonDSL._

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

object PropertyProvider {
  /*
   * System property provider. These properties should never
   * be updated/deleted by things other than rudder.
   */
  final val systemPropertyProvider = PropertyProvider("system")
  final val defaultPropertyProvider = PropertyProvider("default")
}


/*
 * A property is backed by an HOCON config that MUST looks like:
 * { "name": name, "value": value, "provider": "default", "description":"..." }
 * Any other format will lead to errors.
 * Only the name is mandatory, and it is alway escapped as a string.
 * Description, if not provided will return an empty string. Anything else than a string will lead to an error.
 * Provider, if not provided will return None
 * This trait provides update methods for generic processing (since we don't have case class compiler support).
 */
trait GenericProperty[P <: GenericProperty[_]] {
  import GenericProperty._

  def config  : Config
  def fromConfig(v: Config): P

  final def name : String      = config.getString(NAME)
  final def value: ConfigValue = {
    if(config.hasPath(VALUE)) config.getValue(VALUE)
    else ConfigValueFactory.fromAnyRef("")
  }

  final def provider: Option[PropertyProvider] = {
    // optional, default "rudder"
    if(config.hasPath(PROVIDER)) Some(PropertyProvider(config.getString(PROVIDER)))
    else None
  }

  final def description: String = {
    if(config.hasPath(GenericProperty.DESCRIPTION)) config.getString(GenericProperty.DESCRIPTION)
    else ""
  }

  final def withName(name: String): P             = fromConfig(config.withValue(GenericProperty.NAME, name.toConfigValue))
  final def withValue(value: ConfigValue): P      = fromConfig(config.withValue(GenericProperty.VALUE, value))
  final def withValue(value: String): P           = fromConfig(config.withValue(GenericProperty.VALUE, value.toConfigValue))
  final def withProvider(p: PropertyProvider): P  = fromConfig(config.withValue(GenericProperty.PROVIDER, p.value.toConfigValue))
  final def withDescription(d: String): P         = fromConfig(config.withValue(GenericProperty.DESCRIPTION, d.toConfigValue))

  override def toString: String = this.getClass.getSimpleName+"("+this.config.root.render(ConfigRenderOptions.defaults())+")"
}

object GenericProperty {

  val VALUE       = "value"
  val NAME        = "name"
  val PROVIDER    = "provider"
  val DESCRIPTION = "description"

  /**
   * Property name must matches that pattern
   */
  val patternName = Pattern.compile("""[\-a-zA-Z0-9_]+""")

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
    if(newer == Some(PropertyProvider.systemPropertyProvider)) {
      true
    } else old match {
      case None =>
        true
      case Some(p1) if p1 == PropertyProvider.defaultPropertyProvider =>
        true
      case Some(p1) =>
        val res = p1 == newer.getOrElse(PropertyProvider.defaultPropertyProvider)
        res
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
    if(value.valueType() == ConfigValueType.STRING) {
      value.unwrapped().toString
    } else {
      value.render(option)
    }
  }

  /*
   * Merge two json values, overriding or merging recursively
   */
  def mergeValues(oldValue: ConfigValue, newValue: ConfigValue): ConfigValue = {
    newValue.withFallback(oldValue)
  }

  /**
   * Merge two properties. newProp values will win.
   * You should have check before that "name" and "provider" are OK.
   */
  def mergeConfig(oldProp: Config, newProp: Config): Config = {
    newProp.withFallback(oldProp)
  }

  /**
   * Find the first non comment char in a (multiline) string. In our convention, an
   * hocon string must starts (excluded comments and whitespaces) by a '{'.
   */
  @scala.annotation.tailrec
  def firstNonCommentChar(s: String): Option[Char] = {
    val trim = s.dropWhile(c => c.isWhitespace || c.isSpaceChar)
    if(trim.isEmpty) None
    else {
      if(trim.startsWith("#") || trim.startsWith("//")) {
        firstNonCommentChar(trim.dropWhile(_ != '\n'))
      } else { // trim is non empty
        Some(trim.charAt(0))
      }
    }
  }

  /**
   * Contrary to native hocon, we only have TWO kinds of values:
   * - object, which are mandatory to start with a '{' and end by a '}'
   *   (in middle, it's whatever hocon authorize: comments, etc. But the structure must be key/value structured)
   * - strings, which must not start by '{' (you must escape it with \ if so). <- check that. Is it indempotent regarding serialisation?
   *   String can't have comments or anything, they are taken "as is".
   */
  def parseValue(value: String): PureResult[ConfigValue] = {
    // find first char that is no in a commented line nor a space

    if(value == "") Right(ConfigValueFactory.fromAnyRef(""))
    else firstNonCommentChar(value) match {
      case None => // here, either we return empty string, or the orginal one. I thing we should return empty string, since user can quote if he wants.case _: scala.None.type =>
        Right(ConfigValueFactory.fromAnyRef(""))
      case Some(c) if(c == '{') =>
        PureResult.effect(s"Error: value is not parsable as a property: ${value}") {
          ConfigFactory.parseString(
            // it's necessary to put it on its own line to avoid pb with comments/multilines
            s"""{"x":
                ${value}
                }""").getValue("x")
        }
      case _ => // it's a string that should be understood as a string
        Right(ConfigValueFactory.fromAnyRef(value))
    }
  }

  /**
   * Parse a JSON JValue to ConfigValue. It always succeeds.
   */
  def fromJsonValue(value: JValue): ConfigValue = {
    import scala.jdk.CollectionConverters._
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

  /**
   * Parse a name, provider, description and value as a config object. It can fail if `value` doesn't fulfill our requirements:
   * either starts by a `{` and is well formatted hocon property string, or is a string.
   */
  def parseConfig(name: String, value: String, provider: Option[PropertyProvider], description: Option[String], options: ConfigParseOptions = ConfigParseOptions.defaults()): PureResult[Config] = {
    parseValue(value).flatMap { v =>
      val m = new java.util.HashMap[String, ConfigValue]()
      m.put(NAME, ConfigValueFactory.fromAnyRef(name))
      provider.foreach(x => m.put(PROVIDER, ConfigValueFactory.fromAnyRef(x.value)))
      description.foreach(x => m.put(DESCRIPTION, ConfigValueFactory.fromAnyRef(x)))
      m.put(VALUE, v)
      PureResult.effect(ConfigFactory.parseMap(m))
    }
  }

  /**
   * Transform a name, provider, description, and value (as a ConfigValue) into a config.
   */
  def toConfig(name: String, value: ConfigValue, provider: Option[PropertyProvider], description: Option[String], options: ConfigParseOptions = ConfigParseOptions.defaults()): Config = {
    val m = new java.util.HashMap[String, ConfigValue]()
    m.put(NAME, ConfigValueFactory.fromAnyRef(name))
    provider.foreach(x => m.put(PROVIDER, ConfigValueFactory.fromAnyRef(x.value)))
    description.foreach(x => m.put(DESCRIPTION, ConfigValueFactory.fromAnyRef(x)))
    m.put(VALUE, value)
    ConfigFactory.parseMap(m)
  }


  def valueToConfig(value: ConfigValue): Config = {
    ConfigFactory.empty().withValue(VALUE, value)
  }

  /**
   * Parse a string a hocon config object
   */
  def parseConfig(json: String): PureResult[Config] = {
    PureResult.effectM(s"Error when parsing data as a property: ${json}") {
      val cfg = ConfigFactory.parseString(json)
      if(cfg.hasPath(NAME) && cfg.hasPath(VALUE)) Right(cfg)
      else Left(Inconsistency(s"Error when parsing data as a property: it misses required field 'name' or 'value': ${json}"))
    }
  }

  implicit class RenderProperty(val p: GenericProperty[_]) extends AnyVal {
    def valueAsString: String = GenericProperty.serializeToHocon(p.value)
    // get value as a JValue
    def jsonValue: JValue = {
      p.value.valueType() match {
        case ConfigValueType.NULL    => JNothing
        case ConfigValueType.BOOLEAN => JBool(p.value.unwrapped().asInstanceOf[Boolean])
        case ConfigValueType.NUMBER  => p.value.unwrapped() match {
          case d: java.lang.Double   => JDouble(d)
          case i: java.lang.Integer  => JInt(BigInt(i))
          case l: java.lang.Long     => JInt(BigInt(l))
        }
        case ConfigValueType.STRING  => JString(p.value.unwrapped().asInstanceOf[String])
        // the only safe and compatible way for array/object seems to be to render and then parse
        case _    => parse(p.value.render(ConfigRenderOptions.concise()))
      }
    }
  }

  /*
   * Implicit class to change values to ConfigValue
   */
  implicit class StringToConfigValue(val x: String) extends AnyVal {
    def toConfigValue: ConfigValue = ConfigValueFactory.fromAnyRef(x)
  }
  implicit class AnyValToConfigValue[T <: AnyVal](val x: T) extends AnyVal {
    def toConfigValue: ConfigValue = ConfigValueFactory.fromAnyRef(x)
  }
  implicit class BitIntToConfigValue(val x: BigInt) extends AnyVal {
    def toConfigValue: ConfigValue = ConfigValueFactory.fromAnyRef(x)
  }
  implicit class IterableToConfig[T](val x: Iterable[T]) extends AnyVal {
    import scala.jdk.CollectionConverters._
    def toConfigValue: ConfigValue = ConfigValueFactory.fromIterable(x.asJava)
  }
  implicit class MapToConfig[T](val x: Map[String, T]) extends AnyVal {
    import scala.jdk.CollectionConverters._
    def toConfigValue: ConfigValue = ConfigValueFactory.fromMap(x.asJava)
  }

  /*
   * Implicit class to render properties to JSON
   */
  implicit class PropertyToJson(val x: GenericProperty[_]) extends AnyVal {
    def toJson(): JObject = (
        ( "name"     -> x.name  )
      ~ ( "value"    -> parse(x.value.render(ConfigRenderOptions.concise()) ) )
      ~ ( "provider" -> x.provider.map(_.value) )
    )

    def toData: String = x.config.root().render(ConfigRenderOptions.concise().setComments(true))
  }

  implicit class JsonProperties(val props: Seq[GenericProperty[_]]) extends AnyVal {
    implicit def formats = DefaultFormats

    def toApiJson(): JArray = {
      JArray(props.map(_.toJson()).toList)
    }

    def toDataJson(): JObject = {
      props.map(x => JField(x.name, x.jsonValue)).toList.sortBy { _.name }
    }
  }
}

/**
 * A node property is a key/value pair + metadata.
 * For now, only metadata availables is:
 * - the provider of the property. By default Rudder.
 *
 * Only the provider of a property can modify it.
 */
final case class NodeProperty(config: Config) extends GenericProperty[NodeProperty] {
  override def fromConfig(c: Config) = NodeProperty(c)
}

object NodeProperty {

  // the provider that manages inventory custom properties
  val customPropertyProvider = PropertyProvider("inventory")

  /**
   * A builder with the logic to handle the value part.
   *
   * For compatibity reason, we want to be able to process
   * empty (JNothing) and primitive types, especially string, specificaly as
   * a JString *but* a string representing an actual JSON should be
   * used as json.
   */
  def parse(name: String, value: String, provider: Option[PropertyProvider]): PureResult[NodeProperty] = {
    GenericProperty.parseConfig(name, value, provider, None).map(c => new NodeProperty(c))
  }
  def apply(name: String, value: ConfigValue, provider: Option[PropertyProvider]): NodeProperty = {
    new NodeProperty(GenericProperty.toConfig(name, value, provider, None))
  }

  def unserializeLdapNodeProperty(json: String): PureResult[NodeProperty] = {
    GenericProperty.parseConfig(json).map(new NodeProperty(_))
  }
}


final case class GroupProperty(config: Config) extends GenericProperty[GroupProperty] {
  override def fromConfig(c: Config) = GroupProperty(c)
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
  def parse(name: String, value: String, provider: Option[PropertyProvider]): PureResult[GroupProperty] = {
    GenericProperty.parseConfig(name, value, provider, None).map(c => new GroupProperty(c))
  }
  def apply(name: String, value: ConfigValue, provider: Option[PropertyProvider]): GroupProperty = {
    new GroupProperty(GenericProperty.toConfig(name, value, provider, None))
  }

  def unserializeLdapGroupProperty(json: String): PureResult[GroupProperty] = {
    GenericProperty.parseConfig(json).map(new GroupProperty(_))
  }
}


object CompareProperties {
  import cats.implicits._

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


    //check if the prop should be removed or updated
    def isDelete(prop: P): Boolean = (
         !prop.config.hasPath(GenericProperty.VALUE)
      ||  prop.value.valueType() == ConfigValueType.NULL
      || (prop.value.valueType() == ConfigValueType.STRING && prop.value.unwrapped().asInstanceOf[String] == "")
    )

    optNewProps match {
      case None => Right(oldProps)
      case Some(newProps) =>
        val oldPropsMap = oldProps.map(p => (p.name, p)).toMap

        //update only according to rights - we get a seq of option[either[remove, update]]
        for {
          updated <- newProps.toList.traverse { newProp =>
                       oldPropsMap.get(newProp.name) match {
                         case None =>
                           if(isDelete(newProp)) {
                             Right(Left(newProp.name))
                           } else {
                             Right(Right(newProp))
                           }
                         case Some(oldProp) =>
                           if(GenericProperty.canBeUpdated(old = oldProp.provider, newer = newProp.provider)) {
                             if(isDelete(newProp)) {
                               Right(Left(newProp.name))
                             } else {
                               Right(Right(newProp.fromConfig(GenericProperty.mergeConfig(oldProp.config, newProp.config))))
                             }
                           } else {
                             val old = oldProp.provider.getOrElse(PropertyProvider.defaultPropertyProvider).value
                             val current = newProp.provider.getOrElse(PropertyProvider.defaultPropertyProvider).value
                             Left(Inconsistency(s"You can not update property '${oldProp.name}' which is owned by provider '${old}' thanks to provider '${current}'"))
                           }
                       }
        }
        } yield {
          val toRemove = updated.collect { case Left(name)  => name }.toSet
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
sealed trait ParentProperty[A] {
  def displayName: String // human readable information about the parent providing prop
  def value      : A
}

/**
 * A node property with its ohneritance/overriding context.
 */
final case class NodePropertyHierarchy[A](prop: NodeProperty,  parents: List[ParentProperty[A]])

sealed trait FullParentProperty extends ParentProperty[ConfigValue]
object FullParentProperty {
  final case class Group(name: String, id: NodeGroupId, value: ConfigValue) extends FullParentProperty {
    override def displayName: String = s"${name} (${id.value})"
  }
  // a global parameter has the same name as property so no need to be specific for name
  final case class Global(value: ConfigValue) extends FullParentProperty {
    val displayName = "Global Parameter"
  }
}

/**
 * The part dealing with JsonSerialisation of node related
 * attributes (especially properties) and parameters
 */
object JsonSerialisation {

  import net.liftweb.json.JsonDSL._
  import net.liftweb.json._

  implicit class FullParentPropertyToJSon(val p: ParentProperty[ConfigValue]) extends AnyVal {
    def toJson = {
      p match {
        case FullParentProperty.Global(value) =>
          (
            ( "kind"  -> "global")
          ~ ("value" -> GenericProperty.serializeToJson(value))
          )
        case FullParentProperty.Group(name, id, value) =>
          (
            ( "kind"  -> "group" )
          ~ ( "name"  -> name    )
          ~ ( "id"    -> id.value)
          ~ ("value" -> GenericProperty.serializeToJson(value))
          )
        case _ => JNothing
      }
    }
  }

  implicit class JsonNodePropertiesHierarchy(val props: List[NodePropertyHierarchy[ConfigValue]]) extends AnyVal {
    implicit def formats = DefaultFormats

    def toApiJson(): JArray = {
      JArray(props.sortBy(_.prop.name).map { p =>
        p.parents match {
          case Nil  => p.prop.toJson()
          case list => JObject(p.prop.toJson().obj :+ JField("parents", JArray(list.map(_.toJson))))
        }
      })
    }
  }

  implicit class JsonParameter(val x: ParameterEntry) extends AnyVal {
    def toJson(): JObject = (
        ( "name"     -> x.parameterName )
      ~ ( "value"    -> x.escapedValue  )
    )
  }

  implicit class JsonParameters(val parameters: Set[ParameterEntry]) extends AnyVal {
    implicit def formats = DefaultFormats

    def dataJson(x: ParameterEntry) : JField = {
      JField(x.parameterName, x.escapedValue)
    }

    def toDataJson(): JObject = {
      parameters.map(dataJson(_)).toList.sortBy { _.name }
    }
  }

}


