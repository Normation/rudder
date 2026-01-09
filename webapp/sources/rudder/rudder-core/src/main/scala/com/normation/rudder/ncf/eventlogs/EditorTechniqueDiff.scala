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

package com.normation.rudder.ncf.eventlogs

import com.normation.cfclerk.domain.ReportingLogic
import com.normation.inventory.domain.Version
import com.normation.rudder.domain.policies.PolicyMode
import com.normation.rudder.domain.policies.SimpleDiff
import com.normation.rudder.domain.policies.TriggerDeploymentDiff
import com.normation.rudder.ncf.*
import zio.json.ast.Json

/**
 * That file define "diff" objects between EditorTechniques.
 */

sealed trait EditorTechniqueDiff extends TriggerDeploymentDiff

//for change request, with add type tag to EditorTechniqueDiff
sealed trait ChangeRequestEditorTechniqueDiff {
  def editorTechnique: EditorTechnique
}

final case class DeleteEditorTechniqueDiff(
    editorTechnique: EditorTechnique
) extends EditorTechniqueDiff with ChangeRequestEditorTechniqueDiff {
  def needDeployment: Boolean = true
}

// add and modify are put together
sealed trait EditorTechniqueSaveDiff extends EditorTechniqueDiff

final case class AddEditorTechniqueDiff(
    editorTechnique: EditorTechnique
) extends EditorTechniqueSaveDiff with ChangeRequestEditorTechniqueDiff {
  def needDeployment: Boolean = false
}

final case class ModifyEditorTechniqueDiff(
    techniqueId:      BundleName,
    version:          Version,
    name:             String, // keep the name around to be able to display it as it was at that time
    modName:          Option[SimpleDiff[String]],
    modCategory:      Option[SimpleDiff[String]],
    modDescription:   Option[SimpleDiff[String]],
    modDocumentation: Option[SimpleDiff[String]],
    modCalls:         List[TechniqueBlockDiff | TechniqueCallDiff],
    modParameters:    Seq[TechniqueParameterDiff],
    modResources:     Option[SimpleDiff[Seq[ResourceFile]]],
    modTags:          Option[SimpleDiff[Map[String, Json]]]
) extends EditorTechniqueSaveDiff {
  def needDeployment: Boolean = {
    modParameters.nonEmpty || modCalls.nonEmpty || modResources.isDefined
  }
}

sealed trait TechniqueBlockDiff
final case class AddTechniqueBlockDiff(methodBlock: MethodBlock)    extends TechniqueBlockDiff
final case class DeleteTechniqueBlockDiff(methodBlock: MethodBlock) extends TechniqueBlockDiff
final case class ModifyTechniqueBlockDiff(
    id:                 String,
    component:          String,
    modComponent:       Option[SimpleDiff[String]],
    modCondition:       Option[SimpleDiff[String]],
    modeReportingLogic: Option[SimpleDiff[ReportingLogic]],
    modCalls:           List[TechniqueBlockDiff | TechniqueCallDiff],
    modPolicyMode:      Option[SimpleDiff[Option[PolicyMode]]],
    modForeach:         Option[SimpleDiff[Option[List[Map[String, String]]]]],
    modForeachName:     Option[SimpleDiff[Option[String]]]
) extends TechniqueBlockDiff {
  def needDeployment: Boolean = {
    modCalls.nonEmpty || modPolicyMode.isDefined || modeReportingLogic.isDefined
  }
}

object ModifyTechniqueBlockDiff {
  def apply(old: MethodBlock, update: MethodBlock): ModifyTechniqueBlockDiff = {
    ModifyTechniqueBlockDiff(
      old.id,
      old.component,
      SimpleDiff.createDiff(old, update)(_.component),
      SimpleDiff.createDiff(old, update)(_.condition),
      SimpleDiff.createDiff(old, update)(_.reportingLogic),
      TechniqueElemDiff.buildParametersDiff(old.calls, update.calls),
      SimpleDiff.createDiff(old, update)(_.policyMode),
      SimpleDiff.createDiff(old, update)(_.foreach),
      SimpleDiff.createDiff(old, update)(_.foreachName)
    )
  }

}

