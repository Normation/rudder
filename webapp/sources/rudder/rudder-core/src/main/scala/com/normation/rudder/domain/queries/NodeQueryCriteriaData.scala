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

import com.normation.errors._
import com.normation.inventory.domain.AgentType
import com.normation.inventory.domain.NodeId
import com.normation.inventory.ldap.core.LDAPConstants._
import com.normation.inventory.ldap.core.LDAPConstants.A_PROCESS
import com.normation.rudder.domain.RudderLDAPConstants.A_NODE_GROUP_UUID
import com.normation.rudder.domain.RudderLDAPConstants.A_NODE_PROPERTY
import com.normation.rudder.domain.RudderLDAPConstants.A_STATE
import com.normation.rudder.domain.logger.FactQueryProcessorPure
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.properties.NodeProperty
import com.normation.rudder.domain.queries.{KeyValueComparator => KVC}
import com.normation.rudder.domain.queries.KeyValueComparator.HasKey
import com.normation.rudder.facts.nodes.NodeFact
import com.normation.rudder.repository.RoNodeGroupRepository
import com.normation.utils.DateFormaterService
import java.util.function.Predicate
import java.util.regex.Pattern
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import scala.collection.SortedMap
import scala.util.Try
import zio._
import zio.syntax._

trait SubGroupComparatorRepository {
  def getNodeIds(groupId: NodeGroupId): IOResult[Chunk[NodeId]]
  def getGroups: IOResult[Chunk[SubGroupChoice]]
}
// default implementation out of test use GroupRepo for that
class DefaultSubGroupComparatorRepository(repo: RoNodeGroupRepository) extends SubGroupComparatorRepository {

  override def getNodeIds(groupId: NodeGroupId): IOResult[Chunk[NodeId]] = {
    repo.getNodeGroupOpt(groupId).map {
      case None             => Chunk.empty
      case Some((group, _)) => Chunk.fromIterable(group.serverList)
    }
  }

  override def getGroups: IOResult[Chunk[SubGroupChoice]] = {
    repo.getAll().map(seq => Chunk.fromIterable(seq).map(g => SubGroupChoice(g.id, g.name)))
  }
}

// groupRepo must be `=> ` to avoid cyclic dep
class NodeQueryCriteriaData(groupRepo: () => SubGroupComparatorRepository) {

  implicit class IterableToChunk[A](it: Iterable[A]) {
    def toChunk: Chunk[A] = Chunk.fromIterable(it)
  }

  implicit class OptionToChunk[A](opt: Option[A]) {
    def toChunk: Chunk[A] = Chunk.fromIterable(opt)
  }

  implicit class OneToChunk[A](a: A) {
    def wrap: Chunk[A] = Chunk(a)
  }

