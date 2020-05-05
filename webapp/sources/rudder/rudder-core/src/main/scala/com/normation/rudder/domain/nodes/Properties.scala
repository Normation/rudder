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
import net.liftweb.json._
import net.liftweb.json.JsonDSL._
import net.liftweb.json.JsonParser.ParseException

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

object GenericPropertyUtils {
  import net.liftweb.json.JsonAST.JNothing
  import net.liftweb.json.JsonAST.JString
  import net.liftweb.json.{parse => jsonParse}



  /**
   * Property name must matches that pattern
   */
  val patternName = Pattern.compile("""[\-a-zA-Z0-9_]+""")


  /*
   * System property provider. These properties should never
   * be updated/deleted by things other than rudder.
   */
  final val systemPropertyProvider = PropertyProvider("system")
  final val defaultPropertyProvider = PropertyProvider("default")


  /*
   * Check if old property can be updated by a new one matching its providers.
   * The rules are:
   * - system provider has always the right to update,
   * - else default provider has always the right to be updated,
   * - else providers must match (there is no notion of ordering on other providers).
   *
   * Sum up: system > all other providers > none|defaults
   */
  def canBeUpdated(old: Option[PropertyProvider], newer: Option[PropertyProvider]): Boolean = {
    if(newer == Some(systemPropertyProvider)) true
    else old match {
      case None | Some(`defaultPropertyProvider`) =>
        true
      case Some(p1) =>
        p1 == newer.getOrElse(defaultPropertyProvider)
    }
  }

  /**
   * Parse a value that can be a string or some json.
   */
  def parseValue(value: String): JValue = {
    try {
      jsonParse(value) match {
        case JNothing => JString("")
        case json     => json
      }
    } catch {
      case ex: ParseException =>
        // in that case, we didn't had a valid json top-level structure,
        // i.e either object or array. Use a JString with the content
        JString(value)
    }
  }

  /**
   * Write back a value as a string. There is
   * some care to take, because simple jvalue (string, boolean, etc)
   * must be written directly as string without quote.
   */
  def serializeValue(value: JValue): String = {
    value match {
      case JNothing | JNull => ""
      case JString(s)       => s
      case JBool(v)         => v.toString
      case JDouble(v)       => v.toString
      case JInt(v)          => v.toString
      case json             => compactRender(json)
    }
  }

  /*
   * Merge two json values, overriding or merging recursively
   */
  def mergeValues(oldValue: JValue, newValue: JValue): JValue = {
    oldValue.merge(newValue)
  }
}



trait Property[P <: Property[_]] {
  def name    : String
  def value   : JValue
  def provider: Option[PropertyProvider] // optional, default "rudder"

  def setValue(v: JValue): P
}

object Property {
  implicit class RenderNodeProperty(val p: Property[_]) extends AnyVal {
    def renderValue: String = GenericPropertyUtils.serializeValue(p.value)
  }

  implicit class PropertyToJson(val x: Property[_]) extends AnyVal {
    def toJson(): JObject = (
        ( "name"     -> x.name  )
      ~ ( "value"    -> x.value )
      ~ ( "provider" -> x.provider.map(_.value) )
    )

    def toJsonString: String = {
      compactRender(toJson)
    }
  }