sealed trait TechniqueCallDiff
final case class AddTechniqueCallDiff(methodBlock: MethodCall)    extends TechniqueCallDiff
final case class DeleteTechniqueCallDiff(methodBlock: MethodCall) extends TechniqueCallDiff
final case class ModifyTechniqueCallDiff(
    method:               BundleName,
    id:                   String,
    component:            String,
    modComponent:         Option[SimpleDiff[String]],
    modCondition:         Option[SimpleDiff[String]],
    modeDisableReporting: Option[SimpleDiff[Boolean]],
    modParameters:        Option[SimpleDiff[Map[ParameterId, String]]],
    modPolicyMode:        Option[SimpleDiff[Option[PolicyMode]]],
    modForeach:           Option[SimpleDiff[Option[List[Map[String, String]]]]],
    modForeachName:       Option[SimpleDiff[Option[String]]]
) extends TechniqueCallDiff {
  def needDeployment: Boolean = {
    modParameters.isDefined || modPolicyMode.isDefined
  }
}

object ModifyTechniqueCallDiff {
  def apply(old: MethodCall, update: MethodCall): ModifyTechniqueCallDiff = {
    ModifyTechniqueCallDiff(
      old.method,
      old.id,
      old.component,
      SimpleDiff.createDiff(old, update)(_.component),
      SimpleDiff.createDiff(old, update)(_.condition),
      SimpleDiff.createDiff(old, update)(_.disabledReporting),
      SimpleDiff.createDiff(old, update)(_.parameters),
      SimpleDiff.createDiff(old, update)(_.policyMode),
      SimpleDiff.createDiff(old, update)(_.foreach),
      SimpleDiff.createDiff(old, update)(_.foreachName)
    )
  }

}

