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

import com.normation.cfclerk.domain.Technique
import com.normation.cfclerk.domain.TechniqueId
import com.normation.rudder.api.ApiVersion
import com.normation.rudder.apidata.JsonResponseObjects.JRDirective
import com.normation.rudder.apidata.JsonResponseObjects.JRGroup
import com.normation.rudder.apidata.JsonResponseObjects.JRRule
import com.normation.rudder.apidata.implicits._
import com.normation.rudder.configuration.ConfigurationRepository
import com.normation.rudder.domain.appconfig.FeatureSwitch
import com.normation.rudder.domain.logger.ApplicationLogger
import com.normation.rudder.domain.logger.ApplicationLoggerPure
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.RuleTarget
import com.normation.rudder.git.ZipUtils
import com.normation.rudder.git.ZipUtils.Zippable
import com.normation.rudder.repository.xml.TechniqueRevisionRepository
import com.normation.rudder.rest.ApiPath
import com.normation.rudder.rest.AuthzToken
import com.normation.rudder.rest.RudderJsonResponse
import com.normation.rudder.rest.RudderJsonResponse.ResponseSchema
import com.normation.rudder.rest.implicits._
import com.normation.rudder.rest.lift.DummyImportAnswer._
import com.normation.rudder.rest.{ArchiveApi => API}

import net.liftweb.http.LiftResponse
import net.liftweb.http.OutputStreamResponse
import net.liftweb.http.Req

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.text.Normalizer

import zio._
import zio.json._
import zio.syntax._
import com.normation.errors._
import com.normation.zio._

/*
 * Machinery to enable/disable the API given the value of the feature switch in config service.
 * If disabled, always return an error with the info about how to enable it.
 */
final case class FeatureSwitch0[A <: LiftApiModule0](enable: A, disable: A)(featureSwitchState: IOResult[FeatureSwitch]) extends LiftApiModule0 {
  override val schema = enable.schema
  override def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
    featureSwitchState.either.runNow match {
      case Left(err) =>
        ApplicationLogger.error(err.fullMsg)
        RudderJsonResponse.internalError(ResponseSchema.fromSchema(schema), err.fullMsg)(params.prettify).toResponse
      case Right(FeatureSwitch.Disabled) =>
        disable.process0(version, path, req, params, authzToken)
      case Right(FeatureSwitch.Enabled) =>
        enable.process0(version, path, req, params, authzToken)
    }
  }
}

sealed trait ArchiveScope { def value: String }
object ArchiveScope {

  // using nodep/alldep to avoid confusion with "none" in scala code
  final case object AllDep     extends ArchiveScope { val value = "all"        }
  final case object NoDep      extends ArchiveScope { val value = "none"       }
  final case object Directives extends ArchiveScope { val value = "directives" }
  final case object Techniques extends ArchiveScope { val value = "techniques" }
  final case object Groups     extends ArchiveScope { val value = "groups"     }

  def values =  ca.mrvisser.sealerate.values[ArchiveScope].toList.sortBy( _.value )
  def parse(s: String): Either[String, ArchiveScope] = {
    values.find( _.value == s.toLowerCase.strip() ) match {
      case None    => Left(s"Error: can not parse '${s}' as a scope for dependency resolution in archive. Accepted values are: ${values.mkString(", ")}")
      case Some(x) => Right(x)
    }
  }
}