  implicit class JsonProperties(val props: Seq[Property[_]]) extends AnyVal {
    implicit def formats = DefaultFormats

    def dataJson(x: Property[_]) : JField = {
      JField(x.name, x.value)
    }

    def toApiJson(): JArray = {
      JArray(props.map(_.toJson()).toList)
    }

    def toDataJson(): JObject = {
      props.map(dataJson(_)).toList.sortBy { _.name }
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
final case class NodeProperty(
    name    : String
  , value   : JValue
  , provider: Option[PropertyProvider] // optional, default "rudder"
) extends Property[NodeProperty] {
  override def setValue(v: JValue) = NodeProperty(name, v, provider)
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
  def apply(name: String, value: String, provider: Option[PropertyProvider]): NodeProperty = {
    NodeProperty(name, GenericPropertyUtils.parseValue(value), provider)
  }

  def unserializeLdapNodeProperty(json: JValue): PureResult[NodeProperty] = {
    implicit val formats = DefaultFormats
    json.extractOpt[JsonProperty] match {
      case None    => Left(Inconsistency(s"Cannot parse node property from provided json: ${compactRender(json)}"))
      case Some(v) => Right(v.toNode)
    }
  }
}



final case class GroupProperty(
    name    : String
  , value   : JValue
  , provider: Option[PropertyProvider] // optional, default "rudder"
) extends Property[GroupProperty] {
  override def setValue(v: JValue) = GroupProperty(name, v, provider)
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
  def apply(name: String, value: String, provider: Option[PropertyProvider]): GroupProperty = {
    new GroupProperty(name, GenericPropertyUtils.parseValue(value), provider)
  }

  def unserializeLdapGroupProperty(json: JValue): PureResult[GroupProperty] = {
    implicit val formats = DefaultFormats
    json.extractOpt[JsonProperty] match {
      case None    => Left(Inconsistency(s"Cannot parse group property from provided json: ${compactRender(json)}"))
      case Some(v) => Right(v.toGroup)
    }
  }

  def parseSerializedGroupProperty(s: String): Either[RudderError, GroupProperty] = {
    try {
      unserializeLdapGroupProperty(parse(s))
    } catch {
      case ex: ParseException => Left(SystemError("Error when parsing serialized property", ex))
    }
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
  def updateProperties[P <: Property[P]](oldProps: List[P], optNewProps: Option[List[P]]): PureResult[List[P]] = {


    //check if the prop should be removed or updated
    def updateOrRemoveProp(oldValue:JValue, newProp: P): Either[String, P] = {
     if(newProp.value == JString("")) {
       Left(newProp.name)
     } else {
       Right(newProp.setValue(GenericPropertyUtils.mergeValues(oldValue, newProp.value)))
     }
    }

    optNewProps match {
      case None => Right(oldProps)
      case Some(newProps) =>
        val oldPropsMap = oldProps.map(p => (p.name, p)).toMap

        //update only according to rights - we get a seq of option[either[remove, update]]
        for {
          updated <- newProps.toList.traverse { newProp =>
                       oldPropsMap.get(newProp.name) match {
                         case None =>
                           Right(updateOrRemoveProp(JNothing, newProp))
                         case Some(oldProp) =>
                             if(GenericPropertyUtils.canBeUpdated(old = oldProp.provider, newer = newProp.provider)) {
                               Right(updateOrRemoveProp(oldProp.value, newProp))
                             } else {
                               val old = oldProp.provider.getOrElse(GenericPropertyUtils.defaultPropertyProvider).value
                               val current = newProp.provider.getOrElse(GenericPropertyUtils.defaultPropertyProvider).value
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

sealed trait FullParentProperty extends ParentProperty[JValue]
object FullParentProperty {
  final case class Group(name: String, id: NodeGroupId, value: JValue) extends FullParentProperty {
    override def displayName: String = s"${name} (${id.value})"
  }
  // a global parameter has the same name as property so no need to be specific for name
  final case class Global(value: JValue) extends FullParentProperty {
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

  implicit class FullParentPropertyToJSon(val p: ParentProperty[JValue]) extends AnyVal {
    def toJson = {
      p match {
        case FullParentProperty.Global(value) =>
          (
            ( "kind"  -> "global")
          ~ ( "value" -> value   )
          )
        case FullParentProperty.Group(name, id, value) =>
          (
            ( "kind"  -> "group" )
          ~ ( "name"  -> name    )
          ~ ( "id"    -> id.value)
          ~ ( "value" -> value   )
          )
        case _ => JNothing
      }
    }
  }

  implicit class JsonNodePropertiesHierarchy(val props: List[NodePropertyHierarchy[JValue]]) extends AnyVal {
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

/*
 * A simple class container for Json extraction. Lift need it top level
 */
final case class JsonProperty(name: String, value: JValue, provider: Option[String]) {
  def toNode  = new NodeProperty(name, value, provider.map(PropertyProvider))
  def toGroup = new GroupProperty(name, value, provider.map(PropertyProvider))
}
