package bootstrap.liftweb.checks.migration

import com.normation.rudder.MockUserManagement

import bootstrap.liftweb.checks.earlyconfig.db.CheckUsersFile

import com.normation.zio.UnsafeRun
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import org.specs2.specification.core.AsExecution

import scala.xml.Elem

@RunWith(classOf[JUnitRunner])
class TestCheckUsersFile extends Specification {
  sequential

  val shaLegacyFile:           String = "test-users.xml"
  val unknownHashFile:         String = "test-users-wrong-hash.xml"
  val unknownUnsafeHashesFile: String = "test-users-wrong-unsafe-hashes.xml"

  "CheckUsersFile" should {

    def haveHash(hash: String) = beEqualTo(hash) ^^ ((_: Elem).attribute("hash").get.head.text)

    def haveUnsafeHashes(unsafeHashes: Option[String]) =
      beEqualTo(unsafeHashes) ^^ ((_: Elem).attribute("unsafe-hashes").flatMap(_.headOption).map(_.text))

    "start with a sha-1 hash and no unsafe-hashes" in withMigrationCtx(shaLegacyFile) {
      case (initialFile, _) =>
        initialFile() must (haveHash("sha-1") and haveUnsafeHashes(None))
    }

    "migrate legacy hash to bcrypt and enable unsafe-hashes" in withMigrationCtx(shaLegacyFile) {
      case (getFile, checkUsersFile) =>
        checkUsersFile.prog.runNow

        getFile() must (haveHash("bcrypt") and haveUnsafeHashes(Some("true")))
    }

    "keep hash unchanged" in withMigrationCtx(shaLegacyFile) {
      case (getFile, checkUsersFile) =>
        checkUsersFile.prog.runNow
        getFile() must (haveHash("bcrypt") and haveUnsafeHashes(Some("true")))

        // idempotent check
        checkUsersFile.prog.runNow
        getFile() must (haveHash("bcrypt") and haveUnsafeHashes(Some("true")))
    }

    "migrate unknown hash to bcrypt with unsafe-hashes set to false" in withMigrationCtx(unknownHashFile) {
      case (getFile, checkUsersFile) =>
        checkUsersFile.prog.runNow
        getFile() must (haveHash("bcrypt") and haveUnsafeHashes(Some("false")))
    }

    "migrate non-boolean unsafe-hashes to false" in withMigrationCtx(unknownUnsafeHashesFile) {
      case (getFile, checkUsersFile) =>
        checkUsersFile.prog.runNow
        getFile() must (haveHash("bcrypt") and haveUnsafeHashes(Some("false")))
    }
  }

  private def withMigrationCtx[A: AsExecution](
      resourceFile: String
  )(block: (() => Elem, CheckUsersFile) => A): A = {

    val (mockUserManagementTmpDir, mockUserManagement) = MockUserManagement(resourceFile = resourceFile)
    val migration                                      = mockUserManagement.userService
    val checkUsersFile                                 = new CheckUsersFile(migration)

    val elem = () => scala.xml.XML.load(migration.file.inputStream())
    val res  = block(elem, checkUsersFile)

    mockUserManagementTmpDir.delete()
    res
  }
}
