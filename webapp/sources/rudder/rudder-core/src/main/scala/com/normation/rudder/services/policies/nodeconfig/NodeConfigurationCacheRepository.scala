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

package com.normation.rudder.services.policies.nodeconfig

import better.files.File
import cats.implicits.*
import com.normation.box.*
import com.normation.cfclerk.domain.TechniqueVersion
import com.normation.cfclerk.domain.Variable
import com.normation.errors.*
import com.normation.inventory.domain.NodeId
import com.normation.ldap.sdk.LDAPConnectionProvider
import com.normation.ldap.sdk.LDAPEntry
import com.normation.ldap.sdk.RwLDAPConnection
import com.normation.rudder.domain.RudderDit
import com.normation.rudder.domain.RudderLDAPConstants.A_NODE_CONFIG
import com.normation.rudder.domain.RudderLDAPConstants.OC_NODES_CONFIG
import com.normation.rudder.domain.logger.ApplicationLoggerPure
import com.normation.rudder.domain.logger.PolicyGenerationLoggerPure
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.services.policies.NodeConfiguration
import com.normation.rudder.services.policies.Policy
import com.normation.rudder.services.policies.PolicyId
import com.normation.zio.*
import java.nio.charset.StandardCharsets
import net.liftweb.common.Box
import net.liftweb.common.Full
import net.liftweb.common.Loggable
import net.liftweb.json.*
import net.liftweb.json.JsonDSL.*
import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat
import scala.util.control.NonFatal
import zio.*
import zio.syntax.*

final case class PolicyHash(
    draftId:    PolicyId,
    cacheValue: Int
)

final case class NodeConfigurationHashes(hashes: List[NodeConfigurationHash])

object NodeConfigurationHashes {

  // we really want to have one line by node
  def toJson(hashes: NodeConfigurationHashes): String = {
    s"""{"hashes": [${hashes.hashes.map(x => NodeConfigurationHash.toJson(x)).mkString("\n  ", ",\n  ", "\n")}] }"""
  }

  def fromJson(json: String): IOResult[NodeConfigurationHashes] = {
    for {
      jval <- IOResult.attempt(parse(json))
      arr  <- (jval \ "hashes") match {
                case JNothing | JNull => Nil.succeed
                case JArray(arr)      => arr.succeed
                case x                =>
                  Inconsistency(
                    s"Can not parse json as a cache of node configuration hashes. Found: ${compactRender(x).take(100)}..."
                  ).fail
              }
      // ignore only invalide node config hashses
      hs   <- ZIO.foldLeft(arr)(List.empty[NodeConfigurationHash]) {
                case (all, current) =>
                  NodeConfigurationHash.extractNodeConfigCache(current) match {
                    case Left((m, j)) =>
                      PolicyGenerationLoggerPure.error(
                        s"Can not parse following json as node configuration hash: ${m}; corresponding entry will be ignored: ${compactRender(j)}. "
                      ) *>
                      all.succeed
                    case Right(h)     =>
                      (h :: all).succeed
                  }
              }
    } yield {
      NodeConfigurationHashes(hs)
    }
  }

}

/**
 * NodeConfigurationHash keep a track of anything that would mean
 * the node policies have changed and should be rewritten.
 * We are at the node granularity check, because today, we don't
 * know how to rewrite only some directives.
 *
 * Keep in mind that anything that is changing the related resources
 * of policies that are written for the node during policy generation
 * must be taken into account in the hash.
 * Typically, the technique non-template resources, like pure CFEngine
 * file or other configuration files, must be looked for change
 * (for them, this is done with the technique "acceptation" (i.e commit)
 * date.
 *
 */
final case class NodeConfigurationHash(
    id:              NodeId,
    writtenDate:     DateTime,
    nodeInfoHash:    Int,
    parameterHash:   Int,
    nodeContextHash: Int,
    policyHash:      Set[PolicyHash]
) {
  // We need a method to correctly compare a NodeConfigurationHash that was serialized
  // from a NodeConfigurationHash created from a NodeConfiguration (so without writtenDate)
  def equalWithoutWrittenDate(other: NodeConfigurationHash): Boolean = {
    id == other.id &&
    nodeInfoHash == other.nodeInfoHash &&
    parameterHash == other.parameterHash &&
    nodeContextHash == other.nodeContextHash &&
    policyHash == other.policyHash
  }
}

