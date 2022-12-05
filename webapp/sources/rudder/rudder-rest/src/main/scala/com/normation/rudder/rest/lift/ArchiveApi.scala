/*
 *************************************************************************************
 * Copyright 2022 Normation SAS
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

package com.normation.rudder.rest.lift

import better.files.File
import cats.data.NonEmptyList
import com.normation.cfclerk.domain.Technique
import com.normation.cfclerk.domain.TechniqueCategoryName
import com.normation.cfclerk.domain.TechniqueId
import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.domain.TechniqueVersion
import com.normation.cfclerk.services.TechniqueReader
import com.normation.cfclerk.services.TechniqueRepository
import com.normation.cfclerk.services.TechniquesInfo
import com.normation.cfclerk.services.UpdateTechniqueLibrary
import com.normation.cfclerk.xmlparsers.TechniqueParser
import com.normation.errors._
import com.normation.eventlog.EventActor
import com.normation.eventlog.EventMetadata
import com.normation.rudder.api.ApiVersion
import com.normation.rudder.apidata.JsonResponseObjects.JRDirective
import com.normation.rudder.apidata.JsonResponseObjects.JRGroup
import com.normation.rudder.apidata.JsonResponseObjects.JRRule
import com.normation.rudder.apidata.implicits._
import com.normation.rudder.batch.AsyncDeploymentActor
import com.normation.rudder.batch.AutomaticStartDeployment
import com.normation.rudder.configuration.ConfigurationRepository
import com.normation.rudder.domain.appconfig.FeatureSwitch
import com.normation.rudder.domain.logger.ApplicationLogger
import com.normation.rudder.domain.logger.ApplicationLoggerPure
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.nodes.NodeGroupCategoryId
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.policies.Directive
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.Rule
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.RuleTarget
import com.normation.rudder.git.ZipUtils
import com.normation.rudder.git.ZipUtils.Zippable
import com.normation.rudder.ncf.ResourceFile
import com.normation.rudder.ncf.ResourceFileState
import com.normation.rudder.repository.RoDirectiveRepository
import com.normation.rudder.repository.RoNodeGroupRepository
import com.normation.rudder.repository.RoRuleRepository
import com.normation.rudder.repository.WoDirectiveRepository
import com.normation.rudder.repository.WoNodeGroupRepository
import com.normation.rudder.repository.WoRuleRepository
import com.normation.rudder.repository.xml.TechniqueArchiverImpl
import com.normation.rudder.repository.xml.TechniqueRevisionRepository
import com.normation.rudder.rest.{ArchiveApi => API}
import com.normation.rudder.rest.ApiPath
import com.normation.rudder.rest.AuthzToken
import com.normation.rudder.rest.RudderJsonResponse
import com.normation.rudder.rest.RudderJsonResponse.ResponseSchema
import com.normation.rudder.rest.implicits._
import com.normation.rudder.rest.lift.ImportAnswer._
import com.normation.rudder.services.queries.CmdbQueryParser
import com.normation.zio._
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.zip.ZipEntry
import net.liftweb.http.FileParamHolder
import net.liftweb.http.LiftResponse
import net.liftweb.http.OutputStreamResponse
import net.liftweb.http.Req
import scala.xml.XML
import zio._
import zio.json._
import zio.syntax._

/*
 * Machinery to enable/disable the API given the value of the feature switch in config service.
 * If disabled, always return an error with the info about how to enable it.
 */
final case class FeatureSwitch0[A <: LiftApiModule0](enable: A, disable: A)(featureSwitchState: IOResult[FeatureSwitch])
    extends LiftApiModule0 {
  override val schema = enable.schema
  override def process0(
      version:    ApiVersion,
      path:       ApiPath,
      req:        Req,
      params:     DefaultParams,
      authzToken: AuthzToken
  ): LiftResponse = {
    featureSwitchState.either.runNow match {
      case Left(err)                     =>
        ApplicationLogger.error(err.fullMsg)
        RudderJsonResponse.internalError(ResponseSchema.fromSchema(schema), err.fullMsg)(params.prettify).toResponse
      case Right(FeatureSwitch.Disabled) =>
        disable.process0(version, path, req, params, authzToken)
      case Right(FeatureSwitch.Enabled)  =>
        enable.process0(version, path, req, params, authzToken)
    }
  }
}

enum ArchiveScope(val value: String) {
  case AllDep     extends ArchiveScope( "all"        )
  case NoDep      extends ArchiveScope( "none"       )
  case Directives extends ArchiveScope( "directives" )
  case Techniques extends ArchiveScope( "techniques" )
  case Groups     extends ArchiveScope( "groups"     )
}
object ArchiveScope       {
  // using nodep/alldep to avoid confusion with "none" in scala code
  def parse(s: String): Either[String, ArchiveScope] = {
    values.find(_.value == s.toLowerCase.strip()) match {
      case None    =>
        Left(
          s"Error: can not parse '${s}' as a scope for dependency resolution in archive. Accepted values are: ${values.mkString(", ")}"
        )
      case Some(x) => Right(x)
    }
  }
}

