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

import ca.mrvisser.sealerate


/**
 * This file contains domains objects for the quick search service.
 */

/*
 * A quick search query
 */
final case class Query(
   userToken  : String           // what the user want to search for
                                 // do we want to have "a b c" becomes "*a* or *b* or *b*" or "*a b c*" ? (second simpler)
 , objectClass: Set[QSObject]    // what object to look for ? If empty => result empty
 , attributes : Set[QSAttribute] // limit the search on which attributes ?
)

/**
 * A backend for quicksearch, i.e something that is producing quicksearch results
 */
sealed trait QSBackend
object QSBackend {
  final case object LdapBackend      extends QSBackend
  final case object DirectiveBackend extends QSBackend

  final val all: Set[QSBackend] = sealerate.values[QSBackend]
}

/*
 * List of attribute for each type of objects that can be query.
 * The name are the same that the corresponding name from
 * REST API - i.e, they are the name defined at the interface of
 * the system, the one that must be fixed in time (system API).
 *
 * This does not hold for directive, because we don't have a real variable
 * parameter
 */
sealed trait QSAttribute { def name: String }
object QSAttribute {

  //common
  final case object Name             extends QSAttribute { override val name = "displayName" }
  final case object Description      extends QSAttribute { override val name = "description" }
  final case object ShortDescription extends QSAttribute { override val name = "shortDescription" }
  final case object LongDescription  extends QSAttribute { override val name = "longDescription" }
  final case object IsEnabled        extends QSAttribute { override val name = "isEnabled" }

  //Nodes
  final case object NodeId          extends QSAttribute { override val name = "id" }
  final case object Fqdn            extends QSAttribute { override val name = "hostname" }
  final case object OsType          extends QSAttribute { override val name = "os.type" }
  final case object OsName          extends QSAttribute { override val name = "os.name" }
  final case object OsVersion       extends QSAttribute { override val name = "os.version" }
  final case object OsFullName      extends QSAttribute { override val name = "os.fullName" }
  final case object OsKernelVersion extends QSAttribute { override val name = "os.kernelVersion" }
  final case object OsServicePack   extends QSAttribute { override val name = "os.servicePack" }
  final case object Arch            extends QSAttribute { override val name = "architectureDescription" }
  final case object Ram             extends QSAttribute { override val name = "ram" }
  final case object IpAddresses     extends QSAttribute { override val name = "ipAddresses" }
  final case object PolicyServerId  extends QSAttribute { override val name = "policyServerId" }
  final case object Properties      extends QSAttribute { override val name = "properties" }
  final case object RudderRoles     extends QSAttribute { override val name = "rudderRoles" }

  //Groups
  final case object GroupId   extends QSAttribute { override val name = "id" }
  final case object IsDynamic extends QSAttribute { override val name = "isDynamic" }

  //Directives
  final case object DirectiveId       extends QSAttribute { override val name = "id" }
  final case object DirectiveVarName  extends QSAttribute { override val name = "var.name" }
  final case object DirectiveVarValue extends QSAttribute { override val name = "var.value" }
  final case object TechniqueName     extends QSAttribute { override val name = "techniqueName" }
  final case object TechniqueVersion  extends QSAttribute { override val name = "techniqueVersion" }

  //Parameters
  final case object ParameterName  extends QSAttribute { override val name = "id" }
  final case object ParameterValue extends QSAttribute { override val name = "value" }

  //Rules
  final case object RuleId       extends QSAttribute { override val name = "id" }
  final case object DirectiveIds extends QSAttribute { override val name = "directives" }
  final case object Targets      extends QSAttribute { override val name = "targets" }


  ////// all attributes /////
  final val all: Set[QSAttribute] = sealerate.values[QSAttribute]

}



/*
 * Objects on which we are able to perform
 * quicksearch
 */
sealed trait QSObject { def name: String; def attributes: Set[QSAttribute] }

object QSObject {
  import QSAttribute._
  private[this] val commons: Set[QSAttribute] = Set(Name, Description, ShortDescription, LongDescription, IsEnabled)

