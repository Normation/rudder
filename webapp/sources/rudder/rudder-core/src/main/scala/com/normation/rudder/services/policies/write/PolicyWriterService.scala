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
import com.normation.rudder.domain.nodes.NodeProperty
import com.normation.rudder.domain.policies.GlobalPolicyMode
import com.normation.rudder.domain.reports.NodeConfigId
import com.normation.rudder.hooks.HookEnvPairs
import com.normation.rudder.hooks.HooksLogger
import com.normation.rudder.hooks.RunHooks
import com.normation.rudder.services.policies.nodeconfig.NodeConfigurationLogger
import com.normation.templates.FillTemplatesService
import com.normation.templates.STVariable
import com.normation.utils.Control._
import java.io.File
import java.io.IOException

import net.liftweb.common._
import net.liftweb.json.JsonAST
import net.liftweb.json.JsonAST.JValue
import net.liftweb.util.Helpers.tryo
import org.apache.commons.io.FileUtils
import org.apache.commons.io.IOUtils
import org.joda.time.DateTime

import scala.util.{Failure => FailTry}
import scala.util.Success
import scala.util.Try
import com.normation.cfclerk.domain.SystemVariable
import com.normation.cfclerk.domain.Variable
import com.normation.rudder.services.policies.NodeConfiguration
import com.normation.rudder.services.policies.ParameterEntry
import com.normation.rudder.services.policies.PolicyId
import com.normation.rudder.services.policies.Policy
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