class ArchiveApi(
    archiveBuilderService: ZipArchiveBuilderService,
    featureSwitchState:    IOResult[FeatureSwitch],
    getArchiveName:        IOResult[String],
    zipArchiveReader:      ZipArchiveReader,
    saveArchiveService:    SaveArchiveService,
    checkArchiveService:   CheckArchiveService
) extends LiftApiModuleProvider[API] {

  def schemas = API

  def getLiftEndpoints(): List[LiftApiModule] = {
    API.endpoints.map(e => {
      e match {
        case API.Import       => FeatureSwitch0(Import, ImportDisabled)(featureSwitchState)
        case API.ExportSimple => FeatureSwitch0(ExportSimple, ExportSimpleDisabled)(featureSwitchState)
      }
    })
  }

  /*
   * Default answer to use when the feature is disabled
   */
  trait ApiDisabled extends LiftApiModule0 {

    override def process0(
        version:    ApiVersion,
        path:       ApiPath,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      RudderJsonResponse
        .internalError(
          ResponseSchema.fromSchema(schema),
          """This API is disabled. It is in beta version and no compatibility is ensured. You can enable it with """ +
          """the setting `rudder_featureSwitch_archiveApi` in settings API set to `{"value":"enabled"}`"""
        )(params.prettify)
        .toResponse
    }
  }

  object ExportSimpleDisabled extends LiftApiModule0 with ApiDisabled { val schema = API.ExportSimple }

  /*
   * This API does not returns a standard JSON response, it returns a ZIP archive.
   */
  object ExportSimple extends LiftApiModule0 {
    val schema = API.ExportSimple
    /*
     * Request format:
     *   ../archives/export/rules=rule_ids&directives=dir_ids&techniques=tech_ids&groups=group_ids&include=scope
     * Where:
     * - rule_ids = xxxx-xxxx-xxx-xxx[,other ids]
     * - dir_ids = xxxx-xxxx-xxx-xxx[,other ids]
     * - group_ids = xxxx-xxxx-xxx-xxx[,other ids]
     * - tech_ids = techniqueName/1.0[,other tech ids]
     * - scope = all (default), none, directives, techniques (implies directive), groups
     */
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {

      // we use lots of comma separated arg, factor out the splitting logic
      def splitArg(req: Req, name: String): List[String]           =
        req.params.getOrElse(name, Nil).flatMap(seq => seq.split(',').toList.map(_.strip()))
      def parseRuleIds(req: Req):           IOResult[List[RuleId]] = {

        ZIO.foreach(splitArg(req, "rules"))(RuleId.parse(_).toIO)
      }
      def parseDirectiveIds(req: Req): IOResult[List[DirectiveId]]  = {
        ZIO.foreach(splitArg(req, "directives"))(DirectiveId.parse(_).toIO)
      }
      def parseTechniqueIds(req: Req): IOResult[List[TechniqueId]]  = {
        ZIO.foreach(splitArg(req, "techniques"))(TechniqueId.parse(_).toIO)
      }
      def parseGroupIds(req: Req):     IOResult[List[NodeGroupId]]  = {
        ZIO.foreach(splitArg(req, "groups"))(NodeGroupId.parse(_).toIO)
      }
      def parseScopes(req: Req):       IOResult[List[ArchiveScope]] = {
        splitArg(req, "include") match {
          case Nil => List(ArchiveScope.AllDep).succeed
          case seq => ZIO.foreach(seq)(ArchiveScope.parse(_).toIO)
        }
      }

      // lift is not well suited for ZIO...
      val rootDirName = getArchiveName.runNow

      // do zip
      val zippables = for {
        _             <- ApplicationLoggerPure.Archive.debug(s"Building archive '${rootDirName}'")
        ruleIds       <- parseRuleIds(req)
        groupIds      <- parseGroupIds(req)
        directiveIds  <- parseDirectiveIds(req)
        techniquesIds <- parseTechniqueIds(req)
        scopes        <- parseScopes(req)
        _             <- ApplicationLoggerPure.Archive.debug(
                           s"Archive requested for rules: [${ruleIds.map(_.serialize).mkString(", ")}], " +
                           s"directives: [${directiveIds.map(_.serialize).mkString(", ")}], " +
                           s"groups: [${groupIds.map(_.serialize).mkString(", ")}], " +
                           s"techniques: [${techniquesIds.map(_.serialize).mkString(", ")}], " +
                           s"scope: [${scopes.map(_.value).mkString(", ")}]"
                         )
        zippables     <- archiveBuilderService.buildArchive(rootDirName, techniquesIds, directiveIds, groupIds, ruleIds, scopes.toSet)
      } yield {
        zippables
      }

      val headers = List(
        ("Pragma", "public"),
        ("Expires", "0"),
        ("Cache-Control", "must-revalidate, post-check=0, pre-check=0"),
        ("Cache-Control", "public"),
        ("Content-Description", "File Transfer"),
        ("Content-type", "application/octet-stream"),
        ("Content-Disposition", s"""attachment; filename="${rootDirName}.zip""""),
        ("Content-Transfer-Encoding", "binary")
      )
      val send    = (os: OutputStream) => {
        zippables
          .flatMap(z => ZipUtils.zip(os, z))
          .catchAll(err => ApplicationLoggerPure.Archive.error(s"Error when building zip archive: ${err.fullMsg}"))
          .runNow
      }
      new OutputStreamResponse(send, -1, headers, Nil, 200)
    }
  }

  object ImportDisabled extends LiftApiModule0 with ApiDisabled { override val schema = API.Import }

  object Import extends LiftApiModule0 {
    val schema = API.Import
    // name of the form multipart that holds the archive binary content
    val FILE   = "archive"

    /*
     * We expect a binary file in multipart/form-data, not in application/x-www-form-urlencodedcontent
     * You can get that in curl with:
     * curl -k -X POST -H "X-API-TOKEN: ..." https://.../api/latest/archives/import --form "archive=@my-archive.zip"
     */
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {

      def parseArchive(archive: FileParamHolder): IOResult[PolicyArchive] = {
        val originalFilename = archive.fileName

        ZIO
          .acquireReleaseWith(IOResult.attempt(archive.fileStream))(is => effectUioUnit(is.close())) { is =>
            ZipUtils.getZipEntries(originalFilename, is)
          }
          .flatMap(entries => zipArchiveReader.readPolicyItems(originalFilename, entries))
      }

      val prog = (req.uploadedFiles.find(_.name == FILE) match {
        case None      =>
          Unexpected(s"Missing uploaded file with parameter name '${FILE}'").fail
        case Some(zip) =>
          for {
            _       <- ApplicationLoggerPure.Archive.info(s"Received a new policy archive '${zip.fileName}', processing")
            archive <- parseArchive(zip)
            _       <- checkArchiveService.check(archive)
            _       <- saveArchiveService.save(archive, authzToken.actor)
            _       <- ApplicationLoggerPure.Archive.info(s"Uploaded archive '${zip.fileName}' processed successfully")
          } yield JRArchiveImported(true)
      }).tapError(err => ApplicationLoggerPure.Archive.error(s"Error when processing uploaded archive: ${err.fullMsg}"))

      prog.toLiftResponseOne(params, schema, _ => None)
    }
  }
}

/*
 * A dummy object waiting for implementation for import
 */
object ImportAnswer {

  import zio.json._

  case class JRArchiveImported(success: Boolean)

  implicit lazy val encodeJRArchiveImported: JsonEncoder[JRArchiveImported] = DeriveJsonEncoder.gen

}

/**
 * A service that is able to build a human readable file name from a string
 */
class FileArchiveNameService(
    // zip has no max length, but winzip limit to 250 because of windows - https://kb.corel.com/en/125869
    // So 240 for extension etc
    maxSize: Int = 240
) {
  def toFileName(name: String): String = {
    Normalizer
      .normalize(name, Normalizer.Form.NFKD)
      .replaceAll("""[^\p{Alnum}-]""", "_")
      .take(maxSize)
  }

}

/**
 * That class is in charge of building a archive of a set of rudder objects.
 * It knows how to get objects from their ID, serialise them to the expected
 * string representation, and check that file name are not overriding each others,
 * but it does not know about how to get what objects need to be retrieved.
 *
 * For now, I don't see any way to not load rules/etc in memory (for ex for the case
 * where they would already be in json somewhere) since we need name.
 * That may be changed in the future, if config repo and archive converge toward
 * and unique file format and convention, but it's not for now.
 */
class ZipArchiveBuilderService(
    fileArchiveNameService: FileArchiveNameService,
    configRepo:             ConfigurationRepository,
    techniqueRevisionRepo:  TechniqueRevisionRepository
) {

  // names of directories under the root directory of the archive
  val RULES_DIR      = "rules"
  val GROUPS_DIR     = "groups"
  val DIRECTIVES_DIR = "directives"
  val TECHNIQUES_DIR = "techniques"

  /*
   * get the content of the JSON string in the format expected by Zippable
   */
  def getJsonZippableContent(json: String): (InputStream => IOResult[Any]) => IOResult[Any] = {
    (use: InputStream => IOResult[Any]) =>
      ZIO.acquireReleaseWith(IOResult.attempt(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))))(is =>
        effectUioUnit(is.close)
      )(is => use(is))
  }

  /*
   * function that will find the next available name from the given string,
   * looking in pool to check for availability (and updating said pool).
   * Extension is an extension that should not be normalized.
   */
  def findName(
      origName:  String,
      extension: String,
      usedNames: Ref[Map[String, Set[String]]],
      category:  String
  ): IOResult[String] = {
    def findRecName(s: Set[String], base: String, i: Int): String = {
      val n = base + "_" + i
      if (s.contains(n)) findRecName(s, base, i + 1) else n
    }
    val name = fileArchiveNameService.toFileName(origName) + extension

    // find a free name, avoiding overwriting a previous similar one
    usedNames.modify(m => {
      val realName = if (m(category).contains(name)) {
        findRecName(m(category), name, 1)
      } else name
      (realName, m + ((category, m(category) + realName)))
    })
  }

  /*
   * Retrieve the technique using first the cache, then the config service, and update the
   * cache accordingly
   */
  def getTechnique(
      techniqueId: TechniqueId,
      techniques:  Ref.Synchronized[Map[TechniqueId, (Chunk[TechniqueCategoryName], Technique)]]
  ): IOResult[(Chunk[TechniqueCategoryName], Technique)] = {
    techniques.modifyZIO(cache => {
      cache.get(techniqueId) match {
        case None    =>
          for {
            t <- configRepo
                   .getTechnique(techniqueId)
                   .notOptional(s"Technique with id ${techniqueId.serialize} was not found in Rudder")
            c  = cache + ((t._2.id, t))
          } yield (t, c)
        case Some(t) =>
          (t, cache).succeed
      }
    })
  }

  /*
   * Getting technique zippable is more complex than other items because we can have a lot of
   * files. The strategy used is to always copy ALL files for the given technique
   * TechniquesDir is the path where techniques are stored, ie for technique "user/1.0", we have:
   * techniquesDir / user/1.0/ other techniques file
   */
  def getTechniqueZippable(
      archiveName:   String,
      techniquesDir: String,
      cats:          Chunk[TechniqueCategoryName],
      techniqueId:   TechniqueId
  ): IOResult[Seq[Zippable]] = {
    for {
      contents <- techniqueRevisionRepo
                    .getTechniqueFileContents(techniqueId)
                    .notOptional(
                      s"Technique with ID '${techniqueId.serialize}' was not found in repository. Please check name and revision."
                    )
      // we need to change root of zippable, we want techniques/myTechnique/1.0/[HERE] and we need to filter out root category
      catDirs   = cats.collect { case TechniqueCategoryName(value) if value != "/" => value }
      basePath  = techniquesDir + "/" + catDirs.mkString("/") + "/" + techniqueId.withDefaultRev.serialize + "/"
      // start by adding directories toward technique
      zips      = catDirs
                    .foldLeft(List[Zippable]()) {
                      case (dirs, current) =>
                        // each time, head is the last parent, revert at the end
                        dirs.headOption match {
                          case None         => Zippable(techniquesDir + "/" + current, None) :: Nil
                          case Some(parent) => Zippable(parent.path + "/" + current, None) :: dirs
                        }
                    }
                    .reverse ++ contents.map { case (p, opt) => Zippable.make(basePath + p, opt) }
      _        <- ApplicationLoggerPure.Archive.debug(
                    s"Building archive '${archiveName}': adding technique zipables: ${zips.map(_.path).mkString(", ")}"
                  )
    } yield {
      zips
    }
  }

  /*
   * Prepare the archive.
   * `rootDirName` is supposed to be normalized, no change will be done with it.
   * Any missing object will lead to an error.
   * For each element, an human readable name derived from the object name is used when possible.
   *
   * System elements are not archived
   */
  def buildArchive(
      rootDirName:  String,
      techniqueIds: Seq[TechniqueId],
      directiveIds: Seq[DirectiveId],
      groupIds:     Seq[NodeGroupId],
      ruleIds:      Seq[RuleId],
      scopes:       Set[ArchiveScope]
  ): IOResult[Chunk[Zippable]] = {
    // normalize to no slash at end
    val root                 = rootDirName.strip().replaceAll("""/$""", "")
    import ArchiveScope._
    val includeDepTechniques = scopes.intersect(Set(AllDep, Techniques)).nonEmpty
    // scope is transitive, techniques implies directives
    val includeDepDirectives = includeDepTechniques || scopes.intersect(Set(AllDep, Directives)).nonEmpty
    val includeDepGroups     = scopes.intersect(Set(AllDep, Groups)).nonEmpty

    // rule is easy and independent from other

    for {
      // for each kind, we need to keep trace of existing names to avoid overwriting
      usedNames       <- Ref.make(Map.empty[String, Set[String]])
      // dependency id to add at lower level
      depDirectiveIds <- Ref.make(List.empty[DirectiveId])
      depGroupIds     <- Ref.make(List.empty[NodeGroupId])
      _               <- ApplicationLoggerPure.Archive.debug(s"Building archive for rules: ${ruleIds.map(_.serialize).mkString(", ")}")
      rootZip         <- Zippable(rootDirName, None).succeed
      rulesDir         = root + "/" + RULES_DIR
      _               <- usedNames.update(_ + ((RULES_DIR, Set.empty[String])))
      rulesDirZip      = Zippable(rulesDir, None)
      rulesZip        <- ZIO
                           .foreach(ruleIds) { ruleId =>
                             configRepo
                               .getRule(ruleId)
                               .notOptional(s"Rule with id ${ruleId.serialize} was not found in Rudder")
                               .flatMap(rule => {
                                 if (rule.isSystem) None.succeed
                                 else {
                                   for {
                                     _    <- depDirectiveIds.update(x => x ++ rule.directiveIds)
                                     _    <- depGroupIds.update(x => x ++ RuleTarget.getNodeGroupIds(rule.targets))
                                     json  = JRRule.fromRule(rule, None, None, None).toJsonPretty
                                     name <- findName(rule.name, ".json", usedNames, RULES_DIR)
                                     path  = rulesDir + "/" + name
                                     _    <- ApplicationLoggerPure.Archive
                                               .debug(s"Building archive '${rootDirName}': adding rule zipable: ${path}")
                                   } yield {
                                     Some(Zippable(path, Some(getJsonZippableContent(json))))
                                   }
                                 }
                               })
                           }
                           .map(_.flatten)
      groupsDir        = root + "/" + GROUPS_DIR
      _               <- usedNames.update(_ + ((GROUPS_DIR, Set.empty[String])))
      groupsDirZip     = Zippable(groupsDir, None)
      depGroups       <- if (includeDepGroups) depGroupIds.get else Nil.succeed
      groupsZip       <- ZIO
                           .foreach(groupIds ++ depGroups) { groupId =>
                             configRepo
                               .getGroup(groupId)
                               .notOptional(s"Group with id ${groupId.serialize} was not found in Rudder")
                               .flatMap(gc => {
                                 if (gc.group.isSystem) None.succeed
                                 else {
                                   val json = JRGroup.fromGroup(gc.group, gc.categoryId, None).toJsonPretty
                                   for {
                                     name <- findName(gc.group.name, ".json", usedNames, GROUPS_DIR)
                                     path  = groupsDir + "/" + name
                                     _    <- ApplicationLoggerPure.Archive
                                               .debug(s"Building archive '${rootDirName}': adding group zipable: ${path}")
                                   } yield Some(Zippable(path, Some(getJsonZippableContent(json))))
                                 }
                               })
                           }
                           .map(_.flatten)
      // directives need access to technique, but we don't want to look up several time the same one
      techniques      <- Ref.Synchronized.make(Map.empty[TechniqueId, (Chunk[TechniqueCategoryName], Technique)])

      directivesDir    = root + "/" + DIRECTIVES_DIR
      _               <- usedNames.update(_ + ((DIRECTIVES_DIR, Set.empty[String])))
      directivesDirZip = Zippable(directivesDir, None)
      depDirectives   <- if (includeDepDirectives) depDirectiveIds.get else Nil.succeed
      directivesZip   <- ZIO
                           .foreach(directiveIds ++ depDirectives) { directiveId =>
                             configRepo
                               .getDirective(directiveId)
                               .notOptional(s"Directive with id ${directiveId.serialize} was not found in Rudder")
                               .flatMap(ad => {
                                 if (ad.directive.isSystem) None.succeed
                                 else {
                                   for {
                                     tech <- getTechnique(
                                               TechniqueId(ad.activeTechnique.techniqueName, ad.directive.techniqueVersion),
                                               techniques
                                             )
                                     json  = JRDirective.fromDirective(tech._2, ad.directive, None).toJsonPretty
                                     name <- findName(ad.directive.name, ".json", usedNames, DIRECTIVES_DIR)
                                     path  = directivesDir + "/" + name
                                     _    <- ApplicationLoggerPure.Archive
                                               .debug(s"Building archive '${rootDirName}': adding directive zipable: ${path}")
                                   } yield Some(Zippable(path, Some(getJsonZippableContent(json))))
                                 }
                               })
                           }
                           .map(_.flatten)
      // Techniques don't need name normalization, their name is already normalized
      techniquesDir    = root + "/" + TECHNIQUES_DIR
      techniquesDirZip = Zippable(techniquesDir, None)
      depTechniques   <- if (includeDepTechniques) techniques.get.map(_.keys) else Nil.succeed
      allTech         <- ZIO.foreach(techniqueIds ++ depTechniques)(techniqueId => getTechnique(techniqueId, techniques))
      techniquesZip   <- ZIO.foreach(allTech.filter(_._2.isSystem == false)) {
                           case (cats, technique) =>
                             for {
                               techZips <- getTechniqueZippable(rootDirName, techniquesDir, cats, technique.id)
                             } yield {
                               // some directories may have been duplicated if several techniques are in the same category,
                               // and zip does not like that
                               techZips.distinctBy(_.path)
                             }
                         }
    } yield {
      Chunk(
        rootZip,
        rulesDirZip,
        groupsDirZip,
        directivesDirZip,
        techniquesDirZip
      ) ++ rulesZip ++ techniquesZip.flatten ++ directivesZip ++ groupsZip
    }
  }

}

