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
import com.normation.cfclerk.domain.TechniqueCategoryMetadata
import com.normation.cfclerk.domain.TechniqueCategoryName
import com.normation.cfclerk.domain.TechniqueId
import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.domain.TechniqueVersion
import com.normation.cfclerk.services.TechniqueReader
import com.normation.cfclerk.services.TechniqueRepository
import com.normation.cfclerk.services.TechniquesInfo
import com.normation.cfclerk.services.UpdateTechniqueLibrary
import com.normation.cfclerk.xmlparsers.TechniqueParser
import com.normation.errors.*
import com.normation.eventlog.EventMetadata
import com.normation.eventlog.ModificationId
import com.normation.rudder.api.ApiVersion
import com.normation.rudder.apidata.JsonResponseObjects.JRDirective
import com.normation.rudder.apidata.JsonResponseObjects.JRGroup
import com.normation.rudder.apidata.JsonResponseObjects.JRRule
import com.normation.rudder.apidata.RudderJsonDecoders
import com.normation.rudder.apidata.implicits.*
import com.normation.rudder.batch.AutomaticStartDeployment
import com.normation.rudder.configuration.ActiveDirective
import com.normation.rudder.configuration.ConfigurationRepository
import com.normation.rudder.domain.logger.ApplicationLoggerPure
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.nodes.NodeGroupCategory
import com.normation.rudder.domain.nodes.NodeGroupCategoryId
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.nodes.NodeGroupUid
import com.normation.rudder.domain.policies.Directive
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.DirectiveUid
import com.normation.rudder.domain.policies.FullGroupTarget
import com.normation.rudder.domain.policies.FullRuleTargetInfo
import com.normation.rudder.domain.policies.GroupTarget
import com.normation.rudder.domain.policies.PolicyTypes
import com.normation.rudder.domain.policies.Rule
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.RuleTarget
import com.normation.rudder.domain.policies.RuleUid
import com.normation.rudder.domain.properties.PropertyProvider
import com.normation.rudder.facts.nodes.ChangeContext
import com.normation.rudder.facts.nodes.QueryContext
import com.normation.rudder.git.ZipUtils
import com.normation.rudder.git.ZipUtils.Zippable
import com.normation.rudder.ncf.ResourceFile
import com.normation.rudder.ncf.ResourceFileState
import com.normation.rudder.ncf.migration.MigrateJsonTechniquesService
import com.normation.rudder.ncf.yaml.YamlTechniqueSerializer
import com.normation.rudder.repository.FullNodeGroupCategory
import com.normation.rudder.repository.RoDirectiveRepository
import com.normation.rudder.repository.RoNodeGroupRepository
import com.normation.rudder.repository.RoRuleRepository
import com.normation.rudder.repository.WoDirectiveRepository
import com.normation.rudder.repository.WoNodeGroupRepository
import com.normation.rudder.repository.WoRuleRepository
import com.normation.rudder.repository.xml.TechniqueArchiverImpl
import com.normation.rudder.repository.xml.TechniqueFiles
import com.normation.rudder.repository.xml.TechniqueRevisionRepository
import com.normation.rudder.rest.ApiModuleProvider
import com.normation.rudder.rest.ApiPath
import com.normation.rudder.rest.ArchiveApi as API
import com.normation.rudder.rest.AuthzToken
import com.normation.rudder.rest.implicits.*
import com.normation.rudder.rest.lift.ImportAnswer.*
import com.normation.rudder.rule.category.RoRuleCategoryRepository
import com.normation.rudder.rule.category.RuleCategory
import com.normation.rudder.rule.category.RuleCategoryId
import com.normation.rudder.rule.category.WoRuleCategoryRepository
import com.normation.rudder.services.queries.CmdbQueryParser
import com.normation.utils.StringUuidGenerator
import com.normation.utils.XmlSafe
import com.normation.zio.*
import enumeratum.*
import io.scalaland.chimney.Iso
import io.scalaland.chimney.Transformer
import io.scalaland.chimney.syntax.*
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.NoSuchFileException
import java.text.Normalizer
import java.time.Instant
import java.util.zip.ZipEntry
import net.liftweb.common.SimpleActor
import net.liftweb.http.FileParamHolder
import net.liftweb.http.LiftResponse
import net.liftweb.http.OutputStreamResponse
import net.liftweb.http.Req
import scala.util.matching.Regex
import zio.*
import zio.json.*
import zio.syntax.*

sealed trait ArchiveScope extends EnumEntry          { def value: String }
object ArchiveScope       extends Enum[ArchiveScope] {

  // using nodep/alldep to avoid confusion with "none" in scala code
  case object AllDep     extends ArchiveScope { val value = "all"        }
  case object NoDep      extends ArchiveScope { val value = "none"       }
  case object Directives extends ArchiveScope { val value = "directives" }
  case object Techniques extends ArchiveScope { val value = "techniques" }
  case object Groups     extends ArchiveScope { val value = "groups"     }

  val values:           IndexedSeq[ArchiveScope]     = findValues
  def parse(s: String): Either[String, ArchiveScope] = {
    values.find(_.value == s.toLowerCase.strip()) match {
      case None    =>
        Left(
          s"Error: can not parse '${s}' as a scope for dependency resolution in archive. Accepted values are: ${values.sortBy(_.value).mkString(", ")}"
        )
      case Some(x) => Right(x)
    }
  }
}

// explicitly using the entryName because it's an external API needing special care and grep-ability.
sealed abstract class MergePolicy(override val entryName: String) extends EnumEntry
object MergePolicy                                                extends Enum[MergePolicy] {
  // Default merge policy is "override everything", ie what is in the archive replace whatever exists in Rudder
  case object OverrideAll     extends MergePolicy("override-all")
  // A merge policy that will keep current groups for rule with an ID common with one of the archive
  case object KeepRuleTargets extends MergePolicy("keep-rule-targets")

  // A merge policy that will always delete source groups of rule, and if rule exists, copy destination rule target (keep them)
  case object IgnoreSourceTargets extends MergePolicy("ignore-source-targets")

  val values: IndexedSeq[MergePolicy] = findValues

  override val extraNamesToValuesMap: Map[String, MergePolicy] = Map("keep-rule-groups" -> KeepRuleTargets)

  def parse(s: String): Either[String, MergePolicy] = {
    MergePolicy
      .withNameInsensitiveEither(s)
      .left
      .map(err => {
        s"Error: can not parse '${s}' as a merge policy for archive import. Accepted values are: ${values
            .mkString(", ")}. Error was: ${err.getMessage()}"
      })
  }
}

// an object for the special ids for exporting all rules etc
object SpecialExportAll {
  val allRules:      RuleId      = RuleId(RuleUid("all"))
  val allDirectives: DirectiveId = DirectiveId(DirectiveUid("all"))
  val allGroups:     NodeGroupId = NodeGroupId(NodeGroupUid("all"))
  val allTechniques: TechniqueId = TechniqueId(TechniqueName("all"), TechniqueVersion.V1_0)
}

