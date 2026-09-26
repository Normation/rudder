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

import com.normation.rudder.domain.RudderLDAPConstants.*
import com.normation.rudder.repository.ldap.SetupLdapRepositories
import com.normation.zio.*
import com.unboundid.ldap.sdk.DN
import org.junit.runner.*
import org.specs2.mutable.*
import org.specs2.runner.*
import zio.Chunk

/*
 * The migration that tags the objects Rudder provides - the three root categories, the `SystemGroups`
 * category, the three special targets and the `rudder` global parameter - as library objects (`open-ro`):
 * everybody sees and uses them, only an administrator changes them.
 *
 * The shipped LDAP data already carries the tag, so each test starts by removing it, which is the state a
 * directory created before Rudder 9.2 is in.
 */
@RunWith(classOf[JUnitRunner])
class LibraryObjectsTagMigrationTest extends Specification with SetupLdapRepositories {

  sequential

  private lazy val migration = new LibraryObjectsTagMigration(ldap, rudderDit)

  private def tagOf(dn: DN): Option[SecurityTag] = {
    (for {
      con <- ldap
      e   <- con.get(dn)
    } yield SecurityTag.parseLdapValue(e.flatMap(_(A_SECURITY_TAG)), dn.toString)).runNow
  }

  private def setTag(dn: DN, tag: Option[SecurityTag]): Unit = {
    (for {
      con <- ldap
      e   <- con.get(dn).notOptional(s"missing test entry '${dn.toString}'")
      _    = tag match {
               case None    => e.deleteAttribute(A_SECURITY_TAG)
               case Some(t) => e.resetValuesTo(A_SECURITY_TAG, SecurityTag.toLdapValue(t))
             }
      // the attribute is gone from the entry, so the save has to be told to drop it in the directory too
      _   <- con.save(e, removeMissingAttributes = true)
    } yield ()).runNow
  }

  private def untagAll(): Unit = migration.libraryObjects.foreach(dn => setTag(dn, None))

  /*
   * The `rudder` global parameter is not in the shipped LDIF: `CheckRudderGlobalProperties` creates it at
   * boot from `rudder-system-global-parameter.conf`. Create it here the same way an instance upgraded from
   * a version without the tag would have it, so the migration is exercised on it too.
   */
  private def createRudderParameter(): Unit = {
    (for {
      con <- ldap
      e    = rudderDit.PARAMETERS.parameterModel("rudder")
      _    = e.resetValuesTo(A_PARAMETER_NAME, "rudder")
      _   <- con.save(e)
    } yield ()).runNow
  }

  "the library objects" should {
    "all be known to the migration" in {
      // three root categories, the system group category, the `rudder` parameter, three special targets
      migration.libraryObjects.size must beEqualTo(8)
    }

    "all be tagged 'open-ro' by the migration" in {
      createRudderParameter()
      untagAll()
      val changed = migration.migrate().runNow
      (changed must beEqualTo(migration.libraryObjects.size)) and
      (migration.libraryObjects.map(tagOf) must contain(beSome(SecurityTag.OpenRo: SecurityTag)).forall)
    }

    "not be touched again once they are tagged" in {
      migration.migrate().runNow must beEqualTo(0)
    }

    "be tagged whatever the open form they had before" in {
      val dn = rudderDit.GROUP.dn
      setTag(dn, Some(SecurityTag.OpenRw))
      (migration.migrate().runNow must beEqualTo(1)) and
      (tagOf(dn) must beSome(SecurityTag.OpenRo: SecurityTag))
    }
  }

  // narrowing a tag is never automatic: an explicit tenant list is an administrator's decision, and the
  // migration has no way to know whether it was meant
  "an explicit tenant list on a library object" should {
    "be left untouched" in {
      val dn  = rudderDit.RULECATEGORY.dn
      val tag = SecurityTag.ByTenants(Chunk(TenantId("zoneA")))
      setTag(dn, Some(tag))
      (migration.migrate().runNow must beEqualTo(0)) and
      (tagOf(dn) must beSome(tag: SecurityTag))
    }
  }
}
