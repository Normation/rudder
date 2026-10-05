/*
 *************************************************************************************
 * Copyright 2026 Normation SAS
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

package com.normation.rudder.services.policies

import com.normation.cfclerk.domain.RootTechniqueCategoryId
import com.normation.cfclerk.domain.SubTechniqueCategoryId
import com.normation.cfclerk.domain.Technique
import com.normation.cfclerk.domain.TechniqueCategoryId
import com.normation.cfclerk.services.TechniqueRepository
import com.normation.errors.*
import com.normation.rudder.domain.policies.ActiveTechniqueCategory
import com.normation.rudder.domain.policies.ActiveTechniqueCategoryId
import com.normation.rudder.repository.FullActiveTechniqueCategory
import com.normation.rudder.repository.RoDirectiveRepository
import com.normation.rudder.repository.WoDirectiveRepository
import com.normation.rudder.tenants.ChangeContext
import com.normation.rudder.tenants.QueryContext
import com.normation.rudder.tenants.SecurityTag
import com.normation.rudder.tenants.TenantsLogger
import zio.*

/*
 * Copy the tenant tags of the reference library onto the user library.
 * Invariant: what the file system declares is what the active techniques and their categories carry.
 *
 * A technique declares its tag in `metadata.xml` (written by rudderc from the `security` field of
 * `technique.yml`), a category in its `category.xml`. Declaring nothing means administrators only.
 *
 * This is done even if the tenant plugin is absent or disable.
 */
class TechniqueLibraryTenantSync(
    // the raw storage repositories (`directiveRead.storage` / `directiveWrite.storage`), never the proxies
    roActiveTechniqueRepo: RoDirectiveRepository,
    rwActiveTechniqueRepo: WoDirectiveRepository,
    techniqueRepo:         TechniqueRepository
) {
  import com.normation.rudder.services.policies.TechniqueLibraryTenantSync.*

  def syncAll()(implicit cc: ChangeContext): IOResult[Unit] = {
    for {
      lib         <- QueryContext.asSystem("the whole library is aligned, whoever triggered the update") {
                       roActiveTechniqueRepo.getFullDirectiveLibrary()
                     }
      declaredCats = techniqueRepo.getTechniquesInfo().allCategories.map { case (id, cat) => (toActiveCatId(id), cat.security) }
      _           <- syncCategories(lib, declaredCats)
      _           <- syncActiveTechniques(lib)
    } yield ()
  }

  private def syncCategories(
      lib:      FullActiveTechniqueCategory,
      declared: Map[ActiveTechniqueCategoryId, Option[SecurityTag]]
  )(implicit cc: ChangeContext): IOResult[Unit] = {
    ZIO.foreachDiscard(allCategories(lib)) { cat =>
      align(s"active technique category '${cat.id.value}'", cat.security, declaredFor(declared, cat.id)) { tag =>
        rwActiveTechniqueRepo
          .saveActiveTechniqueCategory(
            ActiveTechniqueCategory(
              cat.id,
              cat.name,
              cat.description,
              cat.subCategories.map(_.id),
              cat.activeTechniques.map(_.id),
              cat.isSystem,
              tag
            )
          )
          .unit
      }
    }
  }

  private def syncActiveTechniques(lib: FullActiveTechniqueCategory)(implicit cc: ChangeContext): IOResult[Unit] = {
    ZIO.foreachDiscard(lib.allActiveTechniques.values) { at =>
      align(s"active technique '${at.id.value}'", at.security, declaredFor(at.techniques.values)) { tag =>
        rwActiveTechniqueRepo.changeSecurity(at.id, tag).unit
      }
    }
  }

  // log after the write: an info line saying the tag changed must mean it did
  private def align(what: String, current: Option[SecurityTag], reference: ReferenceTag)(
      apply: Option[SecurityTag] => IOResult[Unit]
  ): IOResult[Unit] = {
    reference match {
      case ReferenceTag.Absent                                    => ZIO.unit
      case ReferenceTag.Declares(declared) if declared == current => ZIO.unit
      case ReferenceTag.Declares(declared)                        =>
        apply(declared) *>
        TenantsLogger.info(
          s"Tenants of ${what} copied from the reference library: ${show(current)} -> ${show(declared)}"
        )
    }
  }
}

enum ReferenceTag {
  case Absent
  case Declares(security: Option[SecurityTag])
}

object TechniqueLibraryTenantSync {

  // an active technique covers every version of its technique, so it shows what any of them shows
  def declaredFor(techniques: Iterable[Technique]): ReferenceTag = {
    if (techniques.isEmpty) ReferenceTag.Absent
    else ReferenceTag.Declares(SecurityTag.joinAll(techniques))
  }

  def declaredFor(declared: Map[ActiveTechniqueCategoryId, Option[SecurityTag]], id: ActiveTechniqueCategoryId): ReferenceTag = {
    declared.get(id).fold(ReferenceTag.Absent)(ReferenceTag.Declares(_))
  }

  def toActiveCatId(id: TechniqueCategoryId): ActiveTechniqueCategoryId = {
    id match {
      case RootTechniqueCategoryId         => ActiveTechniqueCategoryId("Active Techniques")
      case SubTechniqueCategoryId(name, _) => ActiveTechniqueCategoryId(name.value)
    }
  }

  def allCategories(cat: FullActiveTechniqueCategory): List[FullActiveTechniqueCategory] = {
    cat :: cat.subCategories.flatMap(allCategories)
  }

  def show(tag: Option[SecurityTag]): String = {
    tag match {
      case None                            => "admin only"
      case Some(o: SecurityTag.Open)       => o.kind
      case Some(SecurityTag.ByTenants(ts)) => ts.map(_.value).mkString(",")
    }
  }
}
