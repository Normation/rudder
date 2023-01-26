/*
 *************************************************************************************
 * Copyright 2023 Normation SAS
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

package bootstrap.liftweb.checks.action

import bootstrap.liftweb.BootstrapChecks
import bootstrap.liftweb.BootstrapLogger
import com.normation.eventlog.ModificationId
import com.normation.rudder.domain.eventlog.RudderEventActor
import com.normation.rudder.domain.policies.ActiveTechniqueId
import com.normation.rudder.domain.policies.DirectiveUid
import com.normation.rudder.repository.WoDirectiveRepository
import com.normation.utils.StringUuidGenerator
import com.normation.zio._

class RemoveFaultyLdapEntries(
    woLDAPDirectiveRepository: WoDirectiveRepository,
    uuidGen:                   StringUuidGenerator
) extends BootstrapChecks {

  override val description = "Remove LDAP entries breaking directive api, see https://issues.rudder.io/issues/22314"

  override def checks(): Unit = {
    (for {
      directive <- woLDAPDirectiveRepository.delete(
                     DirectiveUid.apply("test_import_export_archive_directive"),
                     ModificationId(uuidGen.newUuid),
                     RudderEventActor,
                     None
                   )
      technique <- woLDAPDirectiveRepository.deleteActiveTechnique(
                     ActiveTechniqueId("test_import_export_archive"),
                     ModificationId(uuidGen.newUuid),
                     RudderEventActor,
                     None
                   )
    } yield {
      ()
    }).catchAll(err => BootstrapLogger.error(s"Error while deleting ldap entries, error details: ${err.fullMsg}")).runNow

  }

}