object NodeConfigurationHash {

  /*
   * Serialization / unserialization to json.
   * We want something as compact as possible, so NodeConfigurationHash are serialized like that:
   * { i: [ "nodeid"(string), "writtendate"(string), nodeInfoCache(int), parameterHash(int), nodecContextHash(int)]
   * , p: [ [ "ruleId"(string), "directiveid"(string), "techniqueversion"(string), hash(int)]
   *      , [ ....
   *      ]
   * } (minified)
   *
   */
  def toJvalue(hash: NodeConfigurationHash): JValue = {
    (
      ("i"   -> JArray(
        List(
          hash.id.value,
          hash.writtenDate.toString(ISODateTimeFormat.dateTime().withZoneUTC()),
          hash.nodeInfoHash,
          hash.parameterHash,
          hash.nodeContextHash
        )
      ))
      ~ ("p" -> JArray(
        hash.policyHash.toList.map(p => {
          JArray(
            List(p.draftId.ruleId.serialize, p.draftId.directiveId.serialize, p.draftId.techniqueVersion.serialize, p.cacheValue)
          )
        })
      ))
    )
  }
  def toJson(hash: NodeConfigurationHash):   String = {
    compactRender(toJvalue(hash))
  }

  def extractNodeConfigCache(j: JValue): Either[(String, JValue), NodeConfigurationHash] = {
    def readDate(date: String): Either[(String, JValue), DateTime] = try {
      Right(ISODateTimeFormat.dateTimeParser().withZoneUTC().parseDateTime(date))
    } catch {
      case NonFatal(ex) => Left((s"Error, written date can not be parsed as a date: ${date}", JString(date)))
    }

    def readPolicy(p: JValue) = {
      p match {
        case JArray(List(JString(ruleId), JString(directiveId), JString(techniqueVerion), JInt(policyHash))) =>
          for {
            version <- TechniqueVersion.parse(techniqueVerion).leftMap(err => (err, p))
            did     <- DirectiveId.parse(directiveId).leftMap(err => (err, p))
            rid     <- RuleId.parse(ruleId).leftMap(err => (err, p))
          } yield PolicyHash(PolicyId(rid, did, version), policyHash.toInt)
        case x                                                                                               => Left((s"Error when parsing policy: a json array", x))
      }
    }

    j match {
      case JObject(
            List(
              JField(
                "i",
                JArray(List(JString(nodeId), JString(date), JInt(nodeInfoCache), JInt(paramCache), JInt(nodeContextCache)))
              ),
              JField("p", JArray(policies))
            )
          ) =>
        for {
          d <- readDate(date)
          p <- policies.traverse(readPolicy(_))
        } yield {
          NodeConfigurationHash(NodeId(nodeId), d, nodeInfoCache.toInt, paramCache.toInt, nodeContextCache.toInt, p.toSet)
        }
      case _ => Left((s"Cannot parse node configuration hash (use 'DEBUG' log level for details).", j))
    }
  }

  def fromJson(json: String): Either[(String, JValue), NodeConfigurationHash] = {
    def jval = try {
      Right(parse(json))
    } catch {
      case NonFatal(ex) =>
        Left((s"Error when parsing node configuration cache entry. Expection was: ${ex.getMessage}", JString(json)))
    }

    for {
      v <- jval
      x <- extractNodeConfigCache(v)
    } yield {
      x
    }
  }

