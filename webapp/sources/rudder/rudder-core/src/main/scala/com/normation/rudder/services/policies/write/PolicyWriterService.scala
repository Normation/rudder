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

package com.normation.rudder.services.policies.write

import better.files.*
import cats.data.NonEmptyList
import com.normation.box.*
import com.normation.cfclerk.domain.SystemVariable
import com.normation.cfclerk.domain.TechniqueFile
import com.normation.cfclerk.domain.TechniqueResourceId
import com.normation.cfclerk.domain.TechniqueTemplate
import com.normation.cfclerk.domain.Variable
import com.normation.cfclerk.services.TechniqueRepository
import com.normation.errors.*
import com.normation.inventory.domain.AgentType
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.Constants
import com.normation.rudder.domain.logger.NodeConfigurationLogger
import com.normation.rudder.domain.logger.PolicyGenerationLogger
import com.normation.rudder.domain.logger.PolicyGenerationLoggerPure
import com.normation.rudder.domain.policies.GlobalPolicyMode
import com.normation.rudder.domain.policies.PolicyTypeName
import com.normation.rudder.domain.properties.NodeProperty
import com.normation.rudder.domain.reports.NodeConfigId
import com.normation.rudder.facts.nodes.CoreNodeFact
import com.normation.rudder.hooks.HookEnvPairs
import com.normation.rudder.hooks.HookReturnCode
import com.normation.rudder.hooks.RunHooks
import com.normation.rudder.services.policies.BundleOrder
import com.normation.rudder.services.policies.NodeConfiguration
import com.normation.rudder.services.policies.ParameterEntry
import com.normation.rudder.services.policies.Policy
import com.normation.rudder.services.policies.PolicyId
import com.normation.templates.FillTemplatesService
import com.normation.templates.FillTemplateThreadUnsafe
import com.normation.templates.FillTemplateTimer
import com.normation.templates.STVariable
import com.normation.zio.*
import java.nio.charset.Charset
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.TimeUnit
import net.liftweb.common.*
import net.liftweb.json.JsonAST
import net.liftweb.json.JsonAST.JValue
import org.apache.commons.io.FileUtils
import org.joda.time.DateTime
import zio.*
import zio.syntax.*

/**
 * Write promises for the set of nodes, with the given configs.
 * Requires access to external templates files.
 */
trait PolicyWriterService {

  /**
   * Write templates for node configuration that changed since the last write.
   *
   */
  def writeTemplate(
      rootNodeId:       NodeId,
      nodesToWrite:     Set[NodeId],
      allNodeConfigs:   Map[NodeId, NodeConfiguration],
      allNodeInfos:     Map[NodeId, CoreNodeFact],
      versions:         Map[NodeId, NodeConfigId],
      globalPolicyMode: GlobalPolicyMode,
      generationTime:   DateTime,
      parallelism:      Int
  ): Box[Seq[NodeId]]
}

object PolicyWriterServiceImpl {
  import PosixFilePermission.*
  // we want: /bin/chmod u-x,u+rwX,g-wx,g+rX,o-rwx ... for nodes other than root
  val defaultFilePerms:      Set[PosixFilePermission] = Set[PosixFilePermission](OWNER_READ, OWNER_WRITE, GROUP_READ)
  val defaultDirectoryPerms: Set[PosixFilePermission] =
    Set[PosixFilePermission](OWNER_READ, OWNER_WRITE, OWNER_EXECUTE, GROUP_READ, GROUP_EXECUTE)
  // we want: /bin/chmod u-x,u+rwX,g-rwx,o-rwx ... for root
  val rootFilePerms:         Set[PosixFilePermission] = Set[PosixFilePermission](OWNER_READ, OWNER_WRITE)
  val rootDirectoryPerms:    Set[PosixFilePermission] = Set[PosixFilePermission](OWNER_READ, OWNER_WRITE, OWNER_EXECUTE)

  def createParentsIfNotExist(file: File, optPerms: Option[Set[PosixFilePermission]], optGroupOwner: Option[String]): Unit = {
    file.parentOption.foreach { parent =>
      if (!parent.exists) {
        createParentsIfNotExist(parent, optPerms, optGroupOwner)
        try {
          parent.createDirectory()
        } catch {
          case _: FileAlreadyExistsException => // too late, does nothing
        }
        optPerms.foreach(parent.setPermissions)
        optGroupOwner.foreach(parent.setGroup)
      }
    }

  }

  def moveFile(
      src:           File,
      dest:          File,
      mvOptions:     Option[File.CopyOptions],
      optPerms:      Option[Set[PosixFilePermission]],
      optGroupOwner: Option[String]
  ): Any = {
    createParentsIfNotExist(dest, optPerms, optGroupOwner)
    // must use FileUtils on different fs, see: https://issues.rudder.io/issues/19218
    mvOptions match {
      case Some(opts) => src.moveTo(dest)(opts)
      case None       => FileUtils.moveDirectory(src.toJava, dest.toJava)
    }
    // optGroupOwner.foreach(dest.setGroup(_))
  }

  // some file path to write in destination agent input directory:
  object filepaths {
    val SYSTEM_VARIABLE_JSON = "rudder.json"
    val DIRECTIVE_RUN_CSV    = "rudder-directives.csv"
    val POLICY_SERVER_CERT   = "certs/policy-server.pem"
    val ROOT_SERVER_CERT     = "certs/root.pem"
  }
}

/*
 * Sort Policy by order based on rule name / directive name and consistent
 * between different places (rudder-directives.cf, rudder-directives.csv...)
 */
object PolicyOrdering {
  /*
   * Sort the techniques according to the order of the associated BundleOrder of Policy.
   * Sort at best: sort rule then directives, and take techniques on that order, only one time.
   *
   * CAREFUL: this method only take care of sorting based on "BundleOrder", other sorting (like
   * "system must go first") are not taken into account here !
   *
   * We also keep compliance tag together, ordered alpha-num,
   */
  def sort(
      policies: Seq[Policy]
  ): Seq[Policy] = {
    // get system tag if present, else the first alpha-num
    def getTagOrder(p: Policy): BundleOrder = {
      if (p.technique.policyTypes.isSystem) {
        BundleOrder(PolicyTypeName.rudderSystem.value)
      } else {
        BundleOrder(
          p.technique.policyTypes.types.toList.map(_.value).sorted.headOption.getOrElse(PolicyTypeName.rudderBase.value)
        )
      }
    }

    def compareBundleOrder(a: Policy, b: Policy): Boolean = {
      // We use rule name, then directive name. For same rule name and directive name, we
      // differentiate on technique id, then on directive id (to keep diff minimal)
      BundleOrder.compareList(
        List(getTagOrder(a), a.ruleOrder, a.directiveOrder, BundleOrder(a.id.getRudderUniqueId)),
        List(getTagOrder(b), b.ruleOrder, b.directiveOrder, BundleOrder(b.id.getRudderUniqueId))
      ) <= 0
    }

    val sorted = policies.sortWith(compareBundleOrder)

    // some debug info to understand what order was used for each node:
    // it's *extremely* verbose, perhaps it should have it's own logger.
    if (PolicyGenerationLogger.isTraceEnabled) {
      val logSorted = sorted
        .map(p => s"${p.technique.id.serialize}: [${p.ruleOrder.value} | ${p.directiveOrder.value}]")
        .mkString("[", "][", "]")
      PolicyGenerationLogger.trace(s"Sorted Technique (and their Rules and Directives used to sort): ${logSorted}")
    }
    sorted
  }
}

/*
 * Timer accumulator for write part, in nanoseconds
 */
