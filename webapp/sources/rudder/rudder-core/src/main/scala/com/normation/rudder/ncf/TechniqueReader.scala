package com.normation.rudder.ncf

import better.files._
import com.normation.errors._
import com.normation.errors.Inconsistency
import com.normation.errors.IOResult
import com.normation.errors.PureChainError
import com.normation.eventlog.ModificationId
import com.normation.inventory.domain.AgentType
import com.normation.rudder.domain.eventlog.RudderEventActor
import com.normation.rudder.git.GitConfigItemRepository
import com.normation.rudder.git.GitRepositoryProvider
import com.normation.rudder.hooks.Cmd
import com.normation.rudder.hooks.CmdResult
import com.normation.rudder.hooks.RunNuCommand
import com.normation.rudder.ncf.Constraint.Constraint
import com.normation.rudder.ncf.ParameterType.ParameterTypeService
import com.normation.rudder.ncf.yaml.YamlTechniqueSerializer
import com.normation.rudder.repository.GitModificationRepository
import com.normation.rudder.repository.xml.RudderPrettyPrinter
import com.normation.rudder.repository.xml.XmlArchiverUtils
import com.normation.rudder.services.user.PersonIdentService
import com.normation.utils.StringUuidGenerator
import com.normation.zio._
import java.time.Instant
import zio.Ref
import zio.ZIO
import zio.ZIO._
import zio.json._
import zio.syntax._

class TechniqueReader(
    uuidGen:                                StringUuidGenerator,
    personIdentService:                     PersonIdentService,
    override val gitRepo:                   GitRepositoryProvider,
    override val xmlPrettyPrinter:          RudderPrettyPrinter,
    override val gitModificationRepository: GitModificationRepository,
    override val encoding:                  String,
    override val groupOwner:                String,
    yamlTechniqueSerializer:                YamlTechniqueSerializer,
    parameterTypeService:                   ParameterTypeService,
    ruddercCmd:                             String,
    methodsSystemLib:                       String,
    methodsLocalLib:                        String
) extends GitConfigItemRepository with XmlArchiverUtils {
  override val relativePath: String = "ncf"
  val configuration_repository = gitRepo.rootDirectory
  val ncfRootDir               = configuration_repository / relativePath
  val methodsFile              = ncfRootDir / "generic_methods.json"

  def getAllTechniqueFiles(currentPath: File): IOResult[List[File]]                                                                 = {
    import com.normation.errors._
    for {
      subdirs      <- IOResult.attempt(s"error when getting subdirectories of ${currentPath.pathAsString}")(
                        currentPath.children.partition(_.isDirectory)._1.toList
                      )
      checkSubdirs <- foreach(subdirs)(getAllTechniqueFiles).map(_.flatten)
      techniqueFilePath: File = currentPath / "technique.yml"
      result <- IOResult.attempt(
                  if (techniqueFilePath.exists) {
                    techniqueFilePath :: checkSubdirs
                  } else {
                    checkSubdirs
                  }
                )
    } yield {
      result
    }
  }
  def readTechniquesMetadataFile:              IOResult[(List[EditorTechnique], Map[BundleName, GenericMethod], List[RudderError])] = {
    import yamlTechniqueSerializer._

    import zio.json.yaml._
    for {
      methods        <- getMethodsMetadata
      techniqueFiles <- getAllTechniqueFiles(configuration_repository / "techniques")
      techniqueRes    = techniqueFiles.map(file => {

                          file.contentAsString
                            .fromYaml[EditorTechnique]
                            .left
                            .map(Inconsistency(_))
                            .chainError(s"An Error occurred while extracting data from technique ${file.pathAsString}")

                        })
      (techniques, errors) = techniqueRes.foldRight((List.empty[EditorTechnique], List.empty[RudderError])) {
                               case (Right(t), (accT, accE)) => (t :: accT, accE)
                               case (Left(e), (accT, accE))  => (accT, e :: accE)
                             }
    } yield {

      (techniques, methods, errors)
    }
  }

  private[this] val methodsCache = Ref.Synchronized.make((Instant.EPOCH, Map[BundleName, GenericMethod]())).runNow

  def getMethodsMetadata: IOResult[Map[BundleName, GenericMethod]] = {
    for {
      cache <- methodsCache.updateAndGetZIO(readMethodsMetadataFile)
    } yield {
      cache._2
    }
  }

  private[this] def readMethodsMetadataFile(
      cache: (Instant, Map[BundleName, GenericMethod])
  ): IOResult[(Instant, Map[BundleName, GenericMethod])] = {
    if (methodsFile.exists()) {
      for {
        methodsFileModifiedTime <- IOResult.attempt(methodsFile.lastModifiedTime())
        methods                 <-
          if (methodsFileModifiedTime.isAfter(cache._1)) {
            for {
              jsonLib       <- IOResult.attempt(s"error while reading ${methodsFile.pathAsString}")(methodsFile.contentAsString)
              parsedMethods <- GenericMethodSerialization.decodeGenericMethodLib(parameterTypeService, jsonLib)
              now           <- currentTimeMillis
            } yield {
              (Instant.ofEpochMilli(now), parsedMethods)
            }
          } else {
            cache.succeed
          }
      } yield {
        methods
      }
    } else {
      updateMethodsMetadataFile *> readMethodsMetadataFile(cache)
    }
  }

  def updateMethodsMetadataFile: IOResult[CmdResult] = {
    val methodLibs    = if (File(methodsLocalLib).exists) {
      methodsSystemLib :: methodsLocalLib :: Nil
    } else {
      methodsSystemLib :: Nil
    }
    val ruddercParams = "lib" :: "--format" :: "json" :: "--stdout" :: Nil
    val ruddercLibs   = methodLibs.flatMap(l => "--library" :: l :: Nil)
    // We want everything in configuration repository to belong to the "rudder" group
    val groupOwner    = "rudder"

    val cmd = Cmd(ruddercCmd, ruddercParams ::: ruddercLibs, Map.empty)
    for {
      updateCmd <- RunNuCommand.run(cmd)
      res       <- updateCmd.await
      _         <-
        ZIO.when(res.code != 0)(
          Inconsistency(
            s"An error occurred while updating generic methods library with command '${cmd.display}':\n code: ${res.code}\n stderr: ${res.stderr}\n stdout: ${res.stdout}"
          ).fail
        )
      // write file
      _         <- IOResult.attempt(methodsFile.parent.createDirectories())
      _         <- IOResult.attempt(methodsFile.parent.setGroup(groupOwner))
      _         <- IOResult.attempt(methodsFile.writeText(res.stdout))
      _         <- IOResult.attempt(methodsFile.setGroup(groupOwner))
      // commit file
      modId      = ModificationId(uuidGen.newUuid)
      ident     <- personIdentService.getPersonIdentOrDefault(RudderEventActor.name)
      _         <- commitAddFileWithModId(modId, ident, toGitPath(methodsFile.toJava), "Saving updated generic methods definition")
    } yield {
      res
    }
  }
}