  /**
   * Build the hash from a node configuration.
   *
   */
  def apply(nodeConfig: NodeConfiguration, writtenDate: DateTime): NodeConfigurationHash = {

    /*
     * A parameter update must lead to a regeneration of the node,
     * because it could be used in pur CFEngine (when used in
     * directive, we already tracked it with directives values).
     *
     * So a modification in ANY parameter lead to a regeneration
     * of ALL NODES (everything, yes).
     *
     * For parameters, do not consider:
     * - overridable
     * - description
     * But as ParameterForConfiguration only has relevant information,
     * we can simply take the hashcode.
     * Also, don't depend of the datastructure used, nor
     * of the order of the variables (so is set is good ?)
     */
    val parameterHash: Int = {
      nodeConfig.parameters.hashCode
    }

    /*
     * Take into account anything that has influence on policies but
     * without being only a directive parameter (that latter case will
     * be handle directly at the directive level) or a system variable
     * (which is already in nodeContext)
     *
     * - all ${rudder.node} params (because can be used in pure CFEngine)
     * - node properties (because can be used in pure CFEngine)
     * - isPolicyServer (for nodes becoming relay)
     * - serverRoles (because not the same set of directives - but
     *   perhaps it is already handle in the directives)
     * - agentsName (because directly used in templates for if conditions)
     * - any node modes config option (policy mode, compliance, agent run -
     *   being node specific or global - because directly used by generation
     *   process to modify things all around)
     *
     * - publicKey / keyStatus? (I don't see why it should be ?)
     */
    val nodeInfoHashValue = {
      val i = nodeConfig.nodeInfo
      List[Int](
        i.fqdn.hashCode,
        i.rudderAgent.hashCode,
        i.rudderSettings.policyServerId.hashCode,
        i.properties.hashCode,
        i.rudderSettings.isPolicyServer.hashCode,
        nodeConfig.modesConfig.hashCode
      ).hashCode
    }

    /*
     * For policy draft, we want to check
     * - technique name, version, acceptation date
     *   (for acceptation date, we don't need to keep the date:
     *   either we don't have the technique in hash, and so we don't care of the date,
     *   or we have it, so we used the date for the previous cache, and so if the date
     *   is not the same it changed since the cache generation and we must regenerate)
     * - variables
     * - serial
     * - priority (may influence order of writting sequence of techniques)
     * - rule/directive order (may influense order in bundle sequence)
     * - policyMode (because influence call to "setDryMode")
     *
     * Ncf techniques update are taken into account thanks to the acceptation date.
     * System variables are tracked throught the node context afterward.
     */
    val policyHashValue = {
      nodeConfig.policies.map {
        case r: Policy =>
          // don't take into account "overrides" in cache: having more or less
          // ignored things must not impact the cache computation
          PolicyHash(
            r.id,
            (
              r.technique.id.hashCode
              + r.techniqueUpdateTime.hashCode
              + r.priority
              + r.ruleOrder.hashCode + r.directiveOrder.hashCode
              + r.policyMode.hashCode()
              + variablesToHash(r.expandedVars.values)
            )
          )
      }.toSet
    }

    /*
     * System variables are variables.
     *
     * Not that with that, we are taking into account
     * node config modes several time, since any mode has
     * a matching system variable.
     */
    val nodeContextHash: Int = {
      variablesToHash(nodeConfig.nodeContext.values)
    }

    new NodeConfigurationHash(
      id = nodeConfig.nodeInfo.id,
      writtenDate = writtenDate,
      nodeInfoHash = nodeInfoHashValue,
      parameterHash = parameterHash,
      nodeContextHash = nodeContextHash,
      policyHash = policyHashValue
    )
  }

  /*
   * Compute the hash cash value for a set of variables:
   * - the order does not matter
   * - duplicate (same name) variable make no sense, so we remove them
   * - only the ORDERED values matters
   *
   * Also, variable with no values are equivalent to no variable, so we
   * remove them.
   */
  private def variablesToHash(variables: Iterable[Variable]): Int = {
    val z = variables.map(x => (x.spec.name, x.values)).filterNot(_._2.isEmpty).toSet
    z.hashCode
  }

}

/**
 * A class that keep minimum information
 * to track changement in node configuration.
 */
trait NodeConfigurationHashRepository {

  /**
   * Delete node config by its id.
   * Returned deleted ids.
   */
  def deleteNodeConfigurations(nodeIds: Set[NodeId]): Box[Set[NodeId]]

  /**
   * delete all node configuration
   */
  def deleteAllNodeConfigurations(): Box[Unit]

  /**
   * Inverse of delete: delete all node configuration not
   * given in the argument.
   */
  def onlyKeepNodeConfiguration(nodeIds: Set[NodeId]): Box[Set[NodeId]]