import com.normation.rudder.domain.logger.PolicyLogger
import com.normation.rudder.hooks.HookReturnCode
import zio._
import zio.syntax._
import zio.duration._
import com.normation.errors._
import com.normation.box._
import com.normation.zio._
import zio.blocking.Blocking
import zio.duration.Duration
import cats.data._
import cats.implicits._
import org.apache.commons.lang.StringUtils

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
      rootNodeId    : NodeId
    , nodesToWrite  : Set[NodeId]
    , allNodeConfigs: Map[NodeId, NodeConfiguration]
    , versions      : Map[NodeId, NodeConfigId]
    , globalPolicyMode: GlobalPolicyMode
    , generationTime  : DateTime
    , parallelism     : Int
  ) : Box[Seq[NodeConfiguration]]
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
) extends PolicyWriterService with Loggable {

  val hookWarnDurationMillis = (60*1000).millis

  val newPostfix = ".new"
  val backupPostfix = ".bkp"

  val policyLogger = PolicyLogger

  private[this] def writeNodePropertiesFile (agentNodeConfig: AgentNodeConfiguration) = {

    def generateNodePropertiesJson(properties : Seq[NodeProperty]): JValue = {
      import com.normation.rudder.domain.nodes.JsonSerialisation._
      import net.liftweb.json.JsonDSL._
      ( "properties" -> properties.toDataJson())
    }

    val fileName = Constants.GENERATED_PROPERTY_FILE
    val path = Constants.GENERATED_PROPERTY_DIR
    val jsonProperties = generateNodePropertiesJson(agentNodeConfig.config.nodeInfo.properties)
    val propertyContent = JsonAST.prettyRender(jsonProperties)
    logger.trace(s"Create node properties file '${agentNodeConfig.paths.newFolder}/${path}/${fileName}'")
    Try {
      val propertyFile = new File ( new File (agentNodeConfig.paths.newFolder, path), fileName)
      FileUtils.writeStringToFile(propertyFile, propertyContent, StandardCharsets.UTF_8)
    } match {
      case FailTry(e) =>
        val message = s"could not write ${fileName} file, cause is: ${e.getMessage}"
        Failure(message)
      case Success(_) =>
        Full(agentNodeConfig)
    }
  }

  private[this] def writeRudderParameterFile(agentNodeConfig: AgentNodeConfiguration) = {
    def generateParametersJson(parameters : Set[ParameterEntry]): JValue = {
      import com.normation.rudder.domain.nodes.JsonSerialisation._
      import net.liftweb.json.JsonDSL._
      ( "parameters" -> parameters.toDataJson())
    }

    val fileName = Constants.GENERATED_PARAMETER_FILE
    val jsonParameters = generateParametersJson(agentNodeConfig.config.parameters.map(x => ParameterEntry(x.name.value, x.value, agentNodeConfig.agentType)))
    val parameterContent = JsonAST.prettyRender(jsonParameters)
    logger.trace(s"Create parameter file '${agentNodeConfig.paths.newFolder}/${fileName}'")
    Try {
      val parameterFile = new File ( new File (agentNodeConfig.paths.newFolder), fileName)
      FileUtils.writeStringToFile(parameterFile, parameterContent, StandardCharsets.UTF_8)
    } match {
      case FailTry(e) =>
        val message = s"could not write ${fileName} file, cause is: ${e.getMessage}"
        Failure(message)
      case Success(_) =>
        Full(agentNodeConfig)
    }
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
  def parrallelSequence[U,T](seq: Seq[U])(f:U => Box[T])(implicit timeout: Duration, maxParallelism: Int): Box[Seq[T]] = {
    ZIO.accessM[Blocking]{ b =>
      seq.accumulateParN(maxParallelism)(a => b.blocking.blocking(f(a).toIO))
    }.timeout(timeout).foldM(
      err => err.fail
    , suc => suc match {
        case None      => //timeout
          Accumulated(NonEmptyList.one(Unexpected(s"Execution of computation timed out after '${timeout.asJava.toString}'"))).fail
        case Some(seq) =>
          seq.succeed
      }
    ).provide(ZioRuntime.environment).toBox
  }

  // a version for Hook with a nicer message accumulation
  def parallelSequenceNodeHook(seq: Seq[AgentNodeConfiguration])(f: AgentNodeConfiguration => HookReturnCode)(implicit timeout: Duration, maxParallelism: Int): Box[Unit] = {

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

    val prog = seq.accumulateParNELN(maxParallelism){ a =>
      zio.Task.effect(f(a)).foldM(
        ex   => HookError(a.config.nodeInfo.id, HookReturnCode.SystemError(ex.getMessage)).fail
      , code => code match {
          case s:HookReturnCode.Success => s.succeed
          case e:HookReturnCode.Error   => HookError(a.config.nodeInfo.id, e).fail
        }
      )
    }

    ZIO.accessM[Blocking](b => b.blocking.blocking(prog.either)).timeout(timeout).provide(ZioRuntime.environment).runNow match {
      case None            => Failure(s"Hook execution timed out after '${timeout.asJava.toString}'")
      case Some(Right(x))  => Full(())
      case Some(Left(nel)) =>
        // in that case, it is extremely likely that most messages are the same. We group them together
        val nodeErrors = nel.toList.map{ err => (err.nodeId, err.msg) }
        val message = nodeErrors.groupBy( _._2 ).foldLeft("Error when executing hooks:") { case (s, (msg, list)) =>
          s + s"\n ${msg} (for node(s) ${list.map(_._1.value).mkString(";")})"
        }
        Failure(message)
    }
  }

  /**
   * Write templates for node configuration that changed since the last write.
   *
   */
  override def writeTemplate(
      rootNodeId      : NodeId
    , nodesToWrite    : Set[NodeId]
    , allNodeConfigs  : Map[NodeId, NodeConfiguration]
    , versions        : Map[NodeId, NodeConfigId]
    , globalPolicyMode: GlobalPolicyMode
    , generationTime  : DateTime
    , parallelism     : Int
  ) : Box[Seq[NodeConfiguration]] = {

    val nodeConfigsToWrite     = allNodeConfigs.filterKeys(nodesToWrite.contains(_))
    val interestingNodeConfigs = allNodeConfigs.filterKeys(k => nodeConfigsToWrite.exists{ case(x, _) => x == k }).values.toSeq
    val techniqueIds           = interestingNodeConfigs.flatMap( _.getTechniqueIds ).toSet

    //debug - but don't fails for debugging !
    logNodeConfig.log(nodeConfigsToWrite.values.toSeq) match {
      case eb:EmptyBox =>
        val e = eb ?~! "Error when trying to write node configurations for debugging"
        logger.error(e)
        e.rootExceptionCause.foreach { ex =>
          logger.error("Root exception cause was:", ex)
        }
      case _ => //nothing to do
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
    import scala.collection.JavaConverters._
    val systemEnv = HookEnvPairs.build(System.getenv.asScala.toSeq:_*)

    // give a timeout for the whole tasks sufficiently large.
    // Hint: CF-promise taking 2s by node, for 10 000 nodes, on
    // 4 cores => ~85 minutes...
    // It is here mostly as a safeguard for generation which went wrong -
    // we will already have timeout at the thread level for stalling threads.
    implicit val timeout = Duration(2, TimeUnit.HOURS)
    // Max number of thread used in the I/O thread pool for blocking tasks.
    implicit val maxParallelism = parallelism

    //interpret HookReturnCode as a Box

    val readTemplateTime1 = System.currentTimeMillis
    for {

      configAndPaths   <- calculatePathsForNodeConfigurations(interestingNodeConfigs, rootNodeId, allNodeConfigs, newPostfix, backupPostfix)
      pathsInfo        =  configAndPaths.map { _.paths }
      templates        <- readTemplateFromFileSystem(techniqueIds)
      resources        <- readResourcesFromFileSystem(techniqueIds)
      // Clearing cache
      _                 = fillTemplates.clearCache
      readTemplateTime2 = System.currentTimeMillis
      readTemplateDur   = readTemplateTime2 - readTemplateTime1
      _                 = policyLogger.debug(s"Paths computed and templates read in ${readTemplateDur} ms")

      //////////
      // nothing agent specific before that
      //////////

      preparedPromises <- parrallelSequence(configAndPaths) { case agentNodeConfig =>
                            val nodeConfigId = versions(agentNodeConfig.config.nodeInfo.id)
                            prepareTemplate.prepareTemplateForAgentNodeConfiguration(agentNodeConfig, nodeConfigId, rootNodeId, templates, allNodeConfigs, Policy.TAG_OF_RUDDER_ID, globalPolicyMode, generationTime) ?~!
                            s"Error when calculating configuration for node '${agentNodeConfig.config.nodeInfo.hostname}' (${agentNodeConfig.config.nodeInfo.id.value})"
                         }
      preparedPromisesTime = System.currentTimeMillis
      preparedPromisesDur  = preparedPromisesTime - readTemplateTime2
      _                    = policyLogger.debug(s"Promises prepared in ${preparedPromisesDur} ms")

      promiseWritten   <- parrallelSequence(preparedPromises) { prepared =>
                            (for {
                              _ <- writePromises(prepared.paths, prepared.agentNodeProps.agentType, prepared.preparedTechniques, resources)
                              _ <- writeAllAgentSpecificFiles.write(prepared) ?~! s"Error with node '${prepared.paths.nodeId.value}'"
                              _ <- writeDirectiveCsv(prepared.paths, prepared.policies, globalPolicyMode)
                              _ <- writeSystemVarJson(prepared.paths, prepared.systemVariables)
                            } yield {
                              "OK"
                            }) ?~! s"Error when writing configuration for node '${prepared.paths.nodeId.value}'"
                          }
      promiseWrittenTime = System.currentTimeMillis
      promiseWrittenDur  = promiseWrittenTime - preparedPromisesTime
      _                  = policyLogger.debug(s"Promises written in ${promiseWrittenDur} ms")


      //////////
      // nothing agent specific after that
      //////////

      propertiesWritten <- parrallelSequence(configAndPaths) { case agentNodeConfig =>
                             writeNodePropertiesFile(agentNodeConfig) ?~!
                               s"An error occured while writing property file for Node ${agentNodeConfig.config.nodeInfo.hostname} (id: ${agentNodeConfig.config.nodeInfo.id.value}"
                           }

      propertiesWrittenTime = System.currentTimeMillis
      propertiesWrittenDur  = propertiesWrittenTime - promiseWrittenTime
      _                     = policyLogger.debug(s"Properties written in ${propertiesWrittenDur} ms")

      parametersWritten <- parrallelSequence(configAndPaths) { case agentNodeConfig =>
                             writeRudderParameterFile(agentNodeConfig) ?~!
                               s"An error occured while writing parameter file for Node ${agentNodeConfig.config.nodeInfo.hostname} (id: ${agentNodeConfig.config.nodeInfo.id.value}"
                          }

      parametersWrittenTime = System.currentTimeMillis
      parametersWrittenDur  = parametersWrittenTime - propertiesWrittenTime
      _                     = policyLogger.debug(s"Parameters written in ${parametersWrittenDur} ms")


      _                 = fillTemplates.clearCache
      /// perhaps that should be a post-hook somehow ?
      // and perhaps we should have an AgentSpecific global pre/post write

      nodePreMvHooks   <- RunHooks.getHooks(HOOKS_D + "/policy-generation-node-ready", HOOKS_IGNORE_SUFFIXES)
      preMvHooks       <- parallelSequenceNodeHook(configAndPaths) { agentNodeConfig =>
                            val timeHooks = System.currentTimeMillis
                            val nodeId = agentNodeConfig.config.nodeInfo.node.id.value
                            val hostname = agentNodeConfig.config.nodeInfo.hostname
                            val policyServer = agentNodeConfig.config.nodeInfo.policyServerId.value
                            val res = RunHooks.syncRun(
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
                            HooksLogger.trace(s"Run post-generation pre-move hooks for node '${nodeId}' in ${System.currentTimeMillis - timeHooks} ms")
                            res
                          }

      movedPromisesTime1 = System.currentTimeMillis
      _                  = policyLogger.debug(s"Hooks for policy-generation-node-ready executed in ${movedPromisesTime1-parametersWrittenTime} ms")

      movedPromises    <- tryo { movePromisesToFinalPosition(pathsInfo) }

      movedPromisesTime2 = System.currentTimeMillis
      movedPromisesDur   = movedPromisesTime2 - movedPromisesTime1
      _                  = policyLogger.debug(s"Policies moved to their final position in ${movedPromisesDur} ms")

      nodePostMvHooks  <- RunHooks.getHooks(HOOKS_D + "/policy-generation-node-finished", HOOKS_IGNORE_SUFFIXES)
      postMvHooks      <- parallelSequenceNodeHook(configAndPaths) { agentNodeConfig =>
                            val timeHooks = System.currentTimeMillis
                            val nodeId = agentNodeConfig.config.nodeInfo.node.id.value
                            val hostname = agentNodeConfig.config.nodeInfo.hostname
                            val policyServer = agentNodeConfig.config.nodeInfo.policyServerId.value
                            val res = RunHooks.syncRun(
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
                            HooksLogger.trace(s"Run post-generation post-move hooks for node '${nodeId}' in ${System.currentTimeMillis - timeHooks} ms")
                            res
                          }
      postMvHooksTime2   = System.currentTimeMillis
      _                  = policyLogger.debug(s"Hooks for policy-generation-node-finished executed in ${postMvHooksTime2 - movedPromisesTime2} ms")
    } yield {
      val ids = movedPromises.map { _.nodeId }.toSet
      allNodeConfigs.filterKeys { id => ids.contains(id) }.values.toSeq
    }

  }

  ///////////// implementation of each step /////////////

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
    , allNodeConfigs     : Map[NodeId, NodeConfiguration]
    , newsFileExtension  : String
    , backupFileExtension: String
  ): Box[Seq[AgentNodeConfiguration]] = {

    val agentConfig = configs.flatMap { config =>
      if(config.nodeInfo.agentsName.size == 0) {
        logger.info(s"Node '${config.nodeInfo.hostname}' (${config.nodeInfo.id.value}) has no agent type configured and so no promises will be generated")
      }
      config.nodeInfo.agentsName.map {agentType => (agentType, config) }
    }

    sequence( agentConfig )  { case (agentInfo, config) =>
      val agentType = agentInfo.agentType
      for {
        paths <- if(rootNodeConfigId == config.nodeInfo.id) {
                    Full(NodePromisesPaths(
                        config.nodeInfo.id
                      , pathComputer.getRootPath(agentType)
                      , pathComputer.getRootPath(agentType) + newsFileExtension
                      , pathComputer.getRootPath(agentType) + backupFileExtension
                    ))
                  } else {
                    pathComputer.computeBaseNodePath(config.nodeInfo.id, rootNodeConfigId, allNodeConfigs.mapValues(_.nodeInfo)).map { case NodePromisesPaths(id, base, news, backup) =>
                        val postfix = agentType.toRulesPath
                        NodePromisesPaths(id, base + postfix, news + postfix, backup + postfix)
                    }
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
  )(implicit timeout: Duration, maxParallelism: Int): Box[Map[(TechniqueResourceId, AgentType), TechniqueTemplateCopyInfo]] = {

    //list of (template id, template out path)
    val templatesToRead = for {
      technique <- techniqueRepository.getByIds(techniqueIds.toSeq)
      template  <- technique.agentConfigs.flatMap(cfg => cfg.templates.map(t => (t.id, cfg.agentType, t.outPath)))
    } yield {
      template
    }

    val now = System.currentTimeMillis()

    /*
     * NOTE : this is inefficient and store in a lot of multiple time the same content
     * if only the outpath change for two differents agent type.
     */
   val res = (parrallelSequence(templatesToRead) { case (templateId, agentType, templateOutPath) =>
      for {
        copyInfo <- techniqueRepository.getTemplateContent(templateId) { optInputStream =>
          optInputStream match {
            case None =>
              Failure(s"Error when trying to open template '${templateId.toString}'. Check that the file exists with a ${TechniqueTemplate.templateExtension} extension and is correctly commited in Git, or that the metadata for the technique are corrects.")
            case Some(inputStream) =>
              logger.trace(s"Loading template: ${templateId}")
              //string template does not allows "." in path name, so we are force to use a templateGroup by polity template (versions have . in them)
              val content = IOUtils.toString(inputStream, StandardCharsets.UTF_8)
              Full(TechniqueTemplateCopyInfo(templateId, templateOutPath, content))
          }
        }
      } yield {
        ((copyInfo.id, agentType), copyInfo)
      }
    }).map( _.toMap)

    logger.debug(s"${templatesToRead.size} promises templates read in ${System.currentTimeMillis-now} ms")
    res
  }


  private[this] def writePromises(
      paths             : NodePromisesPaths
    , agentType         : AgentType
    , preparedTechniques: Seq[PreparedTechnique]
    , resources         : Map[(TechniqueResourceId, AgentType), TechniqueResourceCopyInfo]
  ) : Box[NodePromisesPaths] = {
    // write the promises of the current machine and set correct permission
    for {
      _ <- sequence(preparedTechniques) { preparedTechnique =>
             for {
               templates <-  sequence(preparedTechnique.templatesToProcess.toSeq) { template =>
                               writePromisesFiles(template, preparedTechnique.environmentVariables, paths.newFolder, preparedTechnique.reportIdToReplace)
                             }
               files     <- sequence(preparedTechnique.filesToCopy.toSeq) { file =>
                              copyResourceFile(file, agentType, paths.newFolder, preparedTechnique.reportIdToReplace, resources)
                            }
             } yield {
               "OK"
             }
           }
    } yield {
      paths
    }
  }

  /*
   * We are returning a map where keys are (TechniqueResourceId, AgentType) because
   * for a given resource IDs, you can have different out path for different agent.
   */
  private[this] def readResourcesFromFileSystem(
     techniqueIds: Set[TechniqueId]
  )(implicit timeout: Duration, maxParallelism: Int): Box[Map[(TechniqueResourceId, AgentType), TechniqueResourceCopyInfo]] = {

    val staticResourceToRead = for {
      technique      <- techniqueRepository.getByIds(techniqueIds.toSeq)
      staticResource <- technique.agentConfigs.flatMap(cfg => cfg.files.map(t => (t.id, cfg.agentType, t.outPath)))
    } yield {
      staticResource
    }

    val now = System.currentTimeMillis()

    val res = (parrallelSequence(staticResourceToRead) { case (templateId, agentType, templateOutPath) =>
      for {
        copyInfo <- techniqueRepository.getFileContent(templateId) { optInputStream =>
          optInputStream match {
            case None =>
              Failure(s"Error when trying to open template '${templateId.toString}'. Check that the file exists with a ${TechniqueTemplate.templateExtension} extension and is correctly commited in Git, or that the metadata for the technique are corrects.")
            case Some(inputStream) =>
              logger.trace(s"Loading template: ${templateId}")
              //string template does not allows "." in path name, so we are force to use a templateGroup by polity template (versions have . in them)
              val content = IOUtils.toString(inputStream, StandardCharsets.UTF_8)
              Full(TechniqueResourceCopyInfo(templateId, templateOutPath, content))
          }
        }
      } yield {
        ((copyInfo.id, agentType), copyInfo)
      }
    }).map( _.toMap)

    logger.debug(s"${staticResourceToRead.size} techniques resources read in ${System.currentTimeMillis-now} ms")
    res
  }


  private[this] def writeDirectiveCsv(paths: NodePromisesPaths, policies: Seq[Policy], policyMode : GlobalPolicyMode) =  {
    val path = new File(paths.newFolder, "rudder-directives.csv")

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
    for {
      _ <- tryo { FileUtils.writeStringToFile(path, csvContent.mkString("\n") + "\n", StandardCharsets.UTF_8) } ?~!
        s"Can not write rudder-directives.csv file at path '${path.getAbsolutePath}'"
    } yield {
      AgentSpecificFile(path.getAbsolutePath) :: Nil
    }
  }

  private[this] def writeSystemVarJson(paths: NodePromisesPaths, variables: Map[String, Variable]) =  {
    val path = new File(paths.newFolder, "rudder.json")
    for {
        _ <- tryo { FileUtils.writeStringToFile(path, systemVariableToJson(variables) + "\n", StandardCharsets.UTF_8) } ?~!
               s"Can not write json parameter file at path '${path.getAbsolutePath}'"
    } yield {
      AgentSpecificFile(path.getAbsolutePath) :: Nil
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
   * @param folder : (Container identifier, (base folder, new folder of the policies, backup folder of the policies) )
   */
  private[this] def movePromisesToFinalPosition(folders: Seq[NodePromisesPaths]): Seq[NodePromisesPaths] = {
    // We need to sort the folders by "depth", so that we backup and move the deepest one first
    val sortedFolder = folders.sortBy(x => x.baseFolder.count(_ =='/')).reverse

    val newFolders = scala.collection.mutable.Buffer[NodePromisesPaths]()
    try {
      // Folders is a map of machine.uuid -> (base_machine_folder, backup_machine_folder, machine)
      for (folder @ NodePromisesPaths(_, baseFolder, newFolder, backupFolder) <- sortedFolder) {
        // backup old promises
        logger.trace("Backuping old promises from %s to %s ".format(baseFolder, backupFolder))
        backupNodeFolder(baseFolder, backupFolder)
        try {
          newFolders += folder

          logger.trace("Copying new promises into %s ".format(baseFolder))
          // move new promises
          moveNewNodeFolder(newFolder, baseFolder)

        } catch {
          case ex: Exception =>
            logger.error("Could not write promises into %s, reason : ".format(baseFolder), ex)
            throw ex
        }
      }
      folders
    } catch {
      case ex: Exception =>

        for (folder <- newFolders) {
          logger.info("Restoring old promises on folder %s".format(folder.baseFolder))
          try {
            restoreBackupNodeFolder(folder.baseFolder, folder.backupFolder);
          } catch {
            case ex: Exception =>
              logger.error("could not restore old promises into %s ".format(folder.baseFolder))
              throw ex
          }
        }
        throw ex
    }

  }

  ///////////// utilities /////////////

  /**
   * Copy a resource file from a technique to the node promises directory
   */
  private[this] def copyResourceFile(
      file             : TechniqueFile
    , agentType        : AgentType
    , rulePath         : String
    , reportIdToReplace: Option[PolicyId]
    , resources        : Map[(TechniqueResourceId, AgentType), TechniqueResourceCopyInfo]
  ): Box[String] = {
    val destination = {
      val out = reportIdToReplace match {
        case None     => file.outPath
        case Some(id) => file.outPath.replaceAll(Policy.TAG_OF_RUDDER_MULTI_POLICY, id.getRudderUniqueId)
      }
      new File(rulePath+"/"+out)
    }

    resources.get((file.id, agentType)) match {
      case None    => Failure(s"Can not open the technique resource file ${file.id} for reading")
      case Some(s) =>
        try {
          FileUtils.writeStringToFile(destination, s.content, StandardCharsets.UTF_8)
          Full(destination.getAbsolutePath)
        } catch {
          case ex: Exception => Failure(s"Error when copying technique resoure file '${file.id}' to '${destination.getAbsolutePath}')", Full(ex), Empty)
        }
    }
  }

  /**
   * Write the current seq of template file a the path location, replacing the variables found in variableSet
   * @param fileSet : the set of template to be written
   * @param variableSet : the set of variable
   * @param path : where to write the files
   */
  private[this] def writePromisesFiles(
      templateInfo          : TechniqueTemplateCopyInfo
    , variableSet           : Seq[STVariable]
    , outPath               : String
    , reportIdToReplace     : Option[PolicyId]
  ): Box[String] = {

    //here, we need a big try/catch, because almost anything in string template can
    //throw errors
    // write the files to the new promise folder
    logger.trace(s"Create promises file ${outPath} ${templateInfo.destination}")

    for {
      filled           <- fillTemplates.fill(templateInfo.destination, templateInfo.content, variableSet).toBox
      // if the technique is multipolicy, replace rudderTag by reportId
      (replaced, dest) =  reportIdToReplace match {
                            case None => (filled, templateInfo.destination)
                            case Some(id) =>
                              // this is done quite heavely on big instances, with string rather big, and the performance of
                              // StringUtils.replace ix x4 the one of String.replace (no regex), see:
                              // https://stackoverflow.com/questions/16228992/commons-lang-stringutils-replace-performance-vs-string-replace/19163566
                              val replace = (s: String) => StringUtils.replace(s, Policy.TAG_OF_RUDDER_MULTI_POLICY, id.getRudderUniqueId)
                              (replace(filled), replace(templateInfo.destination))
                          }
      _                <- tryo { FileUtils.writeStringToFile(new File(outPath, dest), replaced, StandardCharsets.UTF_8) } ?~!
                            s"Bad format in Technique ${templateInfo.id.toString} (file: ${templateInfo.destination})"
    } yield {
      outPath
    }
  }

  /**
   * Move the machine promises folder  to the backup folder
   * @param machineFolder
   * @param backupFolder
   */
  private[this] def backupNodeFolder(nodeFolder: String, backupFolder: String): Unit = {
    val src = new File(nodeFolder)
    if (src.isDirectory()) {
      val dest = new File(backupFolder)
      if (dest.isDirectory) {
        // force deletion of previous backuup
        FileUtils.deleteDirectory(dest)
      }
      FileUtils.moveDirectory(src, dest)
    }
  }

  /**
   * Move the newly created folder to the final location
   * @param newFolder : where the promises have been written
   * @param nodeFolder : where the promises will be
   */
  private[this] def moveNewNodeFolder(sourceFolder: String, destinationFolder: String): Unit = {
    val src = new File(sourceFolder)

    logger.trace("Moving folders from %s to %s".format(src, destinationFolder))

    if (src.isDirectory()) {
      val dest = new File(destinationFolder)

      if (dest.isDirectory) {
        // force deletion of previous promises
        FileUtils.deleteDirectory(dest)
      }
      FileUtils.moveDirectory(src, dest)

      // force deletion of dandling new promise folder
      if ( (src.getParentFile().isDirectory) && (src.getParent().endsWith("rules.new"))) {
        FileUtils.deleteDirectory(src.getParentFile())
      }
    } else {
      logger.error("Could not find freshly created promises at %s".format(sourceFolder))
      throw new IOException("Created promises not found !!!!")
    }
  }

  /**
   * Restore (by moving) backup folder to its original location
   * @param machineFolder
   * @param backupFolder
   */
  private[this] def restoreBackupNodeFolder(nodeFolder: String, backupFolder: String): Unit = {
    val src = new File(backupFolder)
    if (src.isDirectory()) {
      val dest = new File(nodeFolder)
      // force deletion of invalid promises
      FileUtils.forceDelete(dest)

      FileUtils.moveDirectory(src, dest)
    } else {
      logger.error("Could not find freshly backup promises at %s".format(backupFolder))
      throw new IOException("Backup promises could not be found, and valid promises couldn't be restored !!!!")
    }
  }
}
