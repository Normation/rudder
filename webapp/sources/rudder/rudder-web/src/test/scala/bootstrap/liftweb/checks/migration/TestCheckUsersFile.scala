package bootstrap.liftweb.checks.migration

import com.normation.errors.IOResult
import com.normation.rudder.MockUserManagement
import com.normation.rudder.users.RudderPasswordEncoder.SecurityLevel
import com.normation.rudder.users.UserFileSecurityLevelMigration
import com.normation.zio.UnsafeRun
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import org.specs2.specification.AfterAll
import scala.xml.Elem

@RunWith(classOf[JUnitRunner])
class TestCheckUsersFile extends Specification with AfterAll {
  sequential

  val (mockUserManagementTmpDir, mockUserManagement) = MockUserManagement()

  val migration: UserFileSecurityLevelMigration = mockUserManagement.userService

  val checkUsersFile: CheckUsersFile = new CheckUsersFile(migration)

  "CheckUsersFile" should {

    def haveHash(hash: String) = beEqualTo(hash) ^^ ((_: Elem).attribute("hash").get.head.text)

    def haveUnsafeHashes(unsafeHashes: Option[String]) =
      beEqualTo(unsafeHashes) ^^ ((_: Elem).attribute("unsafe-hashes").flatMap(_.headOption).map(_.text))

    "start with a sha-1 hash and no unsafe-hashes" in {
      val initialFile = IOResult.attempt(scala.xml.XML.load(migration.file.inputStream())).runNow

      initialFile must (haveHash("sha-1") and haveUnsafeHashes(None))
    }

    "migrate legacy hash to bcrypt and enable unsafe-hashes" in {
      val migratedFile = (checkUsersFile.allChecks(SecurityLevel.Legacy) *> IOResult.attempt(
        scala.xml.XML.load(migration.file.inputStream())
      )).runNow

      migratedFile must (haveHash("bcrypt") and haveUnsafeHashes(Some("true")))
    }

    "keep hash unchanged" in {
      checkUsersFile.checks()
      val sameMigratedFile =
        IOResult.attempt(scala.xml.XML.load(migration.file.inputStream())).runNow
      sameMigratedFile must (haveHash("bcrypt") and haveUnsafeHashes(Some("true")))
    }
  }

  override def afterAll(): Unit = {
    mockUserManagementTmpDir.delete()
  }
}
