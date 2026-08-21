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

package com.normation.rudder.repository.xml

import com.normation.GitVersion
import com.normation.GitVersion.Revision
import com.normation.errors.*
import com.normation.eventlog.*
import com.normation.inventory.domain.Version
import com.normation.rudder.batch.AsyncDeploymentActor
import com.normation.rudder.batch.AutomaticStartDeployment
import com.normation.rudder.domain.Constants.*
import com.normation.rudder.domain.eventlog.*
import com.normation.rudder.domain.logger.GitArchiveLoggerPure
import com.normation.rudder.domain.nodes.*
import com.normation.rudder.domain.policies.*
import com.normation.rudder.git.*
import com.normation.rudder.ncf.{DeleteEditorTechnique as _, *}
import com.normation.rudder.ncf.eventlogs.*
import com.normation.rudder.ncf.yaml.YamlTechniqueSerializer
import com.normation.rudder.repository.*
import com.normation.rudder.tenants.*
import com.normation.rudder.tenants.ChangeContext.toQC
import com.softwaremill.quicklens.*
import java.nio.charset.StandardCharsets
import org.apache.commons.io.IOUtils
import org.eclipse.jgit.lib.PersonIdent
import zio.*
import zio.syntax.*

/**
 * Rollback of one configuration item at a time.
 *
 * Contrary to the whole archive restore of `ItemArchiveManagerImpl`, the previous state of the item is
 * read from the archive at the wanted commit and written back through the standard repositories, under
 * the change context - hence the tenant grant - of the user asking for the rollback.
 *
 * The git repository is shared with the archive operations, so the same `ArchiveLock` is used: an item
 * rollback and an archive import must not run at the same time.
 */