class ArchiveApi(
    archiveBuilderService: ZipArchiveBuilderService
  , featureSwitchState   : IOResult[FeatureSwitch]
  , getArchiveName       : IOResult[String]
) extends LiftApiModuleProvider[API] {



  def schemas = API

  def getLiftEndpoints(): List[LiftApiModule] = {
    API.endpoints.map(e => e match {
      case API.Import       => FeatureSwitch0(Import, ImportDisabled)(featureSwitchState)
      case API.ExportSimple => FeatureSwitch0(ExportSimple, ExportSimpleDisabled)(featureSwitchState)
    })
  }

  /*
   * Default answer to use when the feature is disabled
   */
  trait ApiDisabled extends LiftApiModule0 {

    override def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      RudderJsonResponse.internalError(
          ResponseSchema.fromSchema(schema)
        , """This API is disabled. It is in beta version and no compatibility is ensured. You can enable it with """ +
          """the setting `rudder_featureSwitch_archiveApi` in settings API set to `{"value":"enabled"}`"""
      )(params.prettify).toResponse
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
      def splitArg(req: Req, name: String): List[String] = req.params.getOrElse(name, Nil).flatMap(seq => seq.split(',').toList.map(_.strip()))
      def parseRuleIds(req: Req): IOResult[List[RuleId]] = {

        ZIO.foreach(splitArg(req, "rules"))(RuleId.parse(_).toIO)
      }
      def parseDirectiveIds(req: Req): IOResult[List[DirectiveId]] = {
        ZIO.foreach(splitArg(req, "directives"))(DirectiveId.parse(_).toIO)
      }
      def parseTechniqueIds(req: Req): IOResult[List[TechniqueId]] = {
        ZIO.foreach(splitArg(req, "techniques"))(TechniqueId.parse(_).toIO)
      }
      def parseGroupIds(req: Req): IOResult[List[NodeGroupId]] = {
        ZIO.foreach(splitArg(req, "groups"))(NodeGroupId.parse(_).toIO)
      }
      def parseScopes(req: Req): IOResult[List[ArchiveScope]] = {
        splitArg(req, "include") match {
          case Nil => List(ArchiveScope.AllDep).succeed
          case seq => ZIO.foreach(seq)(ArchiveScope.parse(_).toIO)
        }
      }

      // lift is not well suited for ZIO...
      val rootDirName = getArchiveName.runNow

      //do zip
      val zippables = for {
        _             <- ApplicationLoggerPure.Archive.debug(s"Building archive")
        ruleIds       <- parseRuleIds(req)
        groupIds      <- parseGroupIds(req)
        directiveIds  <- parseDirectiveIds(req)
        techniquesIds <- parseTechniqueIds(req)
        scopes        <- parseScopes(req)
        _             <- ApplicationLoggerPure.Archive.debug(s"Archive requested for rules: [${ruleIds.map(_.serialize).mkString(", ")}], " +
                                                             s"directives: [${directiveIds.map(_.serialize).mkString(", ")}], " +
                                                             s"groups: [${groupIds.map(_.serialize).mkString(", ")}], " +
                                                             s"techniques: [${techniquesIds.map(_.serialize).mkString(", ")}], " +
                                                             s"scope: [${scopes.map(_.value).mkString(", ")}]")
        zippables     <- archiveBuilderService.buildArchive(rootDirName, techniquesIds, directiveIds, groupIds, ruleIds, scopes.toSet)
      } yield {
        zippables
      }

      val headers = List(
          ("Pragma", "public")
        , ("Expires", "0")
        , ("Cache-Control", "must-revalidate, post-check=0, pre-check=0")
        , ("Cache-Control", "public")
        , ("Content-Description", "File Transfer")
        , ("Content-type", "application/octet-stream")
        , ("Content-Disposition", s"""attachment; filename="${rootDirName}.zip"""")
        , ("Content-Transfer-Encoding", "binary")
      )
      val send =  (os: OutputStream) => zippables.flatMap { z =>
        ZipUtils.zip(os, z)
      }.catchAll(err =>
        ApplicationLoggerPure.Archive.error(s"Error when building zip archive: ${err.fullMsg}")
      ).runNow
      new OutputStreamResponse(send, -1, headers, Nil, 200)
    }
  }

  object ImportDisabled extends LiftApiModule0 with ApiDisabled { override val schema = API.Import }

  object Import extends LiftApiModule0 {
    val schema = API.Import
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      val res: IOResult[JRArchiveImported] = JRArchiveImported(true).succeed
      res.toLiftResponseOne(params, schema, _ => None)
    }
  }
}

/*
 * A dummy object waiting for implementation for import
 */
