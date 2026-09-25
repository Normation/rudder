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

package com.normation.rudder.tenants

import com.normation.errors.*
import com.normation.ldap.sdk.LDAPConnectionProvider
import com.normation.ldap.sdk.LDAPEntry
import com.normation.ldap.sdk.RwLDAPConnection
import com.normation.rudder.domain.RudderDit
import com.normation.rudder.domain.RudderLDAPConstants.*
import com.normation.rudder.domain.logger.MigrationLoggerPure
import com.normation.rudder.domain.policies.AllPolicyServers
import com.normation.rudder.domain.policies.AllTarget
import com.normation.rudder.domain.policies.AllTargetExceptPolicyServers
import com.unboundid.ldap.sdk.DN
import zio.*
import zio.syntax.*

/**
 * Give the library tag (`open-ro`) to the objects Rudder provides and everybody builds on. See the ADR on
 * the two open tags.
 *
 * Scope:
 *   - the three root categories (active techniques, groups, rules); seeing a container is what allows
 *     putting an object in it;
 *   - the `SystemGroups` category and the three special targets it holds (`special:all`,
 *     `special:all_exceptPolicyServers`, `special:all_policyServers`), which any rule may target. A rule
 *     whose target does not resolve in the actor's library displays as broken;
 *   - the `rudder` global parameter, read by every node's policies, reset at every boot by
 *     `CheckRudderGlobalProperties`.
 *
 * The technique library is elsewhere: its active techniques and categories follow the tags declared by the
 * reference library, copied over by `TechniqueLibraryTenantSync` at every library update and at boot. A
 * technique with only a `metadata.xml` defaults to `open-ro` when read
 * (`GitTechniqueReader.withDefaultSecurity`); the technique editor's own area under `ncf_techniques`
 * declares its tags; directives are untouched.
 *
 * Other system groups (`hasPolicyServer-*`, `all-nodes-with-cfengine-agent`) stay administrator-only: no
 * user targets them directly, and the system rules that do are administrator-only too.
 *
 * Rudder creates these entries tagged (`RudderDit`, `bootstrap.ldif`), so this migration only serves
 * directories predating the tag - up to 9.1 the three roots were forced open at read time, which hid what
 * was stored and left the special targets administrator-only. Idempotent, runs at every boot.
 *
 * Visibility only grows. An explicit tenant list is an administrator's decision: it is logged and kept.
 */
class LibraryObjectsTagMigration(
    ldap:      LDAPConnectionProvider[RwLDAPConnection],
    rudderDit: RudderDit
) {

  // see `rudder-system-global-parameter.conf`
  private val RUDDER_PARAMETER = "rudder"

  private[tenants] val libraryObjects: List[DN] = {
    rudderDit.ACTIVE_TECHNIQUES_LIB.dn ::
    rudderDit.GROUP.dn ::
    rudderDit.GROUP.SYSTEM.dn ::
    rudderDit.RULECATEGORY.dn ::
    rudderDit.PARAMETERS.parameterDN(RUDDER_PARAMETER) ::
    // `NonGroupRuleTarget` has exactly these three implementations
    List(AllTarget, AllTargetExceptPolicyServers, AllPolicyServers).map(rudderDit.GROUP.SYSTEM.targetDN)
  }

  // returns the number of entries changed, so that a migrating boot is distinguishable from a quiet one
  def migrate(): IOResult[Int] = {
    for {
      con     <- ldap
      changed <- ZIO.foreach(libraryObjects)(dn => migrateOne(con, dn))
    } yield changed.count(identity)
  }

  private def migrateOne(con: RwLDAPConnection, dn: DN): IOResult[Boolean] = {
    con.get(dn).flatMap {
      // entry Rudder has not created yet: no relay, partially initialized directory
      case None    => MigrationLoggerPure.debug(s"No entry '${dn.toString}' to tag as a library object").as(false)
      case Some(e) =>
        currentTag(e) match {
          case Some(SecurityTag.OpenRo)           => false.succeed
          case Some(t @ SecurityTag.ByTenants(_)) =>
            MigrationLoggerPure
              .warn(
                s"Entry '${dn.toString}' is a library object but is restricted to tenants '${t.tenants.map(_.value).mkString(",")}'. " +
                s"It is left as it is, but as long as it is not visible to everyone, the users of the other tenants will not be " +
                s"able to use it"
              )
              .as(false)
          case _                                  => // absent, unreadable, or `open-rw`
            SecurityTag.LIBRARY_SECURITY_TAG.foreach(t => e.resetValuesTo(A_SECURITY_TAG, SecurityTag.toLdapValue(t)))
            con.save(e) *>
            MigrationLoggerPure
              .info(s"Entry '${dn.toString}' is now tagged as a library object, visible to every tenant")
              .as(true)
        }
    }
  }

  // an unreadable tag reads as absent and gets replaced, `parseLdapValue` logs it
  private def currentTag(e: LDAPEntry): Option[SecurityTag] = SecurityTag.parseLdapValue(e(A_SECURITY_TAG), e.dn.toString)
}