class ItemRollbackRepositoryImpl(
    roRuleRepository:            RoRuleRepository,
    woRuleRepository:            WoRuleRepository,
    woDirectiveRepository:       WoDirectiveRepository,
    roGroupRepository:           RoNodeGroupRepository,
    woGroupRepository:           WoNodeGroupRepository,
    roParameterRepository:       RoParameterRepository,
    woParameterRepository:       WoParameterRepository,
    gitRepo:                     GitRepositoryProvider,
    parseRules:                  ParseRules,
    parseActiveTechniqueLibrary: ParseActiveTechniqueLibrary,
    parseGlobalParameters:       ParseGlobalParameters,
    parseGroupLibrary:           ParseGroupLibrary,
    eventLogger:                 EventLogRepository,
    asyncDeploymentAgent:        AsyncDeploymentActor,
    techniqueWriter:             TechniqueWriter,
    yamlTechniqueSerializer:     YamlTechniqueSerializer,
    // shared with the archive import/rollback: both rewrite the same git repository
    importSemaphore:             Semaphore
) extends ItemRollbackRepository {

  // rollback must be exclusive with the archive import/rollback, so if one is already running, it's an error.
  private def useSemaphoreOrFail[A](effect: IOResult[A]): IOResult[A] = {
    // we timeout the semaphore acquisition to fail if another op is already running
    ZIO.scoped(
      importSemaphore.withPermitScoped
        .timeout(1.second)
        .flatMap(isOK => {
          if (isOK.isDefined) {
            effect
          } else {
            Inconsistency(
              "An other operation of import or rollback is already running. You should check its result before doing another one. Please retry latter."
            ).fail
          }
        })
    )
  }

  /**
   * Rollback the items (directives, groups, global parameters, rules, techniques) corresponding to given event logs
   * to their version at given commit: `archiveId` is already the commit to which we want to restore the item.
   *
   * Rollback must ensure that the git repo is consistent even for a single item, so use semaphore
   */
  override def rollbackItem(
      archiveId:        GitCommitId,
      commiter:         PersonIdent,
      rollbackedEvents: Seq[EventLog],
      target:           EventLog
  )(implicit cc: ChangeContext): IOResult[GitCommitId] = {
    import cc.*
    for {
      _ <- GitArchiveLoggerPure.info(s"Rolling back item to their state in commit '${archiveId.value}'")
      _ <- ZIO.foreachDiscard(rollbackedEvents)(ev => useSemaphoreOrFail(rollbackOneItem(archiveId, ev)))
      _ <- eventLogger.saveEventLog(modId, new Rollback(actor, rollbackedEvents, target, "item", message))
    } yield {
      asyncDeploymentAgent ! AutomaticStartDeployment(modId, actor)
      archiveId
    }
  }

  /*
   * Rolling back always needs item ID in repos, we can find it in the event log details, at specific XML selector
   *
   * Logic of reverting change, depends on event log type:
   * - reverting an addition is just a deletion
   * - reverting a deletion or modification is a restore of the item as it is
   *   - archive needs to be observed e.g. for category
   *   - and
   */
  private[xml] def rollbackOneItem(archiveId: GitCommitId, event: EventLog)(implicit cc: ChangeContext): IOResult[Unit] = {
    event match {
      case e: DirectiveEventLog =>
        for {
          sid <- rolledBackItemId(e, XML_TAG_DIRECTIVE)
          id  <- DirectiveId.parse(sid).toIO
          _   <- e match {
                   case _: AddDirective => woDirectiveRepository.delete(id.uid).unit
                   case _: DeleteDirective | _: ModifyDirective => restoreDirective(archiveId, id.uid)
                 }
        } yield ()

      case e: NodeGroupEventLog =>
        for {
          sid <- rolledBackItemId(e, XML_TAG_NODE_GROUP)
          id  <- NodeGroupId.parse(sid).toIO
          _   <- e match {
                   case _: AddNodeGroup => woGroupRepository.delete(id).unit
                   case _: DeleteNodeGroup | _: ModifyNodeGroup => restoreNodeGroup(archiveId, id)
                 }
        } yield ()

      case e: ParameterEventLog =>
        for {
          name <- rolledBackItemId(e, XML_TAG_GLOBAL_PARAMETER, idTag = "name")
          _    <- e match {
                    case _: AddGlobalParameter => deleteParameter(name)
                    case _: DeleteGlobalParameter | _: ModifyGlobalParameter => restoreParameter(archiveId, name)
                  }
        } yield ()

      case e: RuleEventLog =>
        for {
          sid <- rolledBackItemId(e, XML_TAG_RULE)
          // rules are archived at their default revision, drop any revision the event log may carry
          id  <- RuleId.parse(sid).map(r => RuleId(r.uid)).toIO
          _   <- e match {
                   case _: AddRule => woRuleRepository.delete(id).unit
                   case _: DeleteRule | _: ModifyRule => restoreRule(archiveId, id)
                 }
        } yield ()

      case e: EditorTechniqueEventLog =>
        for {
          id      <- rolledBackItemId(e, XML_TAG_EDITOR_TECHNIQUE, idTag = "id")
          version <- rolledBackItemId(e, XML_TAG_EDITOR_TECHNIQUE, idTag = "version")
          _       <- e match {
                       // deleteDirective = false: a rollback must stay scoped to that one item, so we don't
                       // cascade to the directives using the technique. Deletion fails if there are any.
                       case _: AddEditorTechnique => techniqueWriter.deleteTechnique(id, version, deleteDirective = false)
                       case _: DeleteEditorTechnique | _: ModifyEditorTechnique =>
                         restoreEditorTechnique(archiveId, BundleName(id), Version(version))
                     }
        } yield ()

      case _ =>
        GitArchiveLoggerPure.warn(
          s"Item rollback is not supported for event type '${event.eventType.serialize}', that event is ignored"
        )
    }
  }

  /*
   * All item event log details are of the form `<entry><directive ...><id>xxxx</id>...`, whatever
   * the change type: get back the identifier of the item the event is about.
   */
  private def rolledBackItemId(event: EventLog, itemTag: String, idTag: String = "id"): IOResult[String] = {
    (event.details \ itemTag \ idTag).headOption
      .map(_.text.trim)
      .notOptional(s"Missing <${idTag}> in the <${itemTag}> details of event log '${event.id.getOrElse("")}'")
  }

  private def restoreDirective(archiveId: GitCommitId, uid: DirectiveUid)(implicit cc: ChangeContext): IOResult[Unit] = {
    val rev = Revision(archiveId.value)
    for {
      (activeTechnique, directive) <-
        parseActiveTechniqueLibrary
          .getDirectiveRevision(uid, rev)
          .notOptional(
            s"Directive '${uid.value}' was not found in the archive for commit '${archiveId.value}', it can not be restored"
          )
      // the technique version in the archive should be kept as is
      restoredDirective             = directive
                                        .modify(_.id.rev)
                                        .setTo(GitVersion.DEFAULT_REV)
                                        .modify(_.techniqueVersion)
                                        .using(v => if (v.rev == rev) v.withRevision(GitVersion.DEFAULT_REV) else v)
      _                            <- woDirectiveRepository.saveDirective(activeTechnique.id, restoredDirective)
    } yield ()
  }

  private def restoreNodeGroup(archiveId: GitCommitId, id: NodeGroupId)(implicit cc: ChangeContext): IOResult[Unit] = {
    implicit val qc: QueryContext = cc.toQC
    for {
      group        <- parseGroupLibrary
                        .getGroupRevision(id.uid, Revision(archiveId.value))
                        .notOptional(
                          s"Group '${id.serialize}' was not found in the archive for commit '${archiveId.value}', it can not be restored"
                        )
      // same as for directives: the group must be restored in place, not as a copy frozen at the
      // revision we looked it up at
      restoredGroup = group.group.modify(_.id.rev).setTo(GitVersion.DEFAULT_REV)
      existing     <- roGroupRepository.getNodeGroupOpt(id)
      _            <- existing match {
                        case Some(_) => woGroupRepository.update(restoredGroup).unit
                        case None    => woGroupRepository.create(restoredGroup, group.categoryId).unit
                      }
    } yield ()
  }

  private def restoreParameter(archiveId: GitCommitId, name: String)(implicit cc: ChangeContext): IOResult[Unit] = {
    implicit val qc: QueryContext = cc.toQC
    for {
      archive  <- parseGlobalParameters.getArchive(archiveId)
      param    <-
        archive
          .find(_.name == name)
          .notOptional(
            s"Global parameter '${name}' was not found in the archive for commit '${archiveId.value}', it can not be restored"
          )
      existing <- roParameterRepository.getGlobalParameter(name)
      _        <- existing match {
                    case Some(_) => woParameterRepository.updateParameter(param).unit
                    case None    => woParameterRepository.saveParameter(param).unit
                  }
    } yield ()
  }

  /*
   * Deleting a parameter needs its provider, which is only known from the currently stored one.
   */
  private def deleteParameter(name: String)(implicit cc: ChangeContext): IOResult[Unit] = {
    implicit val qc: QueryContext = cc.toQC
    roParameterRepository.getGlobalParameter(name).flatMap {
      case Some(param) => woParameterRepository.delete(name, param.provider).unit
      case None        => ZIO.unit
    }
  }

  private def restoreRule(archiveId: GitCommitId, id: RuleId)(implicit cc: ChangeContext): IOResult[Unit] = {
    implicit val qc: QueryContext = cc.toQC
    for {
      rule        <- parseRules
                       .getRuleRevision(id.uid, Revision(archiveId.value))
                       .notOptional(
                         s"Rule '${id.serialize}' was not found in the archive for commit '${archiveId.value}', it can not be restored"
                       )
      // same as for directives: the rule must be restored in place, not as a copy frozen at the
      // revision we looked it up at
      restoredRule = rule.modify(_.id.rev).setTo(GitVersion.DEFAULT_REV)
      existing    <- roRuleRepository.getOpt(id)
      _           <- existing match {
                       case Some(_) => woRuleRepository.update(restoredRule).unit
                       case None    => woRuleRepository.create(restoredRule).unit
                     }
    } yield ()
  }

  /*
   * Techniques are restored with the `technique.yml` main file: read back that file at the
   * wanted commit and let the technique writer recompile and update
   */
  private def restoreEditorTechnique(archiveId: GitCommitId, id: BundleName, version: Version)(implicit
      cc: ChangeContext
  ): IOResult[Unit] = {
    val yamlPathEnd   = s"/${id.value}/${version.value}/${TechniqueFiles.yaml}"
    // editor techniques are archived under that directory of the config repo, see GitTechniqueArchiverImpl
    val techniquesDir = "techniques"

    for {
      treeId        <- GitFindUtils.findRevTreeFromRevString(gitRepo.db, archiveId.value)
      gitPaths      <- GitFindUtils.listFiles(gitRepo.db, treeId, List(techniquesDir), List(yamlPathEnd))
      gitPath       <- gitPaths.toList match {
                         case p :: Nil => p.succeed

                         case Nil =>
                           Inconsistency(
                             s"Technique '${id.value}/${version.value}' was not found in the archive for commit " +
                             s"'${archiveId.value}', it can not be restored"
                           ).fail

                         case several =>
                           Inconsistency(
                             s"There is more than one technique '${id.value}/${version.value}' in the archive for commit " +
                             s"'${archiveId.value}', it can not be restored: ${several.mkString(", ")}"
                           ).fail

                       }
      techniquePath <- EditorTechniquePath(better.files.File(gitPath)).notOptional(
                         s"'${gitPath}' is not a conventional technique path, it must be of the form " +
                         s"'.../{category}/{techniqueId}/{techniqueVersion}/${TechniqueFiles.yaml}'"
                       )
      content       <- GitFindUtils.getFileContent(gitRepo.db, treeId, gitPath) { is =>
                         IOResult.attempt(s"Error when reading '${gitPath}' in commit '${archiveId.value}'")(
                           IOUtils.toString(is, StandardCharsets.UTF_8)
                         )
                       }
      parsed        <- yamlTechniqueSerializer.yamlToEditorTechnique(content)
      technique     <- ZIO.fromEither(parsed.left.map(EditorTechniqueParsingError(techniquePath, content, _)))
      _             <- techniqueWriter.writeTechniqueAndUpdateLib(technique)
    } yield ()
  }
}