// metadata about policy archive, at least original file name
final case class PolicyArchiveMetadata(
    filename: String
)

case object PolicyArchiveMetadata {
  def empty = PolicyArchiveMetadata("")
}

final case class TechniqueArchive(
    technique: Technique,
    category:  Chunk[String],
    files:     Chunk[(String, Array[Byte])]
)
final case class DirectiveArchive(
    technique: TechniqueName,
    directive: Directive
)
final case class GroupArchive(
    group:    NodeGroup,
    category: NodeGroupCategoryId
)

/**
 * An archive to be saved (~) atomically
 * For techniques, we only parse metadata.xml, and we keep files as is
 */
final case class PolicyArchive(
    metadata:   PolicyArchiveMetadata,
    techniques: Chunk[TechniqueArchive],
    directives: Chunk[DirectiveArchive],
    groups:     Chunk[GroupArchive],
    rules:      Chunk[Rule]
) {
  def debugString: String = {
    s"""Archive ${metadata.filename}:
       | - techniques: ${techniques.map(_.technique.id.serialize).sorted.mkString(", ")}
       | - directives: ${directives.map(d => s"'${d.directive.name}' [${d.directive.id.serialize}]").sorted.mkString(", ")}
       | - groups    : ${groups.map(d => s"'${d.group.name}' [${d.group.id.serialize}]").sorted.mkString(", ")}
       | - rules     : ${rules.map(d => s"'${d.name}' [${d.id.serialize}]").sorted.mkString(", ")}""".stripMargin
  }
}
object PolicyArchive {
  def empty = PolicyArchive(PolicyArchiveMetadata.empty, Chunk.empty, Chunk.empty, Chunk.empty, Chunk.empty)
}