  /**
   * Return all known NodeConfigurationHash
   */
  def getAll(): Box[Map[NodeId, NodeConfigurationHash]]

  /**
   * Update or add NodeConfigurationHash from parameters.
   * No existing NodeConfigurationHash is deleted.
   * Return newly cache node configuration.
   */
  def save(NodeConfigurationHash: Set[NodeConfigurationHash]): Box[Set[NodeId]]
}

class InMemoryNodeConfigurationHashRepository extends NodeConfigurationHashRepository {

  private val repository = scala.collection.mutable.Map[NodeId, NodeConfigurationHash]()

  /**
   * Delete a node by its id
   */
  def deleteNodeConfigurations(nodeIds: Set[NodeId]): Box[Set[NodeId]] = {
    repository --= nodeIds
    Full(nodeIds)
  }

  /**
   * delete all node configuration
   */
  def deleteAllNodeConfigurations(): Box[Unit] = {
    val values = repository.keySet
    repository.clear()

    Full(values.toSet)
  }

  /**
   * Inverse of delete: delete all node configuration not
   * given in the argument.
   */
  def onlyKeepNodeConfiguration(nodeIds: Set[NodeId]): Box[Set[NodeId]] = {
    val remove = repository.keySet.diff(nodeIds)
    repository --= remove
    Full(nodeIds)
  }

  def getAll(): Box[Map[NodeId, NodeConfigurationHash]] = Full(repository.toMap)

  def save(NodeConfigurationHash: Set[NodeConfigurationHash]): Box[Set[NodeId]] = {
    val toAdd = NodeConfigurationHash.map(c => (c.id, c)).toMap
    repository ++= toAdd
    Full(toAdd.keySet)
  }
}

object FileBasedNodeConfigurationHashRepository {
  // default path, to use in rudder (can be change for tests)
  val defaultHashesPath = "/var/rudder/policy-generation-info/node-configuration-hashes.json"
}

/*
 * A version of the repository that stores nodeinfo cache in the file (by default):
 * /var/rudder/policy-generation-info/node-configuration-hashes.json
 * Format on disc is a list of hashes in an object: { "nodeConfigurationHashes": [ { .... }, {.... } ] }
 */
class FileBasedNodeConfigurationHashRepository(path: String) extends NodeConfigurationHashRepository {

  val semaphore: Semaphore = Semaphore.make(1).runNow

  val hashesFile: File = File(path)

  def checkFile(file: File): IOResult[Unit] = {
    (for {
      _ <- ZIO.whenZIO(IOResult.attempt(!hashesFile.parent.exists))(IOResult.attempt(hashesFile.parent.createDirectories()))
      _ <- ZIO.whenZIO(IOResult.attempt(!(hashesFile.parent.isDirectory && hashesFile.parent.isWritable))) {
             ApplicationLoggerPure.error(s"File at path '${hashesFile.parent.pathAsString}' must be writtable directory")
           }
      _ <- ZIO.whenZIO(IOResult.attempt(!hashesFile.exists))(IOResult.attempt(hashesFile.touch()))
      _ <- ZIO.whenZIO(IOResult.attempt(!(hashesFile.isRegularFile && hashesFile.isWritable))) {
             ApplicationLoggerPure.error(s"File at path '${hashesFile.pathAsString}' must be writtable file")
           }
    } yield ())
      .chainError(s"File to store node configuration hashes is not a regular file with write permission: ${file.pathAsString}")
  }

  // some check to run when class is instanciated. Won't fail, but may help debug problems.
  def init: Unit = {
    checkFile(hashesFile).catchAll(err => ApplicationLoggerPure.error(err.fullMsg)).runNow
  }

  // read the file, of return the empty string if file does not exist
  def readHashesAsJsonString(): IOResult[String] = {
    IOResult.attempt(hashesFile.exists).flatMap {
      case true  => IOResult.attempt(hashesFile.contentAsString(using StandardCharsets.UTF_8))
      case false => "".succeed
    }
  }

  private def timeLog[A](msg: String => String)(effect: IOResult[A]): IOResult[A] = {
    for {
      t0  <- currentTimeMillis
      res <- effect
      t1  <- currentTimeMillis
      _   <- PolicyGenerationLoggerPure.timing.debug(msg(s"${t1 - t0} ms"))
    } yield res
  }

