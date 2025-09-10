/*
 *************************************************************************************
 * Copyright 2025 Normation SAS
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

package bootstrap.liftweb.checks.endconfig.migration

import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.domain.TechniqueVersion
import com.normation.errors
import com.normation.errors.IOResult
import com.normation.errors.RudderError
import com.normation.eventlog.ModificationId
import com.normation.inventory.domain.Version
import com.normation.rudder.MockDirectives
import com.normation.rudder.MockGitConfigRepo
import com.normation.rudder.MockTechniques
import com.normation.rudder.domain.eventlog
import com.normation.rudder.domain.policies.Directive
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.DirectiveUid
import com.normation.rudder.hooks.CmdResult
import com.normation.rudder.ncf.*
import com.normation.utils.StringUuidGeneratorImpl
import com.normation.zio.*
import org.junit.runner.*
import org.specs2.matcher.ContentMatchers
import org.specs2.mutable.*
import org.specs2.runner.*
import zio.syntax.*

@RunWith(classOf[JUnitRunner])
class TestMigrateDirectiveWithSelectInputBroken extends Specification with ContentMatchers {
  sequential

  // uses configuration repository from rudder-core tests resources
  val mockDirectives = new MockDirectives(MockTechniques(new MockGitConfigRepo("")))

  val editorTech      = EditorTechnique(
    BundleName("test-migrate-select"),
    new Version("1.0"),
    "test",
    "",
    Seq(),
    "",
    "",
    Seq(
      TechniqueParameter(
        ParameterId("8beca52f-7d5a-4c55-b5ae-fb56b7e50ddf"),
        "test",
        None,
        None,
        false,
        Some(
          Constraints(
            None,
            None,
            None,
            None,
            None,
            None,
            Some(SelectOption("audit", Some("Audit")) :: SelectOption("enforce", Some("Enforce")) :: Nil)
          )
        )
      )
    ),
    Seq(),
    Map.empty,
    None
  )
  val techniqueReader = new EditorTechniqueReader {
    override def readTechniquesMetadataFile
        : IOResult[(List[EditorTechnique], Map[BundleName, GenericMethod], List[errors.RudderError])] =
      (editorTech :: Nil, Map.empty[BundleName, GenericMethod], List[RudderError]()).succeed

    override def getMethodsMetadata: IOResult[Map[BundleName, GenericMethod]] = Map.empty[BundleName, GenericMethod].succeed

    override def updateMethodsMetadataFile: IOResult[CmdResult] = ???
  }

  val directive = Directive(
    DirectiveId(DirectiveUid("test")),
    TechniqueVersion.V1_0,
    Map.empty.updated("test", Seq("enforce")),
    "test-directive",
    "",
    None
  )

  val atId = mockDirectives.directiveRepo
    .getActiveTechnique(TechniqueName("test-migrate-select"))
    .notOptional("should not empty")
    .runNow
    .id
  mockDirectives.directiveRepo.saveDirective(atId, directive, ModificationId("..."), eventlog.RudderEventActor, None).runNow

  val migration = new MigrateDirectiveWithSelectInputBroken(
    techniqueReader,
    mockDirectives.directiveRepo,
    mockDirectives.directiveRepo,
    new StringUuidGeneratorImpl()
  )

  "After migration, a directive with a faulty select parameter is migrated" >> {
    migration.checks()
    mockDirectives.directiveRepo.getDirective(DirectiveUid("test")).runNow === Some(
      directive.copy(parameters =
        directive.parameters.removed("test").updated("8BECA52F-7D5A-4C55-B5AE-FB56B7E50DDF", Seq("enforce"))
      )
    )
  }
}