object TechniqueElemDiff {
  def buildParametersDiff(
      oldElems:    Seq[MethodElem],
      updateElems: Seq[MethodElem]
  ): List[TechniqueCallDiff | TechniqueBlockDiff] = {
    if (oldElems == updateElems) {
      Nil
    } else {
      val items = oldElems.map(Some.apply).zipAll(updateElems.map(Some.apply), None, None).zipWithIndex
      (for {
        ((oldValue, updateValue), index) <- items.toList
      } yield {
        (oldValue, updateValue) match {
          case (None, None)                      => Nil
          case (Some(old: MethodBlock), None)    =>
            updateElems.find(_.id == old.id) match {
              case Some(_: MethodBlock) => Nil
              case _                    => DeleteTechniqueBlockDiff(old) :: Nil
            }
          case (Some(old: MethodCall), None)     =>
            updateElems.find(_.id == old.id) match {
              case Some(_: MethodCall) => Nil
              case _                   => DeleteTechniqueCallDiff(old) :: Nil
            }
          case (None, Some(update: MethodBlock)) =>
            oldElems.find(_.id == update.id) match {
              case Some(old: MethodBlock) =>
                if (old == update) {
                  Nil
                } else {
                  ModifyTechniqueBlockDiff(old, update) :: Nil
                }
              case _                      => AddTechniqueBlockDiff(update) :: Nil
            }
          case (None, Some(update: MethodCall))  =>
            oldElems.find(_.id == update.id) match {
              case Some(old: MethodCall) =>
                if (old == update) {
                  Nil
                } else {
                  ModifyTechniqueCallDiff(old, update) :: Nil
                }
              case _                     =>
                AddTechniqueCallDiff(update) :: Nil
            }

          case (Some(old: MethodCall), Some(update: MethodCall)) =>
            if (old.id == update.id) {
              ModifyTechniqueCallDiff(old, update) :: Nil
            } else {
              val updateDiff = oldElems.find(_.id == update.id) match {
                case Some(realOld: MethodCall) =>
                  if (realOld == update) {
                    Nil
                  } else {
                    ModifyTechniqueCallDiff(realOld, update) :: Nil
                  }
                case _                         => AddTechniqueCallDiff(update) :: Nil
              }
              val oldDiff    = updateElems.find(_.id == old.id) match {
                case Some(_: MethodCall) => Nil
                case _                   => DeleteTechniqueCallDiff(old) :: Nil
              }
              oldDiff ::: updateDiff
            }

          case (Some(old: MethodBlock), Some(update: MethodBlock)) =>
            if (old.id == update.id) {
              ModifyTechniqueBlockDiff(old, update) :: Nil
            } else {
              val updateDiff = oldElems.find(_.id == update.id) match {
                case Some(realOld: MethodBlock) =>
                  if (realOld == update) {
                    Nil
                  } else {
                    ModifyTechniqueBlockDiff(realOld, update) :: Nil
                  }
                case _                          =>
                  AddTechniqueBlockDiff(update) :: Nil
              }
              val oldDiff    = updateElems.find(_.id == old.id) match {
                case Some(_: MethodBlock) =>
                  Nil
                case _                    =>
                  DeleteTechniqueBlockDiff(old) :: Nil
              }
              oldDiff ::: updateDiff
            }

          case (Some(old: MethodCall), Some(update: MethodBlock)) =>
            if (old.id == update.id) {
              DeleteTechniqueCallDiff(old) :: AddTechniqueBlockDiff(update) :: Nil
            } else {
              val updateDiff = oldElems.find(_.id == update.id) match {
                case Some(realOld: MethodBlock) =>
                  if (realOld == update) {
                    Nil
                  } else {
                    ModifyTechniqueBlockDiff(realOld, update) :: Nil
                  }
                case _                          =>
                  AddTechniqueBlockDiff(update) :: Nil
              }
              val oldDiff    = updateElems.find(_.id == old.id) match {
                case Some(_: MethodCall) =>
                  Nil
                case _                   =>
                  DeleteTechniqueCallDiff(old) :: Nil
              }
              oldDiff ::: updateDiff
            }

          case (Some(old: MethodBlock), Some(update: MethodCall)) =>
            if (old.id == update.id) {
              DeleteTechniqueBlockDiff(old) :: AddTechniqueCallDiff(update) :: Nil
            } else {
              val updateDiff = oldElems.find(_.id == update.id) match {
                case Some(realOld: MethodCall) =>
                  if (realOld == update) {
                    Nil
                  } else {
                    ModifyTechniqueCallDiff(realOld, update) :: Nil
                  }
                case _                         =>
                  AddTechniqueCallDiff(update) :: Nil
              }
              val oldDiff    = updateElems.find(_.id == old.id) match {
                case Some(_: MethodCall) => Nil
                case _                   => DeleteTechniqueBlockDiff(old) :: Nil
              }
              oldDiff ::: updateDiff
            }
        }

      }).flatten
    }
  }
}

trait TechniqueParameterDiff
final case class ModifyTechniqueParameterDiff(
    id:               ParameterId,
    name:             String,
    modName:          Option[SimpleDiff[String]],
    modDescription:   Option[SimpleDiff[Option[String]]],
    modDocumentation: Option[SimpleDiff[Option[String]]],
    modMayBeEmpty:    Option[SimpleDiff[Boolean]],
    modConstraints:   Option[ModifyConstraintsDiff]
) extends TechniqueParameterDiff {
  def needDeployment: Boolean = {
    modName.isDefined || modConstraints.isDefined
  }
}

final case class DeleteTechniqueParameterDiff(techniqueParameter: TechniqueParameter) extends TechniqueParameterDiff
final case class AddTechniqueParameterDiff(techniqueParameter: TechniqueParameter)    extends TechniqueParameterDiff

object TechniqueParameterDiff {
  def buildParametersDiff(
      oldParams:    Seq[TechniqueParameter],
      updateParams: Seq[TechniqueParameter]
  ): Seq[TechniqueParameterDiff] = {
    if (oldParams == updateParams) {
      Seq()
    } else {
      ({
        val oldMap    = oldParams.groupMapReduce(_.id)(identity)((a, _) => a)
        val updateMap = updateParams.groupMapReduce(_.id)(identity)((a, _) => a)
        val keys      = oldMap.keySet ++ updateMap.keySet

        for {
          key <- keys.toList
        } yield {
          val oldValue    = oldMap.get(key)
          val updateValue = updateMap.get(key)
          (oldValue, updateValue) match {
            case (None, None)              => None
            case (Some(old), None)         => Some(DeleteTechniqueParameterDiff(old))
            case (None, Some(update))      => Some(AddTechniqueParameterDiff(update))
            case (Some(old), Some(update)) =>
              Some(ModifyTechniqueParameterDiff(old, update))
          }

        }
      }).flatten
    }
  }
}

