/*
*************************************************************************************
* Copyright 2011 Normation SAS
*************************************************************************************
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Affero General Public License as
* published by the Free Software Foundation, either version 3 of the
* License, or (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU Affero GPL v3, the copyright holders add the following
* Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU Affero GPL v3
* licence, when you create a Related Module, this Related Module is
* not considered as a part of the work and may be distributed under the
* license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Affero General Public License for more details.
*
* You should have received a copy of the GNU Affero General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/agpl.html>.
*
*************************************************************************************
*/

package com.normation.rudder.domain.policies

import com.normation.utils.HashcodeCaching
import com.normation.cfclerk.domain.TechniqueVersion
import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.domain.SectionSpec

/**
 * That file define "diff" objects between directives.
 */

sealed trait DirectiveDiff

//for change request, with add type tag to DirectiveDiff
sealed trait ChangeRequestDirectiveDiff {
  def techniqueName : TechniqueName
  def directive     : Directive
}

final case class DeleteDirectiveDiff(
    techniqueName: TechniqueName
  , directive    : Directive
) extends DirectiveDiff with HashcodeCaching with ChangeRequestDirectiveDiff

// add and modify are put together
sealed trait DirectiveSaveDiff extends DirectiveDiff

final case class AddDirectiveDiff(
    techniqueName: TechniqueName
  , directive    : Directive
) extends DirectiveSaveDiff with HashcodeCaching with ChangeRequestDirectiveDiff


final case class ModifyDirectiveDiff(
    techniqueName      : TechniqueName
  , id                 : DirectiveId
  , name               : String //keep the name around to be able to display it as it was at that time
  , modName            : Option[SimpleDiff[String]]           = None
  , modTechniqueVersion: Option[SimpleDiff[TechniqueVersion]] = None
  , modParameters      : Option[SimpleDiff[SectionVal]]       = None
  , modShortDescription: Option[SimpleDiff[String]]           = None
  , modLongDescription : Option[SimpleDiff[String]]           = None
  , modPriority        : Option[SimpleDiff[Int]]              = None
  , modIsActivated     : Option[SimpleDiff[Boolean]]          = None
  , modIsSystem        : Option[SimpleDiff[Boolean]]          = None
) extends DirectiveSaveDiff with HashcodeCaching


final case class ModifyToDirectiveDiff(
    techniqueName: TechniqueName
  , directive    : Directive
  , rootSection  : SectionSpec
) extends DirectiveDiff with HashcodeCaching with ChangeRequestDirectiveDiff