final case class SortedEntries(
    techniques: Chunk[(String, Array[Byte])],
    directives: Chunk[(String, Array[Byte])],
    groups:     Chunk[(String, Array[Byte])],
    rules:      Chunk[(String, Array[Byte])]
)
object SortedEntries {
  def empty = SortedEntries(Chunk.empty, Chunk.empty, Chunk.empty, Chunk.empty)
}

final case class PolicyArchiveUnzip(
    policies: PolicyArchive,
    errors:   Chunk[RudderError]
)

object PolicyArchiveUnzip {
  def empty = PolicyArchiveUnzip(PolicyArchive.empty, Chunk.empty)
}

/**
 * That class is in charge of reading a zip archive (as a sequence of ZipEntry items) and
 * unflatten it into the corresponding Rudder policy items.
 * We want to provide maximum information in one go for the user, so that if an archive
 * can not be saved because some items override existing ones, then we want to list them
 * all (and not stop at the first, then let the user iterate and stop again for the next file).
 */
trait ZipArchiveReader {
  def readPolicyItems(archiveName: String, zipEntries: Seq[(ZipEntry, Option[Array[Byte]])]): IOResult[PolicyArchive]
}
class ZipArchiveReaderImpl(
    cmdbQueryParser: CmdbQueryParser,
    techniqueParser: TechniqueParser
) extends ZipArchiveReader {
  import com.softwaremill.quicklens._

  // we must avoid to eagerly match "ncf_techniques" as "techniques" but still accept when it starts by "techniques" without /
  val techniqueRegex = """(.*/|)techniques/(.+)""".r
  val metadataRegex  = """(.+)/metadata.xml""".r
  val directiveRegex = """(.*/|)directives/(.+.json)""".r
  val groupRegex     = """(.*/|)groups/(.+.json)""".r
  val ruleRegex      = """(.*/|)rules/(.+.json)""".r

  /*
   * For technique, we are parsing metadata.xml.
   * We also find technique name, version, and categories from base path.
   * For file: we keep all, but we make their path relative to technique (ie we remove base path)
   */
  def parseTechnique(archiveName: String, basepath: String, files: Chunk[(String, Array[Byte])]): IOResult[TechniqueArchive] = {
    // base path should look like "some/list/of/cats/techniqueName/techniqueVersion
    def parseBasePath(p: String): IOResult[(TechniqueId, Chunk[String])] = {
      p.split('/').toList.reverse match {
        case version :: name :: cats =>
          TechniqueVersion
            .parse(version)
            .toIO
            .chainError(s"Error when extracting archive for technique at path ${basepath}") map (v =>
            (TechniqueId(TechniqueName(name), v), Chunk.fromIterable(cats.reverse))
          )
        case _                       =>
          Unexpected(
            s"Error when extracting archive '${archiveName}': the path '${basepath}' contains a metadata.xml file but is not a valid " +
            s"technique path. Expected structure is categories/techniqueName/techniqueVersion/metadata.xml"
          ).fail
      }
    }

    for {
      path    <- parseBasePath(basepath)
      (id, c)  = path
      optTech <- Ref.Synchronized.make(Option.empty[Technique])
      // update path in files, and parse metadata when found
      updated <- ZIO.foreach(files) {
                   case (f, content) =>
                     val name = f.replaceFirst(basepath + "/", "")
                     ZIO.when(name.equalsIgnoreCase("metadata.xml")) {
                       for {
                         xml  <- IOResult.attempt(XML.load(new ByteArrayInputStream(content)))
                         tech <- techniqueParser.parseXml(xml, id).toIO
                         _    <- optTech.updateZIO {
                                   case None    => Some(tech).succeed
                                   case Some(_) =>
                                     Unexpected(
                                       s"Error: archive contains several metadata.xml files for " +
                                       s"techniques '${basepath}', exactly one expected"
                                     ).fail
                                 }
                       } yield ()
                     } *>
                     (name, content).succeed
                 }
      tech    <- optTech.get.notOptional(
                   s"Error: archive does not contains a metadata.xml file for technique with id " +
                   s"'${basepath}', exactly one was expected"
                 )
    } yield TechniqueArchive(tech, c, updated)
  }
  def parseDirective(name: String, content: Array[Byte])(implicit dec: JsonDecoder[JRDirective]): IOResult[DirectiveArchive] = {
    (new String(content, StandardCharsets.UTF_8))
      .fromJson[JRDirective]
      .toIO
      .flatMap(_.toDirective().map(t => DirectiveArchive(t._1, t._2)))
  }
  def parseGroup(name: String, content: Array[Byte])(implicit dec: JsonDecoder[JRGroup]):         IOResult[GroupArchive]     = {
    (new String(content, StandardCharsets.UTF_8))
      .fromJson[JRGroup]
      .toIO
      .flatMap(_.toGroup(cmdbQueryParser).map(d => GroupArchive(d._2, d._1)))
  }
  def parseRule(name: String, content: Array[Byte])(implicit dec: JsonDecoder[JRRule]):           IOResult[Rule]             = {
    (new String(content, StandardCharsets.UTF_8)).fromJson[JRRule].toIO.flatMap(_.toRule())
  }

  /*
   * Parse techniques.
   * The map is [techniqueBasePath -> (metadata contant, list of all technique files, including metadata.xlm: (filename (including base path), content))
   */
  def parseTechniques(
      archiveName: String,
      arch:        PolicyArchiveUnzip,
      techniques:  Map[String, Chunk[(String, Array[Byte])]]
  ): IOResult[PolicyArchiveUnzip] = {
    ZIO.foldLeft(techniques)(arch) {
      case (a, (basepath, files)) =>
        parseTechnique(archiveName, basepath, files).either.map {
          case Right(res) => a.modify(_.policies.techniques).using(_ :+ res)
          case Left(err)  => a.modify(_.errors).using(_ :+ err)
        }
    }
  }
  def parseSimpleFile[A](
      arch:     PolicyArchiveUnzip,
      elements: Chunk[(String, Array[Byte])],
      accessor: PathLazyModify[PolicyArchiveUnzip, Chunk[A]],
      parser:   (String, Array[Byte]) => IOResult[A]
  ): IOResult[PolicyArchiveUnzip] = {
    ZIO.foldLeft(elements)(arch) {
      case (a, t) =>
        parser(t._1, t._2).either.map {
          case Right(res) => accessor.using(_ :+ res)(a)
          case Left(err)  => a.modify(_.errors).using(_ :+ err)
        }
    }
  }
  def parseDirectives(arch: PolicyArchiveUnzip, directives: Chunk[(String, Array[Byte])])(implicit
      dec:                  JsonDecoder[JRDirective]
  ): IOResult[PolicyArchiveUnzip] = {
    parseSimpleFile(arch, directives, modifyLens[PolicyArchiveUnzip](_.policies.directives), parseDirective)
  }
  def parseGroups(arch: PolicyArchiveUnzip, groups: Chunk[(String, Array[Byte])])(implicit
      dec:              JsonDecoder[JRGroup]
  ): IOResult[PolicyArchiveUnzip] = {
    parseSimpleFile(arch, groups, modifyLens[PolicyArchiveUnzip](_.policies.groups), parseGroup)
  }
  def parseRules(arch: PolicyArchiveUnzip, rules: Chunk[(String, Array[Byte])])(implicit
      dec:             JsonDecoder[JRRule]
  ): IOResult[PolicyArchiveUnzip] = {
    parseSimpleFile(arch, rules, modifyLens[PolicyArchiveUnzip](_.policies.rules), parseRule)
  }

  def readPolicyItems(archiveName: String, zipEntries: Seq[(ZipEntry, Option[Array[Byte]])]): IOResult[PolicyArchive] = {

    // sort files in rules, directives, groups, techniques
    val sortedEntries = zipEntries.foldLeft(SortedEntries.empty) {
      case (arch, (e, optContent)) =>
        (e.getName, optContent) match {
          case (techniqueRegex(_, x), Some(content)) =>
            ApplicationLoggerPure.Archive.logEffect.trace(s"Archive '${archiveName}': found technique file ${x}")
            arch.modify(_.techniques).using(_ :+ (x, content))
          case (directiveRegex(_, x), Some(content)) =>
            ApplicationLoggerPure.Archive.logEffect.trace(s"Archive '${archiveName}': found directive file ${x}")
            arch.modify(_.directives).using(_ :+ (x, content))
          case (groupRegex(_, x), Some(content))     =>
            ApplicationLoggerPure.Archive.logEffect.trace(s"Archive '${archiveName}': found group file ${x}")
            arch.modify(_.groups).using(_ :+ (x, content))
          case (ruleRegex(_, x), Some(content))      =>
            ApplicationLoggerPure.Archive.logEffect.trace(s"Archive '${archiveName}': found rule file ${x}")
            arch.modify(_.rules).using(_ :+ (x, content))
          case (name, Some(_))                       =>
            ApplicationLoggerPure.Archive.logEffect.debug(
              s"Archive '${archiveName}': file does not matches a known category: ${name}"
            )
            arch
          case (name, None)                          =>
            ApplicationLoggerPure.Archive.logEffect.trace(s"Directory '${name}' in archive '${archiveName}': looking for entries")
            arch
        }
    }

    // techniques are more complicated: they have several files and categories so we don't know where they are at first.
    // We look for metadata.xml files, and from that we deduce a technique base path
    // create a map of technique names -> list of (filename, content)
    val metadatas = sortedEntries.techniques.collect { case (metadataRegex(basePath), c) => basePath }

    // then, group by base path (cats/id/version) for technique ; then for each group, find from base path category
    val techniqueUnzips = sortedEntries.techniques.groupBy {
      case (filename, _) => metadatas.find(base => filename.startsWith(base))
    }.flatMap {
      case (None, files)       => // files that are not related to a metadata file: ignore (but log)
        ApplicationLoggerPure.Archive.debug(
          s"Archive ${archiveName}: these were under 'techniques' directory but are not " +
          s"linked to a metadata.xml file: ${files.map(_._1).mkString(" , ")}"
        )
        None
      case (Some(base), files) =>
        Some((base, files))
    }

    // now, parse everything and collect errors
    import com.normation.rudder.apidata.JsonResponseObjectDecodes._
    for {
      _              <- ApplicationLoggerPure.Archive.debug(
                          s"Processing archive '${archiveName}': techniques: '${techniqueUnzips.keys.mkString("', '")}'"
                        )
      withTechniques <- parseTechniques(archiveName, PolicyArchiveUnzip.empty, techniqueUnzips)
      _              <- ApplicationLoggerPure.Archive.debug(
                          s"Processing archive '${archiveName}': directives: '${sortedEntries.directives.map(_._1).mkString("', '")}'"
                        )
      withDirectives <- parseDirectives(withTechniques, sortedEntries.directives)
      _              <- ApplicationLoggerPure.Archive.debug(
                          s"Processing archive '${archiveName}': groups: '${sortedEntries.groups.map(_._1).mkString("', '")}'"
                        )
      withGroups     <- parseGroups(withDirectives, sortedEntries.groups)
      _              <- ApplicationLoggerPure.Archive.debug(
                          s"Processing archive '${archiveName}': rules: '${sortedEntries.rules.map(_._1).mkString("', '")}'"
                        )
      withRules      <- parseRules(withGroups, sortedEntries.rules)
      // aggregate errors
      policies       <- withRules.errors.toList match {
                          case Nil       => withRules.policies.succeed
                          case h :: tail => Accumulated(NonEmptyList.of(h, tail: _*)).fail
                        }
    } yield policies
  }
}

