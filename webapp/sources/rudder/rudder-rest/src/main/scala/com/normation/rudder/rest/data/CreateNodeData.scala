/*
 *************************************************************************************
 * Copyright 2019 Normation SAS
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

package com.normation.rudder.rest.data

import cats.data.*
import cats.implicits.*
import com.normation.NamedZioLogger
import com.normation.inventory.domain.*
import com.normation.inventory.domain.AgentType.CfeCommunity
import com.normation.inventory.domain.AgentType.Dsc
import com.normation.inventory.domain.VmType.*
import com.normation.rudder.domain.nodes.NodeState
import com.normation.rudder.domain.policies.PolicyMode
import com.normation.rudder.domain.properties.GenericProperty
import com.normation.rudder.domain.properties.NodeProperty
import com.normation.rudder.rest.data.Creation.CreationError
import com.normation.rudder.rest.data.NodeTemplate.AcceptedNodeTemplate
import com.normation.rudder.rest.data.NodeTemplate.PendingNodeTemplate
import com.softwaremill.quicklens.*
import com.typesafe.config.ConfigValue
import enumeratum.*
import java.util.regex.Pattern
import net.liftweb.json.JArray
import net.liftweb.json.JField
import net.liftweb.json.JObject
import net.liftweb.json.JString
import net.liftweb.json.JValue

/**
 * Applicative log of interest for Rudder ops.
 */
object CreateNodeApiLogger extends NamedZioLogger {
  override def loggerName: String = "create-node-api"
}

/*
 * This is the RestNodeDetails as provided by RestDataSerializer, but in a Scala format.
 * Do not change it without versioning, it's an actual API definition.
 */
object Rest {

  final case class OS(
      `type`:      String,
      name:        String,
      version:     String,
      fullName:    String,
      servicePack: Option[String]
  )

  final case class AgentKey(
      value:  String,
      status: Option[String]
  )

  final case class NodeProp(name: String, value: ConfigValue)

  // machine creation doesn't have an ID
  final case class MachineCreate(
      `type`:       String,
      provider:     Option[String],
      manufacturer: Option[String],
      serialNumber: Option[String]
  )

  /*
   * We can have two kinds of node where machine or machine type is given
   */
  final case class NodeDetails(
      id:             String,
      hostname:       String,
      status:         String,
      os:             OS,
      policyServerId: Option[String],
      machineType:    Option[String],
      machine:        Option[MachineCreate],
      state:          Option[String],
      policyMode:     Option[String],
      agentKey:       Option[AgentKey],
      properties:     List[NodeProp],
      ipAddresses:    List[String],
      timezone:       Option[NodeTimezone]
  )

  object JsonCodecNodeDetails {

    import com.typesafe.config.ConfigRenderOptions
    import zio.json.*
    import zio.json.ast.*
    import zio.json.internal.Write

    implicit val codecConfigValue: JsonCodec[ConfigValue] = JsonCodec(
      new JsonEncoder[ConfigValue] {
        override def unsafeEncode(a: ConfigValue, indent: Option[Int], out: Write): Unit = {
          out.write(
            a.render(ConfigRenderOptions.defaults().setJson(true).setComments(false).setFormatted(indent.getOrElse(0) > 0))
          )
        }
      },
      JsonDecoder[Json].map(json => GenericProperty.fromZioJson(json))
    )

    implicit val codecAgentKey:      JsonCodec[AgentKey]      = DeriveJsonCodec.gen
    implicit val codecOS:            JsonCodec[OS]            = DeriveJsonCodec.gen
    implicit val codecNodeTimezone:  JsonCodec[NodeTimezone]  = DeriveJsonCodec.gen
    implicit val codecNodeProp:      JsonCodec[NodeProp]      = DeriveJsonCodec.gen
    implicit val codecMachineCreate: JsonCodec[MachineCreate] = DeriveJsonCodec.gen
    implicit val codecNodeDetails:   JsonCodec[NodeDetails]   = DeriveJsonCodec.gen
  }
}

/*
 * We need to be able to identify what the user actually asked
 * for in the rudder part of the node (state, policy mode, properties)
 * so that we can set that correctly after acceptation, or else let
 * default as they are.
 */
final case class NodeSetup(
    properties: List[NodeProperty],
    policyMode: Option[PolicyMode],
    state:      Option[NodeState]
)

/*
 * An object that hold the will-be-created node:
 * - inventory
 * - node info like properties, etc
 * - the target node status (it's created in "pending" but can be accepted)
 */
sealed trait NodeTemplate {
  def inventory:  FullInventory
  def properties: List[NodeProperty]
}