  /*
   * We never want to fail policy generation because we didn't successfully read cache,
   * but we want to output a BIR error, and return an empty cache.
   */
  private def nonAtomicRead(): UIO[NodeConfigurationHashes] = {
    (for {
      json   <- readHashesAsJsonString()
      hashes <- NodeConfigurationHashes.fromJson(json)
    } yield hashes).catchAll(err => {
      PolicyGenerationLoggerPure.error(
        s"Error when trying to read node configuration hashes, they will be ignored: a full generation will take place. Error was: ${err.fullMsg}"
      ) *>
      NodeConfigurationHashes(Nil).succeed
    })
  }

  private def nonAtomicWrite(hashes: NodeConfigurationHashes): IOResult[Unit] = {
    import java.nio.file.StandardOpenOption.*
    IOResult.attempt(
      hashesFile.writeText(NodeConfigurationHashes.toJson(hashes))(using
        Seq(WRITE, CREATE, TRUNCATE_EXISTING),
        StandardCharsets.UTF_8
      )
    )
  }

  ///// interface implementation /////

  override def deleteAllNodeConfigurations(): Box[Unit] = {
    timeLog(s => s"Deleting node configuration hashes took ${s}")(
      semaphore.withPermits(1)(
        IOResult
          .attempt(
            if (hashesFile.exists) hashesFile.delete() else () // ok, nothing to do
          )
          .unit
      )
    ).toBox
  }

  override def deleteNodeConfigurations(nodeIds: Set[NodeId]): Box[Set[NodeId]] = {
    timeLog(s => s"Deleting up to ${nodeIds.size} node configuration hashes from cache took ${s}")(semaphore.withPermits(1) {
      for {
        hashes <- nonAtomicRead()
        updated = NodeConfigurationHashes(hashes.hashes.filterNot(c => nodeIds.contains(c.id)))
        _      <- nonAtomicWrite(updated)
      } yield nodeIds
    }).toBox
  }

  override def onlyKeepNodeConfiguration(nodeIds: Set[NodeId]): Box[Set[NodeId]] = {
    timeLog(s => s"Keeping at most ${nodeIds.size} node configuration hashes from cache took ${s}")(semaphore.withPermits(1) {
      for {
        hashes <- nonAtomicRead()
        updated = NodeConfigurationHashes(hashes.hashes.filter(c => nodeIds.contains(c.id)))
        _      <- nonAtomicWrite(updated)
      } yield nodeIds
    }).toBox
  }

  override def getAll(): Box[Map[NodeId, NodeConfigurationHash]] = {
    timeLog(s => s"Get all node configuration hashes took ${s}")(semaphore.withPermits(1) {
      nonAtomicRead().map(h => h.hashes.map(x => (x.id, x)).toMap)
    }).toBox
  }

  override def save(hashes: Set[NodeConfigurationHash]): Box[Set[NodeId]] = {
    val nodeIds = hashes.map(_.id)
    timeLog(s => s"Updating node configuration hashes took ${s}")(
      if (hashes.size == 0) nodeIds.succeed
      else {
        semaphore.withPermits(1) {
          for {
            current <- nonAtomicRead()
            filtered = current.hashes.filterNot(h => nodeIds.contains(h.id))
            updated  = (filtered ++ hashes).sortBy(_.id.value)
            _       <- nonAtomicWrite(NodeConfigurationHashes(updated))
          } yield nodeIds
        }
      }
    ).toBox
  }
}

/**
 * An implementation into LDAP
 */