object ModifyTechniqueParameterDiff {
  def apply(old: TechniqueParameter, update: TechniqueParameter): ModifyTechniqueParameterDiff = {
    ModifyTechniqueParameterDiff(
      old.id,
      old.name,
      SimpleDiff.createDiff(old, update)(_.name),
      SimpleDiff.createDiff(old, update)(_.description),
      SimpleDiff.createDiff(old, update)(_.documentation),
      SimpleDiff.createDiff(old, update)(_.mayBeEmpty),
      ModifyConstraintsDiff.apply(old.constraints, update.constraints)
    )
  }

}

final case class ModifyConstraintsDiff(
    modAllowEmpty:      Option[SimpleDiff[Option[Boolean]]],
    modAllowWhiteSpace: Option[SimpleDiff[Option[Boolean]]],
    modMinLength:       Option[SimpleDiff[Option[Int]]],
    modMaxLength:       Option[SimpleDiff[Option[Int]]],
    modRegex:           Option[SimpleDiff[Option[String]]],
    modNotRegex:        Option[SimpleDiff[Option[String]]],
    modSelect:          Option[SimpleDiff[Option[List[SelectOption]]]]
)

object ModifyConstraintsDiff {
  def apply(old: Option[Constraints], update: Option[Constraints]): Option[ModifyConstraintsDiff] = {
    (old, update) match {
      case (None, None) => None
      case _            =>
        val default    = Constraints(None, None, None, None, None, None, None)
        val realOld    = old.getOrElse(default)
        val realUpdate = update.getOrElse(default)
        Some(
          ModifyConstraintsDiff(
            SimpleDiff.createDiff(realOld, realUpdate)(_.allowEmpty),
            SimpleDiff.createDiff(realOld, realUpdate)(_.allowWhiteSpace),
            SimpleDiff.createDiff(realOld, realUpdate)(_.minLength),
            SimpleDiff.createDiff(realOld, realUpdate)(_.maxLength),
            SimpleDiff.createDiff(realOld, realUpdate)(_.regex),
            SimpleDiff.createDiff(realOld, realUpdate)(_.regex),
            SimpleDiff.createDiff(realOld, realUpdate)(_.select)
          )
        )
    }
  }
}

object ModifyEditorTechniqueDiff {
  // ModifyEditorTechniqueDiff that has no modification
  def emptyMod(
      id:      BundleName,
      version: Version,
      name:    String
  ): ModifyEditorTechniqueDiff = {
    ModifyEditorTechniqueDiff(
      id,
      version,
      name,
      modName = None,
      modCategory = None,
      modDescription = None,
      modDocumentation = None,
      modCalls = Nil,
      modParameters = Seq(),
      modResources = None,
      modTags = None
    )
  }

  def apply(old: EditorTechnique, update: EditorTechnique): ModifyEditorTechniqueDiff = {
    ModifyEditorTechniqueDiff(
      old.id,
      old.version,
      old.name,
      SimpleDiff.createDiff(old, update)(_.name),
      SimpleDiff.createDiff(old, update)(_.category),
      SimpleDiff.createDiff(old, update)(_.description),
      SimpleDiff.createDiff(old, update)(_.documentation),
      TechniqueElemDiff.buildParametersDiff(old.calls, update.calls),
      TechniqueParameterDiff.buildParametersDiff(old.parameters, update.parameters),
      SimpleDiff.createDiff(old, update)(_.resources),
      SimpleDiff.createDiff(old, update)(_.tags)
    )
  }
}

final case class ModifyToEditorTechniqueDiff(
    editorTechnique: EditorTechnique
) extends EditorTechniqueDiff with ChangeRequestEditorTechniqueDiff {
  // This case is undecidable, so it is always true
  def needDeployment: Boolean = true
}