  final case object Node      extends QSObject { override val name = "node"
                                                 override val attributes = commons ++ Set(NodeId, Fqdn, OsType, OsName
                                                   , OsVersion, OsFullName, OsKernelVersion, OsServicePack, Arch, Ram
                                                   , IpAddresses, PolicyServerId, Properties, RudderRoles)
  }
  final case object Group     extends QSObject { override val name = "group"
                                                 override val attributes = commons ++ Set(GroupId, IsDynamic)
  }
  final case object Directive extends QSObject { override val name = "directive"
                                                 override val attributes = commons ++ Set(DirectiveId, DirectiveVarName
                                                   , DirectiveVarValue, TechniqueName, TechniqueVersion)
  }
  final case object Parameter extends QSObject { override val name = "parameter"
                                                 override val attributes = commons ++ Set(ParameterName, ParameterValue)
  }
  final case object Rule      extends QSObject { override val name = "rule"
                                                 override val attributes = commons ++ Set(RuleId, DirectiveIds, Targets)
  }

  final val all: Set[QSObject] = sealerate.values[QSObject]


  // default sort for QuickSearchResult:
  // - by type
  // - then by name
  def sortQSObject(a: QSObject, b:QSObject): Boolean = {

    implicit class QSObjectOrder(o: QSObject) {
      def order() = o match {
        case Node      => 1
        case Group     => 2
        case Directive => 3
        case Parameter => 4
        case Rule      => 5
      }
    }
    a.order <= b.order
  }
}

/**
 * Mapping between human and domain name.
 * Mostly used in the query parser, but also in the
 * documentation.
 */
final object QSMapping {
  /**
   * Mapping between a string and actual objects.
   * We try to be kind with users: not case sensitive, not plural sensitive
   */
  val objectNameMapping = {
    QSObject.all.map { obj =>
      val n = obj.name.toLowerCase
      (n -> obj) :: ( n + "s" -> obj) :: Nil
    }.flatten.toMap
  }

  //set of names by attribute
  val attributeNames = {
    import QSAttribute._
    val descriptions = Set(Description, LongDescription, ShortDescription).map( _.name ) ++ Set("descriptions")

    QSAttribute.all.map { a => a match {
      case Name              => (a, Set(Name.name, "name") )
      case Description       => (a, descriptions)
      case ShortDescription  => (a, descriptions)
      case LongDescription   => (a, descriptions)
      case IsEnabled         => (a, Set(IsEnabled.name, "enabled") )
      case NodeId            => (a, Set(NodeId.name, "nodeid") )
      case Fqdn              => (a, Set(Fqdn.name, "fqdn") )
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
  }.toMap

  /*
   * For attributes, we want to be a little more lenient than for objects, and have:
   * - several names mapping to the same attribute. Ex: both (id, nodeid) map to NodeId.
   * - a name mapping to several attributes. Ex. description map to (Description, LongDescription, ShortDescription)
   * - for all name, also have the plural
   */
  val attributeNameMapping: Map[String, Set[QSAttribute]] = {
    //given that mapping, build the map of name -> Set(attribute)
    val byNames: Map[String, Map[String, QSAttribute]] = attributeNames.flatMap { case(a, names) => names.map( n => (n.toLowerCase,a) ) }.groupBy(_._1)
    byNames.mapValues( _.map(_._2).toSet )
  }

}

/**
 * And the domain for results: ids and full results.
 */

sealed trait QuickSearchResultId { def value: String; def tpe: QSObject }

object QuickSearchResultId {
  import QSObject._

  final case class QRNodeId      (value: String) extends QuickSearchResultId { override final val tpe = Node     }
  final case class QRGroupId     (value: String) extends QuickSearchResultId { override final val tpe = Group    }
  final case class QRDirectiveId (value: String) extends QuickSearchResultId { override final val tpe = Directive}
  final case class QRParameterId (value: String) extends QuickSearchResultId { override final val tpe = Parameter}
  final case class QRRuleId      (value: String) extends QuickSearchResultId { override final val tpe = Rule     }
}


final case class QuickSearchResult(
    id       : QuickSearchResultId // the uuid used to build url
  , name     : String              // the user facing name
  , attribute: Option[QSAttribute] // the part that matches the search
  , value    : String              // the value that matches the search
)