// a data class for JSON mapping for structure in /var/rudder/configuration-repository/ncf/generic_methods.json
final case class JsonGenericMethod(
    name:               String,
    description:        String,
    documentation:      Option[String],
    parameter:          List[JsonGenericMethodParameter],
    class_prefix:       String,
    class_parameter:    String,
    class_parameter_id: Int,
    bundle_name:        String,
    bundle_args:        List[String],
    agent_version:      Option[String],
    agent_support:      List[List[AgentType]],
    deprecated:         Option[String],
    rename:             Option[String],
    parameter_rename:   Option[List[JsonGenericMethodParameterRename]]
) {
  def toGenericMethod(id: BundleName, parameterTypeService: ParameterTypeService): PureResult[GenericMethod] = {
    import cats.implicits._

    parameter
      .traverse(_.toMethodParameter(parameterTypeService))
      .map { params =>
        GenericMethod(
          id,
          name,
          parameters = params,
          classParameter = ParameterId(class_parameter),
          classPrefix = class_prefix,
          agentSupport = agent_support.flatten,
          // agent_version is not mapped ?
          description = description,
          documentation = documentation,
          deprecated = deprecated,
          renameTo = rename,
          renameParam =
            parameter_rename.fold[Seq[(String, String)]](Nil)(_.map { case JsonGenericMethodParameterRename(o, n) => (o, n) })
        )
      }
  }
}

final case class JsonGenericMethodParameterRename(old: String, `new`: String)

final case class JsonGenericMethodParameter(
    name:        String,
    description: String,
    constraint:  Option[JsonParameterConstraint],
    `type`:      String
) {
  def toMethodParameter(parameterTypeService: ParameterTypeService): PureResult[MethodParameter] = {
    parameterTypeService.create(`type`).map { t =>
      val c = constraint match {
        case None    => Nil
        case Some(x) => x.toConstraint
      }
      MethodParameter(ParameterId(name), description, c, t)
    }
  }
}

final case class JsonParameterConstraint(
    allow_empty_string:      Boolean,
    allow_whitespace_string: Boolean,
    max_length:              Int,
    min_length:              Option[Int],
    regex:                   Option[String],
    not_regex:               Option[String],
    select:                  Option[List[String]]
) {
  def toConstraint: List[Constraint] = {
    import Constraint._

    AllowEmpty(allow_empty_string) ::
    AllowWhiteSpace(allow_whitespace_string) ::
    MaxLength(max_length) ::
    min_length.map(MinLength.apply).toList :::
    regex.map(MatchRegex.apply).toList :::
    not_regex.map(NotMatchRegex.apply).toList :::
    select.map(FromList.apply).toList
  }
}

object GenericMethodSerialization {

  implicit val decodeJsonParameterConstraint:          JsonDecoder[JsonParameterConstraint]          = DeriveJsonDecoder.gen
  implicit val decodeJsonGenericMethodParameterRename: JsonDecoder[JsonGenericMethodParameterRename] = DeriveJsonDecoder.gen
  implicit val decodeJsonGenericMethodParameter:       JsonDecoder[JsonGenericMethodParameter]       = DeriveJsonDecoder.gen
  implicit val decodeAgentType:                        JsonDecoder[List[AgentType]]                  = JsonDecoder.string.mapOrFail(id => {
    id match {
      case "dsc"                => Right(AgentType.Dsc :: Nil)
      case "cfengine-community" => Right(AgentType.CfeCommunity :: AgentType.CfeEnterprise :: Nil)
      case x                    => Left(s"Error: '${x}' is not recognized as an agent type")
    }
  })
  implicit val decodeJsonGenericMethod:                JsonDecoder[JsonGenericMethod]                = DeriveJsonDecoder.gen

  /*
   * The expected file format is:
   * {
   *   "methodId1": { methode definition },
   *   "methodId2": { methode definition },
   *   etc
   * }
   */
  def decodeGenericMethodLib(
      parameterTypeService: ParameterTypeService,
      json:                 String
  ): IOResult[Map[BundleName, GenericMethod]] = {
    json
      .fromJson[Map[String, JsonGenericMethod]]
      .toIO
      .chainError(s"An Error occurred while extracting data from generic methods ncf API")
      .flatMap(parsedJsonMethods => {
        ZIO.foreach(parsedJsonMethods) {
          case (id, m) =>
            val bn = BundleName(id)
            m.toGenericMethod(bn, parameterTypeService).toIO.map((bn, _))
        }
      })
  }

}
