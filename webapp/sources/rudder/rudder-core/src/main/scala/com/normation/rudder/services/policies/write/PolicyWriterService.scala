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
import com.normation.cfclerk.domain.TechniqueResourceId
import com.normation.cfclerk.domain.TechniqueTemplate
import com.normation.cfclerk.services.TechniqueRepository
import com.normation.inventory.domain.AgentType
import com.normation.inventory.domain.AgentType.CfeEnterprise
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.Constants
import com.normation.rudder.domain.licenses.CfeEnterpriseLicense
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
import org.apache.commons.io.FilenameUtils
import org.apache.commons.io.IOUtils
import org.joda.time.DateTime

import com.normation.cfclerk.domain.SystemVariable
import com.normation.cfclerk.domain.Variable
import com.normation.rudder.services.policies.NodeConfiguration
import com.normation.rudder.services.policies.ParameterEntry
import com.normation.rudder.services.policies.PolicyId
import com.normation.rudder.services.policies.Policy
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

import com.normation.cfclerk.domain.Technique
import com.normation.rudder.domain.logger.PolicyLogger
import com.normation.rudder.hooks.HookReturnCode
import scalaz.zio._
import scalaz.zio.syntax._
import com.normation.errors._
import com.normation.zio._
import scalaz.zio.blocking.Blocking
import scalaz.zio.duration.Duration
import cats.data._
import cats.implicits._
import com.normation.rudder.domain.logger.PolicyLoggerPure


final case class TechniqueResources(
    templates: Map[(TechniqueResourceId, AgentType), TechniqueTemplateCopyInfo]
  , resources: Map[(TechniqueResourceId, AgentType), TechniqueResourceCopyInfo]
)

/**
 * Write promises for the set of nodes, with the given configs.
 * Requires access to external templates files.
 */
trait PolicyWriterService {


  /*
   * Get techniques files (ie templates and resources)
   */
  def getTechniquesResources(
      nodesToWrite    : Set[NodeId]
    , allNodeConfigs  : Map[NodeId, NodeConfiguration]
    , maxParallelism  : Int
  ): IOResult[TechniqueResources]