  val criteria = Chunk(
    ObjectCriterion(
      OC_MACHINE,
      Chunk(
        Criterion("machineType", MachineComparator, NodeCriterionMatcherString(_.machine.machineType.kind.wrap)),
        Criterion("provider", VmTypeComparator, NodeCriterionMatcherString, _.machine.machineType.kind.wrap),
        Criterion(A_MACHINE_UUID, StringComparator, NodeCriterionMatcherString(_.machine.id.value.wrap)),
        Criterion(A_NAME, StringComparator, AlwaysFalse("machine does not have a 'name' attribute in fusion")),
        Criterion(A_DESCRIPTION, StringComparator, AlwaysFalse("machine does not have a 'description' attribute in fusion")),
        Criterion(A_MB_UUID, StringComparator, AlwaysFalse("machine does not have a 'mother board uuid' attribute in fusion")),
        Criterion(
          A_MANUFACTURER,
          StringComparator,
          NodeCriterionMatcherString(_.machine.manufacturer.toChunk.map(_.name))
        ),
        Criterion(A_SERIAL_NUMBER, StringComparator, NodeCriterionMatcherString(_.machine.systemSerial.toChunk))
      )
    ),
    ObjectCriterion(
      OC_MEMORY,
      Chunk(
        Criterion(A_DESCRIPTION, StringComparator, NodeCriterionMatcherString(_.memories.flatMap(_.description))),
        Criterion(A_QUANTITY, LongComparator, NodeCriterionMatcherInt(_.memories.map(_.quantity))),
        Criterion(A_NAME, StringComparator, NodeCriterionMatcherString(_.memories.flatMap(_.name))),
        Criterion(A_MEMORY_CAPACITY, MemoryComparator, NodeCriterionMatcherLong(_.memories.flatMap(_.capacity.map(_.size)))),
        Criterion(A_MEMORY_CAPTION, StringComparator, NodeCriterionMatcherString(_.memories.flatMap(_.caption))),
        Criterion(A_MEMORY_SPEED, LongComparator, NodeCriterionMatcherString(_.memories.flatMap(_.speed))),
        Criterion(A_MEMORY_SLOT_NUMBER, LongComparator, NodeCriterionMatcherString(_.memories.map(_.slotNumber))),
        Criterion(A_MEMORY_TYPE, StringComparator, NodeCriterionMatcherString(_.memories.flatMap(_.memType))),
        Criterion(A_SERIAL_NUMBER, StringComparator, NodeCriterionMatcherString(_.memories.flatMap(_.serialNumber)))
      )
    ),
    ObjectCriterion(
      OC_STORAGE,
      Chunk(
        Criterion(A_NAME, StringComparator, NodeCriterionMatcherString(_.storages.map(_.name))),
        Criterion(A_DESCRIPTION, StringComparator, NodeCriterionMatcherString(_.storages.flatMap(_.description))),
        Criterion(A_MODEL, StringComparator, NodeCriterionMatcherString(_.storages.flatMap(_.model))),
        Criterion(A_SERIAL_NUMBER, StringComparator, NodeCriterionMatcherString(_.storages.flatMap(_.serialNumber))),
        Criterion(A_FIRMWARE, StringComparator, NodeCriterionMatcherString(_.storages.flatMap(_.firmware))),
        Criterion(A_QUANTITY, LongComparator, NodeCriterionMatcherInt(_.storages.map(_.quantity))),
        Criterion(A_SME_TYPE, StringComparator, NodeCriterionMatcherString(_.storages.flatMap(_.sType))),
        Criterion(
          A_MANUFACTURER,
          StringComparator,
          NodeCriterionMatcherString(_.storages.flatMap(_.manufacturer.map(_.name)))
        ),
        Criterion(A_STORAGE_SIZE, MemoryComparator, NodeCriterionMatcherLong(_.storages.flatMap(_.size.map(_.size)))),
        Criterion(A_STORAGE_FIRMWARE, StringComparator, NodeCriterionMatcherString(_.storages.flatMap(_.firmware)))
      )
    ),
    ObjectCriterion(
      OC_BIOS,
      Chunk(
        Criterion(A_BIOS_NAME, StringComparator, NodeCriterionMatcherString(_.bios.map(_.name))),
        Criterion(A_DESCRIPTION, StringComparator, NodeCriterionMatcherString(_.bios.flatMap(_.description))),
        Criterion(A_QUANTITY, LongComparator, NodeCriterionMatcherInt(_.bios.map(_.quantity))),
        Criterion(A_SOFT_VERSION, StringComparator, NodeCriterionMatcherString(_.bios.flatMap(_.version.map(_.value)))),
        Criterion(A_RELEASE_DATE, DateComparator, NodeCriterionMatcherDate(_.bios.flatMap(_.releaseDate))),
        Criterion(A_EDITOR, StringComparator, NodeCriterionMatcherString(_.bios.flatMap(_.editor.map(_.name))))
      )
    ),
    ObjectCriterion(
      OC_CONTROLLER,
      Chunk(
        Criterion(A_CONTROLLER_NAME, StringComparator, NodeCriterionMatcherString(_.controllers.map(_.name))),
        Criterion(A_DESCRIPTION, StringComparator, NodeCriterionMatcherString(_.controllers.flatMap(_.description))),
        Criterion(A_SME_TYPE, StringComparator, NodeCriterionMatcherString(_.controllers.flatMap(_.cType))),
        Criterion(
          A_MANUFACTURER,
          StringComparator,
          NodeCriterionMatcherString(_.controllers.flatMap(_.manufacturer.map(_.name)))
        ),
        Criterion(A_QUANTITY, LongComparator, NodeCriterionMatcherInt(_.controllers.map(_.quantity)))
      )
    ),
    ObjectCriterion(
      OC_PORT,
      Chunk(
        Criterion(A_PORT_NAME, StringComparator, NodeCriterionMatcherString(_.ports.map(_.name))),
        Criterion(A_DESCRIPTION, StringComparator, NodeCriterionMatcherString(_.ports.flatMap(_.description))),
        Criterion(A_SME_TYPE, StringComparator, NodeCriterionMatcherString(_.ports.flatMap(_.pType))),
        Criterion(A_QUANTITY, LongComparator, NodeCriterionMatcherInt(_.ports.map(_.quantity)))
      )
    ),
    ObjectCriterion(
      OC_PROCESSOR,
      Chunk(
        Criterion(A_PROCESSOR_NAME, StringComparator, NodeCriterionMatcherString(_.processors.map(_.name))),
        Criterion(A_DESCRIPTION, StringComparator, NodeCriterionMatcherString(_.processors.flatMap(_.description))),
        Criterion(A_QUANTITY, LongComparator, NodeCriterionMatcherInt(_.processors.map(_.quantity))),
        Criterion(A_MODEL, StringComparator, NodeCriterionMatcherString(_.processors.flatMap(_.model.map(_.toString)))),
        Criterion(
          A_MANUFACTURER,
          StringComparator,
          NodeCriterionMatcherString(_.processors.flatMap(_.manufacturer.map(_.name)))
        ),
        Criterion(A_PROCESSOR_SPEED, LongComparator, NodeCriterionMatcherInt(_.processors.flatMap(_.speed))),
        Criterion(
          A_PROCESSOR_STEPPING,
          StringComparator,
          NodeCriterionMatcherString(_.processors.flatMap(_.stepping.map(_.toString)))
        ),
        Criterion(
          A_PROCESSOR_FAMILLY,
          StringComparator,
          NodeCriterionMatcherString(_.processors.flatMap(_.family.map(_.toString)))
        ),
        Criterion(A_PROCESSOR_FAMILY_NAME, StringComparator, NodeCriterionMatcherString(_.processors.flatMap(_.familyName))),
        Criterion(A_THREAD, StringComparator, NodeCriterionMatcherString(_.processors.flatMap(_.thread.map(_.toString)))),
        Criterion(A_CORE, StringComparator, NodeCriterionMatcherString(_.processors.flatMap(_.core.map(_.toString))))
      )
    ),
    ObjectCriterion(
      OC_SLOT,
      Chunk(
        Criterion(A_DESCRIPTION, StringComparator, NodeCriterionMatcherString(_.slots.flatMap(_.description))),
        Criterion(A_QUANTITY, LongComparator, NodeCriterionMatcherInt(_.slots.map(_.quantity))),
        Criterion(A_STATUS, StringComparator, NodeCriterionMatcherString(_.slots.flatMap(_.status))),
        Criterion(A_SLOT_NAME, StringComparator, NodeCriterionMatcherString(_.slots.map(_.name)))
      )
    ),
    ObjectCriterion(
      OC_SOUND,
      Chunk(
        Criterion(A_DESCRIPTION, StringComparator, NodeCriterionMatcherString(_.sounds.flatMap(_.description))),
        Criterion(A_QUANTITY, LongComparator, NodeCriterionMatcherInt(_.sounds.map(_.quantity))),
        Criterion(A_SOUND_NAME, StringComparator, NodeCriterionMatcherString(_.sounds.map(_.name)))
      )
    ),
    ObjectCriterion(
      OC_VIDEO,
      Chunk(
        Criterion(A_DESCRIPTION, StringComparator, NodeCriterionMatcherString(_.videos.flatMap(_.description))),
        Criterion(A_QUANTITY, LongComparator, NodeCriterionMatcherInt(_.videos.map(_.quantity))),
        Criterion(A_VIDEO_NAME, StringComparator, NodeCriterionMatcherString(_.videos.map(_.name))),
        Criterion(A_VIDEO_CHIPSET, StringComparator, NodeCriterionMatcherString(_.videos.flatMap(_.chipset))),
        Criterion(A_VIDEO_RESOLUTION, StringComparator, NodeCriterionMatcherString(_.videos.flatMap(_.resolution))),
        Criterion(A_MEMORY_CAPACITY, MemoryComparator, NodeCriterionMatcherLong(_.videos.flatMap(_.memory.map(_.size))))
      )
    ),
    ObjectCriterion(
      OC_NODE,
      Chunk(
        Criterion("OS", NodeOstypeComparator, NodeCriterionMatcherString(_.os.os.kernelName.wrap)),
        Criterion(A_NODE_UUID, StringComparator, NodeCriterionMatcherString(_.id.value.wrap)),
        Criterion(A_HOSTNAME, StringComparator, NodeCriterionMatcherString(_.fqdn.wrap)),
        Criterion(A_OS_NAME, NodeOsNameComparator, NodeCriterionMatcherString(_.os.os.name.wrap)),
        Criterion(A_OS_FULL_NAME, OrderedStringComparator, NodeCriterionMatcherString(_.os.fullName.wrap)),
        Criterion(A_OS_VERSION, OrderedStringComparator, NodeCriterionMatcherString(_.os.version.value.wrap)),
        Criterion(A_OS_SERVICE_PACK, OrderedStringComparator, NodeCriterionMatcherString(_.os.servicePack.toChunk)),
        Criterion(A_OS_KERNEL_VERSION, OrderedStringComparator, NodeCriterionMatcherString(_.os.kernelVersion.value.wrap)),
        Criterion(A_ARCH, StringComparator, NodeCriterionMatcherString(_.archDescription.toChunk)),
        Criterion(A_STATE, NodeStateComparator, NodeCriterionMatcherString(_.rudderSettings.state.name.wrap)),
        Criterion(A_OS_RAM, MemoryComparator, NodeCriterionMatcherLong(_.ram.map(_.size).toChunk)),
        Criterion(A_OS_SWAP, MemoryComparator, NodeCriterionMatcherLong(_.swap.map(_.size).toChunk)),
        Criterion(A_AGENTS_NAME, AgentComparator, AgentMatcher),
        Criterion(A_ACCOUNT, StringComparator, NodeCriterionMatcherString(_.accounts)),
        Criterion(A_LIST_OF_IP, NodeIpListComparator, NodeCriterionMatcherString(_.ipAddresses.map(_.inet))),
        Criterion(A_ROOT_USER, StringComparator, NodeCriterionMatcherString(_.rudderAgent.user.wrap)),
        Criterion(A_INVENTORY_DATE, DateComparator, NodeCriterionMatcherDate(_.lastInventoryDate.toChunk)),
        Criterion(
          A_POLICY_SERVER_UUID,
          StringComparator,
          NodeCriterionMatcherString(_.rudderSettings.policyServerId.value.wrap)
        )
      )
    ),
    ObjectCriterion(
      OC_SOFTWARE,
      Chunk(
        Criterion(A_NAME, StringComparator, NodeCriterionMatcherString(_.software.map(_.name))),
        Criterion(A_SOFT_VERSION, StringComparator, NodeCriterionMatcherString(_.software.map(_.version.value))),
        Criterion(A_EDITOR, EditorComparator, NodeCriterionMatcherString(_.software.flatMap(_.publisher))),
        Criterion(A_LICENSE_EXP, DateComparator, NodeCriterionMatcherDate(_.software.flatMap(_.expirationDate))),
        Criterion(A_LICENSE_NAME, StringComparator, NodeCriterionMatcherString(_.software.flatMap(_.licenseName))),
        Criterion(A_LICENSE_PRODUCT_ID, StringComparator, NodeCriterionMatcherString(_.software.flatMap(_.productId))),
        Criterion(A_LICENSE_PRODUCT_KEY, StringComparator, NodeCriterionMatcherString(_.software.flatMap(_.productKey)))
      )
    ),
    ObjectCriterion(
      OC_NET_IF,
      Chunk(
        Criterion(A_DESCRIPTION, StringComparator, NodeCriterionMatcherString(_.networks.flatMap(_.description))),
        Criterion(A_NETWORK_NAME, StringComparator, NodeCriterionMatcherString(_.networks.map(_.name))),
        Criterion(
          A_NETIF_ADDRESS,
          StringComparator,
          NodeCriterionMatcherString(_.networks.flatMap(_.ifAddresses.map(_.getHostAddress)))
        ),
        Criterion(
          A_NETIF_DHCP,
          StringComparator,
          NodeCriterionMatcherString(_.networks.flatMap(_.ifDhcp.map(_.getHostAddress)))
        ),
        Criterion(
          A_NETIF_GATEWAY,
          StringComparator,
          NodeCriterionMatcherString(_.networks.flatMap(_.ifGateway.map(_.getHostAddress)))
        ),
        Criterion(
          A_NETIF_MASK,
          StringComparator,
          NodeCriterionMatcherString(_.networks.flatMap(_.ifMask.map(_.getHostAddress)))
        ),
        Criterion(
          A_NETIF_SUBNET,
          StringComparator,
          NodeCriterionMatcherString(_.networks.flatMap(_.ifSubnet.map(_.getHostAddress)))
        ),
        Criterion(A_NETIF_MAC, StringComparator, NodeCriterionMatcherString(_.networks.flatMap(_.macAddress))),
        Criterion(A_NETIF_TYPE, StringComparator, NodeCriterionMatcherString(_.networks.flatMap(_.ifType))),
        Criterion(A_NETIF_TYPE_MIB, StringComparator, NodeCriterionMatcherString(_.networks.flatMap(_.typeMib)))
      )
    ),
    ObjectCriterion(
      OC_FS,
      Chunk(
        Criterion(A_DESCRIPTION, StringComparator, NodeCriterionMatcherString(_.fileSystems.flatMap(_.description))),
        Criterion(A_NAME, StringComparator, NodeCriterionMatcherString(_.fileSystems.flatMap(_.name))),
        Criterion(A_MOUNT_POINT, StringComparator, NodeCriterionMatcherString(_.fileSystems.map(_.mountPoint))),
        Criterion(A_FILE_COUNT, LongComparator, NodeCriterionMatcherInt(_.fileSystems.flatMap(_.fileCount))),
        Criterion(A_FREE_SPACE, MemoryComparator, NodeCriterionMatcherLong(_.fileSystems.flatMap(_.freeSpace.map(_.size)))),
        Criterion(A_TOTAL_SPACE, MemoryComparator, NodeCriterionMatcherLong(_.fileSystems.flatMap(_.totalSpace.map(_.size))))
      )
    ),
    ObjectCriterion(
      A_PROCESS,
      Chunk(
        Criterion("pid", JsonFixedKeyComparator(A_PROCESS, "pid", false), NodeCriterionMatcherInt(_.processes.map(_.pid))),
        Criterion(
          "commandName",
          JsonFixedKeyComparator(A_PROCESS, "commandName", true),
          NodeCriterionMatcherString(_.processes.flatMap(_.commandName))
        ),
        Criterion(
          "cpuUsage",
          JsonFixedKeyComparator(A_PROCESS, "cpuUsage", false),
          NodeCriterionMatcherString(_.processes.flatMap(_.cpuUsage.map(_.toString)))
        ),
        Criterion(
          "memory",
          JsonFixedKeyComparator(A_PROCESS, "memory", false),
          NodeCriterionMatcherFloat(_.processes.flatMap(_.memory))
        ),
        Criterion("tty", JsonFixedKeyComparator(A_PROCESS, "tty", true), NodeCriterionMatcherString(_.processes.flatMap(_.tty))),
        Criterion(
          "virtualMemory",
          JsonFixedKeyComparator(A_PROCESS, "virtualMemory", false),
          NodeCriterionMatcherDouble(_.processes.flatMap(_.virtualMemory))
        ),
        Criterion(
          "started",
          JsonFixedKeyComparator(A_PROCESS, "started", true),
          NodeCriterionMatcherString(_.processes.flatMap(_.started))
        ),
        Criterion(
          "user",
          JsonFixedKeyComparator(A_PROCESS, "user", true),
          NodeCriterionMatcherString(_.processes.flatMap(_.user))
        )
      )
    ),
    ObjectCriterion(
      OC_VM_INFO,
      Chunk(
        Criterion(A_DESCRIPTION, StringComparator, NodeCriterionMatcherString(_.vms.flatMap(_.description))),
        Criterion(A_VM_TYPE, StringComparator, NodeCriterionMatcherString(_.vms.flatMap(_.vmtype))),
        Criterion(A_VM_OWNER, StringComparator, NodeCriterionMatcherString(_.vms.flatMap(_.owner))),
        Criterion(A_VM_STATUS, StringComparator, NodeCriterionMatcherString(_.vms.flatMap(_.status))),
        Criterion(A_VM_CPU, LongComparator, NodeCriterionMatcherInt(_.vms.flatMap(_.vcpu))),
        Criterion(A_VM_MEMORY, LongComparator, NodeCriterionMatcherString(_.vms.flatMap(_.memory))),
        Criterion(A_VM_ID, StringComparator, NodeCriterionMatcherString(_.vms.map(_.uuid.value))),
        Criterion(A_VM_SUBSYSTEM, StringComparator, NodeCriterionMatcherString(_.vms.flatMap(_.subsystem))),
        Criterion(A_VM_NAME, StringComparator, NodeCriterionMatcherString(_.vms.flatMap(_.name)))
      )
    ),
    ObjectCriterion(
      A_EV,
      Chunk(
        Criterion("name.value", NameValueComparator(A_EV), EnvironmentVariableMatcher)
      )
    ),
    ObjectCriterion(
      A_NODE_PROPERTY,
      Chunk(
        Criterion("name.value", NodePropertyComparator(A_NODE_PROPERTY), NodePropertiesMatcher)
      )
    ),
    ObjectCriterion(
      "group",
      Chunk(
        Criterion(
          A_NODE_GROUP_UUID,
          SubGroupComparator(groupRepo),
          AlwaysFalse("sub-group matcher should have been handled at a higher level. Please report.")
        )
      )
    )
  )