class ArchiveApi(
    archiveBuilderService: ZipArchiveBuilderService,
    getArchiveName:        IOResult[String],
    zipArchiveReader:      ZipArchiveReader,
    saveArchiveService:    SaveArchiveService,
    checkArchiveService:   CheckArchiveService
) extends LiftApiModuleProvider[API] {

  def schemas: ApiModuleProvider[API] = API

  def getLiftEndpoints(): List[LiftApiModule] = {
    API.endpoints.map {
      case API.Import       => Import
      case API.ExportSimple => ExportSimple
    }
  }

  /*
   * This API does not return a standard JSON response, it returns a ZIP archive.
   */
  object ExportSimple extends LiftApiModule0 {
    val schema:                                                                                                API.ExportSimple.type = API.ExportSimple
    /*
     * Request format:
     *   ../archives/export?rules=rule_ids&directives=dir_ids&techniques=tech_ids&groups=group_ids&include=scope
     * Where:
     * - rule_ids = xxxx-xxxx-xxx-xxx[,other ids]
     * - dir_ids = xxxx-xxxx-xxx-xxx[,other ids]
     * - group_ids = xxxx-xxxx-xxx-xxx[,other ids]
     * - tech_ids = techniqueName/1.0[,other tech ids]
     * - scope = all (default), none, directives, techniques (implies directive), groups
     */
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse          = {

      // we use lots of comma separated arg, factor out the splitting logic
      def splitArg(req: Req, name: String): List[String]                 =
        req.params.getOrElse(name, Nil).flatMap(seq => seq.split(',').toList.map(_.strip()).distinct)
      def parseRuleIds(req: Req):           IOResult[List[RuleId]]       = {
        ZIO.foreach(splitArg(req, "rules"))(RuleId.parse(_).toIO)
      }
      def parseDirectiveIds(req: Req):      IOResult[List[DirectiveId]]  = {
        ZIO.foreach(splitArg(req, "directives"))(DirectiveId.parse(_).toIO)
      }
      def parseTechniqueIds(req: Req):      IOResult[List[TechniqueId]]  = {
        ZIO.foreach(splitArg(req, "techniques"))(t => {
          if (t == "all") SpecialExportAll.allTechniques.succeed
          else TechniqueId.parse(t).toIO
        })
      }
      def parseGroupIds(req: Req):          IOResult[List[NodeGroupId]]  = {
        ZIO.foreach(splitArg(req, "groups"))(NodeGroupId.parse(_).toIO)
      }
      def parseScopes(req: Req):            IOResult[List[ArchiveScope]] = {
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

  object Import extends LiftApiModule0 {
    val schema: API.Import.type = API.Import
    // name of the form multipart that holds the archive binary content
    val FILE = "archive"

    /*
     * We expect a binary file in multipart/form-data, not in application/x-www-form-urlencodedcontent
     * You can get that in curl with:
     * curl -k -X POST -H "X-API-TOKEN: ..." https://.../api/latest/archives/import --form "merge=keep-rule-targets" --form="archive=@my-archive.zip"
     */
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {

      // find merge policy. For now, it's a one-parameter arg with default = override-all if missing
      def parseMergePolicy(req: Req): IOResult[MergePolicy] = {
        req.params.get("merge") match {
          case None | Some(Nil) => MergePolicy.OverrideAll.succeed
          case Some(s :: Nil)   => MergePolicy.parse(s).toIO
          case Some(list)       => Inconsistency(s"Only one merge parameter is allowed, but found several: ${list.mkString(", ")}").fail
        }
      }

      def parseArchive(archive: FileParamHolder, name: String): IOResult[PolicyArchive] = {
        ZIO
          .acquireReleaseWith(IOResult.attempt(archive.fileStream))(is => effectUioUnit(is.close())) { is =>
            ZipUtils.getZipEntries(name, is)
          }
          .flatMap(entries => zipArchiveReader.readPolicyItems(name, entries))
      }

      val prog = (req.uploadedFiles.find(_.name == FILE) match {
        case None      =>
          Unexpected(s"Missing uploaded file with parameter name '${FILE}'").fail
        case Some(zip) =>
          val originalFilename = File(zip.fileName).name

          for {
            _       <- ApplicationLoggerPure.Archive.info(s"Received a new policy archive '${originalFilename}', processing")
            merge   <- parseMergePolicy(req)
            archive <- parseArchive(zip, originalFilename)
            _       <- checkArchiveService.check(archive)
            _       <- saveArchiveService.save(archive, merge)(using authzToken.qc)
            _       <- ApplicationLoggerPure.Archive.info(s"Uploaded archive '${originalFilename}' processed successfully")
          } yield JRArchiveImported(success = true)
      }).tapError(err => ApplicationLoggerPure.Archive.error(s"Error when processing uploaded archive: ${err.fullMsg}"))

      prog.toLiftResponseOne(params, schema, _ => None)
    }
  }
}

/*
 * A dummy object waiting for implementation for import
 */
object ImportAnswer {

  import zio.json.*

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

final case class JRuleCategories(
    id:          RuleCategoryId,
    name:        String,
    description: String,
    children:    List[JRuleCategories]
) {
  def keepInHierarchy(ids: Set[RuleCategoryId]): JRuleCategories = {
    copy(children = children.filter(_.hasDescendantIn(ids)).map(_.keepInHierarchy(ids)))
  }

  private def hasDescendantIn(ids: Set[RuleCategoryId]): Boolean = {
    ids.contains(id) || children.exists(_.hasDescendantIn(ids))
  }

}
object JRuleCategories {
  implicit val encoderId: JsonEncoder[RuleCategoryId] = JsonEncoder[String].contramap(_.value)

  implicit lazy val encoder: JsonEncoder[JRuleCategories] = DeriveJsonEncoder.gen[JRuleCategories]
  implicit lazy val decoder: JsonDecoder[JRuleCategories] = DeriveJsonDecoder.gen[JRuleCategories]

  implicit lazy val transformer:        Transformer[RuleCategory, JRuleCategories]        = {
    Transformer
      .define[RuleCategory, JRuleCategories]
      .withFieldComputed(_.children, _.childs.sortBy(_.id.value).map(_.transformInto[JRuleCategories]))
      .buildTransformer
  }
  implicit lazy val transformerArchive: Transformer[JRuleCategories, RuleCategoryArchive] = {
    Transformer.derive[JRuleCategories, RuleCategoryArchive]
  }
}

// a simple Json-able object for group categories
case class JGroupCategory(
    id:          String,
    name:        String,
    description: String
)
object JGroupCategory {

  implicit val encoderJGroupCategory: JsonEncoder[JGroupCategory] = DeriveJsonEncoder.gen
  implicit val decoderJGroupCategory: JsonDecoder[JGroupCategory] = DeriveJsonDecoder.gen

  def fromFullNodeGroupCategory(g: FullNodeGroupCategory): JGroupCategory = {
    JGroupCategory(g.id.value, g.name, g.description)
  }
}

object ZipArchiveBuilderService {
  // names of directories under the root directory of the archive
  val RULES_DIR:      String = "rules"
  val GROUPS_DIR:     String = "groups"
  val DIRECTIVES_DIR: String = "directives"
  val TECHNIQUES_DIR: String = "techniques"

  val GROUP_CAT_FILENAME = "category.json"
  val RULE_CATS          = "rule-categories.json"

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
    techniqueRevisionRepo:  TechniqueRevisionRepository,
    groupRepo:              RoNodeGroupRepository,
    ruleRepo:               RoRuleRepository,
    ruleCategoryRepo:       RoRuleCategoryRepository,
    directiveRepo:          RoDirectiveRepository,
    techniqueRepo:          TechniqueRepository
) {

  import ZipArchiveBuilderService.*

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
    usedNames.modify { m =>
      m.get(category) match {
        case None                           =>
          (name, m + ((category, Set(name))))
        case Some(c) if (!c.contains(name)) =>
          (name, m + ((category, c + name)))
        case Some(c)                        =>
          val realName = findRecName(c, name, 1)
          (realName, m + ((category, m(category) + realName)))
      }

    }
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
   * Retrieve the group using first cache, then the config service, and update cache accordingly
   */

  /*
   * Get zips for technique categories.
   * - we need to don't have duplicate to avoid zipping the same things.
   * - we may have two categories with different versions. Version aren't ordered, so we can't take
   *   the newest. Since that case should be super rare and generally without impact (technique category description
   *   are not often changed), just take one at random
   * - we assume that path are the whole path (just missing leading "/")
   */
  def getTechniqueCategoryZippable(
      techniquesDir: String,
      cats:          Seq[(Chunk[TechniqueCategoryName], TechniqueVersion)]
  ): IOResult[Seq[Zippable]] = {
    for {
      ref   <- Ref.Synchronized.make(Map[String, Zippable]())
      _     <- ZIO.foreachDiscard(cats.distinctBy(_._1)) {
                 case (cs, v) =>
                   // build the list of path. getTechniqueCategoryMetadata will add the leading part of the
                   // path and category.xml at the end, so remove leading "/" and skip last part.
                   val (paths, _) = cs.foldLeft((List.empty[String], "")) {
                     case (x, catName) if (catName.value == "/" || catName.value == TechniqueCategoryMetadata.FILE_NAME_XML) =>
                       x
                     case ((acc, parent), catName)                                                                           =>
                       val newParent = parent + "/" + catName.value

                       (newParent :: acc, newParent)
                   }
                   ZIO.foreachDiscard(paths) { p =>
                     ref.updateZIO { m =>
                       // at each level, we need to add the directory and the file
                       val dirPath      = techniquesDir + "/" + p
                       val filePathJson = dirPath + "/" + TechniqueCategoryMetadata.FILE_NAME_JSON
                       if (m.contains(filePathJson)) m.succeed // if category.json is here, by construction its parent dir is, too
                       else {
                         techniqueRevisionRepo.getTechniqueCategoryMetadata(p, v.rev).map {
                           case None       => m
                           case Some(data) =>
                             m + (dirPath -> Zippable(dirPath, None)) + (filePathJson -> Zippable(
                               filePathJson,
                               Some(getJsonZippableContent(data.toJsonPretty))
                             ))
                         }
                       }
                     }
                   }
               }
      infos <- ref.get
    } yield {
      val sorted = infos.toList.sortBy(_._1)
      sorted.map(_._2)
    }
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
      // We need to separate the case for historical techniques and for yaml techniques. When we have a yaml technique,
      // we need to filter-out every file that rudderc generate: technique.{ps1, cf}, metadata.xml.
      // When we have a `technique.json`, it's an unexpected case: migration should have been done at boot. So we are ignoring
      // that case here and we are just considering it as an historical technique for export.
      // In the last case (historical technique), we need to keep everything.
      // The upd-to-date list of file for a technique is in: TechniqueFiles.all. We filter them out excepted `technique.yml`

      filtered = if (contents.exists(f => f._1 == TechniqueFiles.yaml)) { // this is a yaml technique, keep only the yaml source
                   contents.filter(x => !TechniqueFiles.Generated.all.contains(x._1))
                 } else contents

      // we need to change root of zippable, we want techniques/myTechnique/1.0/[HERE] and we need to filter out root category
      catDirs  = cats.collect { case TechniqueCategoryName(value) if value != "/" => value }
      basePath = techniquesDir + "/" + catDirs.mkString("/") + "/" + techniqueId.withDefaultRev.serialize + "/"
      // start by adding directories toward technique
      zips     = filtered.map { case (p, opt) => Zippable.make(basePath + p, opt) }
      _       <- ApplicationLoggerPure.Archive.debug(
                   s"Building archive '${archiveName}': adding technique zippables: ${zips.map(_.path).mkString(", ")}"
                 )
    } yield {
      zips
    }
  }

  def getRuleCatZippable(ids: Set[RuleCategoryId], root: String): IOResult[Seq[Zippable]] = {
    val path = root + "/" + RULE_CATS
    if (ids.isEmpty) {
      Seq().succeed
    } else {
      ruleCategoryRepo
        .getRootCategory()
        .map(_.transformInto[JRuleCategories].keepInHierarchy(ids).toJsonPretty)
        .map(json => List(Zippable(path, Some(getJsonZippableContent(json)))))
    }
  }

  /*
   * Get the list of all group category or group as Zip object, in the order they need to be created
   * (ie first categories from root to leaves, then groups)
   */
  def getGroupLibZippable(
      ids:         Seq[NodeGroupId],
      groupsDir:   String,
      usedNames:   Ref[Map[String, Set[String]]],
      rootDirName: String,
      groupLib:    FullNodeGroupCategory
  ): IOResult[Seq[Zippable]] = {

    import com.softwaremill.quicklens.*

    // filter the group category, keeping only groups from list, then non-empty cat, recursively
    def recFilter(ids: Seq[NodeGroupId], cat: FullNodeGroupCategory): Option[FullNodeGroupCategory] = {
      if (ids.isEmpty) None
      else {
        val subCats   = cat.subCategories.flatMap(recFilter(ids, _))
        val subGroups = cat.targetInfos.filter { t =>
          t.target.target match {
            case GroupTarget(id) => ids.contains(id)
            case _               => false
          }
        }

        if (subCats.isEmpty && subGroups.isEmpty) None
        else {
          Some(
            cat
              .modify(_.subCategories)
              .setTo(subCats)
              .modify(_.targetInfos)
              .setTo(subGroups)
          )
        }
      }
    }

    def missingGroups(ids: Iterable[NodeGroupId]): IOResult[Chunk[Zippable]] = {
      Inconsistency(s"The following groups were not found in Rudder: '${ids.map(_.debugString).mkString("','")}'").fail
    }

    /*
     * Map FullNodeGroupLibrary to a structure of zippable:
     * To avoid root, we avoid current cat and only deal with children.
     *
     * - start with a base path (without ending /) to which are adding items
     * - deep-first in sub-categories:
     *   - find a name for the cat
     *   - create the new base path with parent path + new cat name appended
     *   - create zip "category.json" under it
     *   - recurse in cat
     * - then groups
     * - for each level, maintain a list of used name to avoid duplicate which would override
     *
     * Here: base
     */
    def recMapToZippable(
        basePath:    String,
        cat:         FullNodeGroupCategory,
        catFileName: String,
        usedNames:   Ref[Map[String, Set[String]]]
    ): IOResult[Chunk[Zippable]] = {
      for {
        ref   <- Ref.make(Chunk.empty[Zippable])
        _     <- ZIO.foreach(cat.subCategories) { sub =>
                   val c    = JGroupCategory.fromFullNodeGroupCategory(sub)
                   val json = c.toJsonPretty
                   for {
                     catDir     <- findName(c.name, "", usedNames, basePath)
                     path        = basePath + "/" + catDir
                     catName    <- findName(catFileName, ".json", usedNames, path)
                     catFilePath = path + "/" + catName
                     _          <- ApplicationLoggerPure.Archive
                                     .debug(s"Building archive '${rootDirName}': adding group category zippable: ${catFilePath}")
                     _          <- ref.update(_.appended(Zippable(catFilePath, Some(getJsonZippableContent(json)))))
                     children   <- recMapToZippable(path, sub, catFileName, usedNames)
                     _          <- ref.update(_.appendedAll(children))
                   } yield ()
                 }
        // we only archive non-system NodeGroups, other kind of special targets are ignored
        groups = cat.targetInfos.collect { case FullRuleTargetInfo(FullGroupTarget(_, g), _, _, _, false) => g }
        _     <- ZIO.foreach(groups) { g =>
                   val json = JRGroup.fromGroup(g, cat.id, None).toJsonPretty
                   for {
                     name <- findName(g.name, ".json", usedNames, basePath)
                     path  = basePath + "/" + name
                     _    <- ApplicationLoggerPure.Archive
                               .debug(s"Building archive '${rootDirName}': adding group zippable: ${path}")
                     _    <- ref.update(_.appended(Zippable(path, Some(getJsonZippableContent(json)))))
                   } yield ()
                 }
        zips  <- ref.get
      } yield zips
    }

    // we need the file name without extension
    val GROUP_CAT_WITHOUT_EXT = GROUP_CAT_FILENAME.split("\\.")(0)

    val allIds: Seq[NodeGroupId] = if (ids.contains(SpecialExportAll.allGroups)) groupLib.allGroups.keySet.toSeq else ids

    recFilter(allIds, groupLib) match {
      case None      => if (ids.isEmpty) Nil.succeed else missingGroups(ids)
      case Some(cat) =>
        val missing = cat.allGroups.keySet -- ids
        if (missing.nonEmpty) missingGroups(missing)
        else {
          recMapToZippable(groupsDir, cat, GROUP_CAT_WITHOUT_EXT, usedNames)
        }
    }
  }

  /*
   * Prepare the archive.
   * `rootDirName` is supposed to be normalized, no change will be done with it.
   * Any missing object will lead to an error.
   * For each element, a human-readable name derived from the object name is used when possible.
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
    import ArchiveScope.*
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
      ruleCatsRef     <- Ref.make(Set[RuleCategoryId]())
      rules           <- if (ruleIds.contains(SpecialExportAll.allRules)) ruleRepo.getAll(includeSytem = false)
                         else {
                           ZIO.foreach(ruleIds) { ruleId =>
                             configRepo
                               .getRule(ruleId)
                               .notOptional(s"Rule with id ${ruleId.serialize} was not found in Rudder")
                           }
                         }
      rulesZip        <- ZIO
                           .foreach(rules) { rule =>
                             if (rule.isSystem) None.succeed
                             else {
                               for {
                                 _    <- depDirectiveIds.update(x => x ++ rule.directiveIds)
                                 _    <- depGroupIds.update(x => x ++ RuleTarget.getNodeGroupIds(rule.targets))
                                 json  = JRRule.fromRule(rule, None, None, None).toJsonPretty
                                 name <- findName(rule.name, ".json", usedNames, RULES_DIR)
                                 path  = rulesDir + "/" + name
                                 _    <- ApplicationLoggerPure.Archive
                                           .debug(s"Building archive '${rootDirName}': adding rule zippable: ${path}")
                                 _    <- ruleCatsRef.update(_ + rule.categoryId)
                               } yield {
                                 Some(Zippable(path, Some(getJsonZippableContent(json))))
                               }
                             }
                           }
                           .map(_.flatten)
      ruleCats        <- ruleCatsRef.get
      ruleCatsZip     <- getRuleCatZippable(ruleCats, root)
      groupsDir        = root + "/" + GROUPS_DIR
      _               <- usedNames.update(_ + ((GROUPS_DIR, Set.empty[String])))
      groupsDirZip     = Zippable(groupsDir, None)
      allGroupIDs     <- if (groupIds.contains(SpecialExportAll.allGroups))
                           groupRepo.getAll().map(_.collect { case g if !g.isSystem => g.id })
                         else (if (includeDepGroups) depGroupIds.get else Nil.succeed).map(_ ++ groupIds)
      groupLib        <- groupRepo.getFullGroupLibrary()
      groupDir         = root + "/" + GROUPS_DIR
      groupsZip       <- getGroupLibZippable(allGroupIDs, groupDir, usedNames, rootDirName, groupLib)
      // directives need access to technique, but we don't want to look up several time the same one
      techniques      <- Ref.Synchronized.make(Map.empty[TechniqueId, (Chunk[TechniqueCategoryName], Technique)])

      directivesDir    = root + "/" + DIRECTIVES_DIR
      _               <- usedNames.update(_ + ((DIRECTIVES_DIR, Set.empty[String])))
      directivesDirZip = Zippable(directivesDir, None)
      depDirectives   <- if (includeDepDirectives) depDirectiveIds.get else Nil.succeed
      directives      <- if (directiveIds.contains(SpecialExportAll.allDirectives)) {
                           directiveRepo
                             .getFullDirectiveLibrary()
                             .map(_.allDirectives.collect {
                               case (_, (fat, d)) if !d.isSystem => ActiveDirective(fat.toActiveTechnique(), d)
                             })
                         } else {
                           ZIO
                             .foreach(directiveIds ++ depDirectives) { directiveId =>
                               configRepo
                                 .getDirective(directiveId)
                                 .notOptional(s"Directive with id ${directiveId.serialize} was not found in Rudder")
                             }
                         }
      directivesZip   <- ZIO
                           .foreach(directives) { ad =>
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
                                           .debug(s"Building archive '${rootDirName}': adding directive zippable: ${path}")
                               } yield Some(Zippable(path, Some(getJsonZippableContent(json))))
                             }
                           }
                           .map(_.flatten)
      // Techniques don't need name normalization, their name is already normalized
      techniquesDir    = root + "/" + TECHNIQUES_DIR
      techniquesDirZip = Zippable(techniquesDir, None)
      allTechIds      <- if (techniqueIds.contains(SpecialExportAll.allTechniques))
                           techniqueRepo.getAll().collect { case (id, t) if (t.policyTypes == PolicyTypes.rudderBase) => id }.succeed
                         else (if (includeDepTechniques) techniques.get.map(_.keys) else Nil.succeed).map(_ ++ techniqueIds)
      allTech         <- ZIO.foreach(allTechIds)(techniqueId => getTechnique(techniqueId, techniques))
      // start by zipping categories after having dedup them
      techCats         = allTech.collect { case (c, t) => (c, t.id.version) }.toSeq
      techCatsZip     <- getTechniqueCategoryZippable(techniquesDir, techCats)
      techniquesZip   <- ZIO.foreach(allTech.filter(_._2.policyTypes.isBase)) {
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
      ) ++ ruleCatsZip ++ rulesZip ++ techCatsZip ++ techniquesZip.flatten ++ directivesZip ++ groupsZip
    }
  }

}

// metadata about policy archive, at least original file name
final case class PolicyArchiveMetadata(
    filename: String
)

case object PolicyArchiveMetadata {
  def empty: PolicyArchiveMetadata = PolicyArchiveMetadata("")
}

final case class TechniqueCategoryArchive(
    metadata: TechniqueCategoryMetadata,
    // the path, last one is category with the metadata (ie also category id)
    // Can't be empty because we don't change root category with archive.
    category: NonEmptyChunk[String]
)

final case class TechniqueInfo(id: TechniqueId, name: String, kind: TechniqueType)

final case class TechniqueArchive(
    technique: TechniqueInfo,
    category:  Chunk[String],
    files:     Chunk[(String, Array[Byte])]
)
final case class DirectiveArchive(
    technique: TechniqueName,
    directive: Directive
)
final case class GroupCategoryArchive(
    category: JGroupCategory,
    // the path for that cat
    catPath:  String
)
final case class GroupArchive(
    group:    NodeGroup,
    category: NodeGroupCategoryId
)

/**
 * The root of the rule categories, there is only a single root
 */
final case class RuleCategoryArchive(
    id:          RuleCategoryId,
    name:        String,
    description: String,
    children:    List[RuleCategoryArchive]
) {
  import RuleCategoryArchive.*

  /**
   * All children of the root rule category
   */
  def childCategories:  Map[RuleCategoryId, RuleCategoryArchive]      = {
    children.map(c => c.id -> c).toMap.flatMap { case (k, v) => v.childCategories + (k -> v) }
  }
  def parentCategories: Map[RuleCategoryArchive, RuleCategoryArchive] = {
    (children.map(_ -> this) ++ children.flatMap(_.parentCategories)).toMap
  }

  def toRuleCategory: RuleCategory = {
    // Root RuleCategory is system, so upon transformation the root is system
    this.transformInto[RuleCategory].copy(isSystem = true)
  }
}
object RuleCategoryArchive {
  implicit lazy val iso: Iso[RuleCategoryArchive, RuleCategory] = {
    Iso
      .define[RuleCategoryArchive, RuleCategory]
      .enableDefaultValues
      .withFieldRenamed(_.children, _.childs)
      .buildIso
  }
}

/**
 * An archive to be saved (~) atomically
 * For techniques, we only parse metadata.xml, and we keep files as is.
 * Rule categories should have the single root if present
 */
final case class PolicyArchive(
    metadata:      PolicyArchiveMetadata,
    techniqueCats: Chunk[TechniqueCategoryArchive],
    techniques:    Chunk[TechniqueArchive],
    directives:    Chunk[DirectiveArchive],
    groupCats:     Chunk[GroupCategoryArchive],
    groups:        Chunk[GroupArchive],
    ruleCats:      Chunk[RuleCategoryArchive],
    rules:         Chunk[Rule]
) {
  // format: off
  def debugString: String = {
    s"""Archive ${metadata.filename}:
       | - technique categories: ${techniqueCats.map(c => s"${c.category.mkString("/", "/", "/")}${metadata.filename}").sorted.mkString(", ")}
       | - techniques          : ${techniques.map(_.technique.id.serialize).sorted.mkString(", ")}
       | - directives          : ${directives.map(d => s"'${d.directive.name}' [${d.directive.id.serialize}]").sorted.mkString(", ")}
       | - group categories    : ${groupCats.map(c => s"'${c.category.name}' [${c.category.id}]").sorted.mkString(", ")}
       | - groups              : ${groups.map(g => s"'${g.group.name}' [${g.group.id.serialize}]").sorted.mkString(", ")}
       | - rule categories     : ${ruleCats.toList.flatMap(_.childCategories).map { case (id, cat) => s"'${cat.name}' [${id.value}]"}.sorted.mkString(", ")}\"\"\".stripMargin
       | - rules               : ${rules.map(r => s"'${r.name}' [${r.id.serialize}]").sorted.mkString(", ")}""".stripMargin
  }
  // format: on
}
object PolicyArchive {
  def empty: PolicyArchive = {
    PolicyArchive(
      PolicyArchiveMetadata.empty,
      Chunk.empty,
      Chunk.empty,
      Chunk.empty,
      Chunk.empty,
      Chunk.empty,
      Chunk.empty,
      Chunk.empty
    )
  }
}

final case class SortedEntries(
    techniquesCats: Chunk[(String, Array[Byte])],
    techniques:     Chunk[(String, Array[Byte])],
    directives:     Chunk[(String, Array[Byte])],
    groupCats:      Chunk[(String, Array[Byte])],
    groups:         Chunk[(String, Array[Byte])],
    ruleCats:       Chunk[(String, Array[Byte])],
    rules:          Chunk[(String, Array[Byte])]
)
object SortedEntries {
  def empty: SortedEntries =
    SortedEntries(Chunk.empty, Chunk.empty, Chunk.empty, Chunk.empty, Chunk.empty, Chunk.empty, Chunk.empty)
}

final case class PolicyArchiveUnzip(
    policies: PolicyArchive,
    errors:   Chunk[RudderError]
)

object PolicyArchiveUnzip {
  def empty: PolicyArchiveUnzip = PolicyArchiveUnzip(PolicyArchive.empty, Chunk.empty)
}

sealed trait TechniqueType { def name: String }
object TechniqueType       {
  case object Yaml     extends TechniqueType { val name = TechniqueFiles.yaml               }
  case object Json     extends TechniqueType { val name = TechniqueFiles.json               }
  case object Metadata extends TechniqueType { val name = TechniqueFiles.Generated.metadata }
}

/**
 * That class is in charge of reading a zip archive (as a sequence of ZipEntry items) and
 * unflatten it into the corresponding Rudder policy items.
 * Nothing is put on file system at that point.
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
  import com.softwaremill.quicklens.*

  // we must avoid to eagerly match "ncf_techniques" as "techniques" but still accept when it starts by "techniques" without /
  val techniqueCatsRegex: Regex = """(.*/|)techniques/(.+category.json)""".r
  val techniqueRegex:     Regex = """(.*/|)techniques/(.+)""".r
  val yamlRegex:          Regex = s"""(.+)/${TechniqueType.Yaml.name}""".r
  val jsonRegex:          Regex = s"""(.+)/${TechniqueType.Json.name}""".r
  val metadataRegex:      Regex = s"""(.+)/${TechniqueType.Metadata.name}""".r
  val directiveRegex:     Regex = """(.*/|)directives/(.+.json)""".r
  val groupCatsRegex:     Regex = """(.*/|)groups/(.*category.json)""".r
  val groupRegex:         Regex = """(.*/|)groups/(.+.json)""".r
  val ruleCatsRegex:      Regex = """(.*/|)(rule-categories\.json)""".r
  val ruleRegex:          Regex = """(.*/|)rules/(.+.json)""".r

  /*
   * For technique, we are parsing metadata.xml.
   * We also find technique name, version, and categories from base path.
   * For file: we keep all, but we make their path relative to technique (ie we remove base path)
   */
  def parseTechniqueCat(name: String, content: Array[Byte])(implicit
      dec: JsonDecoder[TechniqueCategoryMetadata]
  ): IOResult[TechniqueCategoryArchive] = {
    // we need to keep path to be able to find back hierarchy later on
    val catPath = name.split("/").dropRight(1).toList

    catPath match {
      case Nil          =>
        Unexpected(
          s"Category in archive must have at least one parent, but category relative path is: ${name}"
        ).fail
      case head :: tail =>
        new String(content, StandardCharsets.UTF_8)
          .fromJson[TechniqueCategoryMetadata]
          .toIO
          .map(c => TechniqueCategoryArchive(c, NonEmptyChunk.fromIterable(head, tail)))
    }
  }

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

    /*
     * As we go through all files of the technique package, we look for either technique.{json,yml} or metadata.xml
     * to find the technique information.
     * If technique.json is available, it is migrated to yaml on the fly.
     * Updated content or previous one is returned.
     * Both technique.json and technique.yaml have priority above metadata.xml
     */
    def checkParseTechniqueDescriptor(
        basePath: String,
        id:       TechniqueId,
        optTech:  Ref[Option[TechniqueInfo]],
        name:     String,
        content:  Array[Byte]
    ): IOResult[(String, Array[Byte])] = {
      import YamlTechniqueSerializer.*
      import com.normation.rudder.ncf.yaml.Technique as YTechnique
      import zio.json.yaml.*

      // when the technique is a YAML technique, we need to check that technique ID matches technique path
      def checkYamlIdMatchesPath(path: String, tech: YTechnique, id: TechniqueId): IOResult[Unit] = {
        TechniqueVersion.parse(tech.version.value).toIO.flatMap { v =>
          val yamlId = TechniqueId(TechniqueName(tech.id.value), v)
          if (yamlId == id) ZIO.unit
          else {
            Inconsistency(
              s"Error: technique in archive at path '${path}' should have ID from ${id.serialize} based " +
              s"on pattern 'category/techniqueName/techniqueVersion' but the technique descriptor contains id: ${yamlId.serialize}. " +
              s"You need to make both match."
            ).fail
          }
        }
      }

      // In the technique.json and technique.yml case, we always override what we might already have parsed for metadata/
      // In technique.json, we also first try to migrate to yml.
      name.toLowerCase() match {
        case TechniqueType.Json.name =>
          val json = new String(content, StandardCharsets.UTF_8)
          for {
            yaml <- MigrateJsonTechniquesService.toYaml(json).toIO
            res  <-
              checkParseTechniqueDescriptor(basePath, id, optTech, TechniqueType.Yaml.name, yaml.getBytes(StandardCharsets.UTF_8))
          } yield res

        case TechniqueType.Yaml.name =>
          val yaml = new String(content, StandardCharsets.UTF_8)
          for {
            tech <- yaml.fromYaml[YTechnique].toIO
            _    <- checkYamlIdMatchesPath(basePath, tech, id)
            _    <- optTech.set(Some(TechniqueInfo(id, tech.name, TechniqueType.Yaml)))
          } yield (name, content)

        case TechniqueType.Metadata.name =>
          for {
            xml  <- IOResult.attempt(XmlSafe.load(new ByteArrayInputStream(content)))
            tech <- techniqueParser.parseXml(xml, id).toIO
            _    <- optTech.update {
                      case None    => Some(TechniqueInfo(tech.id, tech.name, TechniqueType.Metadata))
                      case Some(x) => Some(x) // ignore, we may already found a technique.yml
                    }
          } yield (name, content)

        case x => // zap
          (name, content).succeed
      }
    }

    for {
      path    <- parseBasePath(basepath)
      (id, c)  = path
      optTech <- Ref.Synchronized.make(Option.empty[TechniqueInfo])
      // update path in files, and parse metadata when found
      updated <- ZIO.foreach(files) {
                   case (f, content) =>
                     val name = f.replaceFirst(basepath + "/", "")
                     checkParseTechniqueDescriptor(basepath, id, optTech, name, content)
                 }
      tech    <-
        optTech.get.notOptional(
          s"Error: archive does not contains a ${TechniqueType.Yaml.name} or ${TechniqueType.Metadata.name} or ${TechniqueType.Json.name} file " +
          s"for technique with id '${basepath}', at least one was expected"
        )
    } yield {
      // if we have a Yaml technique, we need to remove generated contents
      val files = if (tech.kind == TechniqueType.Yaml) {
        updated.filter { case (name, _) => !TechniqueFiles.Generated.all.contains(name) }
      } else updated
      TechniqueArchive(tech, c, files)
    }
  }
  def parseDirective(name: String, content: Array[Byte])(implicit dec: JsonDecoder[JRDirective]): IOResult[DirectiveArchive] = {
    (new String(content, StandardCharsets.UTF_8))
      .fromJson[JRDirective]
      .toIO
      .flatMap(_.toDirective().map(t => DirectiveArchive(t._1, t._2)))
  }
  def parseGroupCat(name: String, content: Array[Byte])(implicit
      dec: JsonDecoder[JGroupCategory]
  ): IOResult[GroupCategoryArchive] = {
    // we need to keep path to be able to find back hierarchy later on
    (new String(content, StandardCharsets.UTF_8))
      .fromJson[JGroupCategory]
      .toIO
      .map(c => GroupCategoryArchive(c, File(name).parent.pathAsString))
  }
  def parseGroup(name: String, content: Array[Byte])(implicit dec: JsonDecoder[JRGroup]):         IOResult[GroupArchive]     = {
    (new String(content, StandardCharsets.UTF_8))
      .fromJson[JRGroup]
      .toIO
      .flatMap(_.toGroup(cmdbQueryParser).map(d => GroupArchive(d._2, d._1)))
  }
  def parseRuleCat(name: String, content: Array[Byte])(implicit
      dec: JsonDecoder[JRuleCategories]
  ): IOResult[RuleCategoryArchive] = {
    (new String(content, StandardCharsets.UTF_8)).fromJson[JRuleCategories].toIO.map(_.transformInto[RuleCategoryArchive])
  }
  def parseRule(name: String, content: Array[Byte])(implicit dec: JsonDecoder[JRRule]):           IOResult[Rule]             = {
    (new String(content, StandardCharsets.UTF_8)).fromJson[JRRule].toIO.flatMap(_.toRule())
  }

  def parseTechniqueCats(arch: PolicyArchiveUnzip, cats: Chunk[(String, Array[Byte])])(implicit
      dec: JsonDecoder[TechniqueCategoryMetadata]
  ): IOResult[PolicyArchiveUnzip] = {
    parseSimpleFile(arch, cats, modifyLens[PolicyArchiveUnzip](_.policies.techniqueCats), parseTechniqueCat)
  }
  /*
   * Parse techniques.
   * The map is [techniqueBasePath -> (metadata content, list of all technique files, including metadata.xlm: (filename (including base path), content))
   */
  def parseTechniques(
      archiveName: String,
      arch:        PolicyArchiveUnzip,
      techniques:  Map[String, Chunk[(String, Array[Byte])]]
  ): UIO[PolicyArchiveUnzip] = {
    ZIO.foldLeft(techniques)(arch) {
      case (a, (basepath, files)) =>
        parseTechnique(archiveName, basepath, files).either.map {
          case Right(res) => a.modify(_.policies.techniques).using(_ :+ res)
          case Left(err)  => a.modify(_.errors).using(_ :+ Chained(s"Could not parse techniques in archive '${archiveName}'", err))
        }
    }
  }
  def parseSimpleFile[A](
      arch:     PolicyArchiveUnzip,
      elements: Chunk[(String, Array[Byte])],
      accessor: PathLazyModify[PolicyArchiveUnzip, Chunk[A]],
      parser:   (String, Array[Byte]) => IOResult[A]
  ): UIO[PolicyArchiveUnzip] = {
    ZIO.foldLeft(elements)(arch) {
      case (a, (file, content)) =>
        parser(file, content).either.map {
          case Right(res) => accessor.using(_ :+ res)(a)
          case Left(err)  =>
            a.modify(_.errors)
              .using(_ :+ Chained(s"Could not parse file '${file}' in archive '${arch.policies.metadata.filename}'", err))
        }
    }
  }
  def parseDirectives(arch: PolicyArchiveUnzip, directives: Chunk[(String, Array[Byte])])(implicit
      dec: JsonDecoder[JRDirective]
  ): UIO[PolicyArchiveUnzip] = {
    parseSimpleFile(arch, directives, modifyLens[PolicyArchiveUnzip](_.policies.directives), parseDirective)
  }
  def parseGroupCats(arch: PolicyArchiveUnzip, cats: Chunk[(String, Array[Byte])])(implicit
      dec: JsonDecoder[JGroupCategory]
  ): UIO[PolicyArchiveUnzip] = {
    parseSimpleFile(arch, cats, modifyLens[PolicyArchiveUnzip](_.policies.groupCats), parseGroupCat)
  }
  def parseGroups(arch: PolicyArchiveUnzip, groups: Chunk[(String, Array[Byte])])(implicit
      dec: JsonDecoder[JRGroup]
  ): UIO[PolicyArchiveUnzip] = {
    parseSimpleFile(arch, groups, modifyLens[PolicyArchiveUnzip](_.policies.groups), parseGroup)
  }
  def parseRuleCats(arch: PolicyArchiveUnzip, cats: Chunk[(String, Array[Byte])])(implicit
      dec: JsonDecoder[JRuleCategories]
  ): UIO[PolicyArchiveUnzip] = {
    parseSimpleFile(arch, cats, modifyLens[PolicyArchiveUnzip](_.policies.ruleCats), parseRuleCat)
  }
  def parseRules(arch: PolicyArchiveUnzip, rules: Chunk[(String, Array[Byte])])(implicit
      dec: JsonDecoder[JRRule]
  ): UIO[PolicyArchiveUnzip] = {
    parseSimpleFile(arch, rules, modifyLens[PolicyArchiveUnzip](_.policies.rules), parseRule)
  }

  def readPolicyItems(archiveName: String, zipEntries: Seq[(ZipEntry, Option[Array[Byte]])]): IOResult[PolicyArchive] = {
    // sort files in rules, directives, groups, techniques
    val sortedEntries = zipEntries.foldLeft(SortedEntries.empty) {
      case (arch, (e, optContent)) =>
        (e.getName, optContent) match {
          case (techniqueCatsRegex(_, x), Some(content)) =>
            ApplicationLoggerPure.Archive.logEffect.trace(s"Archive '${archiveName}': found technique category file ${x}")
            arch.modify(_.techniquesCats).using(_ :+ (x, content))
          case (techniqueRegex(_, x), Some(content))     =>
            ApplicationLoggerPure.Archive.logEffect.trace(s"Archive '${archiveName}': found technique file ${x}")
            arch.modify(_.techniques).using(_ :+ (x, content))
          case (directiveRegex(_, x), Some(content))     =>
            ApplicationLoggerPure.Archive.logEffect.trace(s"Archive '${archiveName}': found directive file ${x}")
            arch.modify(_.directives).using(_ :+ (x, content))
          case (groupCatsRegex(_, x), Some(content))     =>
            ApplicationLoggerPure.Archive.logEffect.trace(s"Archive '${archiveName}': found group category file ${x}")
            arch.modify(_.groupCats).using(_ :+ (x, content))
          case (groupRegex(_, x), Some(content))         =>
            ApplicationLoggerPure.Archive.logEffect.trace(s"Archive '${archiveName}': found group file ${x}")
            arch.modify(_.groups).using(_ :+ (x, content))
          case (ruleCatsRegex(_, x), Some(content))      =>
            ApplicationLoggerPure.Archive.logEffect.trace(s"Archive '${archiveName}': found rule category file ${x}")
            arch.modify(_.ruleCats).using(_ :+ (x, content))
          case (ruleRegex(_, x), Some(content))          =>
            ApplicationLoggerPure.Archive.logEffect.trace(s"Archive '${archiveName}': found rule file ${x}")
            arch.modify(_.rules).using(_ :+ (x, content))
          case (name, Some(_))                           =>
            ApplicationLoggerPure.Archive.logEffect.debug(
              s"Archive '${archiveName}': file does not matches a known category: ${name}"
            )
            arch
          case (name, None)                              =>
            ApplicationLoggerPure.Archive.logEffect.trace(s"Directory '${name}' in archive '${archiveName}': looking for entries")
            arch
        }
    }

    // techniques are more complicated: they have several files and categories so we don't know where they are at first.
    // Plus we need to know if we have a technique.yml or an old one.
    // We look for metadata.xml files, and from that we deduce a technique base path
    // create a map of technique names -> list of (filename, content)
    val techniques = sortedEntries.techniques.collect {
      case (yamlRegex(basePath), _)     => basePath
      case (jsonRegex(basePath), _)     => basePath
      case (metadataRegex(basePath), _) => basePath
    }

    // then, group by base path (cats/id/version) for technique ; then for each group, find from base path category
    // if it's a json or yml technique
    val techniqueUnzips = sortedEntries.techniques.groupBy {
      case (filename, _) =>
        techniques.find(base => filename.startsWith(base))
    }.flatMap {
      case (None, files)       => // files that are not related to a metadata file: ignore (but log)
        ApplicationLoggerPure.Archive.debug(
          s"Archive ${archiveName}: these were under 'techniques' directory but are not " +
          s"linked to a technique.yml or metadata.xml (old format) file: ${files.map(_._1).mkString(" , ")}"
        )
        None
      case (Some(base), files) =>
        Some((base, files))
    }

    // now, parse everything and collect errors
    import JsonResponseObjectDecodes.given
    import com.normation.cfclerk.domain.TechniqueCategoryMetadata.codecTechniqueCategoryMetadata
    for {
      _                 <- ApplicationLoggerPure.Archive.debug(
                             s"Processing archive '${archiveName}': techniques: '${techniqueUnzips.keys.mkString("', '")}'"
                           )
      // check for ZipSlip path traversal
      _                 <- ZIO.foreach(zipEntries) { case (e, _) => ZipUtils.checkForZipSlip(e) }
      withTechniques    <- parseTechniques(archiveName, PolicyArchiveUnzip.empty, techniqueUnzips)
      _                 <-
        ApplicationLoggerPure.Archive.debug(
          s"Processing archive '${archiveName}': technique categories: '${sortedEntries.techniquesCats.map(_._1).mkString("', '")}'"
        )
      withTechniqueCats <- parseTechniqueCats(withTechniques, sortedEntries.techniquesCats)
      _                 <- ApplicationLoggerPure.Archive.debug(
                             s"Processing archive '${archiveName}': directives: '${sortedEntries.directives.map(_._1).mkString("', '")}'"
                           )
      withDirectives    <- parseDirectives(withTechniqueCats, sortedEntries.directives)
      _                 <- ApplicationLoggerPure.Archive.debug(
                             s"Processing archive '${archiveName}': groups categories: '${sortedEntries.groupCats.map(_._1).mkString("', '")}'"
                           )
      withGroupCats     <- parseGroupCats(withDirectives, sortedEntries.groupCats)
      _                 <- ApplicationLoggerPure.Archive.debug(
                             s"Processing archive '${archiveName}': groups: '${sortedEntries.groups.map(_._1).mkString("', '")}'"
                           )
      withGroups        <- parseGroups(withGroupCats, sortedEntries.groups)
      _                 <- ApplicationLoggerPure.Archive.debug(
                             s"Processing archive '${archiveName}': rules categories: '${sortedEntries.ruleCats.map(_._1).mkString("', '")}'"
                           )
      withRuleCats      <- parseRuleCats(withGroups, sortedEntries.ruleCats)
      _                 <- ApplicationLoggerPure.Archive.debug(
                             s"Processing archive '${archiveName}': rules: '${sortedEntries.rules.map(_._1).mkString("', '")}'"
                           )
      withRules         <- parseRules(withRuleCats, sortedEntries.rules)
      // aggregate errors
      policies          <- withRules.errors.toList match {
                             case Nil       => withRules.policies.succeed
                             case h :: tail => Accumulated(NonEmptyList.of(h, tail*)).fail
                           }
    } yield policies
  }
}

/*
 * A service in charge of assessing the consistency of an archive before saving it, and trying
 * to pack problems so that an user has as much information as they can in one round.
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
    def checkTechniqueExistSameCat() = {
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

    checkTechniqueExistSameCat()
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
  def save(archive: PolicyArchive, mergePolicy: MergePolicy)(implicit qc: QueryContext): IOResult[Unit]
}

object SaveArchiveServicebyRepo {

  /*
   * Create the resource diff between the archive technique and the corresponding path
   * (assumed to be the technique base directory, ie ../category/1.0/techniqueId).
   * If file does not exists, everything is marked as "new", including the base directory.
   */
  def buildDiff(t: TechniqueArchive, techniqueBaseDirectory: File): IOResult[Chunk[ResourceFile]] = {
    for {
      // path ot 't' are relative to technique. Files we need to add as new in the end
      addedRef <- Ref.make(Chunk.fromIterable(t.files.map(_._1)))
      diffRef  <- Ref.make(Chunk[ResourceFile]())
      // get all file path (relative to technique dir)
      existing <- IOResult.attempt {
                    if (techniqueBaseDirectory.exists) {
                      techniqueBaseDirectory
                        .collectChildren(!_.isDirectory) // we don't have directories in the archive, only files
                        .to(Chunk)
                        .map(_.pathAsString.replaceFirst("\\Q" + techniqueBaseDirectory.pathAsString + "/\\E", ""))
                    } else { // technique or technique version does not exists
                      Chunk.empty
                    }
                  }
      _        <- ZIO.foreachDiscard(existing) { e =>
                    for {
                      keep <- addedRef.modify(a => {
                                if (a.contains(e)) {
                                  (true, a.filterNot(_ == e))
                                } else {
                                  (false, a)
                                }
                              })
                      _    <- diffRef.update(_ :+ ResourceFile(e, if (keep) ResourceFileState.Modified else ResourceFileState.Deleted))
                    } yield ()
                  }
      added    <- addedRef.get.map(_.map(ResourceFile(_, ResourceFileState.New)))
      updated  <- diffRef.get
    } yield added ++ updated
  }

}

/*
 * This implementation does not check for possible conflicts and just
 * overwrite existing policies if updates are available in the archive.
 */
class SaveArchiveServicebyRepo(
    techniqueArchiver:   TechniqueArchiverImpl,
    techniqueReader:     TechniqueReader,
    roDirectiveRepos:    RoDirectiveRepository,
    woDirectiveRepos:    WoDirectiveRepository,
    roGroupRepos:        RoNodeGroupRepository,
    woGroupRepos:        WoNodeGroupRepository,
    roRuleRepos:         RoRuleRepository,
    woRuleRepos:         WoRuleRepository,
    roRuleCategoryRepos: RoRuleCategoryRepository,
    woRuleCategoryRepos: WoRuleCategoryRepository,
    techLibUpdate:       UpdateTechniqueLibrary,
    asyncDeploy:         SimpleActor[AutomaticStartDeployment],
    uuidGen:             StringUuidGenerator
) extends SaveArchiveService {

  val GroupRootId = NodeGroupCategoryId("GroupRoot")

  def saveTechniqueCat(eventMetadata: EventMetadata, a: TechniqueCategoryArchive): IOResult[Unit] = {
    val catPath = a.category.toList

    ApplicationLoggerPure.Archive.debug(
      s"Adding technique category from archive: '${a.metadata.name}' (${catPath.mkString("/")}/category.xml)"
    ) *>
    techniqueArchiver
      .saveTechniqueCategory(
        catPath,
        a.metadata,
        eventMetadata.modId,
        eventMetadata.actor,
        eventMetadata.msg.getOrElse(s"Update technique category '${catPath.mkString("/")}' from archive import")
      )
      .unit
  }

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
      _    <- ApplicationLoggerPure.Archive.debug(
                s"Adding technique from archive: '${t.technique.name}' (${techniqueDir.pathAsString})"
              )
      diff <- SaveArchiveServicebyRepo.buildDiff(t, techniqueDir)
      // now, actually delete files marked so
      _    <- ApplicationLoggerPure.Archive.trace {
                val deleted = diff.collect {
                  case f if (f.state == ResourceFileState.Deleted) => f.path
                }.toList match {
                  case Nil => "none"
                  case l   => l.mkString(", ")
                }
                s"Deleting technique files for technique '${t.technique.id.serialize}': ${deleted}"
              }
      _    <- ZIO.foreachDiscard(diff) { u =>
                if (u.state == ResourceFileState.Deleted) {
                  IOResult.attempt(File(techniqueDir.pathAsString + "/" + u.path).delete()).catchSome {
                    case SystemError(_, _: NoSuchFileException) => ZIO.unit
                  }
                } else ZIO.unit
              }
      // now, write new/updated files
      _    <- ApplicationLoggerPure.Archive.trace(
                s"Writing for commit files for technique '${t.technique.id.serialize}': ${t.files.map(_._1).mkString(", ")} "
              )
      _    <- ZIO.foreachDiscard(t.files) {
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
      _    <- techniqueArchiver.saveTechnique(
                t.technique.id,
                t.category,
                diff,
                eventMetadata.modId,
                eventMetadata.actor,
                eventMetadata.msg.getOrElse(s"Committing technique '${t.technique.id.serialize}' from archive")
              )

    } yield ()
  }

  def saveDirective(eventMetadata: EventMetadata, d: DirectiveArchive):          IOResult[Unit] = {
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
  def saveGroupCat(
      eventMetadata: EventMetadata,
      cat:           GroupCategoryArchive,
      catMap:        Map[String, NodeGroupCategoryId]
  ): IOResult[Unit] = {
    for {
      _       <- ApplicationLoggerPure.Archive.debug(s"Adding group category from archive: '${cat.category.name}' (${cat.category.id})")
      /*
       * Categories are sorted by parents-first in the archive, so we are sure at that
       * point that at least all parents exists. But perhaps not that one.
       */
      id       = NodeGroupCategoryId(cat.category.id)
      exists  <- roGroupRepos.categoryExists(id)
      parentId = catMap.get(File(cat.catPath).parent.pathAsString) match {
                   case Some(p) => p
                   case None    => GroupRootId
                 }
      category = NodeGroupCategory(id, cat.category.name, cat.category.description, Nil, Nil)
      _       <- if (exists) {
                   woGroupRepos.saveGroupCategory(category, parentId, eventMetadata.modId, eventMetadata.actor, eventMetadata.msg)
                 } else {
                   woGroupRepos.addGroupCategorytoCategory(
                     category,
                     parentId,
                     eventMetadata.modId,
                     eventMetadata.actor,
                     eventMetadata.msg
                   )
                 }
    } yield ()
  }
  def saveGroup(g: GroupArchive)(implicit cc: ChangeContext):                    IOResult[Unit] = {
    import cc.*
    for {
      _ <- ApplicationLoggerPure.Archive.debug(s"Adding group from archive: '${g.group.name}' (${g.group.id.serialize})")
      // normally, at that point the category of the group exists because we took care to create them before.
      // But we used to have a bug where categories where not exported, so until Rudder 9 we need to check for that
      // if category of group doesn't exist, we need to create one using the UUID as name
      c <- roGroupRepos.categoryExists(g.category)
      _ <- ZIO.when(!c) {
             woGroupRepos.addGroupCategorytoCategory(
               NodeGroupCategory(g.category, g.category.value, "", Nil, Nil),
               GroupRootId,
               modId,
               actor,
               message
             )
           }
      // now we can check if we create a new group or update one
      x <- roGroupRepos.getNodeGroupOpt(g.group.id)(using cc.toQuery)
      _ <- x match {
             case Some((_, parentId)) =>
               woGroupRepos.update(g.group, modId, actor, message) *>
               // we need to check if the group moved
               ZIO.when(parentId != g.category) {
                 woGroupRepos.move(g.group.id, g.category)
               }

             case None =>
               woGroupRepos.create(g.group, g.category, modId, actor, message)
           }
    } yield ()
  }
  def saveRuleCategory(eventMetadata: EventMetadata, r: RuleCategoryArchive):    IOResult[Unit] = {
    roRuleCategoryRepos.getRootCategory().flatMap { root =>
      val newRoot  = r.toRuleCategory
      val parents  = r.parentCategories
      val children = r.childCategories
      def recSave(c: RuleCategoryArchive, created: Ref[Set[RuleCategoryId]]): IOResult[Unit] = {
        def needsCreate    = !root.contains(c.id) && newRoot.contains(c.id)
        val hasSameParents = root.findParents(c.id).map(_.map(_.id)) == newRoot.findParents(c.id).map(_.map(_.id))
        def alreadyExists  = (id: RuleCategoryId) => created.get.map(_.contains(id))
        if (c.id == root.id || hasSameParents) {
          ZIO.unit
        } else {
          // create starting from the greatest parent
          ZIO.foreach(parents.get(c))(recSave(_, created)) *>
          (if (needsCreate) {
             ZIO.unlessZIODiscard(alreadyExists(c.id))(
               woRuleCategoryRepos.create(
                 c.toRuleCategory,
                 parents.get(c).fold(newRoot.id)(_.id),
                 eventMetadata.modId,
                 eventMetadata.actor,
                 eventMetadata.msg
               ) *> created.update(_ + c.id) *>
               ApplicationLoggerPure.Archive.trace(
                 s"Created rule category parent from archive: '${c.name}' (${c.id.value})"
               )
             )
           } else {
             woRuleCategoryRepos.updateAndMove(
               c.toRuleCategory,
               parents.get(c).fold(newRoot.id)(_.id),
               eventMetadata.modId,
               eventMetadata.actor,
               eventMetadata.msg
             ) *> ApplicationLoggerPure.Archive.trace(
               s"Moved rule category parent from archive: '${c.name}' (${c.id.value}) to ${parents(c).id}"
             )
           })
        }
      }
      for {
        created <- Ref.make(Set.empty[RuleCategoryId])
        _       <- ZIO.foreachDiscard(children.values)(recSave(_, created))
      } yield ()
    }
  }
  def saveRule(eventMetadata: EventMetadata, mergePolicy: MergePolicy, r: Rule): IOResult[Unit] = {
    for {
      _ <- ApplicationLoggerPure.Archive.debug(s"Adding rule from archive: '${r.name}' (${r.id.serialize})")
      x <- roRuleRepos.getOpt(r.id)
      _ <- x match {
             case Some(value) =>
               // if merge policy asks for that, update rule from archive with existing groups before saving so
               // that the rules use the actual groups meaning something in that instance.
               val ruleToSave = {
                 if (mergePolicy == MergePolicy.KeepRuleTargets || mergePolicy == MergePolicy.IgnoreSourceTargets) {
                   r.copy(targets = value.targets)
                 } else r
               }
               woRuleRepos.update(ruleToSave, eventMetadata.modId, eventMetadata.actor, eventMetadata.msg)
             case None        =>
               val ruleToSave = if (mergePolicy == MergePolicy.IgnoreSourceTargets) {
                 r.copy(targets = Set())
               } else r
               woRuleRepos.create(ruleToSave, eventMetadata.modId, eventMetadata.actor, eventMetadata.msg)
           }
    } yield ()
  }

  /*
   * For rules/directives/groups, we build a change request & commit it.
   * For techniques, we just write them in fs, commit, reload tech lib.
   * Starts with techniques then other things.
   */
  override def save(archive: PolicyArchive, mergePolicy: MergePolicy)(implicit qc: QueryContext): IOResult[Unit] = {
    implicit val cc: ChangeContext = ChangeContext(
      ModificationId(uuidGen.newUuid),
      qc.actor,
      Instant.now(),
      Some(s"Importing archive '${archive.metadata.filename}'"),
      None,
      qc.nodePerms
    )
    val eventMetadata = cc.transformInto[EventMetadata]
    for {
      _ <- ZIO.foreach(archive.techniqueCats)(saveTechniqueCat(eventMetadata, _))
      _ <- ZIO.foreach(archive.techniques)(saveTechnique(eventMetadata, _))
      _ <- IOResult.attempt(techniqueReader.readTechniques)
      _ <- ZIO.foreach(archive.directives)(saveDirective(eventMetadata, _))
      // we need the map of all (categoryPath -> id) in the archive
      m  = archive.groupCats.map(c => (c.catPath, NodeGroupCategoryId(c.category.id))).toMap
      _ <- ZIO.foreach(archive.groupCats)(saveGroupCat(eventMetadata, _, m))
      _ <- ZIO.foreach(archive.groups)(saveGroup(_))
      _ <- ZIO.foreach(archive.ruleCats)(saveRuleCategory(eventMetadata, _))
      _ <- ZIO.foreach(archive.rules)(saveRule(eventMetadata, mergePolicy, _))
      // update technique lib, regenerate policies
      _ <- ZIO.when(archive.techniques.nonEmpty) {
             techLibUpdate
               .update(cc.modId, cc.actor, Some(s"Update Technique library after import of and archive"))
               .toIO
               .chainError(
                 s"An error occurred during technique update after import of archive"
               )
           }
      _ <- IOResult.attempt(asyncDeploy ! AutomaticStartDeployment(cc.modId, cc.actor))
    } yield ()
  }
}

/*
 * Decoders for JsonResponse objects used in Archive API :
 * structures that need to be read back as they serialized AND deserialized in this API
 */
private object JsonResponseObjectDecodes extends RudderJsonDecoders {
  import com.normation.rudder.apidata.JsonResponseObjects.*

  given decodeJRParentProperty:          JsonDecoder[JRParentProperty]          = DeriveJsonDecoder.gen
  given decodeJRPropertyHierarchy:       JsonDecoder[JRPropertyHierarchy]       = DeriveJsonDecoder.gen
  given decodePropertyProvider:          JsonDecoder[PropertyProvider]          = JsonDecoder.string.map(s => PropertyProvider(s))
  given decodeJRParentPropertyDetails:   JsonDecoder[JRParentPropertyDetails]   = DeriveJsonDecoder.gen
  given decodeJRPropertyHierarchyStatus: JsonDecoder[JRPropertyHierarchyStatus] = DeriveJsonDecoder.gen
  given decodeJRProperty:                JsonDecoder[JRProperty]                = DeriveJsonDecoder.gen

  given decodeJRCriterium:           JsonDecoder[JRCriterium]           = DeriveJsonDecoder.gen
  given decodeJRDirectiveSectionVar: JsonDecoder[JRDirectiveSectionVar] = DeriveJsonDecoder.gen

  given decodeJRApplicationStatus: JsonDecoder[JRApplicationStatus] = DeriveJsonDecoder.gen
  given decodeJRQuery:             JsonDecoder[JRQuery]             = DeriveJsonDecoder.gen
  given decodeJRDirectiveSection:  JsonDecoder[JRDirectiveSection]  = DeriveJsonDecoder.gen
  given decodeJRRule:              JsonDecoder[JRRule]              = DeriveJsonDecoder.gen
  given decodeJRGroup:             JsonDecoder[JRGroup]             = DeriveJsonDecoder.gen
  given decodeJRDirective:         JsonDecoder[JRDirective]         = DeriveJsonDecoder.gen

}