/*
 * A service in charge of assessing the consistency of an archive before saving it, and trying
 * to pack problems so that an user has as much information as he can in one round.
 */
trait CheckArchiveService {
  /*
   * Check if archive might be saved. The goal here is to provide as many insights as possible to
   * user, and not only just the first one.
   * Individual checks can be redo during the actual save, and other errors can happen at that moment.
   * Here, it's for UX.
   */
  def check(archive: PolicyArchive): IOResult[Unit]

}

/*
 * Default implementation:
 * - check that a technique is in the same category as the existing one
 */
class CheckArchiveServiceImpl(
    techniqueRepos: TechniqueRepository
) extends CheckArchiveService {

  def checkTechnique(techInfo: TechniquesInfo, techArchive: TechniqueArchive): IOResult[Unit] = {

    // check that the imported technique category is the same than the one already existing, if any
    techInfo.techniquesCategory.collectFirst {
      case (TechniqueId(name, _), catId) if (name.value == techArchive.technique.id.name.value) => catId
    } match {
      case None        => // technique not present, ok
        ZIO.unit
      case Some(catId) =>
        val existing = catId.getPathFromRoot.map(_.value).tail.mkString("/")
        val archive  = techArchive.category.mkString("/")
        if (existing == archive) {
          ZIO.unit
        } else {
          Inconsistency(
            s"Technique '${techArchive.technique.id.serialize}' from archive has category '${archive}' but a " +
            s"technique with that name already exists in category '${existing}': it must be imported in the " +
            s"same category, please update your archive."
          ).fail
        }
    }
  }
  def checkItem[A](a: A): IOResult[Unit] = { // for now, nothing to check for directive, rule, group
    ZIO.unit
  }

  // check if we can import archive.
  def check(archive: PolicyArchive): IOResult[Unit] = {
    val techInfo = techniqueRepos.getTechniquesInfo()
    for {
      tRes <- ZIO.foreach(archive.techniques)(t => checkTechnique(techInfo, t).either)
      // TODO: check that all techniques (name and version) used by directive are known of rudder
      dRes <- ZIO.foreach(archive.directives)(checkItem(_).either)
      gRes <- ZIO.foreach(archive.groups)(checkItem(_).either)
      rRes <- ZIO.foreach(archive.rules)(checkItem(_).either)
      // collect all errors
      _    <- (tRes ++ dRes ++ gRes ++ rRes).accumulateEitherDiscard.toIO
    } yield ()
  }
}