  val criteriaMap: SortedMap[String, ObjectCriterion] = SortedMap.from(criteria.map(c => (c.objectType, c)))
}

////// below, criterion matching logic /////.

//////////////////////////////////////////////////////////////////////
/////////////////// direct matching with NodeFact ///////////////////
/////////////////////////////////////////////////////////////////////

object MatcherUtils {
  def getRegex(regexText: String): IOResult[Predicate[String]] = {
    IOResult.attempt(
      s"The regular expression '${regexText}' is not valid. Expected regex syntax is the java " +
      s"one, documented here: http://docs.oracle.com/javase/8/docs/api/java/util/regex/Pattern.html"
    )(Pattern.compile(regexText, Pattern.DOTALL).asMatchPredicate())
  }

}

final case class DebugInfo(comparatorName: String, value: Option[String]) {
  def formatValue = value match {
    case None    => ""
    case Some(v) => s" ${v}"
  }
  def debugMsg[A](values: Chunk[A], res: Boolean)(implicit serializer: A => String): String = {
    s"    [${res}] for '${comparatorName}${formatValue}' on [${values.map(serializer).mkString("|")}]"
  }
}

final case class MatchHolderZio[A](debug: DebugInfo, values: Chunk[A], matcher: Chunk[A] => IOResult[Boolean])(implicit
    serializer:                           A => String
) {
  def matches = for {
    res <- matcher(values)
    _   <- FactQueryProcessorPure.trace(debug.debugMsg(values, res))
  } yield res
}