class LdapNodeConfigurationHashRepository(
    rudderDit: RudderDit,
    ldapCon:   LDAPConnectionProvider[RwLDAPConnection]
) extends NodeConfigurationHashRepository with Loggable {

  /**
   * Logic: there is only one object that contains all node config cache.
   * Each node config cache is stored in one value of the "nodeConfig" attribute.
   * The serialisation is simple json.
   * We won't be able to simply delete one value with that method, but it is not
   * the principal use case.
   */

  /**
   * That mapping ignore malformed node configuration and work in a
   * "best effort" way. Bad config are logged as error.
   * We fail if the entry is not of the expected type
   */
  private def fromLdap(entry: Option[LDAPEntry]): IOResult[Set[NodeConfigurationHash]] = {
    entry match {
      case None    => Set.empty[NodeConfigurationHash].succeed
      case Some(e) =>
        for {
          typeOk <- ZIO.when(!e.isA(OC_NODES_CONFIG)) {
                      Inconsistency(
                        s"Entry ${e.dn} is not a '${OC_NODES_CONFIG}', can not find node configuration caches. Entry details: ${e}"
                      ).fail
                    }
        } yield {
          e.valuesFor(A_NODE_CONFIG).flatMap { json =>
            NodeConfigurationHash.fromJson(json) match {
              case Right(value)        => Some(value)
              case Left((error, json)) =>
                logger.info(s"Ignoring node configuration cache info because of deserialization issue: ${error}")
                logger.debug(s"Json causing node configuration deserialization issue: ${net.liftweb.json.compactRender(json)}")
                None
            }
          }
        }
    }
  }

  private def toLdap(nodeConfigs: Set[NodeConfigurationHash]): LDAPEntry = {
    val caches = nodeConfigs.map(x => NodeConfigurationHash.toJson(x))
    val entry  = rudderDit.NODE_CONFIGS.model
    entry.resetValuesTo(A_NODE_CONFIG, caches.toSeq*)
    entry
  }

  /*
   * Delete node config matching predicate.
   * Return the list of remaining ids.
   */
  private def deleteCacheMatching(shouldDeleteConfig: NodeConfigurationHash => Boolean): IOResult[Set[NodeId]] = {
    for {
      ldap         <- ldapCon
      currentEntry <- ldap.get(rudderDit.NODE_CONFIGS.dn)
      remaining    <- fromLdap(currentEntry).map(_.filterNot(shouldDeleteConfig))
      newEntry      = toLdap(remaining)
      saved        <- ldap.save(newEntry)
    } yield {
      remaining.map(_.id)
    }
  }

  /**
   * Delete node config by its id
   */
  def deleteNodeConfigurations(nodeIds: Set[NodeId]): Box[Set[NodeId]] = {
    for {
      _ <- deleteCacheMatching(nodeConfig => nodeIds.contains(nodeConfig.id))
    } yield {
      nodeIds
    }
  }.toBox

  /**
   * Inverse of delete: delete all node configuration not
   * given in the argument.
   */
  def onlyKeepNodeConfiguration(nodeIds: Set[NodeId]): Box[Set[NodeId]] = {
    for {
      _ <- deleteCacheMatching(nodeConfig => !nodeIds.contains(nodeConfig.id))
    } yield {
      nodeIds
    }
  }.toBox

  /**
   * delete all node configuration
   */
  def deleteAllNodeConfigurations(): Box[Unit] = {
    for {
      ldap    <- ldapCon
      deleted <- ldap.delete(rudderDit.NODE_CONFIGS.dn)
      cleaned <- ldap.save(rudderDit.NODE_CONFIGS.model)
    } yield {
      ()
    }
  }.toBox

  def getAll(): Box[Map[NodeId, NodeConfigurationHash]] = {
    for {
      ldap        <- ldapCon
      entry       <- ldap.get(rudderDit.NODE_CONFIGS.dn)
      nodeConfigs <- fromLdap(entry)
    } yield {
      nodeConfigs.map(x => (x.id, x)).toMap
    }
  }.toBox

  def save(caches: Set[NodeConfigurationHash]): Box[Set[NodeId]] = {
    val updatedIds = caches.map(_.id)
    for {
      ldap          <- ldapCon
      existingEntry <- ldap.get(rudderDit.NODE_CONFIGS.dn)
      existingCache <- fromLdap(existingEntry)
      // only update and add, keep existing config cache not updated
      keptConfigs    = existingCache.map(x => (x.id, x)).toMap.view.filterKeys(k => !updatedIds.contains(k)).toMap
      entry          = toLdap(caches ++ keptConfigs.values)
      saved         <- ldap.save(entry)
    } yield {
      updatedIds
    }
  }.toBox
}
