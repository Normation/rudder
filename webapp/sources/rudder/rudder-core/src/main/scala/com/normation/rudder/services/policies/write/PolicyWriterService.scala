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

import com.normation.cfclerk.domain.TechniqueFile
import com.normation.cfclerk.domain.TechniqueId
import com.normation.cfclerk.domain.TechniqueResourceId
import com.normation.cfclerk.domain.TechniqueTemplate
import com.normation.cfclerk.services.TechniqueRepository
import com.normation.inventory.domain.AgentType
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.Constants
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.domain.nodes.NodeProperty
import com.normation.rudder.domain.policies.GlobalPolicyMode
import com.normation.rudder.domain.reports.NodeConfigId
import com.normation.rudder.hooks.HookEnvPairs
import com.normation.rudder.hooks.RunHooks
import com.normation.templates.FillTemplatesService
import com.normation.templates.STVariable
import net.liftweb.common._
import net.liftweb.json.JsonAST
import net.liftweb.json.JsonAST.JValue
import org.joda.time.DateTime
import com.normation.cfclerk.domain.SystemVariable
import com.normation.cfclerk.domain.Variable
import com.normation.rudder.services.policies.NodeConfiguration
import com.normation.rudder.services.policies.ParameterEntry
import com.normation.rudder.services.policies.PolicyId
import com.normation.rudder.services.policies.Policy
import java.nio.charset.Charset
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.TimeUnit

import com.normation.rudder.hooks.HookReturnCode
import zio._
import zio.syntax._
import zio.duration._
import com.normation.errors._
import com.normation.box._
import com.normation.zio._
import zio.duration.Duration
import cats.data._
import cats.implicits._
import com.normation.rudder.domain.logger.NodeConfigurationLogger
import better.files._
import com.normation.rudder.domain.logger.PolicyGenerationLogger
import com.normation.rudder.domain.logger.PolicyGenerationLoggerPure
import com.normation.templates.FillTemplateThreadUnsafe
import com.normation.templates.FillTemplateTimer

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
      rootNodeId      : NodeId
    , nodesToWrite    : Set[NodeId]
    , allNodeConfigs  : Map[NodeId, NodeConfiguration]
    , allNodeInfos    : Map[NodeId, NodeInfo]
    , versions        : Map[NodeId, NodeConfigId]
    , globalPolicyMode: GlobalPolicyMode
    , generationTime  : DateTime
    , parallelism     : Int
  ) : Box[Seq[NodeId]]
}

object PolicyWriterServiceImpl {
  import PosixFilePermission._
  // we want: /bin/chmod u-x,u+rwX,g-wx,g+rX,o-rwx ... for nodes other than root
  val defaultFilePerms      = Set[PosixFilePermission](OWNER_READ, OWNER_WRITE, GROUP_READ)
  val defaultDirectoryPerms = Set[PosixFilePermission](OWNER_READ, OWNER_WRITE, OWNER_EXECUTE, GROUP_READ, GROUP_EXECUTE)
  // we want: /bin/chmod u-x,u+rwX,g-rwx,o-rwx ... for root
  val rootFilePerms         = Set[PosixFilePermission](OWNER_READ, OWNER_WRITE)
  val rootDirectoryPerms    = Set[PosixFilePermission](OWNER_READ, OWNER_WRITE, OWNER_EXECUTE)

  def createParentsIfNotExist(file: File, optPerms: Option[Set[PosixFilePermission]], optGroupOwner: Option[String]): Unit = {
    file.parentOption.foreach { parent =>
      if(!parent.exists) {
        createParentsIfNotExist(parent, optPerms, optGroupOwner)
        try {
          parent.createDirectory()
        } catch {
          case _:FileAlreadyExistsException => // too late, does nothing
        }
        optPerms.foreach(parent.setPermissions)
        optGroupOwner.foreach(parent.setGroup)
      }
    }
  }

  def moveFile(src: File, dest: File, mvOptions: File.CopyOptions, optPerms: Option[Set[PosixFilePermission]], optGroupOwner: Option[String]) = {
    createParentsIfNotExist(dest, optPerms, optGroupOwner)
    src.moveTo(dest)(mvOptions)
    optGroupOwner.foreach(dest.setGroup(_))
  }
}

/*
 * Timer accumulator for write part, in nanoseconds
 */
case class WriteTimer(
    writeTemplate: Ref[Long]
  , fillTemplate : Ref[Long]
  , copyResources: Ref[Long]
  , agentSpecific: Ref[Long]
  , writeCSV     : Ref[Long]
  , writeJSON    : Ref[Long]
)

object WriteTimer {
  def make() = for {
    a <- Ref.make(0L)
    b <- Ref.make(0L)
    c <- Ref.make(0L)
    d <- Ref.make(0L)
    e <- Ref.make(0L)
    f <- Ref.make(0L)
  } yield WriteTimer(a, b, c, d, e, f)
}