object MatchHolder {
  def apply[A](debugInfo: DebugInfo, values: Chunk[A], matcher: Chunk[A] => Boolean)(implicit
      serializer:         A => String
  ): MatchHolderZio[A] = {
    MatchHolderZio[A](debugInfo, values, (vs: Chunk[A]) => matcher(vs).succeed)
  }
}

trait NodeCriterionMatcher {
  def matches(n: NodeFact, comparator: CriterionComparator, value: String): IOResult[Boolean]
}

case class AlwaysFalse(reason: String) extends NodeCriterionMatcher {
  override def matches(n: NodeFact, comparator: CriterionComparator, value: String): IOResult[Boolean] = {
    FactQueryProcessorPure.trace(s"    [false] for AlwaysFalse: ${reason} ") *>
    false.succeed
  }
}

/*
 * A generic matcher that matches anything that has an order and can be parsed/serialized
 * to one value. Typically, it can be String, numeric value, date, memories, etc.
 */
trait NodeCriterionOrderedValueMatcher[A] extends NodeCriterionMatcher {
  def extractor: NodeFact => Chunk[A]
  def parseNum(value: String): Option[A]
  def serialise(a:    A):      String
  def order: Ordering[A]

  def tryMatches(value: String, matches: A => MatchHolderZio[A]): IOResult[Boolean] = {
    parseNum(value) match {
      case Some(a) => matches(a).matches
      case None    =>
        FactQueryProcessorPure.trace(s"    - '${value}' can not be parsed into correct type: false'") *>
        false.succeed
    }
  }

