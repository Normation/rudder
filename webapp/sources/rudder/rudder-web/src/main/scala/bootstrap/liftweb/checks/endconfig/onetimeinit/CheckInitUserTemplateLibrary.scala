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

package bootstrap.liftweb.checks.endconfig.onetimeinit

import bootstrap.liftweb.BootstrapChecks
import bootstrap.liftweb.BootstrapLogger
import com.normation.box.*
import com.normation.cfclerk.services.*
import com.normation.eventlog.ModificationId
import com.normation.inventory.ldap.core.LDAPConstants.A_OC
import com.normation.ldap.sdk.*
import com.normation.rudder.batch.AsyncDeploymentActor
import com.normation.rudder.batch.AutomaticStartDeployment
import com.normation.rudder.domain.RudderDit
import com.normation.rudder.domain.RudderLDAPConstants.*
import com.normation.rudder.domain.eventlog.RudderEventActor
import com.normation.rudder.repository.*
import com.normation.utils.DateFormaterService
import com.normation.utils.StringUuidGenerator
import net.liftweb.common.*
import org.joda.time.DateTime
import org.joda.time.DateTimeZone

/**
 * That class add all the available reference template in
 * the default user library
 * if it wasn't already initialized.
 */
class CheckInitUserTemplateLibrary(
    rudderDit:            RudderDit,
    ldap:                 LDAPConnectionProvider[RwLDAPConnection],
    techniqueRepository:  TechniqueRepository,
    roDirectiveRepos:     RoDirectiveRepository,
    woDirectiveRepos:     WoDirectiveRepository,
    uuidGen:              StringUuidGenerator,
    asyncDeploymentAgent: AsyncDeploymentActor
) extends BootstrapChecks {

  val initDirectivesTree = new InitDirectivesTree(
    techniqueRepository: TechniqueRepository,
    roDirectiveRepos:    RoDirectiveRepository,
    woDirectiveRepos:    WoDirectiveRepository,
    uuidGen:             StringUuidGenerator
  )

  override val description = "Check initialization of User Technique Library"

  override def checks(): Unit = {
    (for {
      con <- ldap
      res <- con.get(rudderDit.ACTIVE_TECHNIQUES_LIB.dn, A_INIT_DATETIME, A_OC)
    } yield {
      res
    }).toBox match {
      case eb: EmptyBox =>
        val e = eb ?~! s"Error when trying to check for root entry of the user template library"
        BootstrapLogger.logEffect.error(e.messageChain)
      case Full(None) => BootstrapLogger.logEffect.error("The root entry of the user template library was not found")
      case Full(Some(root)) =>
        root.getAsGTime(A_INIT_DATETIME) match {
          case Some(date) =>
            BootstrapLogger.logEffect.debug(
              s"The root user template library was initialized on ${DateFormaterService.serializeInstant(date.instant)}"
            )
          case None       =>
            BootstrapLogger.logEffect.info(
              "The Active Technique library is not marked as being initialized: adding all policies from reference library..."
            )
            initDirectivesTree.copyReferenceLib() match {
              case Full(x) =>
                asyncDeploymentAgent ! AutomaticStartDeployment(ModificationId(uuidGen.newUuid), RudderEventActor)
                BootstrapLogger.logEffect.info("...done")
              case eb: EmptyBox =>
                val e   = eb ?~! "Some error where encountered during the initialization of the user library"
                val msg = e.messageChain.split("<-").mkString("\n ->")
                BootstrapLogger.logEffect.warn(msg)
                e.rootExceptionCause.foreach(ex => BootstrapLogger.logEffect.debug("cause was:", ex))
                // Even if complete reload failed, we need to trigger a policy deployment, as otherwise it will never be done
                asyncDeploymentAgent ! AutomaticStartDeployment(ModificationId(uuidGen.newUuid), RudderEventActor)
            }
            root.addValues(A_OC, OC_ACTIVE_TECHNIQUE_LIB_VERSION)
            root.resetValuesTo(A_INIT_DATETIME, GeneralizedTime(DateTime.now(DateTimeZone.UTC)).toString)
            ldap.flatMap(_.save(root)).toBox match {
              case eb: EmptyBox =>
                val e = eb ?~! "Error when updating information about the LDAP root entry of technique library."
                BootstrapLogger.logEffect.error(e.messageChain)
                e.rootExceptionCause.foreach(ex => BootstrapLogger.logEffect.error("Root exception was: ", ex))
              case _ => // nothing to do
            }
        }
    }
  }
}