object NodeTemplate {
  final case class PendingNodeTemplate(
      inventory:  FullInventory,
      properties: List[NodeProperty]
  ) extends NodeTemplate

  final case class AcceptedNodeTemplate(
      inventory:  FullInventory,
      properties: List[NodeProperty],
      policyMode: Option[PolicyMode],
      state:      Option[NodeState]
  ) extends NodeTemplate
}

/*
 * for each will-be-created node, we want to store if the result is success
 * of error. We will decide what to return from the API based on that.
 */
final case class ResultHolder(
    // lists because we want to be able to have several time the same id
    created: List[NodeId],
    failed:  List[(String, CreationError)] // string because perhaps it's not yet a nodeId
)

object ResultHolder {

  implicit class ResultHolderToJson(res: ResultHolder) {

    def toJson(): JValue = {
      import net.liftweb.json.JsonDSL.*
      (
        ("created"  -> JArray(res.created.map(id => JString(id.value))))
        ~ ("failed" ->
        JArray(res.failed.map {
          case (id, error) =>
            JObject(JField(id, errorToJson(error)))
        }))
      )
    }

    def errorToJson(error: CreationError): JValue = {
      error match {
        case x: CreationError.OnAcceptation   => JString(x.errorMsg)
        case x: CreationError.OnSaveInventory => JString(x.errorMsg)
        case x: CreationError.OnSaveNode      => JString(x.errorMsg)
        case CreationError.OnValidation(nel) => JArray(nel.map(e => JString(s"[validation] ${e.msg}")).toList)
      }
    }
  }
}

object Creation {

  sealed trait CreationError {
    def errorMsg: String
  }

  object CreationError {

    final case class OnValidation(validations: NonEmptyList[Validation.NodeValidationError]) extends CreationError {
      override def errorMsg: String = s"[validation] ${validations.map(_.msg).toList.mkString("; ")}"
    }
    final case class OnSaveInventory(message: String)                                        extends CreationError {
      override def errorMsg: String = s"[save inventory] ${message}"
    }
    final case class OnSaveNode(message: String)                                             extends CreationError {
      override def errorMsg: String = s"[save node] ${message}"
    }
    final case class OnAcceptation(message: String)                                          extends CreationError {
      override def errorMsg: String = s"[accept] ${message}"
    }
  }

}

object Validation {
  import Rest.*
  import Validated.*
  import com.normation.inventory.domain.AcceptedInventory
  import com.normation.inventory.domain.PendingInventory
  import com.normation.rudder.domain.policies.PolicyMode as PM

  type Validation[T] = ValidatedNel[NodeValidationError, T]

  /*
   * Validate user provided node details and create the corresponding inventory
   */
  def toNodeTemplate(nodeDetails: Rest.NodeDetails): ValidatedNel[NodeValidationError, NodeTemplate] = {
    val os = getos(
      nodeDetails.os.`type`,
      nodeDetails.os.name,
      nodeDetails.os.version,
      nodeDetails.os.fullName,
      nodeDetails.os.servicePack
    )

    val checkedAgent: Validation[AgentInfo] = nodeDetails.agentKey match {
      case Some(a) => checkAgent(os.os, a)
      case None    => AgentInfo(AgentType.CfeCommunity, None, PublicKey("placeholder-value - not a real key"), Set.empty).validNel
    }

    (checkId(nodeDetails.id.toLowerCase), checkStatus(nodeDetails.status)).mapN(Tuple2(_, _)) andThen {
      case (id, status) =>
        val machine = {
          val tpe = (nodeDetails.machineType, nodeDetails.machine) match {
            case (None, None)     => "physical"
            // make the machine structure more privileged
            case (_, Some(m))     => m.provider.getOrElse(m.`type`)
            case (Some(mt), None) => mt
          }

          getMachine(id, tpe)
            .modify(_.manufacturer)
            .setToIfDefined(nodeDetails.machine.map(_.manufacturer.map(Manufacturer)))
            .modify(_.systemSerialNumber)
            .setToIfDefined(nodeDetails.machine.map(_.serialNumber))
        }

        (
          checkSummary(nodeDetails, id, PendingInventory, os),
          checkedAgent,
          checkPolicyMode(status, nodeDetails.policyMode),
          checkState(status, nodeDetails.state)
        ).mapN {
          case (summary, agent, mode, state) =>
            val inventory  = FullInventory(
              NodeInventory(
                summary,
                agents = Seq(agent),
                machineId = Some((machine.id, PendingInventory)),
                timezone = nodeDetails.timezone
              ),
              Some(machine)
            )
            val properties = nodeDetails.properties
            if (status == PendingInventory) {
              PendingNodeTemplate(inventory, properties.map(p => NodeProperty(p.name, p.value, None, None)))
            } else {
              AcceptedNodeTemplate(inventory, properties.map(p => NodeProperty(p.name, p.value, None, None)), mode, state)
            }
        }
    }
  }