  def matches(n: NodeFact, comparator: CriterionComparator, value: String): IOResult[Boolean] = {
    implicit val ser = serialise _

    comparator match {
      case Equals    =>
        tryMatches(value, a => MatchHolder[A](DebugInfo(Equals.id, Some(value)), extractor(n), _.exists(_ == a)))
      case NotEquals =>
        tryMatches(value, a => MatchHolder[A](DebugInfo(NotEquals.id, Some(value)), extractor(n), _.forall(_ != a)))
      case Regex     =>
        for {
          m <- MatcherUtils.getRegex(value)
          r <- MatchHolder[A](
                 DebugInfo(Regex.id, Some(value)),
                 extractor(n),
                 _.exists(s => m.test(serialise(s)))
               ).matches
        } yield r
      case NotRegex  =>
        for {
          m <- MatcherUtils.getRegex(value)
          r <- MatchHolder[A](
                 DebugInfo(NotRegex.id, Some(value)),
                 extractor(n),
                 _.forall(s => !m.test(serialise(s)))
               ).matches
        } yield r
      case Exists    =>
        MatchHolder[A](DebugInfo(Exists.id, None), extractor(n), _.nonEmpty).matches
      case NotExists =>
        MatchHolder[A](DebugInfo(NotExists.id, None), extractor(n), _.isEmpty).matches
      case Lesser    =>
        tryMatches(value, a => MatchHolder[A](DebugInfo(Lesser.id, Some(value)), extractor(n), _.exists(order.lt(_, a))))
      case LesserEq  =>
        tryMatches(value, a => MatchHolder[A](DebugInfo(Lesser.id, Some(value)), extractor(n), _.exists(order.lteq(_, a))))
      case Greater   =>
        tryMatches(value, a => MatchHolder[A](DebugInfo(Lesser.id, Some(value)), extractor(n), _.exists(order.gt(_, a))))
      case GreaterEq =>
        tryMatches(
          value,
          a => MatchHolder[A](DebugInfo(Lesser.id, Some(value)), extractor(n), _.exists(order.gteq(_, a)))
        )
      case c         => MatchHolder[A](DebugInfo(s"unknown comparator: ${c}", Some(value)), Chunk(), _ => false).matches
    }
  }
}