trait SaveArchiveService {

  /*
   * Save archive, as atomically as possible. We don't do revert on error, because import is not
   * a system property, and we may have no human overlooking that no other changes are reverted.
   * In case of error, provides as many insight as possible to user:
   * - what was committed,
   * - what was not
   * - what may be partially
   */
  def save(archive: PolicyArchive, actor: EventActor): IOResult[Unit]
}

/*
 * This implementation does not check for possible conflicts and just
 * overwrite existing policies if updates are available in the archive.
 */
class SaveArchiveServicebyRepo(
    techniqueArchiver: TechniqueArchiverImpl,
    techniqueReader:   TechniqueReader,
    techniqueRepos:    TechniqueRepository,
    roDirectiveRepos:  RoDirectiveRepository,
    woDirectiveRepos:  WoDirectiveRepository,
    roGroupRepos:      RoNodeGroupRepository,
    woGroupRepos:      WoNodeGroupRepository,
    roRuleRepos:       RoRuleRepository,
    woRuleRepos:       WoRuleRepository,
    techLibUpdate:     UpdateTechniqueLibrary,
    asyncDeploy:       AsyncDeploymentActor
) extends SaveArchiveService {

  /*
   * Saving a techniques:
   * - override all files that are coming from archive
   * - see if there's other file present - delete them
   * - commit
   */
  def saveTechnique(eventMetadata: EventMetadata, t: TechniqueArchive): IOResult[Unit] = {
    val techniqueDir = File(
      techniqueArchiver.gitRepo.rootDirectory.pathAsString + "/" + techniqueArchiver.relativePath + "/" + t.category.mkString(
        "/"
      ) + "/" + t.technique.id.serialize
    )

    for {
      _        <- ApplicationLoggerPure.Archive.debug(
                    s"Adding technique from archive: '${t.technique.name}' (${techniqueDir.pathAsString})"
                  )
      addedRef <-
        Ref.make(Chunk.fromIterable(t.files.map(_._1))) // path are relative to technique. Files we need to add as new in the end
      diffRef  <- Ref.make(Chunk[ResourceFile]())
      // get all file path (relative to technique dir)
      existing <-
        IOResult.attempt(if (techniqueDir.exists) {
          techniqueDir.collectChildren(_ => true).toList.map(_.pathAsString.replaceFirst(techniqueDir.pathAsString + "/", ""))
        } else { // technique or technique version does not exists
          Chunk.empty
        })
      _        <- ZIO.foreachDiscard(existing) { e =>
                    for {
                      keep <- addedRef.modify(a => {
                                if (a.contains(e)) { (true, a.filterNot(_ == e)) }
                                else { (false, a) }
                              })
                      _    <- diffRef.update(_ :+ ResourceFile(e, if (keep) ResourceFileState.Modified else ResourceFileState.Deleted))
                    } yield ()
                  }
      added    <- addedRef.get.map(_.map(ResourceFile(_, ResourceFileState.New)))
      updated  <- diffRef.get

      // now, actually delete files marked so
      _ <- ApplicationLoggerPure.Archive.trace(
             s"Deleting technique files for technique '${t.technique.id.serialize}': ${updated.collect {
                 case f if (f.state == ResourceFileState.Deleted) => f.path
               }.mkString(", ")}"
           )
      _ <- ZIO.foreachDiscard(updated) { u =>
             if (u.state == ResourceFileState.Deleted) {
               IOResult.attempt(File(techniqueDir.pathAsString).delete())
             } else ZIO.unit
           }
      // now, write new/updated files
      _ <- ApplicationLoggerPure.Archive.trace(
             s"Writing for commit files for technique '${t.technique.id.serialize}': ${t.files.map(_._1).mkString(", ")} "
           )
      _ <- ZIO.foreachDiscard(t.files) {
             case (p, bytes) =>
               val f = File(techniqueDir.pathAsString + "/" + p)
               IOResult.attempt {
                 f.parent.createDirectoryIfNotExists(createParents =
                   true
                 ) // for when the technique, or subdirectories for resources are not existing yet
                 f.writeBytes(bytes.iterator)
               }
           }
      // finally commit
      _ <- techniqueArchiver.saveTechnique(
             t.technique.id,
             t.category,
             added ++ updated,
             eventMetadata.modId,
             eventMetadata.actor,
             eventMetadata.msg.getOrElse(s"Committing technique '${t.technique.id.serialize}' from archive")
           )

    } yield ()
  }

  def saveDirective(eventMetadata: EventMetadata, d: DirectiveArchive): IOResult[Unit] = {
    for {
      at <-
        roDirectiveRepos
          .getActiveTechnique(d.technique)
          .notOptional(s"Technique '${d.technique.value}' is used in imported directive ${d.directive.name} but is not in Rudder")
      _  <-
        ApplicationLoggerPure.Archive.debug(s"Adding directive from archive: '${d.directive.name}' (${d.directive.id.serialize})")
      _  <- woDirectiveRepos.saveDirective(at.id, d.directive, eventMetadata.modId, eventMetadata.actor, eventMetadata.msg)
    } yield ()
  }
  def saveGroup(eventMetadata: EventMetadata, g: GroupArchive):         IOResult[Unit] = {
    for {
      _ <- ApplicationLoggerPure.Archive.debug(s"Adding group from archive: '${g.group.name}' (${g.group.id.serialize})")
      x <- roGroupRepos.getNodeGroupOpt(g.group.id)
      _ <- x match {
             case Some(value) => woGroupRepos.update(g.group, eventMetadata.modId, eventMetadata.actor, eventMetadata.msg)
             case None        => woGroupRepos.create(g.group, g.category, eventMetadata.modId, eventMetadata.actor, eventMetadata.msg)
           }
    } yield ()
  }
  def saveRule(eventMetadata: EventMetadata, r: Rule):                  IOResult[Unit] = {
    for {
      _ <- ApplicationLoggerPure.Archive.debug(s"Adding rule from archive: '${r.name}' (${r.id.serialize})")
      x <- roRuleRepos.getOpt(r.id)
      _ <- x match {
             case Some(value) => woRuleRepos.update(r, eventMetadata.modId, eventMetadata.actor, eventMetadata.msg)
             case None        => woRuleRepos.create(r, eventMetadata.modId, eventMetadata.actor, eventMetadata.msg)
           }
    } yield ()
  }

  /*
   * For rules/directives/groups, we build a change request & commit it.
   * For techniques, we just write them in fs, commit, reload tech lib.
   * Starts with techniques then other things.
   */
  override def save(archive: PolicyArchive, actor: EventActor): IOResult[Unit] = {
    val eventMetadata = EventMetadata.withNewId(actor, Some(s"Importing archive '${archive.metadata.filename}'"))
    for {
      _ <- ZIO.foreach(archive.techniques)(saveTechnique(eventMetadata, _))
      _ <- IOResult.attempt(techniqueReader.readTechniques)
      _ <- ZIO.foreach(archive.directives)(saveDirective(eventMetadata, _))
      _ <- ZIO.foreach(archive.groups)(saveGroup(eventMetadata, _))
      _ <- ZIO.foreach(archive.rules)(saveRule(eventMetadata, _))
      // update technique lib, regenerate policies
      _ <- ZIO.when(archive.techniques.nonEmpty) {
             techLibUpdate
               .update(eventMetadata.modId, eventMetadata.actor, Some(s"Update Technique library after import of and archive"))
               .toIO
               .chainError(
                 s"An error occurred during technique update after import of archive"
               )
           }
      _ <- IOResult.attempt(asyncDeploy ! AutomaticStartDeployment(eventMetadata.modId, eventMetadata.actor))
    } yield ()
  }
}