  sealed trait Machine extends EnumEntry     {
    def tpe:        MachineType
    final def name: String = tpe match {
      case PhysicalMachineType               => "physical"
      case VirtualMachineType(UnknownVmType) => "vm"
      case UnknownMachineType                => "unknown"
      case VirtualMachineType(vm)            => vm.name
    }
  }
  object Machine       extends Enum[Machine] {
    case object MPhysical      extends Machine { val tpe: MachineType = PhysicalMachineType                      }
    case object MUnknownVmType extends Machine { val tpe: VirtualMachineType = VirtualMachineType(UnknownVmType) }
    case object MSolarisZone   extends Machine { val tpe: VirtualMachineType = VirtualMachineType(SolarisZone)   }
    case object MVirtualBox    extends Machine { val tpe: VirtualMachineType = VirtualMachineType(VirtualBox)    }
    case object MVMWare        extends Machine { val tpe: VirtualMachineType = VirtualMachineType(VMWare)        }
    case object MQEmu          extends Machine { val tpe: VirtualMachineType = VirtualMachineType(QEmu)          }
    case object MXen           extends Machine { val tpe: VirtualMachineType = VirtualMachineType(Xen)           }
    case object MAixLPAR       extends Machine { val tpe: VirtualMachineType = VirtualMachineType(AixLPAR)       }
    case object MHyperV        extends Machine { val tpe: VirtualMachineType = VirtualMachineType(HyperV)        }
    case object MBSDJail       extends Machine { val tpe: VirtualMachineType = VirtualMachineType(BSDJail)       }

    val values: IndexedSeq[Machine] = findValues
  }

  sealed trait NodeValidationError { def msg: String }
  object NodeValidationError       {
    def names[T](values: Iterable[T])(show: T => String): String = values.toSeq.map(show).sorted.mkString("'", ", ", "'")
    final case class UUID(x: String)          extends NodeValidationError {
      val msg: String =
        s"Only ID matching the shape of an UUID (xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx) are authorized but '${x}' provided"
    }
    case object Hostname                      extends NodeValidationError { val msg = "Hostname can't be empty" }
    final case class Status(x: String)        extends NodeValidationError {
      val msg: String = s"Node 'status' must be one of ${names(allStatus)(_.name)} but '${x}' provided"
    }
    final case class PolicyMode(x: String)    extends NodeValidationError {
      val msg: String = s"Node's policy mode must be one of ${names(PM.values)(_.name)} but '${x}' provided"
    }
    case object PolicyModeOnBadStatus         extends NodeValidationError {
      val msg: String = s"Policy mode can not be specified when status=pending"
    }
    final case class State(x: String)         extends NodeValidationError {
      val msg: String = s"Node 'state' must be one of ${names(NodeState.values)(_.name)} but '${x}' provided"
    }
    case object StateOnBadStatus              extends NodeValidationError {
      val msg: String = s"Node 'state' can not be specified when status=pending"
    }
    final case class Ram(x: String)           extends NodeValidationError {
      val msg: String = s"Provided RAM '${x}'can not be parsed as a memory amount"
    }
    final case class MachineTpe(x: String)    extends NodeValidationError {
      val msg: String = s"Machine type must be one of ${names(Machine.values)(_.name)} but '${x}' provided"
    }
    final case class Agent(x: String)         extends NodeValidationError {
      val msg: String = s"Agent's management technology must be one of ${names(AgentType.values)(_.id)} but '${x}' provided"
    }
    final case class KeyStatus(x: String)     extends NodeValidationError {
      val msg: String = s"Key status must be '${CertifiedKey.value}' or '${UndefinedKey.value}' but '${x}' provided"
    }
    final case class SecurityVal(msg: String) extends NodeValidationError
  }

  // only check shape alike bd645dfe-b4ce-4475-8d52-b7cfa106e333
  // but with any chars in [a-z0-9]
  val idregex:             Pattern                                   = """[a-z0-9]{8}-([a-z0-9]{4}-){3}[a-z0-9]{12}""".r.pattern
  def checkId(id: String): ValidatedNel[NodeValidationError, NodeId] = {
    if (idregex.matcher(id).matches()) NodeId(id).validNel
    else NodeValidationError.UUID(id).invalidNel
  }

  def checkPolicyId(id: String): Validation[NodeId] = if (id == "root") NodeId("root").validNel else checkId(id)