final case class NodeCriterionMatcherString(extractor: NodeFact => Chunk[String])
    extends NodeCriterionOrderedValueMatcher[String] {
  override def parseNum(value: String): Option[String] = Some(value)
  override def serialise(a: String):    String         = a
  val order = Ordering.String
}

final case class NodeCriterionMatcherInt(extractor: NodeFact => Chunk[Int]) extends NodeCriterionOrderedValueMatcher[Int] {
  override def parseNum(value: String): Option[Int] = try { Some(Integer.parseInt(value)) }
  catch { case ex: NumberFormatException => None }
  override def serialise(a: Int):       String      = a.toString
  val order = Ordering.Int
}

final case class NodeCriterionMatcherLong(extractor: NodeFact => Chunk[Long]) extends NodeCriterionOrderedValueMatcher[Long] {
  override def parseNum(value: String): Option[Long] = try { Some(java.lang.Long.parseLong(value)) }
  catch { case ex: NumberFormatException => None }
  override def serialise(a: Long):      String       = a.toString
  val order = Ordering.Long
}

final case class NodeCriterionMatcherFloat(extractor: NodeFact => Chunk[Float]) extends NodeCriterionOrderedValueMatcher[Float] {
  override def parseNum(value: String): Option[Float] = try { Some(java.lang.Float.parseFloat(value)) }
  catch { case ex: NumberFormatException => None }
  override def serialise(a: Float):     String        = a.toString
  val order = Ordering.Float.TotalOrdering
}