object DummyImportAnswer {

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
    Normalizer.normalize(name, Normalizer.Form.NFKD)
      .replaceAll("""[^\p{Alnum}-]""", "_").take(maxSize)
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
    fileArchiveNameService: FileArchiveNameService
  , configRepo            : ConfigurationRepository
  , techniqueRevisionRepo : TechniqueRevisionRepository
) {


  // names of directories under the root directory of the archive
  val RULES_DIR = "rules"
  val GROUPS_DIR = "groups"
  val DIRECTIVES_DIR = "directives"
  val TECHNIQUES_DIR = "techniques"

  /*
   * get the content of the JSON string in the format expected by Zippable
   */
  def getJsonZippableContent(json: String): (InputStream => IOResult[Any]) => IOResult[Any] = {
    (use: InputStream => IOResult[Any]) =>
    IOResult.effect(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))).bracket(is => effectUioUnit(is.close)) { is =>
      use(is)
    }
  }


  /*
   * function that will find the next available name from the given string,
   * looking in pool to check for availability (and updating said pool).
   * Extension is an extension that should not be normalized.
   */
  def findName(origName: String, extension: String, usedNames: Ref[Map[String, Set[String]]], category: String): IOResult[String] = {
    def findRecName(s: Set[String], base: String, i: Int): String = {
      val n = base+"_"+i
      if(s.contains(n)) findRecName(s, base, i+1) else n
    }
    val name = fileArchiveNameService.toFileName(origName)+extension

    // find a free name, avoiding overwriting a previous similar one
    usedNames.modify(m => {
      val realName = if( m(category).contains(name) ) {
        findRecName(m(category), name, 1)
      } else name
      ( realName, m + ((category, m(category)+realName))  )
    } )
  }

  /*
   * Retrieve the technique using first the cache, then the config service, and update the
   * cache accordingly
   */
  def getTechnique(techniqueId: TechniqueId, techniques: RefM[Map[TechniqueId, Technique]]): IOResult[Technique] = {
    techniques.modify(cache => cache.get(techniqueId) match {
     case None =>
       for {
         t <- configRepo.getTechnique(techniqueId).notOptional(s"Technique with id ${techniqueId.serialize} was not found in Rudder")
         c =  cache + ((t.id, t))
       } yield (t, c)
     case Some(t) =>
       (t, cache).succeed
   })
  }

  /*
   * Getting technique zippable is more complex than other items because we can have a lot of
   * files. The strategy used is to always copy ALL files for the given technique
   * TechniquesDir is the path where techniques are stored, ie for technique "user/1.0", we have:
   * techniquesDir / user/1.0/ other techniques file
   */
  def getTechniqueZippable(techniquesDir: String, techniqueId: TechniqueId): IOResult[Seq[Zippable]] = {
    techniqueRevisionRepo.getTechnique(techniqueId.name, techniqueId.version.version, techniqueId.version.rev)
    for {
      contents <- techniqueRevisionRepo.getTechniqueFileContents(techniqueId).notOptional(s"Technique with ID '${techniqueId.serialize}' was not found in repository. Please check name and revision.")
    } yield {
      // we need to change root of zippable, we want techniques/myTechnique/1.0/[HERE]
      val basePath = techniquesDir+"/" + techniqueId.withDefaultRev.serialize + "/"
      contents.map { case (p, opt) => Zippable(basePath + p, opt.map(_.use))}
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
      rootDirName: String
    , techniqueIds: Seq[TechniqueId]
    , directiveIds: Seq[DirectiveId]
    , groupIds: Seq[NodeGroupId]
    , ruleIds: Seq[RuleId]
    , scopes: Set[ArchiveScope]
  ): IOResult[Chunk[Zippable]] = {
    // normalize to no slash at end
    val root = rootDirName.strip().replaceAll("""/$""", "")
    import ArchiveScope._
    val includeDepTechniques = scopes.intersect(Set(AllDep, Techniques)).nonEmpty
    // scope is transitive, techniques implies directives
    val includeDepDirectives = includeDepTechniques || scopes.intersect(Set(AllDep, Directives)).nonEmpty
    val includeDepGroups = scopes.intersect(Set(AllDep, Groups)).nonEmpty

    // rule is easy and independent from other

    for {
      // for each kind, we need to keep trace of existing names to avoid overwriting
      usedNames        <- Ref.make(Map.empty[String, Set[String]])
      // dependency id to add at lower level
      depDirectiveIds  <- Ref.make(List.empty[DirectiveId])
      depGroupIds      <- Ref.make(List.empty[NodeGroupId])
      _                <- ApplicationLoggerPure.Archive.debug(s"Building archive for rules: ${ruleIds.map(_.serialize).mkString(", ")}")
      rootZip          <- Zippable(rootDirName, None).succeed
      rulesDir         =  root + "/" + RULES_DIR
      _                <- usedNames.update( _ + ((RULES_DIR, Set.empty[String])))
      rulesDirZip      =  Zippable(rulesDir, None)
      rulesZip         <- ZIO.foreach(ruleIds) { ruleId =>
                            configRepo.getRule(ruleId).notOptional(s"Rule with id ${ruleId.serialize} was not found in Rudder").flatMap(rule =>
                              if(rule.isSystem) None.succeed else {
                                for {
                                  _ <- depDirectiveIds.update(x => x ++ rule.directiveIds)
                                  _ <- depGroupIds.update(x => x ++ RuleTarget.getNodeGroupIds(rule.targets))
                                  json = JRRule.fromRule(rule, None, None, None).toJsonPretty
                                  name <- findName(rule.name, ".json", usedNames, RULES_DIR)
                                } yield {
                                  Some(Zippable(rulesDir + "/" + name, Some(getJsonZippableContent(json))))
                                }
                              }
                            )
                          }.map(_.flatten)
      groupsDir        =  root + "/" + GROUPS_DIR
      _                <- usedNames.update( _ + ((GROUPS_DIR, Set.empty[String])))
      groupsDirZip     =  Zippable(groupsDir, None)
      depGroups        <- if(includeDepGroups) depGroupIds.get else Nil.succeed
      groupsZip        <- ZIO.foreach(groupIds ++ depGroups) { groupId =>
                            configRepo.getGroup(groupId).notOptional(s"Group with id ${groupId.serialize} was not found in Rudder").flatMap(gc =>
                              if(gc.group.isSystem) None.succeed else {
                                val json =  JRGroup.fromGroup(gc.group, gc.categoryId, None).toJsonPretty
                                for {
                                  name <- findName(gc.group.name, ".json", usedNames, GROUPS_DIR)
                                } yield Some(Zippable(groupsDir + "/" + name, Some(getJsonZippableContent(json))))
                              }
                            )
                          }.map(_.flatten)
      // directives need access to technique, but we don't want to look up several time the same one
      techniques       <- RefM.make(Map.empty[TechniqueId, Technique])

      directivesDir    =  root + "/" + DIRECTIVES_DIR
      _                <- usedNames.update( _ + ((DIRECTIVES_DIR, Set.empty[String])))
      directivesDirZip =  Zippable(directivesDir, None)
      depDirectives    <- if(includeDepDirectives) depDirectiveIds.get else Nil.succeed
      directivesZip    <- ZIO.foreach(directiveIds ++ depDirectives) { directiveId =>
                            configRepo.getDirective(directiveId).notOptional(s"Directive with id ${directiveId.serialize} was not found in Rudder").flatMap(ad =>
                              if(ad.directive.isSystem) None.succeed else {
                                for {
                                  tech <- getTechnique(TechniqueId(ad.activeTechnique.techniqueName, ad.directive.techniqueVersion), techniques)
                                  json =  JRDirective.fromDirective(tech, ad.directive, None).toJsonPretty
                                  name <- findName(ad.directive.name, ".json", usedNames, DIRECTIVES_DIR)
                                } yield Some(Zippable(directivesDir + "/" + name, Some(getJsonZippableContent(json))))
                              }
                            )
                          }.map(_.flatten)
      // Techniques don't need name normalization, their name is already normalized
      techniquesDir    =  root + "/" + TECHNIQUES_DIR
      techniquesDirZip =  Zippable(techniquesDir, None)
      depTechniques    <- if(includeDepTechniques) techniques.get.map(_.keys) else Nil.succeed
      allTech          <- ZIO.foreach(techniqueIds ++ depTechniques) { techniqueId => getTechnique(techniqueId, techniques) }
      techniquesZip    <- ZIO.foreach(allTech.filter(_.isSystem == false)) { technique =>
                            for {
                              techZips  <- getTechniqueZippable(techniquesDir, technique.id)
                            } yield techZips
                          }
    } yield {
      Chunk(rootZip, rulesDirZip, groupsDirZip, directivesDirZip, techniquesDirZip) ++ rulesZip ++ techniquesZip.flatten ++ directivesZip ++ groupsZip
    }
  }

}