class PolicyWriterServiceImpl(
    techniqueRepository       : TechniqueRepository
  , pathComputer              : PathComputer
  , logNodeConfig             : NodeConfigurationLogger
  , prepareTemplate           : PrepareTemplateVariables
  , fillTemplates             : FillTemplatesService
  , writeAllAgentSpecificFiles: WriteAllAgentSpecificFiles
  , HOOKS_D                   : String
  , HOOKS_IGNORE_SUFFIXES     : List[String]
  , implicit val charset      : Charset
  , groupOwner                : Option[String]
) extends PolicyWriterService {
  import PolicyWriterServiceImpl._

  val clock = ZioRuntime.environment
  val timingLogger = PolicyGenerationLoggerPure.timing

  val hookWarnDurationMillis = (60*1000).millis

  val newPostfix = ".new"
  val backupPostfix = ".bkp"

  //an utility that write text in a file and create file parents if needed
  implicit class CreateParentAndWrite(val file: File) {
    import StandardOpenOption._
    // open file mode for create or overwrite mode
    def createParentsAndWrite(text: String, isRootServer: Boolean) = IOResult.effect {
      val (optGroupOwner, filePerms, dirPerms) = if(isRootServer) {
        (None      , rootFilePerms   , rootDirectoryPerms   )
      } else {
        (groupOwner, defaultFilePerms, defaultDirectoryPerms)
      }
      createParentsIfNotExist(file, Some(dirPerms), optGroupOwner)
      file.writeText(text)(Seq(WRITE, TRUNCATE_EXISTING, CREATE), charset).setPermissions(filePerms)
      optGroupOwner.foreach(file.setGroup)
    }
  }

  private[this] def writeNodePropertiesFile (agentNodeConfig: AgentNodeConfiguration) = {

    def generateNodePropertiesJson(properties : Seq[NodeProperty]): JValue = {
      import net.liftweb.json.JsonDSL._
      ( "properties" -> properties.toDataJson())
    }

    val path = Constants.GENERATED_PROPERTY_DIR
    val file = File(agentNodeConfig.paths.newFolder, path, Constants.GENERATED_PROPERTY_FILE)
    val jsonProperties = generateNodePropertiesJson(agentNodeConfig.config.nodeInfo.properties)
    val propertyContent = JsonAST.prettyRender(jsonProperties)

    for {
      _ <- PolicyGenerationLoggerPure.trace(s"Create node properties file '${agentNodeConfig.paths.newFolder}/${path}/${file.name}'")
      _ <- file.createParentsAndWrite(propertyContent, agentNodeConfig.config.isRootServer).chainError(s"could not write file: '${file.name} '")
    } yield ()
  }

  private[this] def writeRudderParameterFile(agentNodeConfig: AgentNodeConfiguration): IOResult[Unit] = {
    def generateParametersJson(parameters : Set[ParameterEntry]): JValue = {
      import com.normation.rudder.domain.nodes.JsonPropertySerialisation._
      import net.liftweb.json.JsonDSL._
      ( "parameters" -> parameters.toDataJson())
    }

    val file = File(agentNodeConfig.paths.newFolder, Constants.GENERATED_PARAMETER_FILE)
    val jsonParameters = generateParametersJson(agentNodeConfig.config.parameters.map(x => ParameterEntry(x.name, x.value, agentNodeConfig.agentType)))
    val parameterContent = JsonAST.prettyRender(jsonParameters)

    for {
      _ <- PolicyGenerationLoggerPure.trace(s"Create parameter file '${agentNodeConfig.paths.newFolder}/${file.name}'")
      _ <- file.createParentsAndWrite(parameterContent, agentNodeConfig.config.isRootServer).chainError(s"could not write file: ' ${file.name}'")
    } yield ()
  }

  /*
   * For all the writing part, we want to limit the number of concurrent workers on two aspects:
   * - we want an I/O threadpool, which knows what to do when a thread is blocked for a long time,
   *   and risks thread exaustion
   *   (it can happens, since we have a number of files to write, some maybe big)
   * - we want to limit the total number of concurrent execution to avoid blowming up the number
   *   of open files, concurrent hooks, etc. This is not directly linked to the number of CPU,
   *   but clearly there is no point having a pool of 4 threads for 1000 nodes which will
   *   fork 1000 times for hooks - even if there is 4 threads used for that.
   *
   * That means that we want something looking like a sequencePar interface, but with:
   * - a common thread pool, i/o oriented
   * - a common max numbers of concurrent tasks.
   *   I believe it should be common to all steps because it's mostly on the same machine - but
   *   for now, that doesn't really matter, since each step is bloking (ie wait before next).
   *   A parallelism around the thread pool sizing (by default, number of CPU) seems ok.
   *
   * here, f must not throws execption.
   *
   */
  def parrallelSequence[U,T](seq: Seq[U])(f:U => IOResult[T])(implicit timeout: Duration, maxParallelism: Int): IOResult[Seq[T]] = {
    seq.accumulateParN(maxParallelism)(a => f(a)).timeout(timeout).foldM(
      err => err.fail
    , suc => suc match {
        case None      => //timeout
          Accumulated(NonEmptyList.one(Unexpected(s"Execution of computation timed out after '${timeout.asJava.toString}'"))).fail
        case Some(seq) =>
          seq.succeed
      }
    ).provide(clock)
  }

  // a version for Hook with a nicer message accumulation
  def parallelSequenceNodeHook(seq: Seq[AgentNodeConfiguration])(f: AgentNodeConfiguration => IOResult[HookReturnCode])(implicit timeout: Duration, maxParallelism: Int): IOResult[Unit] = {

    // a dedicated error for hooks
    final case class HookError(nodeId: NodeId, errorCode: HookReturnCode.Error) extends RudderError {
      def limitOut(s: String) = {
        val max = 300
        if(s.size <= max) s else s.substring(0, max-3) + "..."
      }
      val msg = errorCode match {
          // we need to limit sdtout/sdterr lenght
          case HookReturnCode.ScriptError(code, stdout, stderr, msg) => s"${msg} [stdout:${limitOut(stdout)}][stderr:${limitOut(stderr)}]"
          case x                                                     => x.msg
      }
    }

    seq.accumulateParNELN(maxParallelism){ a =>
      f(a).foldM(
        ex   => HookError(a.config.nodeInfo.id, HookReturnCode.SystemError(ex.fullMsg)).fail
      , code => code match {
          case s:HookReturnCode.Success => s.succeed
          case e:HookReturnCode.Error   => HookError(a.config.nodeInfo.id, e).fail
        }
      )
    }.timeout(timeout).foldM(
        nel => {
          // in that case, it is extremely likely that most messages are the same. We group them together
          val nodeErrors = nel.toList.map{ err => (err.nodeId, err.msg) }
          val message = nodeErrors.groupBy( _._2 ).foldLeft("Error when executing hooks:") { case (s, (msg, list)) =>
            s + s"\n ${msg} (for node(s) ${list.map(_._1.value).mkString(";")})"
          }
          Unexpected(message).fail
        }
      , ok => ok match {
          case None    => Unexpected(s"Execution of computation timed out after '${timeout.asJava.toString}'").fail
          case Some(x) => UIO.unit
        }
    ).provide(clock)
  }.untraced


  /**
   * Write templates for node configuration that changed since the last write.
   *
   */
  override def writeTemplate(
      rootNodeId      : NodeId
    , nodesToWrite    : Set[NodeId]
    , allNodeConfigs  : Map[NodeId, NodeConfiguration]
    , allNodeInfos    : Map[NodeId, NodeInfo]
    , versions        : Map[NodeId, NodeConfigId]
    , globalPolicyMode: GlobalPolicyMode
    , generationTime  : DateTime
    , parallelism     : Int
  ) : Box[Seq[NodeId]] = {
    writeTemplatePure(rootNodeId, nodesToWrite, allNodeConfigs, allNodeInfos, versions, globalPolicyMode, generationTime, parallelism).toBox
  }

  def writeTemplatePure(
      rootNodeId      : NodeId
    , nodesToWrite    : Set[NodeId]
    , allNodeConfigs  : Map[NodeId, NodeConfiguration]
    , allNodeInfos    : Map[NodeId, NodeInfo]
    , versions        : Map[NodeId, NodeConfigId]
    , globalPolicyMode: GlobalPolicyMode
    , generationTime  : DateTime
    , parallelism     : Int
  ) : IOResult[Seq[NodeId]] = {

    val interestingNodeConfigs = allNodeConfigs.collect { case (nodeId, nodeConfiguration) if (nodesToWrite.contains(nodeId)) => nodeConfiguration }.toSeq

    val techniqueIds           = interestingNodeConfigs.flatMap( _.getTechniqueIds ).toSet

    //debug - but don't fails for debugging !
    val logNodeConfigurations = ZIO.when(logNodeConfig.isDebugEnabled) {
      IOResult.effect(logNodeConfig.log(interestingNodeConfigs)).foldM(
          err => PolicyGenerationLoggerPure.error(s"Error when trying to write node configurations for debugging: ${err.fullMsg}")
        , ok  => UIO.unit
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

    //we need to add the current environment variables to the script context
    //plus the script environment variables used as script parameters
    import scala.jdk.CollectionConverters._

    // give a timeout for the whole tasks sufficiently large.
    // Hint: CF-promise taking 2s by node, for 10 000 nodes, on
    // 4 cores => ~85 minutes...
    // It is here mostly as a safeguard for generation which went wrong -
    // we will already have timeout at the thread level for stalling threads.
    implicit val timeout = Duration(2, TimeUnit.HOURS)
    // Max number of fiber used in parallel.
    implicit val maxParallelism = parallelism

    for {
      _                    <- logNodeConfigurations
      systemEnv            <- IOResult.effect(System.getenv.asScala.toSeq).map(seq => HookEnvPairs.build(seq:_*))
      readTemplateTime1    <- currentTimeMillis
      configAndPaths       <- calculatePathsForNodeConfigurations(interestingNodeConfigs, rootNodeId, allNodeInfos, newPostfix, backupPostfix)
      pathsInfo            =  configAndPaths.map { _.paths }
      // we need for yield that to free all agent specific resources
      _ <- for {
        pair <- for {
          templates         <- readTemplateFromFileSystem(techniqueIds)
          resources         <- readResourcesFromFileSystem(techniqueIds)
          // Clearing cache
          _                 <- IOResult.effect(fillTemplates.clearCache)
          readTemplateTime2 <- currentTimeMillis
          _                 <- timingLogger.debug(s"Paths computed and templates read in ${readTemplateTime2 - readTemplateTime1} ms")

          //////////
          // nothing agent specific before that
          //////////
          prepareTimer          <- PrepareTemplateTimer.make()
          preparedTemplates     <- parrallelSequence(configAndPaths) { case agentNodeConfig =>
                                     val nodeConfigId = versions(agentNodeConfig.config.nodeInfo.id)
                                     prepareTemplate.prepareTemplateForAgentNodeConfiguration(agentNodeConfig, nodeConfigId, rootNodeId, templates, allNodeConfigs, Policy.TAG_OF_RUDDER_ID, globalPolicyMode, generationTime, prepareTimer).chainError(
                                       s"Error when calculating configuration for node '${agentNodeConfig.config.nodeInfo.hostname}' (${agentNodeConfig.config.nodeInfo.id.value})"
                                     )
                                   }
          preparedTemplatesTime <- currentTimeMillis
          _                     <- timingLogger.debug(s"Policy templates prepared in ${preparedTemplatesTime - readTemplateTime2} ms") *> {
                                     ZIO.when(timingLogger.logEffect.isTraceEnabled) { (prepareTimer.buildBundleSeq.get <*> prepareTimer.buildAgentVars.get <*> prepareTimer.prepareTemplate.get).flatMap( t =>
                                       timingLogger.trace(s" -> bundle sequence built in ${t._1._1} ms | agent system variables built in ${t._1._2} ms | policy template prepared in ${t._2} ms")
                                     ) }
                                   }
        } yield {
          (preparedTemplates, resources)
        }
        (preparedTemplates, resources) = pair

        writeTimer           <- WriteTimer.make()
        fillTimer            <- FillTemplateTimer.make()
        beforeWrittingTime   <- currentTimeMillis
        promiseWritten       <- writePolicies(preparedTemplates, writeTimer, fillTimer)
        others               <- writeOtherResources(preparedTemplates, writeTimer, globalPolicyMode, resources)
        promiseWrittenTime   <- currentTimeMillis
        _                    <- timingLogger.debug(s"Policies written in ${promiseWrittenTime - beforeWrittingTime} ms")
        nanoToMillis         =  1000*1000
        f                    <- writeTimer.fillTemplate.get.map(_/nanoToMillis)
        w                    <- writeTimer.writeTemplate.get.map(_/nanoToMillis)
        r                    <- writeTimer.copyResources.get.map(_/nanoToMillis)
        a                    <- writeTimer.agentSpecific.get.map(_/nanoToMillis)
        c                    <- writeTimer.writeCSV.get.map(_/nanoToMillis)
        j                    <- writeTimer.writeJSON.get.map(_/nanoToMillis)
        ftf                  <- fillTimer.fill.get.map(_ / nanoToMillis)
        fts                  <- fillTimer.stringify.get.map(_ / nanoToMillis)
        ftw                  <- fillTimer.waitFill.get.map(_/nanoToMillis)
        gte                  <- fillTimer.get.get.map(_/nanoToMillis)
        gtw                  <- fillTimer.waitGet.get.map(_/nanoToMillis)
        _                    <- timingLogger.trace(s" -> fill template: ${f} ms [fill template: ${ftf} ms | to string template: ${fts} ms | fill template wait: ${ftw} ms | get template exec: ${gte} ms | get template wait: ${gtw} ms]")
        _                    <- timingLogger.trace(s" -> write template: ${w} ms | copy resources: ${r} ms | agent specific: ${a} ms | write CSV: ${c} ms| write JSON: ${j} ms")
        getRefTime           <- currentTimeMillis
        _                    <- timingLogger.debug(s"Getting info for timing trace in ${getRefTime - promiseWrittenTime} ms")

      } yield {
        ()
      }

      //////////
      // nothing agent specific after that
      //////////
      beforePropertiesTime <- currentTimeMillis
      propertiesWritten    <- parrallelSequence(configAndPaths) { case agentNodeConfig =>
                                writeNodePropertiesFile(agentNodeConfig).chainError(
                                  s"An error occured while writing property file for Node ${agentNodeConfig.config.nodeInfo.hostname} (id: ${agentNodeConfig.config.nodeInfo.id.value}"
                                )
                              }

      propertiesWrittenTime<- currentTimeMillis
      _                    <- timingLogger.debug(s"Properties written in ${propertiesWrittenTime - beforePropertiesTime} ms")

      parametersWritten    <- parrallelSequence(configAndPaths) { case agentNodeConfig =>
                                writeRudderParameterFile(agentNodeConfig).chainError(
                                  s"An error occured while writing parameter file for Node ${agentNodeConfig.config.nodeInfo.hostname} (id: ${agentNodeConfig.config.nodeInfo.id.value}"
                                )
                              }

      parametersWrittenTime <- currentTimeMillis
      _                     <- timingLogger.debug(s"Parameters written in ${parametersWrittenTime - propertiesWrittenTime} ms")


      _                     <- IOResult.effect(fillTemplates.clearCache)
      /// perhaps that should be a post-hook somehow ?
      // and perhaps we should have an AgentSpecific global pre/post write

      nodePreMvHooks        <- RunHooks.getHooksPure(HOOKS_D + "/policy-generation-node-ready", HOOKS_IGNORE_SUFFIXES)
      preMvHooks            <- parallelSequenceNodeHook(configAndPaths) { agentNodeConfig =>
                                 val nodeId = agentNodeConfig.config.nodeInfo.node.id.value
                                 val hostname = agentNodeConfig.config.nodeInfo.hostname
                                 val policyServer = agentNodeConfig.config.nodeInfo.policyServerId.value
                                 for {
                                   timeHooks0 <- currentTimeMillis
                                   res        <- RunHooks.asyncRun(
                                                     nodePreMvHooks
                                                   , HookEnvPairs.build(
                                                       ("RUDDER_GENERATION_DATETIME", generationTime.toString)
                                                     , ("RUDDER_NODE_ID", nodeId)
                                                     , ("RUDDER_NODE_HOSTNAME", hostname)
                                                     , ("RUDDER_NODE_POLICY_SERVER_ID", policyServer)
                                                     , ("RUDDER_AGENT_TYPE", agentNodeConfig.agentType.id)
                                                     , ("RUDDER_POLICIES_DIRECTORY_NEW", agentNodeConfig.paths.newFolder)
                                                       // for compat in 4.1. Remove in 4.2
                                                     , ("RUDDER_NODEID", nodeId)
                                                     , ("RUDDER_NEXT_POLICIES_DIRECTORY", agentNodeConfig.paths.newFolder)
                                                   )
                                                   , systemEnv
                                                   , hookWarnDurationMillis // warn if a hook took more than a minute
                                                 )
                                   timeHooks1 <- currentTimeMillis
                                   _          <- timingLogger.hooks.trace(s"Run post-generation pre-move hooks for node '${nodeId}' in ${timeHooks1 - timeHooks0} ms")
                                 } yield res._1
                               }

      movedPromisesTime1    <- currentTimeMillis
      _                     <- timingLogger.hooks.debug(s"Hooks for policy-generation-node-ready executed in ${movedPromisesTime1-parametersWrittenTime} ms")
      movedPromises         <- movePromisesToFinalPosition(pathsInfo, maxParallelism)
      movedPromisesTime2    <- currentTimeMillis
      _                     <- timingLogger.debug(s"Policies moved to their final position in ${movedPromisesTime2 - movedPromisesTime1} ms")

      nodePostMvHooks       <- RunHooks.getHooksPure(HOOKS_D + "/policy-generation-node-finished", HOOKS_IGNORE_SUFFIXES)
      postMvHooks           <- parallelSequenceNodeHook(configAndPaths) { agentNodeConfig =>
                                 val nodeId = agentNodeConfig.config.nodeInfo.node.id.value
                                 val hostname = agentNodeConfig.config.nodeInfo.hostname
                                 val policyServer = agentNodeConfig.config.nodeInfo.policyServerId.value
                                 for {
                                   timeHooks0 <- currentTimeMillis
                                   res        <- RunHooks.asyncRun(
                                                     nodePostMvHooks
                                                   , HookEnvPairs.build(
                                                        ("RUDDER_GENERATION_DATETIME", generationTime.toString)
                                                      , ("RUDDER_NODE_ID", nodeId)
                                                      , ("RUDDER_NODE_HOSTNAME", hostname)
                                                      , ("RUDDER_NODE_POLICY_SERVER_ID", policyServer)
                                                      , ("RUDDER_AGENT_TYPE", agentNodeConfig.agentType.id)
                                                      , ("RUDDER_POLICIES_DIRECTORY_CURRENT", agentNodeConfig.paths.baseFolder)
                                                        // for compat in 4.1. Remove in 4.2
                                                      , ("RUDDER_NODEID", nodeId)
                                                      , ("RUDDER_POLICIES_DIRECTORY", agentNodeConfig.paths.baseFolder)
                                                     )
                                                   , systemEnv
                                                   , hookWarnDurationMillis // warn if a hook took more than a minute
                                                 )
                                   timeHooks1 <- currentTimeMillis
                                   _          <- timingLogger.hooks.trace(s"Run post-generation post-move hooks for node '${nodeId}' in ${timeHooks1 - timeHooks0} ms")
                                 } yield res._1
                               }
      postMvHooksTime2         <- currentTimeMillis
      _                        <- timingLogger.hooks.debug(s"Hooks for policy-generation-node-finished executed in ${postMvHooksTime2 - movedPromisesTime2} ms")
    } yield {
      pathsInfo.map { _.nodeId }
    }
  }

  ///////////// implementation of each step /////////////

  case class TemplateFillInfo(
      id               : TechniqueResourceId
    , destination      : String
    , newFolder        : String
    , envVars          : Seq[STVariable]
    , reportIdToReplace: Option[PolicyId]
    , isRootServer     : Boolean
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
  private[this] def writePolicies(preparedTemplates: Seq[AgentNodeWritableConfiguration], writeTimer: WriteTimer, fillTimer: FillTemplateTimer)(implicit maxParallelism: Int, timeout: Duration): IOResult[Unit] = {
    /*
     * Idea: sort template by content (ie what is cached). Put each unique cache in a seq with the corresponding list of
     * template to fill.
     * Get the the corresponding list of filled template.
     * Write it.
     * No semaphore, no synchronisation.
     */

    // template are created based on "content"
    val byTemplate = (for {
      p  <- preparedTemplates
      pt <- p.preparedTechniques
      t  <- pt.templatesToProcess
    } yield {
      (t.content, TemplateFillInfo(t.id, t.destination, p.paths.newFolder, pt.environmentVariables, pt.reportIdToReplace, p.agentNodeProps.nodeId == Constants.ROOT_POLICY_SERVER_ID))
    }).groupMap(_._1)(_._2)


    import org.antlr.stringtemplate.StringTemplate
    import com.normation.stringtemplate.language.NormationAmpersandTemplateLexer

    // now process by template, which are not thread safe but here, accessed sequentially
    parrallelSequence(byTemplate.toSeq) { case (content, seqInfos) =>
      for {
        t0      <- currentTimeNanos
        parsed  <- IOResult.effect(s"Error when trying to parse template '${seqInfos.head.destination}'") { // head ok because of groupBy
                     new StringTemplate(content, classOf[NormationAmpersandTemplateLexer])
                   }
        t1      <- currentTimeNanos
        _       <- fillTimer.get.update(_ + t1 - t0)
        _       <- ZIO.foreach_(seqInfos) { info =>

                     for {
                       // we need to for {} yield {} to free resources
                       replacedDest   <- for {
                                           _      <- PolicyGenerationLoggerPure.trace(s"Create policies file ${info.newFolder} ${info.destination}")
                                           t0     <- currentTimeNanos
                                           filled <- FillTemplateThreadUnsafe.fill(info.destination, parsed, info.envVars, fillTimer, info.reportIdToReplace.map(id => (Policy.TAG_OF_RUDDER_MULTI_POLICY, id.getRudderUniqueId)))
                                           t1     <- currentTimeNanos
                                           _      <- writeTimer.fillTemplate.update(_ + t1 - t0)
                                         } yield {
                                           filled
                                         }
                       (replaced, dest) = replacedDest
                       t1               <- currentTimeNanos
                       _                <- File(info.newFolder, dest).createParentsAndWrite(replaced, info.isRootServer).chainError(
                                               s"Bad format in Technique ${info.id.toString} (file: ${info.destination})"
                                           )
                       t2               <- currentTimeNanos
                       _                <- writeTimer.writeTemplate.update(_ + t2 - t1)
                     } yield ()
                   }
      } yield ()
    }.unit
  }


  private[this] def writeOtherResources(preparedTemplates: Seq[AgentNodeWritableConfiguration], writeTimer: WriteTimer, globalPolicyMode: GlobalPolicyMode, resources: Map[(TechniqueResourceId, AgentType), TechniqueResourceCopyInfo])(implicit timeout: Duration, maxParallelism: Int): IOResult[Unit] = {
    parrallelSequence(preparedTemplates) { prepared =>
      val isRootServer = prepared.agentNodeProps.nodeId == Constants.ROOT_POLICY_SERVER_ID
      val writeResources = ZIO.foreach_(prepared.preparedTechniques) { preparedTechnique => ZIO.foreach_(preparedTechnique.filesToCopy) { file =>
         for {
           t0 <- currentTimeNanos
           r  <- copyResourceFile(file, isRootServer, prepared.agentNodeProps.agentType, prepared.paths.newFolder, preparedTechnique.reportIdToReplace, resources)
           t1 <- currentTimeNanos
           _  <- writeTimer.copyResources.update(_ + t1 - t0)
         } yield ()
      } }

      val writeAgent = for {
              // changing `writeAllAgentSpecificFiles.write` to IOResult breaks DSC
        t0 <- currentTimeNanos
        _  <- IOResult.effect(writeAllAgentSpecificFiles.write(prepared)).chainError(s"Error with node '${prepared.paths.nodeId.value}'")
        t1 <- currentTimeNanos
        _  <- writeTimer.agentSpecific.update(_ + t1 - t0)
      } yield ()
      val writeCSV = for {
        t0 <- currentTimeNanos
        _  <- writeDirectiveCsv(prepared.paths, prepared.policies, globalPolicyMode)
        t1 <- currentTimeNanos
        _  <- writeTimer.writeCSV.update(_ + t1 - t0)
      } yield ()
      val writeJSON = for {
        t0 <- currentTimeNanos
        _  <- writeSystemVarJson(prepared.paths, prepared.systemVariables)
        t1 <- currentTimeNanos
        _  <- writeTimer.writeJSON.update(_ + t1 - t0)
      } yield ()

      // this allows to do some IO (JSON, CSV) while waiting for semaphores in
      // writePromise (for template filling)
      ZIO.collectAllPar(writeResources :: writeAgent :: writeCSV :: writeJSON :: Nil).
        chainError(s"Error when writing configuration for node '${prepared.paths.nodeId.value}'")
    }.unit
  }

  /**
   * Calculate path for node configuration.
   * Path are agent dependant, so from that point, all
   * node configuration are also identified by agent.
   * Note that a node without any agent configured won't
   * have any promise written.
   */
  private[this] def calculatePathsForNodeConfigurations(
      configs            : Seq[NodeConfiguration]
    , rootNodeConfigId   : NodeId
    , allNodeInfos       : Map[NodeId, NodeInfo]
    , newsFileExtension  : String
    , backupFileExtension: String
  ): IOResult[Seq[AgentNodeConfiguration]] = {

    val agentConfig = configs.flatMap { config =>
      if(config.nodeInfo.agentsName.size == 0) {
        PolicyGenerationLogger.info(s"Node '${config.nodeInfo.hostname}' (${config.nodeInfo.id.value}) has no agent type configured and so no policies will be generated")
      }
      config.nodeInfo.agentsName.map {agentType => (agentType, config) }
    }

    ZIO.foreach( agentConfig )  { case (agentInfo, config) =>
      val agentType = agentInfo.agentType
      for {
        paths <- if(rootNodeConfigId == config.nodeInfo.id) {
                    NodePoliciesPaths(
                        config.nodeInfo.id
                      , pathComputer.getRootPath(agentType)
                      , pathComputer.getRootPath(agentType) + newsFileExtension
                      , pathComputer.getRootPath(agentType) + backupFileExtension
                    ).succeed
                  } else {
                    pathComputer.computeBaseNodePath(config.nodeInfo.id, rootNodeConfigId, allNodeInfos).map { case NodePoliciesPaths(id, base, news, backup) =>
                        val postfix = agentType.toRulesPath
                        NodePoliciesPaths(id, base + postfix, news + postfix, backup + postfix)
                    }.toIO
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
  private[this] def readTemplateFromFileSystem(
      techniqueIds: Set[TechniqueId]
  )(implicit timeout: Duration, maxParallelism: Int): IOResult[Map[(TechniqueResourceId, AgentType), TechniqueTemplateCopyInfo]] = {

    //list of (template id, template out path)
    val templatesToRead = for {
      technique <- techniqueRepository.getByIds(techniqueIds.toSeq)
      template  <- technique.agentConfigs.flatMap(cfg => cfg.templates.map(t => (t.id, cfg.agentType, t.outPath)))
    } yield {
      template
    }

    /*
     * NOTE : this is inefficient and store in a lot of multiple time the same content
     * if only the outpath change for two differents agent type.
     */
    for {
      t0  <- currentTimeMillis
      res <- (parrallelSequence(templatesToRead) { case (templateId, agentType, templateOutPath) =>
               for {
                 copyInfo <- techniqueRepository.getTemplateContent(templateId) { optInputStream =>
                               optInputStream match {
                                 case None =>
                                   Unexpected(s"Error when trying to open template '${templateId.displayPath}'. Check that the file exists with a ${TechniqueTemplate.templateExtension} extension and is correctly commited in Git, or that the metadata for the technique are corrects.").fail
                                 case Some(inputStream) =>
                                   for {
                                     _       <- PolicyGenerationLoggerPure.trace(s"Loading template: ${templateId.displayPath}")
                                               //string template does not allows "." in path name, so we are force to use a templateGroup by polity template (versions have . in them)
                                     content <- IOResult.effect(s"Error when copying technique template '${templateId.displayPath}'")(inputStream.asString(false))
                                   } yield {
                                     TechniqueTemplateCopyInfo(templateId, templateOutPath, content)
                                   }
                               }
                             }
               } yield {
                 ((copyInfo.id, agentType), copyInfo)
               }
             }).map( _.toMap )
      t1  <- currentTimeMillis
      _   <- timingLogger.debug(s"${templatesToRead.size} promises templates read in ${t1-t0} ms")
    } yield res
  }



  /*
   * We are returning a map where keys are (TechniqueResourceId, AgentType) because
   * for a given resource IDs, you can have different out path for different agent.
   */
  private[this] def readResourcesFromFileSystem(
     techniqueIds: Set[TechniqueId]
  )(implicit timeout: Duration, maxParallelism: Int): IOResult[Map[(TechniqueResourceId, AgentType), TechniqueResourceCopyInfo]] = {

    val staticResourceToRead = for {
      technique      <- techniqueRepository.getByIds(techniqueIds.toSeq)
      staticResource <- technique.agentConfigs.flatMap(cfg => cfg.files.map(t => (t.id, cfg.agentType, t.outPath)))
    } yield {
      staticResource
    }

    for {
      t0  <- currentTimeMillis
      res <- (parrallelSequence(staticResourceToRead) { case (templateId, agentType, templateOutPath) =>
               for {
                 copyInfo <- techniqueRepository.getFileContent(templateId) { optInputStream =>
                               optInputStream match {
                                 case None =>
                                   Unexpected(s"Error when trying to open resource '${templateId.displayPath}'. Check that the file exists is correctly commited in Git, or that the metadata for the technique are corrects.").fail
                                 case Some(inputStream) =>
                                   for {
                                     _       <- PolicyGenerationLoggerPure.trace(s"Loading resource: ${templateId.displayPath}")
                                               //string template does not allows "." in path name, so we are force to use a templateGroup by polity template (versions have . in them)
                                     content <- IOResult.effect(s"Error when copying technique resource '${templateId.displayPath}'")(inputStream.asString(false))
                                   } yield {
                                     TechniqueResourceCopyInfo(templateId, templateOutPath, content)
                                   }
                               }
                             }
               } yield {
                 ((copyInfo.id, agentType), copyInfo)
               }
             }).map( _.toMap)
      t1  <- currentTimeMillis
      _   <- timingLogger.debug(s"${staticResourceToRead.size} promises resources read in ${t1-t0} ms")
    } yield res
  }


  private[this] def writeDirectiveCsv(paths: NodePoliciesPaths, policies: Seq[Policy], policyMode: GlobalPolicyMode): IOResult[List[AgentSpecificFile]] =  {
    val path = File(paths.newFolder, "rudder-directives.csv")

    val csvContent = for {
      policy <- policies.sortBy(_.directiveOrder.value)
    } yield {
      ( policy.id.directiveId.value ::
        policy.policyMode.getOrElse(policyMode.mode).name ::
        policy.technique.generationMode.name ::
        policy.technique.agentConfig.runHooks.nonEmpty ::
        policy.technique.id.name ::
        policy.technique.id.version ::
        policy.technique.isSystem ::
        policy.directiveOrder.value ::
        Nil
      ).mkString("\"","\",\"","\"")

    }
    val isRootServer = paths.nodeId == Constants.ROOT_POLICY_SERVER_ID
    for {
      _ <- path.createParentsAndWrite(csvContent.mkString("\n") + "\n", isRootServer).chainError(
             s"Can not write rudder-directives.csv file at path '${path.pathAsString}'"
           )
    } yield {
      AgentSpecificFile(path.pathAsString) :: Nil
    }
  }

  private[this] def writeSystemVarJson(paths: NodePoliciesPaths, variables: Map[String, Variable]): IOResult[List[AgentSpecificFile]] =  {
    val path = File(paths.newFolder, "rudder.json")
    val isRootServer = paths.nodeId == Constants.ROOT_POLICY_SERVER_ID
    for {
        _ <- path.createParentsAndWrite(systemVariableToJson(variables) + "\n", isRootServer).chainError(
               s"Can not write json parameter file at path '${path.pathAsString}'"
             )
    } yield {
      AgentSpecificFile(path.pathAsString) :: Nil
    }
  }

  private[this] def systemVariableToJson(vars: Map[String, Variable]): String = {
    //only keep system variables, sort them by name
    import net.liftweb.json._

    //remove these system vars (perhaps they should not even be there, in fact)
    val filterOut = Set(
        "SUB_NODES_ID"
      , "SUB_NODES_KEYHASH"
      , "SUB_NODES_NAME"
      , "SUB_NODES_SERVER"
      , "MANAGED_NODES_CERT_UUID"
      , "MANAGED_NODES_CERT_CN"
      , "MANAGED_NODES_CERT_DN"
      , "MANAGED_NODES_CERT_PEM"
      , "MANAGED_NODES_ADMIN"
      , "MANAGED_NODES_ID"
      , "MANAGED_NODES_IP"
      , "MANAGED_NODES_KEY"
      , "MANAGED_NODES_NAME"
      , "COMMUNITY", "NOVA"
      , "RUDDER_INVENTORY_VARS"
      , "BUNDLELIST", "INPUTLIST"
    )

    val systemVars = vars.toList.sortBy( _._2.spec.name ).collect { case (_, v: SystemVariable) if(!filterOut.contains(v.spec.name)) =>
      // if the variable is multivalued, create an array, else just a String
      // special case for RUDDER_DIRECTIVES_INPUTS - also an array
      val value = if(v.spec.multivalued || v.spec.name == "RUDDER_DIRECTIVES_INPUTS") {
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
  private[this] def movePromisesToFinalPosition(folders: Seq[NodePoliciesPaths], maxParallelism: Int): IOResult[Unit] = {
    // We need to sort the folders by "depth", so that we backup and move the deepest one first
    // 2020-01: FAR and NCH are not sure why we need to do that. But it seems that we can parallelize it nonetheless.
    val sortedFolder = folders.sortBy(x => x.baseFolder.count(_ =='/')).reverse

    if(sortedFolder.isEmpty) {
      UIO.unit
    } else {
      for {
        mvOptions  <- getMoveOptions(sortedFolder.head)
        newFolders <- Ref.make(List.empty[NodePoliciesPaths])
                      // can't trivialy parallelise because we need parents before children
        _          <- ZIO.foreachParN_(maxParallelism)(sortedFolder) { case folder @ NodePoliciesPaths(nodeId, baseFolder, newFolder, backupFolder) =>
                        val (optGroupOwner, perms) = if(nodeId == Constants.ROOT_POLICY_SERVER_ID) (None, rootDirectoryPerms) else (groupOwner, defaultDirectoryPerms)
                        for {
                          _ <- PolicyGenerationLoggerPure.trace(s"Backuping old policies from '${baseFolder}' to '${backupFolder} ")
                          _ <- backupNodeFolder(baseFolder, backupFolder, mvOptions, optGroupOwner, perms)
                          _ <- newFolders.update( folder :: _ )
                          _ <- PolicyGenerationLoggerPure.trace(s"Copying new policies into '${baseFolder}'")
                          _ <- moveNewNodeFolder(newFolder, baseFolder, mvOptions, optGroupOwner, perms)
                        } yield ()
                      }.catchAll(err =>
                        //in case of any error, restore all folders which were backuped, i.e in newFolders
                        //here we do "as best as possible"
                        for {
                          folders <- newFolders.get
                          _       <- ZIO.foreach(folders) { folder =>
                                       val (optGroupOwner, perms) = if(folder.nodeId == Constants.ROOT_POLICY_SERVER_ID) (None, rootDirectoryPerms) else (groupOwner, defaultDirectoryPerms)
                                       PolicyGenerationLoggerPure.error(s"Error when moving policies to their node folder. Restoring old policies on folder ${folder.baseFolder}. Error was: ${err.fullMsg}") *>
                                       restoreBackupNodeFolder(folder.baseFolder, folder.backupFolder, mvOptions, optGroupOwner, perms).catchAll(err =>
                                         PolicyGenerationLoggerPure.error(s"could not restore old policies into ${folder.baseFolder} ")
                                       )
                                     }
                        } yield ()
                      )
      } yield ()
    }
  }
  ///////////// utilities /////////////

  // check if we can use an atomic move or if we need a standard one
  def getMoveOptions(examplePath: NodePoliciesPaths): IOResult[File.CopyOptions] = {
    val default    = StandardCopyOption.REPLACE_EXISTING :: Nil
    val atomically = StandardCopyOption.ATOMIC_MOVE :: default

    def testMove(file: File, destination: String): Task[File] = {
      IO.effect {
        val destDir = File(destination).parent
        if(file.parent.path == destDir.path) file // same directory is ok
        else {
          destDir.createDirectoryIfNotExists(true)
          file.moveTo(destDir / file.name)(atomically)
        }
      }.catchAll(ex => IO.effect(file.delete(true)) *> ex.fail)
    }

    // try to create a file in new folder parent, move it to base folder, move
    // it to backup. In case of AtomicNotSupported execption, revert to
    // simple move
    (for {
      n  <- currentTimeNanos
      f1 <- IO.effect {
              File(examplePath.newFolder).parent.createChild("test-rudder-policy-mv-options" + n.toString, true, true)
            }
      f2 <- testMove(f1, examplePath.baseFolder)
      _  <- PolicyGenerationLoggerPure.trace(s"Can use atomic move from policy new folder to base folder")
      f3 <- testMove(f2, examplePath.backupFolder)
      _  <- PolicyGenerationLoggerPure.trace(s"Can use atomic move from policy base folder to archive folder")
      _  <- PolicyGenerationLoggerPure.debug(s"Using atomic move for node policies")
      _  <- IO.effect(f3.delete(true))
    } yield {
      atomically
    }).catchAll {
      case ex: AtomicMoveNotSupportedException =>
        PolicyGenerationLoggerPure.warn(s"Node policy directories (${examplePath.baseFolder}, ${examplePath.newFolder}, ${examplePath.backupFolder}) " +
                              s"are not on the same file system. Rudder won't be able to use atomic move for policies, which may have dire " +
                              s"effect on policy generation performance. You should use the file system.") *>
        default.succeed
      case ex: Throwable =>
        val err = SystemError("Error when testing for policy 'mv' mode", ex)
        PolicyGenerationLoggerPure.debug(err.fullMsg) *> err.fail
    }
  }


  /**
   * Copy a resource file from a technique to the node promises directory
   */
  private[this] def copyResourceFile(
      file             : TechniqueFile
    , isRootServer     : Boolean
    , agentType        : AgentType
    , rulePath         : String
    , reportIdToReplace: Option[PolicyId]
    , resources        : Map[(TechniqueResourceId, AgentType), TechniqueResourceCopyInfo]
  ): IOResult[String] = {
    val destination = {
      val out = reportIdToReplace match {
        case None     => file.outPath
        case Some(id) => file.outPath.replaceAll(Policy.TAG_OF_RUDDER_MULTI_POLICY, id.getRudderUniqueId)
      }
      File(rulePath+"/"+out)
    }
    resources.get((file.id, agentType)) match {
      case None    => Unexpected(s"Can not open the technique resource file ${file.id} for reading").fail
      case Some(s) =>
        for {
          _ <- destination.createParentsAndWrite(s.content, isRootServer).chainError(
                 s"Error when copying technique resoure file '${file.id}' to '${destination.pathAsString}'"
               )
        } yield {
          destination.pathAsString
        }
    }
  }

  /**
   * Move the machine policies folder to the backup folder
   */
  private[this] def backupNodeFolder(nodeFolder: String, backupFolder: String, mvOptions: File.CopyOptions, optGroupOwner: Option[String], perms: Set[PosixFilePermission]): IOResult[Unit] = {
    IOResult.effect {
      val src = File(nodeFolder)
      if (src.isDirectory()) {
        val dest = File(backupFolder)
        if (dest.isDirectory) {
          // force deletion of previous backup
          dest.delete(false, File.LinkOptions.noFollow)
        }
        PolicyGenerationLogger.trace(s"Backup old '${nodeFolder}' into ${backupFolder}")
        moveFile(src, dest, mvOptions, Some(perms), optGroupOwner)
      }
    }
  }

  /**
   * Move the newly created folder to the final location
   */
  private[this] def moveNewNodeFolder(sourceFolder: String, destinationFolder: String, mvOptions: File.CopyOptions, optGroupOwner: Option[String], perms: Set[PosixFilePermission]): IOResult[Unit] = {

    val src = File(sourceFolder)

    for {
      b <- IOResult.effect(src.isDirectory)
      _ <- if (b) {
             val dest = File(destinationFolder)
             for {
               _ <- PolicyGenerationLoggerPure.trace(s"Moving folders: \n  from ${src.pathAsString}\n  to   ${dest.pathAsString}")
               _ <- ZIO.whenM(IOResult.effect(dest.isDirectory)) {
                      // force deletion of previous promises
                      IOResult.effect(dest.delete(false, File.LinkOptions.noFollow))
                    }
               _ <- IOResult.effect {
                      moveFile(src, dest, mvOptions, Some(perms), optGroupOwner)
                    }.chainError(s"Error when moving newly generated policies to node directory")
                    // force deletion of dandling new promise folder
               _ <- ZIO.whenM(IOResult.effect(src.parent.isDirectory && src.parent.pathAsString.endsWith("rules.new"))) {
                      IOResult.effect(src.parent.delete(false, File.LinkOptions.noFollow))
                    }
             } yield ()
           } else {
             PolicyGenerationLoggerPure.error(s"Could not find freshly created policies at '${sourceFolder}'") *>
             Inconsistency(s"Source policies at '${src.pathAsString}' are missing'").fail
           }
    } yield ()
  }

  /**
   * Restore (by moving) backup folder to its original location
   * @param nodeFolder
   * @param backupFolder
   */
  private[this] def restoreBackupNodeFolder(nodeFolder: String, backupFolder: String, mvOptions: File.CopyOptions, optGroupOwner: Option[String], perms: Set[PosixFilePermission]): IOResult[Unit] = {
    IOResult.effectM {
      val src = File(backupFolder)
      if (src.isDirectory()) {
        val dest = File(nodeFolder)
        // force deletion of invalid promises
        dest.delete(false, File.LinkOptions.noFollow)
        moveFile(src, dest, mvOptions, Some(perms), optGroupOwner)
        UIO.unit
      } else {
        PolicyGenerationLoggerPure.error(s"Could not find freshly backup policies at '${backupFolder}'") *>
        Inconsistency(s"Backup policies could not be found at '${src.pathAsString}', and valid policies couldn't be restored.").fail
      }
    }
  }
}