final case class NodeCriterionMatcherDouble(extractor: NodeFact => Chunk[Double])
    extends NodeCriterionOrderedValueMatcher[Double] {
  override def parseNum(value: String): Option[Double] = try { Some(java.lang.Double.parseDouble(value)) }
  catch { case ex: NumberFormatException => None }
  override def serialise(a: Double):    String         = a.toString
  val order = Ordering.Double.TotalOrdering
}

final case class NodeCriterionMatcherDate(extractorNode: NodeFact => Chunk[DateTime])
    extends NodeCriterionOrderedValueMatcher[DateTime] {
  val parseDate = (s: String) =>
    DateFormaterService.parseDate(s).toOption.orElse(Try(DateTimeFormat.forPattern("dd/MM/YYYY").parseDateTime(s)).toOption)
  // we need to accept both ISO format and old dd/MM/YYYY format for compatibility
  // also, we discard the time, only keep date

  override def extractor:               NodeFact => Chunk[DateTime] = (n: NodeFact) => extractorNode(n).map(_.withTimeAtStartOfDay())
  override def parseNum(value: String): Option[DateTime]            = parseDate(value).map(_.withTimeAtStartOfDay())
  override def serialise(a: DateTime):  String                      = DateFormaterService.serialize(a)
  val order = Ordering.by(_.getMillis)
}

/*
 * Agent matcher is very special with some magic case like "any cfengine"
 */
object AgentMatcher extends NodeCriterionMatcher {
  override def matches(n: NodeFact, comparator: CriterionComparator, value: String): IOResult[Boolean] = {

    implicit val serializer = (a: AgentType) => a.id
    // this is magic: we equals on agent ID and in addition we have the magic value "cfengine" that matches
    // any cfengine, which is just cfengine-community since we don't do anything else
    def eq(value: String, tpe: AgentType): Boolean = {
      value match {
        case AgentComparator.ANY_CFENGINE => tpe == AgentType.CfeCommunity || tpe == AgentType.CfeEnterprise
        case x                            => tpe.id == x || tpe.oldShortName == x
      }
    }

    comparator match {
      case Equals    =>
        MatchHolder[AgentType](DebugInfo(Equals.id, Some(value)), Chunk(n.rudderAgent.agentType), _.exists(eq(value, _))).matches
      case NotEquals => matches(n, Equals, value).map(!_)
      case c         => MatchHolder[AgentType](DebugInfo(s"unknown comparator: ${c}", Some(value)), Chunk(), _ => false).matches
    }
  }
}

/*
 * A generic matcher for (key, value) elements.
 * Key are string, values are string or json.
 * It will allow to match on:
 * - key=value
 * - hasKey
 * - jsonSelect
 * In addition to eq/exist/regex
 * Regex follows a
 *
 * The serialise method is used for the debug representation and k=v matcher.
 * The getValue method is for the value part, used in jsonSelect.
 */
trait NodeCriterionKeyValueMatcher[A] extends NodeCriterionMatcher {
  def extractor: NodeFact => Chunk[A]
  def serialise(a: A): String
  def getKey(a:    A): String
  def getValue(a:  A): String
  // ordering on key, alternative could be done on values
  def order: Ordering[String] = Ordering.String