  /**
   * Write templates for node configuration that changed since the last write.
   *
   */
  def writeTemplate(
      rootNodeId        : NodeId
    , nodesToWrite      : Set[NodeId]
    , allNodeConfigs    : Map[NodeId, NodeConfiguration]
    , versions          : Map[NodeId, NodeConfigId]
    , allLicenses       : Map[NodeId, CfeEnterpriseLicense]
    , techniqueResources: TechniqueResources
    , globalPolicyMode  : GlobalPolicyMode
    , generationTime    : DateTime
    , maxParallelism    : Long
  ) : IOResult[Seq[NodeConfiguration]]
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
) extends PolicyWriterService {

  val newPostfix = ".new"
  val backupPostfix = ".bkp"

  private[this] def writeNodePropertiesFile (agentNodeConfig: AgentNodeConfiguration): IOResult[AgentNodeConfiguration] = {

    def generateNodePropertiesJson(properties : Seq[NodeProperty]): JValue = {
      import com.normation.rudder.domain.nodes.JsonSerialisation._
      import net.liftweb.json.JsonDSL._
      ( "properties" -> properties.toDataJson())
    }

    val fileName = Constants.GENERATED_PROPERTY_FILE
    val path = Constants.GENERATED_PROPERTY_DIR
    val jsonProperties = generateNodePropertiesJson(agentNodeConfig.config.nodeInfo.properties)
    val propertyContent = JsonAST.prettyRender(jsonProperties)

    for {
      _ <- PolicyLoggerPure.trace(s"Create node properties file '${agentNodeConfig.paths.newFolder}/${path}/${fileName}'")
      _ <- IOResult.effect(s"could not write ${fileName} file") {
             val propertyFile = new File ( new File (agentNodeConfig.paths.newFolder, path), fileName)
             FileUtils.writeStringToFile(propertyFile, propertyContent, StandardCharsets.UTF_8)
           }
    } yield {
      agentNodeConfig
    }
  }

  private[this] def writeRudderParameterFile(agentNodeConfig: AgentNodeConfiguration): IOResult[AgentNodeConfiguration] = {
    def generateParametersJson(parameters : Set[ParameterEntry]): JValue = {
      import com.normation.rudder.domain.nodes.JsonSerialisation._
      import net.liftweb.json.JsonDSL._
      ( "parameters" -> parameters.toDataJson())
    }

    val fileName = Constants.GENERATED_PARAMETER_FILE
    val jsonParameters = generateParametersJson(agentNodeConfig.config.parameters.map(x => ParameterEntry(x.name.value, x.value, agentNodeConfig.agentType)))
    val parameterContent = JsonAST.prettyRender(jsonParameters)
    for {
      _ <- PolicyLoggerPure.trace(s"Create parameter file '${agentNodeConfig.paths.newFolder}/${fileName}'")
      _ <- IOResult.effect(s"could not write ${fileName} file"){
             val parameterFile = new File ( new File (agentNodeConfig.paths.newFolder), fileName)
             FileUtils.writeStringToFile(parameterFile, parameterContent, StandardCharsets.UTF_8)
           }
    } yield {
      agentNodeConfig
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
  def parrallelSequence[U,T](seq: Seq[U])(f:U => Box[T])(implicit timeout: Duration, maxParallelism: Long): IOResult[Seq[T]] = {
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
    ).provide(ZioRuntime.Environment)
  }

  // a version for Hook with a nicer message accumulation
  def parrallelSequenceNodeHook(timeout: Duration, maxParallelism: Long)(seq: Seq[AgentNodeConfiguration])(f: AgentNodeConfiguration => HookReturnCode): IOResult[Unit] = {

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
      scalaz.zio.Task.effect(f(a)).foldM(
        ex   => HookError(a.config.nodeInfo.id, HookReturnCode.SystemError(ex.getMessage)).fail
      , code => code match {
          case s:HookReturnCode.Success => s.succeed
          case e:HookReturnCode.Error   => HookError(a.config.nodeInfo.id, e).fail
        }
      )
    }

    ZIO.accessM[Blocking](b => b.blocking.blocking(prog.either)).timeout(timeout).provide(ZioRuntime.Environment).flatMap {
      case None            => Unexpected(s"Hook execution timed out after '${timeout.asJava.toString}'").fail
      case Some(Right(x))  => UIO.unit
      case Some(Left(nel)) =>
        // in that case, it is extremely likely that most messages are the same. We group them together
        val nodeErrors = nel.toList.map{ err => (err.nodeId, err.msg) }
        val message = nodeErrors.groupBy( _._2 ).foldLeft("Error when executing hooks:") { case (s, (msg, list)) =>
          s + s"\n ${msg} (for node(s) ${list.map(_._1.value).mkString(";")})"
        }
        Unexpected(message).fail
    }
  }

  override def getTechniquesResources(
      nodesToWrite    : Set[NodeId]
    , allNodeConfigs  : Map[NodeId, NodeConfiguration]
    , maxParallelism  : Int
  ): IOResult[TechniqueResources]  = {
    /*
     * We are returning a map where keys are (TechniqueResourceId, AgentType) because
     * for a given resource IDs, you can have different out path for different agent.
     */
    def readTemplateFromFileSystem(
        techniques: Seq[Technique]
      , maxParallelism  : Int
    ): IOResult[Map[(TechniqueResourceId, AgentType), TechniqueTemplateCopyInfo]] = {

      //list of (template id, template out path)
      val templatesToRead = for {
        technique <- techniques
        template  <- technique.agentConfigs.flatMap(cfg => cfg.templates.map(t => (t.id, cfg.agentType, t.outPath)))
      } yield {
        template
      }

      for {
        now <- IOResult.effect(System.currentTimeMillis())
        /*
         * NOTE : this is inefficient and store in a lot of multiple time the same content
         * if only the outpath change for two differents agent type.
         */
        res <- ZIO.foreachParN(maxParallelism.toLong)(templatesToRead) { case (templateId, agentType, templateOutPath) =>
                  for {
                    copyInfo <- techniqueRepository.getTemplateContent(templateId) { optInputStream =>
                      optInputStream match {
                        case None =>
                          Unexpected(s"Error when trying to open template '${templateId.toString}'. Check that the file exists with a ${TechniqueTemplate.templateExtension} extension and is correctly commited in Git, or that the metadata for the technique are corrects.").fail
                        case Some(inputStream) =>
                          PolicyLoggerPure.trace(s"Loading template: ${templateId}") *> IOResult.effect {
                            //string template does not allows "." in path name, so we are force to use a templateGroup by polity template (versions have . in them)
                            val content = IOUtils.toString(inputStream, StandardCharsets.UTF_8)
                            TechniqueTemplateCopyInfo(templateId, templateOutPath, content)
                          }
                      }
                    }
                  } yield {
                    ((copyInfo.id, agentType), copyInfo)
                  }
              }.map( _.toMap)
        _  <- PolicyLoggerPure.Timing.trace(s"${templatesToRead.size} promises templates read in ${System.currentTimeMillis-now} ms")
      } yield {
        res
      }
    }

    /*
     * We are returning a map where keys are (TechniqueResourceId, AgentType) because
     * for a given resource IDs, you can have different out path for different agent.
     */
    def readResourcesFromFileSystem(
        techniques: Seq[Technique]
      , maxParallelism  : Int
    ): IOResult[Map[(TechniqueResourceId, AgentType), TechniqueResourceCopyInfo]] = {

      val staticResourceToRead = for {
        technique      <- techniques
        staticResource <- technique.agentConfigs.flatMap(cfg => cfg.files.map(t => (t.id, cfg.agentType, t.outPath)))
      } yield {
        staticResource
      }

      for {
        now <- IOResult.effect(System.currentTimeMillis())
        res <- ZIO.foreachParN(maxParallelism.toLong)(staticResourceToRead) { case (templateId, agentType, templateOutPath) =>
                for {
                  copyInfo <- techniqueRepository.getFileContent(templateId) { optInputStream =>
                    optInputStream match {
                      case None =>
                        Unexpected(s"Error when trying to open template '${templateId.toString}'. Check that the file exists with a ${TechniqueTemplate.templateExtension} extension and is correctly commited in Git, or that the metadata for the technique are corrects.").fail
                      case Some(inputStream) =>
                        PolicyLoggerPure.trace(s"Loading template: ${templateId}") *> IOResult.effect {
                          //string template does not allows "." in path name, so we are force to use a templateGroup by polity template (versions have . in them)
                          val content = IOUtils.toString(inputStream, StandardCharsets.UTF_8)
                          TechniqueResourceCopyInfo(templateId, templateOutPath, content)
                        }
                    }
                  }
                } yield {
                  ((copyInfo.id, agentType), copyInfo)
                }
              }.map( _.toMap)
        _ <- PolicyLoggerPure.Timing.trace(s"${staticResourceToRead.size} techniques resources read in ${System.currentTimeMillis-now} ms")
      } yield {
        res
      }
    }

    val nodeConfigsToWrite     = allNodeConfigs.filterKeys(nodesToWrite.contains(_))
    val interestingNodeConfigs = allNodeConfigs.filterKeys(k => nodeConfigsToWrite.exists{ case(x, _) => x == k }).values.toSeq
    val techniqueIds           = interestingNodeConfigs.flatMap( _.getTechniqueIds ).toSet
    val readTemplateTime1      = System.currentTimeMillis()
    val techniques             = techniqueRepository.getByIds(techniqueIds.toSeq)

    for {
      templates         <- readTemplateFromFileSystem(techniques, maxParallelism)
      resources         <- readResourcesFromFileSystem(techniques, maxParallelism)
      readTemplateTime2 <- IOResult.effect(DateTime.now.getMillis)
      readTemplateDur   =  readTemplateTime2 - readTemplateTime1
      _                 <- PolicyLoggerPure.Timing.debug(s"Technique templates and resources read in ${readTemplateDur} ms")
    } yield {
      TechniqueResources(templates, resources)
    }
  }



  /**
   * Write templates for node configuration that changed since the last write.
   *
   */
  override def writeTemplate(
      rootNodeId         : NodeId
    , nodesToWrite       : Set[NodeId]
    , allNodeConfigs     : Map[NodeId, NodeConfiguration]
    , versions           : Map[NodeId, NodeConfigId]
    , allLicenses        : Map[NodeId, CfeEnterpriseLicense]
    , techniquesResources: TechniqueResources
    , globalPolicyMode   : GlobalPolicyMode
    , generationTime     : DateTime
    , maxParallelism     : Long
  ) : IOResult[Seq[NodeConfiguration]] = {

    val traceId = {
      val id = if(nodesToWrite.size == 1) nodesToWrite.head.value
               else nodesToWrite.hashCode().toString
      "[" + id + "]"
    }

    val nodeConfigsToWrite     = allNodeConfigs.filterKeys(nodesToWrite.contains(_))
    val interestingNodeConfigs = allNodeConfigs.filterKeys(k => nodeConfigsToWrite.exists{ case(x, _) => x == k }).values.toSeq


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

    //interpret HookReturnCode as a Box

    for {
      //debug - but don't fails for debugging !
      _                 <- logNodeConfig.log(nodeConfigsToWrite.values.toSeq).toIO.
                            chainError("Error when trying to write node configurations for debugging").foldM(
                              err => PolicyLoggerPure.error(err.fullMsg) *> UIO.unit
                            , ok  => UIO.unit
                            )

      readTemplateTime1 <- UIO.effectTotal(System.currentTimeMillis)
      configAndPaths    <- calculatePathsForNodeConfigurations(interestingNodeConfigs, rootNodeId, allNodeConfigs, newPostfix, backupPostfix)
      pathsInfo         =  configAndPaths.map { _.paths }
      readTemplateTime2 <- UIO.effectTotal(System.currentTimeMillis)
      readTemplateDur   =  readTemplateTime2 - readTemplateTime1
      _                 <- PolicyLoggerPure.Timing.trace(s"${traceId} Paths computed in ${readTemplateDur} ms")

      //////////
      // nothing agent specific before that
      //////////

      preparedPromises <- ZIO.foreachParN(maxParallelism)(configAndPaths) { agentNodeConfig =>
                            val nodeConfigId = versions(agentNodeConfig.config.nodeInfo.id)
                            prepareTemplate.prepareTemplateForAgentNodeConfiguration(agentNodeConfig, nodeConfigId, rootNodeId, techniquesResources.templates, allNodeConfigs, Policy.TAG_OF_RUDDER_ID, globalPolicyMode, generationTime).toIO.chainError(
                            s"Error when calculating configuration for node '${agentNodeConfig.config.nodeInfo.hostname}' (${agentNodeConfig.config.nodeInfo.id.value})")
                         }
      preparedPromisesTime <- UIO.effectTotal(System.currentTimeMillis)
      preparedPromisesDur  = preparedPromisesTime - readTemplateTime2
      _                    <- PolicyLoggerPure.Timing.trace(s"${traceId} Promises prepared in ${preparedPromisesDur} ms")

      promiseWritten   <- ZIO.foreachParN(maxParallelism)(preparedPromises) { prepared =>
                            (for {
                              _ <- writePromises(prepared.paths, prepared.agentNodeProps.agentType, prepared.preparedTechniques, techniquesResources.resources).toIO
                              _ <- writeAllAgentSpecificFiles.write(prepared).toIO.chainError(s"Error with node '${prepared.paths.nodeId.value}'")
                              _ <- writeSystemVarJson(prepared.paths, prepared.systemVariables).toIO
                            } yield {
                              ()
                            }).chainError(s"Error when writing configuration for node '${prepared.paths.nodeId.value}'")
                          }
      promiseWrittenTime <- UIO.effectTotal(System.currentTimeMillis)
      promiseWrittenDur  =  promiseWrittenTime - preparedPromisesTime
      _                  <- PolicyLoggerPure.Timing.trace(s"${traceId} Promises written in ${promiseWrittenDur} ms")


      //////////
      // nothing agent specific after that
      //////////

      propertiesWritten <- ZIO.foreachParN(maxParallelism)(configAndPaths) { case agentNodeConfig =>
                             writeNodePropertiesFile(agentNodeConfig).chainError(
                               s"An error occured while writing property file for Node ${agentNodeConfig.config.nodeInfo.hostname} (id: ${agentNodeConfig.config.nodeInfo.id.value}")
                           }

      propertiesWrittenTime <- UIO.effectTotal(System.currentTimeMillis)
      propertiesWrittenDur  = propertiesWrittenTime - promiseWrittenTime
      _                     <- PolicyLoggerPure.Timing.trace(s"${traceId} Properties written in ${propertiesWrittenDur} ms")

      parametersWritten     <- ZIO.foreachParN(maxParallelism)(configAndPaths) { case agentNodeConfig =>
                                 writeRudderParameterFile(agentNodeConfig).chainError(
                                   s"An error occured while writing parameter file for Node ${agentNodeConfig.config.nodeInfo.hostname} (id: ${agentNodeConfig.config.nodeInfo.id.value}")
                               }

      parametersWrittenTime <- UIO.effectTotal(System.currentTimeMillis)
      parametersWrittenDur  =  parametersWrittenTime - propertiesWrittenTime
      _                     <- PolicyLoggerPure.Timing.trace(s"${traceId} Parameters written in ${parametersWrittenDur} ms")

      licensesCopied        <- ZIO.foreachParN(maxParallelism)(configAndPaths) { case agentNodeConfig => copyLicenses(agentNodeConfig, allLicenses) }

      licensesCopiedTime <- UIO.effectTotal(System.currentTimeMillis)
      licensesCopiedDur  =  licensesCopiedTime - parametersWrittenTime
      _                  <- PolicyLoggerPure.Timing.trace(s"${traceId} Licenses copied in ${licensesCopiedDur} ms")
      /// perhaps that should be a post-hook somehow ?
      // and perhaps we should have an AgentSpecific global pre/post write

      nodePreMvHooks   <- RunHooks.getHooks(HOOKS_D + "/policy-generation-node-ready", HOOKS_IGNORE_SUFFIXES).toIO
      preMvHooks       <- parrallelSequenceNodeHook(timeout, maxParallelism)(configAndPaths) { agentNodeConfig =>
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
                            )
                            PolicyLogger.Timing.trace(s"${traceId} Run post-generation pre-move hooks for node '${nodeId}' in ${System.currentTimeMillis - timeHooks} ms")
                            res
                          }

      movedPromisesTime1 <- UIO.effectTotal(System.currentTimeMillis)

      movedPromises      <- IOResult.effect { movePromisesToFinalPosition(pathsInfo) }

      movedPromisesTime2 <- UIO.effectTotal(System.currentTimeMillis)
      movedPromisesDur   =  movedPromisesTime2 - movedPromisesTime1
      _                  <- PolicyLoggerPure.Timing.trace(s"${traceId} Policies moved to their final position in ${movedPromisesDur} ms")

      nodePostMvHooks  <- RunHooks.getHooks(HOOKS_D + "/policy-generation-node-finished", HOOKS_IGNORE_SUFFIXES).toIO
      postMvHooks      <- parrallelSequenceNodeHook(timeout, maxParallelism)(configAndPaths) { agentNodeConfig =>
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
                            )
                            HooksLogger.trace(s"${traceId} Run post-generation post-move hooks for node '${nodeId}' in ${System.currentTimeMillis - timeHooks} ms")
                            res
                          }
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
  ): IOResult[Seq[AgentNodeConfiguration]] = {
    ZIO.foreach( configs )  { config =>
      if(config.nodeInfo.agentsName.size == 0) {
        PolicyLoggerPure.info(s"Node '${config.nodeInfo.hostname}' (${config.nodeInfo.id.value}) has no agent type configured and so no promises will be generated") *>
        Nil.succeed
      } else {
        ZIO.foreach(config.nodeInfo.agentsName) { agentInfo =>
          val agentType = agentInfo.agentType
          for {
            paths <- if(rootNodeConfigId == config.nodeInfo.id) {
                        NodePromisesPaths(
                            config.nodeInfo.id
                          , pathComputer.getRootPath(agentType)
                          , pathComputer.getRootPath(agentType) + newsFileExtension
                          , pathComputer.getRootPath(agentType) + backupFileExtension
                        ).succeed
                      } else {
                        (pathComputer.computeBaseNodePath(config.nodeInfo.id, rootNodeConfigId, allNodeConfigs.mapValues(_.nodeInfo)).map { case NodePromisesPaths(id, base, news, backup) =>
                            val postfix = agentType.toRulesPath
                            NodePromisesPaths(id, base + postfix, news + postfix, backup + postfix)
                        }).toIO
                      }
          } yield {
            AgentNodeConfiguration(config, agentType, paths)
          }
        }
      }
    }.map( _.flatten )
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


  // just write an empty file for now
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
   * For agent needing it, copy licences to the correct path
   */
  private[this] def copyLicenses(agentNodeConfiguration: AgentNodeConfiguration, licenses: Map[NodeId, CfeEnterpriseLicense]): IOResult[AgentNodeConfiguration] = {

    val AgentNodeConfiguration(config, agentType, paths) = agentNodeConfiguration

    agentType match {
      case CfeEnterprise =>
        IOResult.effectM {
          PolicyLogger.trace("Writing licence for nodeConfiguration  " + config.nodeInfo.id);
          val sourceLicenceNodeId = if(config.nodeInfo.isPolicyServer) {
            config.nodeInfo.id
          } else {
            config.nodeInfo.policyServerId
          }

          licenses.get(sourceLicenceNodeId) match {
            case None =>
              // we are in the "free case", just log-debug it (as we already informed the user that there is no license)
              PolicyLogger.trace(s"Not copying missing license file into '${paths.newFolder}' for node '${config.nodeInfo.hostname}' (${config.nodeInfo.id.value}).")
              agentNodeConfiguration.succeed

            case Some(license) =>
              val licenseFile = new File(license.file)
              if (licenseFile.exists) {
                val destFile = FilenameUtils.normalize(paths.newFolder + "/license.dat")
                FileUtils.copyFile(licenseFile, new File(destFile) )
                agentNodeConfiguration.succeed
              } else {
                PolicyLogger.error(s"Could not find the license file ${licenseFile.getAbsolutePath} for server ${sourceLicenceNodeId.value}")
                throw new Exception("Could not find license file " +license.file)
              }
          }
        }
      case _ => agentNodeConfiguration.succeed
    }
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
        PolicyLogger.trace("Backuping old promises from %s to %s ".format(baseFolder, backupFolder))
        backupNodeFolder(baseFolder, backupFolder)
        try {
          newFolders += folder

          PolicyLogger.trace("Copying new promises into %s ".format(baseFolder))
          // move new promises
          moveNewNodeFolder(newFolder, baseFolder)

        } catch {
          case ex: Exception =>
            PolicyLogger.error("Could not write promises into %s, reason : ".format(baseFolder), ex)
            throw ex
        }
      }
      folders
    } catch {
      case ex: Exception =>

        for (folder <- newFolders) {
          PolicyLogger.info("Restoring old promises on folder %s".format(folder.baseFolder))
          try {
            restoreBackupNodeFolder(folder.baseFolder, folder.backupFolder);
          } catch {
            case ex: Exception =>
              PolicyLogger.error("could not restore old promises into %s ".format(folder.baseFolder))
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
    PolicyLogger.trace(s"Create promises file ${outPath} ${templateInfo.destination}")

    for {
      filled           <- fillTemplates.fill(templateInfo.destination, templateInfo.content, variableSet)
      // if the technique is multipolicy, replace rudderTag by reportId
      (replaced, dest) =  reportIdToReplace match {
                            case None => (filled, templateInfo.destination)
                            case Some(id) =>
                              val replace = (s: String) => s.replaceAll(Policy.TAG_OF_RUDDER_MULTI_POLICY, id.getRudderUniqueId)
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

    PolicyLogger.trace("Moving folders from %s to %s".format(src, destinationFolder))

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
      PolicyLogger.error("Could not find freshly created promises at %s".format(sourceFolder))
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
      PolicyLogger.error("Could not find freshly backup promises at %s".format(backupFolder))
      throw new IOException("Backup promises could not be found, and valid promises couldn't be restored !!!!")
    }
  }
}