  def checkHostname(h: String): Validation[String] = if (h.nonEmpty) h.validNel else NodeValidationError.Hostname.invalidNel

  def checkSummary(nodeDetails: NodeDetails, id: NodeId, status: InventoryStatus, os: OsDetails): Validation[NodeSummary] = {
    val root = os.os match {
      case _: WindowsType => "administrator"
      case _ => "root"
    }

    val policyServerId = nodeDetails.policyServerId.getOrElse("root")

    (
      checkHostname(nodeDetails.hostname),
      checkPolicyId(policyServerId.toLowerCase),
      nodeDetails.agentKey.flatMap(_.status) match {
        case None    => UndefinedKey.validNel
        case Some(x) => checkKeyStatus(x)
      }
    ).mapN(NodeSummary(id, status, root, _, os, _, _))
  }

  def getMachine(id: NodeId, machineType: String): MachineInventory = {
    val machineId = MachineUuid(IdGenerator.md5Hash(id.value))
    val machine   = machineType.toLowerCase match {
      case "virtual" => "vm"
      case x         => x
    }
    MachineInventory(
      machineId,
      PendingInventory, // machine and node are created in Pending, then accepted is requested status is Accepted
      Machine.values.find(_.name == machine).getOrElse(Machine.MPhysical).tpe,
      Some(machineId.value)
    )
  }

  def checkAgent(osType: OsType, agent: AgentKey): Validation[AgentInfo] = {
    def checkSecurityToken(agent: AgentType, token: String): Validation[SecurityToken] = {
      import net.liftweb.json.JsonDSL.*
      val tpe = if (token.contains("BEGIN CERTIFICATE")) Certificate.kind else PublicKey.kind
      AgentInfoSerialisation.parseSecurityToken(agent, ("type" -> tpe) ~ ("value" -> token), None) match {
        case Left(err) => NodeValidationError.SecurityVal(err.fullMsg).invalidNel
        case Right(x)  => x.validNel
      }
    }
    val tpe = osType match {
      case _: WindowsType => Dsc
      case _ => CfeCommunity
    }

    checkSecurityToken(tpe, agent.value) andThen { token => AgentInfo(tpe, None, token, Set.empty).validNel }
  }

  def checkKeyStatus(s: String): Validation[KeyStatus] = KeyStatus(s) match {
    case Left(_)  => NodeValidationError.KeyStatus(s).invalidNel
    case Right(x) => x.validNel
  }

  /*
   * Check is string can be transformed to a T
   */
  def checkFind[T](input: String, error: NodeValidationError)(find: String => Option[T]): ValidatedNel[NodeValidationError, T] = {
    val i = input.toLowerCase()
    find(i) match {
      case None    => error.invalidNel
      case Some(x) => x.validNel
    }
  }

  // we only accept node in pending / accepted
  val allStatus:                   Set[InventoryStatus]        = Set(AcceptedInventory, PendingInventory)
  def checkStatus(status: String): Validation[InventoryStatus] = checkFind(status, NodeValidationError.Status(status)) { x =>
    allStatus.find(_.name == x)
  }

  // state can not be set on pending status
  def checkState(status: InventoryStatus, state: Option[String]): Validation[Option[NodeState]] = state match {
    case None    => None.validNel
    case Some(s) =>
      if (status == PendingInventory) NodeValidationError.StateOnBadStatus.invalidNel
      else checkFind(s, NodeValidationError.State(s))(x => NodeState.values.find(_.name == x)).map(Some(_))
  }

  def checkPolicyMode(status: InventoryStatus, mode: Option[String]): Validation[Option[PolicyMode]] = mode match {
    case None     => None.validNel
    case Some(pm) =>
      if (status == PendingInventory) NodeValidationError.PolicyModeOnBadStatus.invalidNel
      else checkFind(pm, NodeValidationError.PolicyMode(pm))(x => PM.values.find(_.name == x).map(Some(_)))
  }

  // this part is adapted from inventory extraction. It should be factored out
  // and not duplicated, obviously
  def getos(tpe: String, name: String, vers: String, fullName: String, srvPk: Option[String]): OsDetails = {
    val kernelVersion = new Version("N/A")

    val version = new Version(vers match {
      case "" => "N/A"
      case x  => x
    })

    val servicePack = srvPk match {
      case None     => None
      case Some("") => None
      case Some(x)  => Some(x)
    }

    // find os type, and name
    val os = ParseOSType.getType(tpe, name, name + " " + fullName)
    ParseOSType.getDetails(os, fullName, version, servicePack, kernelVersion)
  }
}