  def matches(n: NodeFact, comparator: CriterionComparator, value: String): IOResult[Boolean] = {
    implicit val ser = serialise _

    // for Key/Value comparator, we have an expected format for the value for some operator:
    // Equals and NotEquals: value is: ${key}=${value}, ie "=" is mandatory
    // HasKey: value is: ${key}:${value} , ie ":" is mandatory
    def getKVEquals(value: String): IOResult[SplittedValue] = {
      val kv = NodePropertyMatcherUtils.splitInput(value, "=")
      kv.values match {
        case Nil =>
          Inconsistency(
            s"When looking for 'key=value', the '=' is mandatory. The left part is a key name, and the right part is the string to look for."
          ).fail
        case _   => kv.succeed
      }
    }

    def getKVHasKey(value: String): IOResult[SplittedValue] = {
      val kv = NodePropertyMatcherUtils.splitInput(value, ":")
      kv.values match {
        case Nil =>
          Inconsistency(
            s"When looking for 'key:json path expression', we found zero ':', but at least one is mandatory. The left " +
            "part is a key name, and the right part is the JSON path expression (see https://github.com/json-path/JsonPath). " +
            "For example: datacenter:world.europe.[?(@.city=='Paris')]"
          ).fail
        case _   => kv.succeed
      }
    }

    comparator match {
      case Equals    =>
        for {
          kv  <- getKVEquals(value)
          res <- MatchHolder[A](
                   DebugInfo(Equals.id, Some(value)),
                   extractor(n),
                   _.exists(v => getKey(v) == kv.key && getValue(v) == kv.value)
                 ).matches
        } yield res

      // not equals look for a value not equal to parameter but only for the given key
      // not having the key is also not equals
      case NotEquals =>
        for {
          kv  <- getKVEquals(value)
          res <- MatchHolder[A](
                   DebugInfo(Equals.id, Some(value)),
                   extractor(n),
                   _.forall(v => getKey(v) != kv.key || (getKey(v) == kv.key && getValue(v) != kv.value))
                 ).matches
        } yield res

      case Regex     =>
        for {
          m   <- MatcherUtils.getRegex(value)
          res <- MatchHolder[A](
                   DebugInfo(Regex.id, Some(value)),
                   extractor(n),
                   _.exists(s => m.test(serialise(s)))
                 ).matches
        } yield res
      case NotRegex  =>
        for {
          m   <- MatcherUtils.getRegex(value)
          res <- MatchHolder[A](
                   DebugInfo(NotRegex.id, Some(value)),
                   extractor(n),
                   _.forall(s => !m.test(serialise(s)))
                 ).matches
        } yield res
      case Exists    =>
        MatchHolder[A](DebugInfo(Exists.id, None), extractor(n), _.nonEmpty).matches
      case NotExists =>
        MatchHolder[A](DebugInfo(NotExists.id, None), extractor(n), _.isEmpty).matches
      case Lesser    =>
        MatchHolder[A](DebugInfo(Lesser.id, Some(value)), extractor(n), _.exists(a => order.lt(getKey(a), value))).matches
      case LesserEq  =>
        MatchHolder[A](DebugInfo(Lesser.id, Some(value)), extractor(n), _.exists(a => order.lteq(getKey(a), value))).matches
      case Greater   =>
        MatchHolder[A](DebugInfo(Lesser.id, Some(value)), extractor(n), _.exists(a => order.gt(getKey(a), value))).matches
      case GreaterEq =>
        MatchHolder[A](DebugInfo(Lesser.id, Some(value)), extractor(n), _.exists(a => order.gteq(getKey(a), value))).matches

      case HasKey         =>
        MatchHolder[A](DebugInfo(HasKey.id, Some(value)), extractor(n), _.exists(kv => getKey(kv) == value)).matches
      case KVC.JsonSelect =>
        // for JSON select: error in json path matching (ie "JsonSelect.exists") are considered as not matching, not as errors
        for {
          kv     <- getKVHasKey(value)
          path   <- JsonSelect.compilePath(kv.value).toIO
          matcher = (as: Chunk[A]) => {
                      ZIO.exists(as)(a => {
                        ZIO
                          .when(getKey(a) == kv.key)(JsonSelect.exists(path, getValue(a)).toIO)
                          .map(_.getOrElse(false))
                          .catchAll(_ => false.succeed)
                      })
                    }
          res    <- MatchHolderZio[A](DebugInfo(KVC.JsonSelect.id, Some(value)), extractor(n), matcher).matches
        } yield res

      case c => MatchHolder[A](DebugInfo(s"unknown comparator: ${c}", Some(value)), Chunk(), _ => false).matches
    }
  }
}

object EnvironmentVariableMatcher extends NodeCriterionKeyValueMatcher[(String, String)] {
  override def extractor:                      NodeFact => Chunk[(String, String)] = { (n: NodeFact) => n.environmentVariables }
  override def serialise(a: (String, String)): String                              = s"""${a._1}=${a._2}"""
  override def getKey(a: (String, String)):    String                              = a._1
  override def getValue(a: (String, String)):  String                              = a._2
}

object NodePropertiesMatcher extends NodeCriterionKeyValueMatcher[NodeProperty] {
  /*
   * Node properties search are done on both node properties and inventory custom properties
   */
  override def extractor:                  NodeFact => Chunk[NodeProperty] = { (n: NodeFact) => n.properties }
  override def serialise(a: NodeProperty): String                          = s"""${a.name}=${a.valueAsString}"""
  override def getKey(a: NodeProperty):    String                          = a.name
  override def getValue(a: NodeProperty):  String                          = a.valueAsString
}