case class WriteTimer(
    writeTemplate: Ref[Long],
    fillTemplate:  Ref[Long],
    copyResources: Ref[Long],
    agentSpecific: Ref[Long],
    writeCSV:      Ref[Long],
    writeJSON:     Ref[Long],
    writePEM:      Ref[Long]
)

object WriteTimer {
  def make(): ZIO[Any, Nothing, WriteTimer] = for {
    a <- Ref.make(0L)
    b <- Ref.make(0L)
    c <- Ref.make(0L)
    d <- Ref.make(0L)
    e <- Ref.make(0L)
    f <- Ref.make(0L)
    g <- Ref.make(0L)
  } yield WriteTimer(a, b, c, d, e, f, g)
}

final case class ToRead(techniqueId: TechniqueResourceId, agentType: AgentType, outpath: String)

class PolicyWriterServiceImpl(
    techniqueRepository:        TechniqueRepository,
    pathComputer:               PathComputer,
    logNodeConfig:              NodeConfigurationLogger,
    prepareTemplate:            PrepareTemplateVariables,
    fillTemplates:              FillTemplatesService,
    writeAllAgentSpecificFiles: WriteAllAgentSpecificFiles,
    HOOKS_D:                    String,
    HOOKS_IGNORE_SUFFIXES:      List[String],
    implicit val charset:       Charset,
    groupOwner:                 Option[String]
) extends PolicyWriterService {
  import com.normation.rudder.services.policies.write.PolicyWriterServiceImpl.*

  val clock        = ZioRuntime.environment
  val timingLogger = PolicyGenerationLoggerPure.timing

  val hookGlobalWarnTimeout: Duration = 60.seconds // max time before warning for executing all hooks
  val hookUnitWarnTimeout:   Duration = 2.seconds  // max time before warning for executing each hook
  val hookUnitKillTimeout:   Duration = 20.seconds // max time before killing for executing one hook

  val newPostfix    = ".new"
  val backupPostfix = ".bkp"

  // an utility that write text in a file and create file parents if needed
  implicit class CreateParentAndWrite(val file: File) {
    def getPerms(isRootServer: Boolean):                            (Option[String], Set[PosixFilePermission], Set[PosixFilePermission]) = {
      if (isRootServer) {
        (None, rootFilePerms, rootDirectoryPerms)
      } else {
        (groupOwner, defaultFilePerms, defaultDirectoryPerms)
      }
    }
    import StandardOpenOption.*
    // open file mode for create or overwrite mode
    def createParentsAndWrite(text: String, isRootServer: Boolean): IO[SystemError, Unit]                                                = IOResult.attempt {
      val (optGroupOwner, filePerms, dirPerms) = getPerms(isRootServer)
      createParentsIfNotExist(file, Some(dirPerms), optGroupOwner)
      file.writeText(text)(Seq(WRITE, TRUNCATE_EXISTING, CREATE), charset).setPermissions(filePerms)
      optGroupOwner.foreach(file.setGroup)
    }

    def createParentsAndWrite(content: Array[Byte], isRootServer: Boolean): IO[SystemError, Unit] = IOResult.attempt {
      val (optGroupOwner, filePerms, dirPerms) = getPerms(isRootServer)
      createParentsIfNotExist(file, Some(dirPerms), optGroupOwner)
      file.writeByteArray(content)(Seq(WRITE, TRUNCATE_EXISTING, CREATE)).setPermissions(filePerms)
      optGroupOwner.foreach(file.setGroup)
    }
  }

  private def writeNodePropertiesFile(agentNodeConfig: AgentNodeConfiguration) = {

    def generateNodePropertiesJson(properties: Seq[NodeProperty]): JValue = {
      import net.liftweb.json.JsonDSL.*
      ("properties" -> properties.toDataJson)
    }

    val path            = Constants.GENERATED_PROPERTY_DIR
    val file            = File(agentNodeConfig.paths.newFolder, path, Constants.GENERATED_PROPERTY_FILE)
    val jsonProperties  = generateNodePropertiesJson(agentNodeConfig.config.nodeInfo.properties)
    val propertyContent = JsonAST.prettyRender(jsonProperties)

    for {
      _ <-
        PolicyGenerationLoggerPure.trace(s"Create node properties file '${agentNodeConfig.paths.newFolder}/${path}/${file.name}'")
      _ <- file
             .createParentsAndWrite(propertyContent, agentNodeConfig.config.isRootServer)
             .chainError(s"could not write file: '${file.name} '")
    } yield ()
  }

  private def writeRudderParameterFile(agentNodeConfig: AgentNodeConfiguration): IOResult[Unit] = {
    def generateParametersJson(parameters: Set[ParameterEntry]): JValue = {
      import com.normation.rudder.domain.properties.JsonPropertySerialisation.*
      import net.liftweb.json.JsonDSL.*
      ("parameters" -> parameters.toDataJson)
    }

    val file             = File(agentNodeConfig.paths.newFolder, Constants.GENERATED_PARAMETER_FILE)
    val jsonParameters   = generateParametersJson(
      agentNodeConfig.config.parameters.map(x => ParameterEntry(x.name, x.value, agentNodeConfig.agentType))
    )
    val parameterContent = JsonAST.prettyRender(jsonParameters)

    for {
      _ <- PolicyGenerationLoggerPure.trace(s"Create parameter file '${agentNodeConfig.paths.newFolder}/${file.name}'")
      _ <- file
             .createParentsAndWrite(parameterContent, agentNodeConfig.config.isRootServer)
             .chainError(s"could not write file: ' ${file.name}'")
    } yield ()
  }

  /*
   * For all the writing part, we want to limit the number of concurrent workers on two aspects:
   * - we want an I/O thread-pool, which knows what to do when a thread is blocked for a long time,
   *   and risks thread exhaustion
   *   (it can happens, since we have a number of files to write, some maybe big)
   * - we want to limit the total number of concurrent execution to avoid blowing up the number
   *   of open files, concurrent hooks, etc. This is not directly linked to the number of CPU,
   *   but clearly there is no point having a pool of 4 threads for 1000 nodes which will
   *   fork 1000 times for hooks - even if there is 4 threads used for that.
   *
   * That means that we want something looking like a sequencePar interface, but with:
   * - a common thread pool, i/o oriented
   * - a common max numbers of concurrent tasks.
   *   I believe it should be common to all steps because it's mostly on the same machine - but
   *   for now, that doesn't really matter, since each step is blocking (ie wait before next).
   *   A parallelism around the thread pool sizing (by default, number of CPU) seems ok.
   *
   * here, f must not throws exception.
   *
   */
  def parallelSequence[U, T](
      seq: Seq[U]
  )(f: U => IOResult[T])(implicit timeout: Duration, maxParallelism: Int): IOResult[Seq[T]] = {
    (seq
      .accumulateParN(maxParallelism)(f))
      .timeoutFail(
        Accumulated(NonEmptyList.one(Unexpected(s"Execution of computation timed out after '${timeout.asJava.toString}'")))
      )(timeout)
  }

  // a version for Hook with a nicer message accumulation
  def parallelSequenceNodeHook(
      seq: Seq[AgentNodeConfiguration]
  )(f: AgentNodeConfiguration => IOResult[HookReturnCode])(implicit timeout: Duration, maxParallelism: Int): IOResult[Unit] = {

    // a dedicated error for hooks
    final case class HookError(nodeId: NodeId, errorCode: HookReturnCode.Error) extends RudderError {
      def limitOut(s: String) = {
        val max = 300
        if (s.size <= max) s else s.substring(0, max - 3) + "..."
      }
      val msg                 = errorCode match {
        // we need to limit sdtout/sdterr length
        case HookReturnCode.ScriptError(code, stdout, stderr, msg) =>
          s"${msg} [stdout:${limitOut(stdout)}][stderr:${limitOut(stderr)}]"
        case x                                                     => x.msg
      }
    }

    seq
      .accumulateParNELN(maxParallelism) { a =>
        f(a).foldZIO(
          ex => HookError(a.config.nodeInfo.id, HookReturnCode.SystemError(ex.fullMsg)).fail,
          code => {
            code match {
              case s: HookReturnCode.Success => s.succeed
              case e: HookReturnCode.Error   => HookError(a.config.nodeInfo.id, e).fail
            }
          }
        )
      }
      .timeout(timeout)
      .foldZIO(
        nel => {
          // in that case, it is extremely likely that most messages are the same. We group them together
          val nodeErrors = nel.toList.map(err => (err.nodeId, err.msg))
          val message    = nodeErrors.groupBy(_._2).foldLeft("Error when executing hooks:") {
            case (s, (msg, list)) =>
              s + s"\n ${msg} (for node(s) ${list.map(_._1.value).mkString(";")})"
          }
          Unexpected(message).fail
        },
        ok => {
          ok match {
            case None    => Unexpected(s"Execution of computation timed out after '${timeout.asJava.toString}'").fail
            case Some(x) => ZIO.unit
          }
        }
      )
  }

  /**
   * Write templates for node configuration that changed since the last write.
   *
   */
  override def writeTemplate(
      rootNodeId:       NodeId,
      nodesToWrite:     Set[NodeId],
      allNodeConfigs:   Map[NodeId, NodeConfiguration],
      allNodeInfos:     Map[NodeId, CoreNodeFact],
      versions:         Map[NodeId, NodeConfigId],
      globalPolicyMode: GlobalPolicyMode,
      generationTime:   DateTime,
      parallelism:      Int
  ): Box[Seq[NodeId]] = {
    writeTemplatePure(
      rootNodeId,
      nodesToWrite,
      allNodeConfigs,
      allNodeInfos,
      versions,
      globalPolicyMode,
      generationTime,
      parallelism
    ).toBox
  }

  def writeTemplatePure(
      rootNodeId:       NodeId,
      nodesToWrite:     Set[NodeId],
      allNodeConfigs:   Map[NodeId, NodeConfiguration],
      allNodeInfos:     Map[NodeId, CoreNodeFact],
      versions:         Map[NodeId, NodeConfigId],
      globalPolicyMode: GlobalPolicyMode,
      generationTime:   DateTime,
      parallelism:      Int
  ): IOResult[Seq[NodeId]] = {

    val interestingNodeConfigs = allNodeConfigs.collect {
      case (nodeId, nodeConfiguration) if (nodesToWrite.contains(nodeId)) => nodeConfiguration
    }.toSeq

    // collect templates and files to read
    val (templateToRead, fileToRead) = allNodeConfigs.foldLeft((Set.empty[ToRead], Set.empty[ToRead])) {
      case (current, (_, conf)) =>
        conf.policies.map(_.technique).foldLeft(current) {
          case ((templates, files), next) =>
            (
              templates ++ next.agentConfig.templates.map(x => ToRead(x.id, next.agentConfig.agentType, x.outPath)),
              files ++ next.agentConfig.files.map(x => ToRead(x.id, next.agentConfig.agentType, x.outPath))
            )
        }
    }

    // debug - but don't fails for debugging !
    val logNodeConfigurations = ZIO.when(logNodeConfig.isDebugEnabled) {
      IOResult
        .attempt(logNodeConfig.log(interestingNodeConfigs))
        .foldZIO(
          err =>
            PolicyGenerationLoggerPure.error(s"Error when trying to write node configurations for debugging: ${err.fullMsg}"),
          ok => ZIO.unit
        )
    }

    /*
     * Here come the general writing process
     *
     * The general algorithm flow as following:
     * - for all nodes, for each of its agent, build path and configuration (both node&agent related)
     * - then, for each node/agent, prepare for each techniques the context variable to use, and the expected reports file to construct
     * - then, actually write things. For a node/agent, write into the ".new" directory:
     *   - for each technique:
     *        - the corresponding promises with templates filled
     *       - other resources files
     *   - the expected reports file
     *   - the bundle file
     * - then, copy the license, if applicable
     * - then, check generated promises and changes file permissions
     * - and finally, move everything to each node rules directory
     */

    // we need to add the current environment variables to the script context
    // plus the script environment variables used as script parameters

    // give a timeout for the whole tasks sufficiently large.
    // Hint: CF-promise taking 2s by node, for 10 000 nodes, on
    // 4 cores => ~85 minutes...
    // It is here mostly as a safeguard for generation which went wrong -
    // we will already have timeout at the thread level for stalling threads.
    implicit val timeout        = Duration(2, TimeUnit.HOURS)
    // Max number of fiber used in parallel.
    implicit val maxParallelism = parallelism

    for {
      _                    <- logNodeConfigurations
      systemEnv            <- System.envs.foldZIO(
                                err => SystemError("error when accessing environment variable to run hooks", err).fail,
                                vars => HookEnvPairs.build(vars.toSeq*).succeed
                              )
      readTemplateTime1    <- currentTimeMillis
      configAndPaths       <-
        calculatePathsForNodeConfigurations(interestingNodeConfigs, rootNodeId, allNodeInfos, newPostfix, backupPostfix)
      pathsInfo             = configAndPaths.map(_.paths)
      // we need for yield that to free all agent specific resources
      _                    <- for {
                                pair                          <- for {
                                                                   templates             <- readTemplateFromFileSystem(templateToRead.toSeq)
                                                                   resources             <- readResourcesFromFileSystem(fileToRead.toSeq)
                                                                   // Clearing cache
                                                                   _                     <- IOResult.attempt(fillTemplates.clearCache())
                                                                   readTemplateTime2     <- currentTimeMillis
                                                                   _                     <-
                                                                     timingLogger.debug(s"Paths computed and templates read in ${readTemplateTime2 - readTemplateTime1} ms")

                                                                   //////////
                                                                   // nothing agent specific before that
                                                                   //////////
                                                                   prepareTimer          <- PrepareTemplateTimer.make()
                                                                   preparedTemplates     <- parallelSequence(configAndPaths) {
                                                                                              case agentNodeConfig =>
                                                                                                val nodeConfigId = versions(agentNodeConfig.config.nodeInfo.id)
                                                                                                prepareTemplate
                                                                                                  .prepareTemplateForAgentNodeConfiguration(
                                                                                                    agentNodeConfig,
                                                                                                    nodeConfigId,
                                                                                                    rootNodeId,
                                                                                                    templates,
                                                                                                    allNodeConfigs,
                                                                                                    Policy.TAG_OF_RUDDER_ID,
                                                                                                    globalPolicyMode,
                                                                                                    generationTime,
                                                                                                    prepareTimer
                                                                                                  )
                                                                                                  .chainError(
                                                                                                    s"Error when calculating configuration for node '${agentNodeConfig.config.nodeInfo.fqdn}' (${agentNodeConfig.config.nodeInfo.id.value})"
                                                                                                  )
                                                                                            }
                                                                   preparedTemplatesTime <- currentTimeMillis
                                                                   _                     <-
                                                                     timingLogger.debug(s"Policy templates prepared in ${preparedTemplatesTime - readTemplateTime2} ms") *> {
                                                                       ZIO.when(timingLogger.logEffect.isTraceEnabled) {
                                                                         (prepareTimer.buildBundleSeq.get <*> prepareTimer.buildAgentVars.get <*> prepareTimer.prepareTemplate.get)
                                                                           .flatMap(t => {
                                                                             timingLogger.trace(
                                                                               s" -> bundle sequence built in ${t._1} ms | agent system variables built in ${t._2} ms | policy template prepared in ${t._3} ms"
                                                                             )
                                                                           })
                                                                       }
                                                                     }
                                                                 } yield {
                                                                   (preparedTemplates, resources)
                                                                 }
                                (preparedTemplates, resources) = pair

                                writeTimer         <- WriteTimer.make()
                                fillTimer          <- FillTemplateTimer.make()
                                beforeWritingTime  <- currentTimeMillis
                                promiseWritten     <- writePolicies(preparedTemplates, writeTimer, fillTimer)
                                others             <- writeOtherResources(preparedTemplates, writeTimer, globalPolicyMode, resources)
                                promiseWrittenTime <- currentTimeMillis
                                _                  <- timingLogger.debug(s"Policies written in ${promiseWrittenTime - beforeWritingTime} ms")
                                nanoToMillis        = 1000 * 1000
                                f                  <- writeTimer.fillTemplate.get.map(_ / nanoToMillis)
                                w                  <- writeTimer.writeTemplate.get.map(_ / nanoToMillis)
                                r                  <- writeTimer.copyResources.get.map(_ / nanoToMillis)
                                a                  <- writeTimer.agentSpecific.get.map(_ / nanoToMillis)
                                c                  <- writeTimer.writeCSV.get.map(_ / nanoToMillis)
                                j                  <- writeTimer.writeJSON.get.map(_ / nanoToMillis)
                                ftf                <- fillTimer.fill.get.map(_ / nanoToMillis)
                                fts                <- fillTimer.stringify.get.map(_ / nanoToMillis)
                                ftw                <- fillTimer.waitFill.get.map(_ / nanoToMillis)
                                gte                <- fillTimer.get.get.map(_ / nanoToMillis)
                                gtw                <- fillTimer.waitGet.get.map(_ / nanoToMillis)
                                _                  <-
                                  timingLogger.trace(
                                    s" -> fill template: ${f} ms [fill template: ${ftf} ms | to string template: ${fts} ms | fill template wait: ${ftw} ms | get template exec: ${gte} ms | get template wait: ${gtw} ms]"
                                  )
                                _                  <-
                                  timingLogger.trace(
                                    s" -> write template: ${w} ms | copy resources: ${r} ms | agent specific: ${a} ms | write CSV: ${c} ms| write JSON: ${j} ms"
                                  )
                                getRefTime         <- currentTimeMillis
                                _                  <- timingLogger.debug(s"Getting info for timing trace in ${getRefTime - promiseWrittenTime} ms")

                              } yield {
                                ()
                              }

      //////////
      // nothing agent specific after that
      //////////
      beforePropertiesTime <- currentTimeMillis
      propertiesWritten    <- parallelSequence(configAndPaths) {
                                case agentNodeConfig =>
                                  writeNodePropertiesFile(agentNodeConfig).chainError(
                                    s"An error occurred while writing property file for Node ${agentNodeConfig.config.nodeInfo.fqdn} (id: ${agentNodeConfig.config.nodeInfo.id.value}"
                                  )
                              }

      propertiesWrittenTime <- currentTimeMillis
      _                     <- timingLogger.debug(s"Properties written in ${propertiesWrittenTime - beforePropertiesTime} ms")

      parametersWritten <- parallelSequence(configAndPaths) {
                             case agentNodeConfig =>
                               writeRudderParameterFile(agentNodeConfig).chainError(
                                 s"An error occurred while writing parameter file for Node ${agentNodeConfig.config.nodeInfo.fqdn} (id: ${agentNodeConfig.config.nodeInfo.id.value}"
                               )
                           }

      parametersWrittenTime <- currentTimeMillis
      _                     <- timingLogger.debug(s"Parameters written in ${parametersWrittenTime - propertiesWrittenTime} ms")

      _              <- IOResult.attempt(fillTemplates.clearCache())
      /// perhaps that should be a post-hook somehow ?
      // and perhaps we should have an AgentSpecific global pre/post write
      preMvHooksName  = "policy-generation-node-ready"
      nodePreMvHooks <- RunHooks.getHooksPure(HOOKS_D + "/" + preMvHooksName, HOOKS_IGNORE_SUFFIXES)
      preMvHooks     <- parallelSequenceNodeHook(configAndPaths) { agentNodeConfig =>
                          val nodeId       = agentNodeConfig.config.nodeInfo.id.value
                          val hostname     = agentNodeConfig.config.nodeInfo.fqdn
                          val policyServer = agentNodeConfig.config.nodeInfo.rudderSettings.policyServerId.value
                          for {
                            timeHooks0 <- currentTimeMillis
                            res        <- RunHooks.asyncRun(
                                            preMvHooksName,
                                            nodePreMvHooks,
                                            HookEnvPairs.build(
                                              ("RUDDER_GENERATION_DATETIME", generationTime.toString),
                                              ("RUDDER_NODE_ID", nodeId),
                                              ("RUDDER_NODE_HOSTNAME", hostname),
                                              ("RUDDER_NODE_POLICY_SERVER_ID", policyServer),
                                              ("RUDDER_AGENT_TYPE", agentNodeConfig.agentType.id),
                                              (
                                                "RUDDER_POLICIES_DIRECTORY_NEW",
                                                agentNodeConfig.paths.newFolder
                                              ), // for compat in 4.1. Remove in 4.2

                                              ("RUDDER_NODEID", nodeId),
                                              ("RUDDER_NEXT_POLICIES_DIRECTORY", agentNodeConfig.paths.newFolder)
                                            ),
                                            systemEnv,
                                            hookGlobalWarnTimeout, // warn if all hooks took more than a minute

                                            hookUnitWarnTimeout,
                                            hookUnitKillTimeout
                                          )
                            timeHooks1 <- currentTimeMillis
                            _          <- timingLogger.hooks.trace(
                                            s"Run post-generation pre-move hooks for node '${nodeId}' in ${timeHooks1 - timeHooks0} ms"
                                          )
                          } yield res._1
                        }

      movedPromisesTime1 <- currentTimeMillis
      _                  <- timingLogger.hooks.debug(
                              s"Hooks for policy-generation-node-ready executed in ${movedPromisesTime1 - parametersWrittenTime} ms"
                            )
      movedPromises      <- movePromisesToFinalPosition(pathsInfo, maxParallelism)
      movedPromisesTime2 <- currentTimeMillis
      _                  <- timingLogger.debug(s"Policies moved to their final position in ${movedPromisesTime2 - movedPromisesTime1} ms")

      postMvHooksName   = "policy-generation-node-finished"
      nodePostMvHooks  <- RunHooks.getHooksPure(HOOKS_D + "/" + postMvHooksName, HOOKS_IGNORE_SUFFIXES)
      postMvHooks      <- parallelSequenceNodeHook(configAndPaths) { agentNodeConfig =>
                            val nodeId       = agentNodeConfig.config.nodeInfo.id.value
                            val hostname     = agentNodeConfig.config.nodeInfo.fqdn
                            val policyServer = agentNodeConfig.config.nodeInfo.rudderSettings.policyServerId.value
                            for {
                              timeHooks0 <- currentTimeMillis
                              res        <- RunHooks.asyncRun(
                                              postMvHooksName,
                                              nodePostMvHooks,
                                              HookEnvPairs.build(
                                                ("RUDDER_GENERATION_DATETIME", generationTime.toString),
                                                ("RUDDER_NODE_ID", nodeId),
                                                ("RUDDER_NODE_HOSTNAME", hostname),
                                                ("RUDDER_NODE_POLICY_SERVER_ID", policyServer),
                                                ("RUDDER_AGENT_TYPE", agentNodeConfig.agentType.id),
                                                (
                                                  "RUDDER_POLICIES_DIRECTORY_CURRENT",
                                                  agentNodeConfig.paths.baseFolder
                                                ), // for compat in 4.1. Remove in 4.2

                                                ("RUDDER_NODEID", nodeId),
                                                ("RUDDER_POLICIES_DIRECTORY", agentNodeConfig.paths.baseFolder)
                                              ),
                                              systemEnv,
                                              hookGlobalWarnTimeout, // warn if a hook took more than a minute

                                              hookUnitWarnTimeout,
                                              hookUnitKillTimeout
                                            )
                              timeHooks1 <- currentTimeMillis
                              _          <- timingLogger.hooks.trace(
                                              s"Run post-generation post-move hooks for node '${nodeId}' in ${timeHooks1 - timeHooks0} ms"
                                            )
                            } yield res._1
                          }
      postMvHooksTime2 <- currentTimeMillis
      _                <- timingLogger.hooks.debug(
                            s"Hooks for policy-generation-node-finished executed in ${postMvHooksTime2 - movedPromisesTime2} ms"
                          )
    } yield {
      pathsInfo.map(_.nodeId)
    }
  }

  ///////////// implementation of each step /////////////

  case class TemplateFillInfo(
      id:                TechniqueResourceId,
      destination:       String,
      newFolder:         String,
      envVars:           Seq[STVariable],
      reportIdToReplace: Option[PolicyId],
      isRootServer:      Boolean
  )
  /*
   * This method is an hot spot in Rudder. It is where most of the generation is taking time, and that for two reasons:
   * - the fisrt one is that we write a lot of template. Not much can be done here.
   * - the second is around template filling with StringTemplate. Parsing a template is very slow, so we cache them.
   *   But even once parsed, we can't just use the parsed value, we need to protect it with a semaphore from
   *   the moment we duplicate it to the moment we get the final string with replaced values: StringTemplate
   *   is not even a bit thread safe.
   *
   * Note: just making traverse for prepared technique/writePromisesFiles parallel increase latency by x5
   */
  private def writePolicies(
      preparedTemplates: Seq[AgentNodeWritableConfiguration],
      writeTimer:        WriteTimer,
      fillTimer:         FillTemplateTimer
  )(implicit maxParallelism: Int, timeout: Duration): IOResult[Unit] = {
    /*
     * Idea: sort template by content (ie what is cached). Put each unique cache in a seq with the corresponding list of
     * template to fill.
     * Get the corresponding list of filled template.
     * Write it.
     * No semaphore, no synchronisation.
     */

    // template are created based on "content"
    val byTemplate = (for {
      p  <- preparedTemplates
      pt <- p.preparedTechniques
      t  <- pt.templatesToProcess
    } yield {
      (
        t.content,
        TemplateFillInfo(
          t.id,
          t.destination,
          p.paths.newFolder,
          pt.environmentVariables,
          pt.reportIdToReplace,
          p.agentNodeProps.nodeId == Constants.ROOT_POLICY_SERVER_ID
        )
      )
    }).groupMap(_._1)(_._2)

    import org.antlr.stringtemplate.StringTemplate
    import com.normation.stringtemplate.language.NormationAmpersandTemplateLexer

    // now process by template, which are not thread safe but here, accessed sequentially
    parallelSequence(byTemplate.toSeq) {
      case (content, seqInfos) =>
        for {
          t0     <- currentTimeNanos
          parsed <-
            IOResult
              .attempt(s"Error when trying to parse template '${seqInfos.head.destination}'") { // head ok because of groupBy
                new StringTemplate(content, classOf[NormationAmpersandTemplateLexer])
              }
          t1     <- currentTimeNanos
          _      <- fillTimer.get.update(_ + t1 - t0)
          _      <- ZIO.foreachDiscard(seqInfos) { info =>
                      for {
                        // we need to for {} yield {} to free resources
                        replacedDest    <-
                          for {
                            _      <- PolicyGenerationLoggerPure.trace(s"Create policies file ${info.newFolder} ${info.destination}")
                            t0     <- currentTimeNanos
                            filled <- FillTemplateThreadUnsafe.fill(
                                        info.destination,
                                        parsed,
                                        info.envVars,
                                        fillTimer,
                                        info.reportIdToReplace.map(id => (Policy.TAG_OF_RUDDER_MULTI_POLICY, id.getRudderUniqueId))
                                      )
                            t1     <- currentTimeNanos
                            _      <- writeTimer.fillTemplate.update(_ + t1 - t0)
                          } yield {
                            filled
                          }
                        (replaced, dest) = replacedDest
                        t1              <- currentTimeNanos
                        _               <- File(info.newFolder, dest)
                                             .createParentsAndWrite(replaced, info.isRootServer)
                                             .chainError(
                                               s"Bad format in Technique ${info.id.displayPath} (file: ${info.destination})"
                                             )
                        t2              <- currentTimeNanos
                        _               <- writeTimer.writeTemplate.update(_ + t2 - t1)
                      } yield ()
                    }
        } yield ()
    }.unit
  }

  private def writeOtherResources(
      preparedTemplates: Seq[AgentNodeWritableConfiguration],
      writeTimer:        WriteTimer,
      globalPolicyMode:  GlobalPolicyMode,
      resources:         Map[(TechniqueResourceId, AgentType), TechniqueResourceCopyInfo]
  )(implicit timeout: Duration, maxParallelism: Int): IOResult[Unit] = {
    parallelSequence(preparedTemplates) { prepared =>
      val isRootServer   = prepared.agentNodeProps.nodeId == Constants.ROOT_POLICY_SERVER_ID
      val writeResources = ZIO.foreachDiscard(prepared.preparedTechniques) { preparedTechnique =>
        ZIO.foreachDiscard(preparedTechnique.filesToCopy) { file =>
          for {
            t0 <- currentTimeNanos
            r  <- copyResourceFile(
                    file,
                    isRootServer,
                    prepared.agentNodeProps.agentType,
                    prepared.paths.newFolder,
                    preparedTechnique.reportIdToReplace,
                    resources
                  )
            t1 <- currentTimeNanos
            _  <- writeTimer.copyResources.update(_ + t1 - t0)
          } yield ()
        }
      }

      val writeAgent = for {
        // changing `writeAllAgentSpecificFiles.write` to IOResult breaks DSC
        t0 <- currentTimeNanos
        _  <- writeAllAgentSpecificFiles
                .write(prepared)
                .chainError(s"Error with node '${prepared.paths.nodeId.value}'")
                .toIO
        t1 <- currentTimeNanos
        _  <- writeTimer.agentSpecific.update(_ + t1 - t0)
      } yield ()
      val writeCSV   = for {
        t0 <- currentTimeNanos
        _  <- writeDirectiveCsv(prepared.paths, prepared.policies, globalPolicyMode)
        t1 <- currentTimeNanos
        _  <- writeTimer.writeCSV.update(_ + t1 - t0)
      } yield ()
      val writeJSON  = for {
        t0 <- currentTimeNanos
        _  <- writeSystemVarJson(prepared.paths, prepared.systemVariables)
        t1 <- currentTimeNanos
        _  <- writeTimer.writeJSON.update(_ + t1 - t0)
      } yield ()
      val writePEM   = for {
        t0 <- currentTimeNanos
        _  <- writePolicyServerPem(prepared.paths, prepared.policyServerCerts)
        t1 <- currentTimeNanos
        _  <- writeTimer.writePEM.update(_ + t1 - t0)
      } yield ()

      // this allows to do some IO (JSON, CSV) while waiting for semaphores in
      // writePromise (for template filling)
      ZIO
        .collectAllPar(writeResources :: writeAgent :: writeCSV :: writeJSON :: writePEM :: Nil)
        .chainError(s"Error when writing configuration for node '${prepared.paths.nodeId.value}'")
    }.unit
  }

  /**
   * Calculate path for node configuration.
   * Path are agent dependant, so from that point, all
   * node configuration are also identified by agent.
   * Note that a node without any agent configured won't
   * have any promise written.
   */
  private def calculatePathsForNodeConfigurations(
      configs:             Seq[NodeConfiguration],
      rootNodeConfigId:    NodeId,
      allNodeInfos:        Map[NodeId, CoreNodeFact],
      newsFileExtension:   String,
      backupFileExtension: String
  ): IOResult[Seq[AgentNodeConfiguration]] = {

    val agentConfig = configs.map(config => (config.nodeInfo.rudderAgent, config))

    ZIO.foreach(agentConfig) {
      case (agentInfo, config) =>
        val agentType = agentInfo.agentType
        for {
          paths <- if (rootNodeConfigId == config.nodeInfo.id) {
                     NodePoliciesPaths(
                       config.nodeInfo.id,
                       pathComputer.getRootPath(agentType),
                       pathComputer.getRootPath(agentType) + newsFileExtension,
                       Some(pathComputer.getRootPath(agentType) + backupFileExtension)
                     ).succeed
                   } else {
                     pathComputer
                       .computeBaseNodePath(config.nodeInfo.id, rootNodeConfigId, allNodeInfos)
                       .map {
                         case NodePoliciesPaths(id, base, news, backup) =>
                           val postfix = agentType.toRulesPath
                           NodePoliciesPaths(id, base + postfix, news + postfix, backup.map(_ + postfix))
                       }
                       .toIO
                   }
        } yield {
          AgentNodeConfiguration(config, agentType, paths)
        }
    }
  }

  /*
   * We are returning a map where keys are (TechniqueResourceId, AgentType) because
   * for a given resource IDs, you can have different out path for different agent.
   */
  private def readTemplateFromFileSystem(
      templatesToRead: Seq[ToRead]
  )(implicit
      timeout:         Duration,
      maxParallelism:  Int
  ): IOResult[Map[(TechniqueResourceId, AgentType), TechniqueTemplateCopyInfo]] = {

    /*
     * NOTE : this is inefficient and store in a lot of multiple time the same content
     * if only the outpath change for two differents agent type.
     */
    for {
      t0  <- currentTimeMillis
      res <-
        (parallelSequence(templatesToRead) {
          case ToRead(templateId, agentType, templateOutPath) =>
            for {
              copyInfo <-
                techniqueRepository.getTemplateContent(templateId) { optInputStream =>
                  optInputStream match {
                    case None              =>
                      Unexpected(
                        s"Error when trying to open template '${templateId.displayPath}'. Check that the file exists with a ${TechniqueTemplate.templateExtension} extension and is correctly commited in Git, or that the metadata for the technique are corrects."
                      ).fail
                    case Some(inputStream) =>
                      for {
                        _       <- PolicyGenerationLoggerPure.trace(s"Loading template: ${templateId.displayPath}")
                        // string template does not allows "." in path name, so we are force to use a templateGroup by polity template (versions have . in them)
                        content <- IOResult.attempt(s"Error when copying technique template '${templateId.displayPath}'")(
                                     inputStream.asString(false)
                                   )
                      } yield {
                        TechniqueTemplateCopyInfo(templateId, templateOutPath, content)
                      }
                  }
                }
            } yield {
              ((copyInfo.id, agentType), copyInfo)
            }
        }).map(_.toMap)
      t1  <- currentTimeMillis
      _   <- timingLogger.debug(s"${templatesToRead.size} promises templates read in ${t1 - t0} ms")
    } yield res
  }

  /*
   * We are returning a map where keys are (TechniqueResourceId, AgentType) because
   * for a given resource IDs, you can have different out path for different agent.
   */
  private def readResourcesFromFileSystem(
      staticResourceToRead: Seq[ToRead]
  )(implicit
      timeout:              Duration,
      maxParallelism:       Int
  ): IOResult[Map[(TechniqueResourceId, AgentType), TechniqueResourceCopyInfo]] = {

    for {
      t0  <- currentTimeMillis
      res <-
        (parallelSequence(staticResourceToRead) {
          case ToRead(templateId, agentType, templateOutPath) =>
            for {
              copyInfo <- techniqueRepository.getFileContent(templateId) { optInputStream =>
                            optInputStream match {
                              case None              =>
                                Unexpected(
                                  s"Error when trying to open resource '${templateId.displayPath}'. Check that the file exists is correctly commited in Git, or that the metadata for the technique are corrects."
                                ).fail
                              case Some(inputStream) =>
                                TechniqueResourceCopyInfo(templateId, templateOutPath, inputStream.byteArray).succeed
                            }
                          }
            } yield {
              ((copyInfo.id, agentType), copyInfo)
            }
        }).map(_.toMap)
      t1  <- currentTimeMillis
      _   <- timingLogger.debug(s"${staticResourceToRead.size} promises resources read in ${t1 - t0} ms")
    } yield res
  }

  private def writeDirectiveCsv(
      paths:      NodePoliciesPaths,
      policies:   Seq[Policy],
      policyMode: GlobalPolicyMode
  ): IOResult[List[AgentSpecificFile]] = {
    val path = File(paths.newFolder, filepaths.DIRECTIVE_RUN_CSV)

    val csvContent   = for {
      // use the same order than for rudder-directive.cf
      policy <- PolicyOrdering.sort(policies)
    } yield {
      (policy.id.directiveId.serialize ::
      policy.policyMode.getOrElse(policyMode.mode).name ::
      policy.technique.generationMode.name ::
      policy.technique.agentConfig.runHooks.nonEmpty.toString ::
      policy.technique.id.name.value ::
      policy.technique.id.version.serialize ::
      policy.technique.policyTypes.isSystem.toString ::
      policy.directiveOrder.value ::
      Nil).mkString("\"", "\",\"", "\"")

    }
    val isRootServer = paths.nodeId == Constants.ROOT_POLICY_SERVER_ID
    for {
      _ <- path
             .createParentsAndWrite(csvContent.mkString("\n") + "\n", isRootServer)
             .chainError(
               s"Can not write rudder-directives.csv file at path '${path.pathAsString}'"
             )
    } yield {
      AgentSpecificFile(path.pathAsString) :: Nil
    }
  }

  private def writeSystemVarJson(
      paths:     NodePoliciesPaths,
      variables: Map[String, Variable]
  ): IOResult[List[AgentSpecificFile]] = {
    val path         = File(paths.newFolder, filepaths.SYSTEM_VARIABLE_JSON)
    val isRootServer = paths.nodeId == Constants.ROOT_POLICY_SERVER_ID
    for {
      _ <- path
             .createParentsAndWrite(systemVariableToJson(variables) + "\n", isRootServer)
             .chainError(
               s"Can not write json parameter file at path '${path.pathAsString}'"
             )
    } yield {
      AgentSpecificFile(path.pathAsString) :: Nil
    }
  }

  /*
   * Write in inputs/certs root.pem (root server certificate) and policy-server.pem (relay certificate, or a
   * symbolic link toward root.pem if it's the same)
   * https://issues.rudder.io/issues/19529
   */
  private def writePolicyServerPem(
      paths:                    NodePoliciesPaths,
      policyServerCertificates: PolicyServerCertificates
  ): IOResult[List[AgentSpecificFile]] = {
    val isRootServer = paths.nodeId == Constants.ROOT_POLICY_SERVER_ID
    def writeCert(name: String, content: IOResult[String]): IOResult[File] = {
      val path = File(paths.newFolder, name)
      for {
        pem <- content
        _   <- path.createParentsAndWrite(pem, isRootServer)
      } yield {
        path
      }
    }
    for {
      rootPem  <- writeCert(filepaths.ROOT_SERVER_CERT, policyServerCertificates.root)
      relayPem <- policyServerCertificates.relay match {
                    case Some(relayPem) =>
                      writeCert(filepaths.POLICY_SERVER_CERT, relayPem)
                    case None           => // create a symlink from root to policy-server.pem
                      IOResult.attempt {
                        // we want to have a symlink with a relative path, not full path
                        val source = Paths.get(rootPem.name)
                        val dest   = File(paths.newFolder, filepaths.POLICY_SERVER_CERT)
                        // we can't overwrite a file with a symlink, so erase existing one
                        if (dest.exists) { dest.delete() }
                        Files.createSymbolicLink(dest.path, source, File.Attributes.default*)
                        dest
                      }
                  }
    } yield {
      AgentSpecificFile(rootPem.pathAsString) :: AgentSpecificFile(relayPem.pathAsString) :: Nil
    }
  }

  private def systemVariableToJson(vars: Map[String, Variable]): String = {
    // only keep system variables, sort them by name
    import net.liftweb.json.*

    // remove these system vars (perhaps they should not even be there, in fact)
    val filterOut = Set(
      "SUB_NODES_ID",
      "SUB_NODES_KEYHASH",
      "SUB_NODES_NAME",
      "SUB_NODES_SERVER",
      "MANAGED_NODES_CERT_UUID",
      "MANAGED_NODES_CERT_CN",
      "MANAGED_NODES_CERT_DN",
      "MANAGED_NODES_CERT_PEM",
      "MANAGED_NODES_ADMIN",
      "MANAGED_NODES_ID",
      "MANAGED_NODES_IP",
      "MANAGED_NODES_KEY",
      "MANAGED_NODES_NAME",
      "COMMUNITY",
      "RUDDER_INVENTORY_VARS",
      "BUNDLELIST",
      "INPUTLIST"
    )

    val systemVars = vars.toList.sortBy(_._2.spec.name).collect {
      case (_, v: SystemVariable) if (!filterOut.contains(v.spec.name)) =>
        // if the variable is multivalued, create an array, else just a String
        // special case for RUDDER_DIRECTIVES_INPUTS - also an array
        val value = if (v.spec.multivalued || v.spec.name == "RUDDER_DIRECTIVES_INPUTS") {
          JArray(v.values.toList.map(JString))
        } else {
          JString(v.values.headOption.getOrElse(""))
        }
        JField(v.spec.name, value)
    }

    prettyRender(JObject(systemVars))
  }

  /**
   * Move the generated promises from the new folder to their final folder, backuping previous promises in the way
   * @param folders : (Container identifier, (base folder, new folder of the policies, backup folder of the policies) )
   */
  private def movePromisesToFinalPosition(folders: Seq[NodePoliciesPaths], maxParallelism: Int): IOResult[Unit] = {
    // We need to sort the folders by "depth", so that we backup and move the deepest one first
    // 2020-01: FAR and NCH are not sure why we need to do that. But it seems that we can parallelize it nonetheless.
    val sortedFolder = folders.sortBy(x => x.baseFolder.count(_ == '/')).reverse

    if (sortedFolder.isEmpty) {
      ZIO.unit
    } else {
      for {
        mvOptions              <- getMoveOptions(sortedFolder.head)
        (newMvOpt, backupMvOpt) = mvOptions
        newFolders             <- Ref.make(List.empty[NodePoliciesPaths])
        count                  <- Ref.make(0)
        totalNum                = sortedFolder.size
        // can't trivialy parallelise because we need parents before
        // here, base folder, newFolder and backupFolder target agent directory under rules: we need one level up appart for root.
        _                      <-
          (ZIO
            .foreachParDiscard(sortedFolder) {
              case folder @ NodePoliciesPaths(nodeId, baseFolderAgent, newFolderAgent, backupFolderAgent) =>
                val (optGroupOwner, perms)                = {
                  if (nodeId == Constants.ROOT_POLICY_SERVER_ID) (None, rootDirectoryPerms)
                  else (groupOwner, defaultDirectoryPerms)
                }
                val (baseFolder, newFolder, backupFolder) = nodeId match {
                  case Constants.ROOT_POLICY_SERVER_ID =>
                    // root server doesn't have the "rule" middle directory, don't get parent
                    (File(baseFolderAgent), File(newFolderAgent), backupFolderAgent.map(x => File(x)))
                  case _                               =>
                    // get parent of /var/rudder/share/xxxx-xxxx-xxxx/rules/cfengine-community
                    (File(baseFolderAgent).parent, File(newFolderAgent).parent, backupFolderAgent.map(x => File(x).parent))
                }
                for {
                  _ <- PolicyGenerationLoggerPure.trace(s"Backuping old policies from '${baseFolder}' to '${backupFolder} ")
                  _ <- backupNodeFolder(baseFolder, backupFolder, backupMvOpt, optGroupOwner, perms)
                  _ <- newFolders.update(folder :: _)
                  _ <- PolicyGenerationLoggerPure.trace(s"Copying new policies into '${baseFolder}'")
                  _ <- moveNewNodeFolder(newFolder, baseFolder, newMvOpt, optGroupOwner, perms)
                } yield ()
            }
            .withParallelism(maxParallelism))
            .tapError(err => {
              // in case of any error, restore all folders which were backuped, i.e in newFolders
              // here we do "as best as possible"
              for {
                folders <- newFolders.get
                _       <-
                  ZIO.foreach(folders) { folder =>
                    folder.backupFolder match {
                      case None    =>
                        PolicyGenerationLoggerPure
                          .error(s"Error when moving policies to their node folder. Error was: ${err.fullMsg}")
                      case Some(x) =>
                        val (optGroupOwner, perms) = {
                          if (folder.nodeId == Constants.ROOT_POLICY_SERVER_ID) (None, rootDirectoryPerms)
                          else (groupOwner, defaultDirectoryPerms)
                        }
                        PolicyGenerationLoggerPure.error(
                          s"Error when moving policies to their node folder. Restoring old policies on folder ${folder.baseFolder}. Error was: ${err.fullMsg}"
                        ) *>
                        restoreBackupNodeFolder(folder.baseFolder, x, backupMvOpt, optGroupOwner, perms).catchAll(err =>
                          PolicyGenerationLoggerPure.error(s"could not restore old policies into ${folder.baseFolder} ")
                        )
                    }
                  }
              } yield ()
            })
      } yield ()
    }
  }
  ///////////// utilities /////////////

  // check if we can use an atomic move or if we need a standard one
  // first is for move from rules.new to rule. It really should be atomic.
  // second if for backup
  def getMoveOptions(examplePath: NodePoliciesPaths): IOResult[(Option[File.CopyOptions], Option[File.CopyOptions])] = {
    val atomically = StandardCopyOption.ATOMIC_MOVE :: StandardCopyOption.REPLACE_EXISTING :: Nil

    def testMove(file: File, destination: String): Task[File] = {
      ZIO.attempt {
        val destDir = File(destination).parent
        if (file.parent.path == destDir.path) file // same directory is ok
        else {
          destDir.createDirectoryIfNotExists(true)
          file.moveTo(destDir / file.name)(atomically)
        }
        File(destDir, file.name).delete(false)
      }.catchAll(ex => ZIO.attempt(file.delete(true)) *> ex.fail)
    }

    def decideIfAtomic(src: File, destDir: String): IOResult[Option[File.CopyOptions]] = {
      testMove(src, destDir).map(_ => Some(atomically)).catchAll {
        // since we don't really use these options, it's just a warning about performances
        case ex: AtomicMoveNotSupportedException =>
          PolicyGenerationLoggerPure.warn(
            s"Node policy directories (${src.parent}, ${destDir}) " +
            s"are not on the same file system. Rudder won't be able to use atomic move for policies, which may have dire " +
            s"effect on policy generation performance. You should use the same file system."
          ) *>
          None.succeed
        case ex: Throwable                       =>
          val err = SystemError("Error when testing for policy 'mv' mode", ex)
          PolicyGenerationLoggerPure.debug(err.fullMsg) *> err.fail
      }
    }

    // try to create a file in new folder parent, move it to base folder, move
    // it to backup. In case of AtomicNotSupported execption, revert to
    // simple move
    for {
      n    <- currentTimeNanos
      f1   <- IOResult.attempt {
                File(examplePath.newFolder).parent.createChild("test-rudder-policy-mv-options" + n.toString, true, true)
              }
      opt1 <- decideIfAtomic(f1, examplePath.baseFolder)
      _    <- PolicyGenerationLoggerPure.debug(s"Can use atomic move from policy new folder to base folder")
      f2   <- IOResult.attempt {
                File(examplePath.baseFolder).parent.createChild("test-rudder-policy-mv-options" + n.toString, true, true)
              }
      opt2 <- examplePath.backupFolder match {
                case Some(b) => decideIfAtomic(f2, b)
                // if backup disable, we don't case about the kind of move
                case None    => None.succeed
              }
      _    <- PolicyGenerationLoggerPure.debug(s"Can use atomic move from policy base folder to archive folder")
    } yield {
      (opt1, opt2)
    }
  }

  /**
   * Copy a resource file from a technique to the node promises directory
   */
  private def copyResourceFile(
      file:              TechniqueFile,
      isRootServer:      Boolean,
      agentType:         AgentType,
      rulePath:          String,
      reportIdToReplace: Option[PolicyId],
      resources:         Map[(TechniqueResourceId, AgentType), TechniqueResourceCopyInfo]
  ): IOResult[String] = {
    val destination = {
      val out = reportIdToReplace match {
        case None     => file.outPath
        case Some(id) => file.outPath.replaceAll(Policy.TAG_OF_RUDDER_MULTI_POLICY, id.getRudderUniqueId)
      }
      File(rulePath + "/" + out)
    }
    resources.get((file.id, agentType)) match {
      case None    => Unexpected(s"Can not open the technique resource file ${file.id.displayPath} for reading").fail
      case Some(s) =>
        for {
          _ <- destination
                 .createParentsAndWrite(s.content, isRootServer)
                 .chainError(
                   s"Error when copying technique resource file '${file.id.displayPath}' to '${destination.pathAsString}'"
                 )
        } yield {
          destination.pathAsString
        }
    }
  }

  /**
   * Move the machine policies folder to the backup folder if it's not None, else ignore backup.
   */
  private def backupNodeFolder(
      nodeFolder:    File,
      backupFolder:  Option[File],
      mvOptions:     Option[File.CopyOptions],
      optGroupOwner: Option[String],
      perms:         Set[PosixFilePermission]
  ): IOResult[Unit] = {
    backupFolder match {
      case None    => // just delete base
        ZIO
          .whenZIO(IOResult.attempt(nodeFolder.exists)) {
            IOResult.attempt(nodeFolder.delete(false, File.LinkOptions.noFollow))
          }
          .unit
      case Some(d) =>
        IOResult.attempt {
          if (nodeFolder.isDirectory()) {
            if (d.isDirectory) {
              // force deletion of previous backup
              try {
                d.delete(false, File.LinkOptions.noFollow)
              } catch {
                case _: NoSuchFileException => // ok, deleted elsewhere
              }
            }
            PolicyGenerationLogger.trace(s"Backup old '${nodeFolder}' into ${backupFolder}")
            moveFile(nodeFolder, d, mvOptions, Some(perms), optGroupOwner)
          }
        }
    }
  }

  /**
   * Move the newly created folder to the final location, ie we move
   *  var/rudder/share/00000038-55a2-4b97-8529-5154cbb63a18/rules.new/ into var/rudder/share/00000038-55a2-4b97-8529-5154cbb63a18/rules
   */
  private def moveNewNodeFolder(
      src:           File,
      dest:          File,
      mvOptions:     Option[File.CopyOptions],
      optGroupOwner: Option[String],
      perms:         Set[PosixFilePermission]
  ): IOResult[Unit] = {

    for {
      b <- IOResult.attempt(src.isDirectory)
      _ <- if (b) {
             for {
               _ <- PolicyGenerationLoggerPure.trace(s"Moving folders: \n  from ${src.pathAsString}\n  to   ${dest.pathAsString}")
               _ <- ZIO.whenZIO(IOResult.attempt(dest.isDirectory)) {
                      // force deletion of previous promises
                      IOResult.attempt(dest.delete(false, File.LinkOptions.noFollow))
                    }
               _ <- IOResult.attempt {
                      moveFile(src, dest, mvOptions, Some(perms), optGroupOwner)
                    }.chainError(s"Error when moving newly generated policies to node directory")
               // force deletion of dandling new promise folder
               _ <- ZIO.whenZIO(IOResult.attempt(src.parent.isDirectory && src.parent.pathAsString.endsWith("rules.new"))) {
                      IOResult.attempt(src.parent.delete(false, File.LinkOptions.noFollow))
                    }
             } yield ()
           } else {
             PolicyGenerationLoggerPure.error(s"Could not find freshly created policies at '${src.pathAsString}'") *>
             Inconsistency(s"Source policies at '${src.pathAsString}' are missing'").fail
           }
    } yield ()
  }

  /**
   * Restore (by moving) backup folder to its original location
   * @param nodeFolder
   * @param backupFolder
   */
  private def restoreBackupNodeFolder(
      nodeFolder:    String,
      backupFolder:  String,
      mvOptions:     Option[File.CopyOptions],
      optGroupOwner: Option[String],
      perms:         Set[PosixFilePermission]
  ): IOResult[Unit] = {
    IOResult.attemptZIO {
      val src = File(backupFolder)
      if (src.isDirectory()) {
        val dest = File(nodeFolder)
        // force deletion of invalid promises
        dest.delete(false, File.LinkOptions.noFollow)
        moveFile(src, dest, mvOptions, Some(perms), optGroupOwner)
        ZIO.unit
      } else {
        PolicyGenerationLoggerPure.error(s"Could not find freshly backup policies at '${backupFolder}'") *>
        Inconsistency(
          s"Backup policies could not be found at '${src.pathAsString}', and valid policies couldn't be restored."
        ).fail
      }
    }
  }
}
