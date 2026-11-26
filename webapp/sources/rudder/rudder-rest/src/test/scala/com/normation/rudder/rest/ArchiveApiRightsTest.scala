package com.normation.rudder.rest

import com.normation.rudder.AuthorizationType
import com.normation.rudder.Rights
import com.normation.rudder.Role
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

/*
 * Archive import is an administration action (#30147): only `archive_write` gives it, and only administrator has it
 * by default. Export stays open to every role that reads the configuration.
 */
@RunWith(classOf[JUnitRunner])
class ArchiveApiRightsTest extends Specification {
  private val mapping = new RoleApiMapping(new AuthorizationMappingListEndpoint(ArchiveApi.endpoints))

  private def canAccess(rights: Rights, endpoint: ArchiveApi): Boolean = {
    AclCheck(mapping.getApiAclFromRights(rights), endpoint.path, endpoint.action)
  }

  private def builtin(name: Role.BuiltinName): Rights = Role.allBuiltInRoles(name.value).rights

  private val nonAdminBuiltins = Role.standardBuiltIn.keys.toList

  "Archive import" should {
    "be allowed to administrator" in {
      canAccess(Role.Administrator.rights, ArchiveApi.Import) must beTrue
    }
    "be allowed to a custom role with archive_write" in {
      canAccess(Rights(AuthorizationType.Archive.Write), ArchiveApi.Import) must beTrue
    }
    "be refused to every other built-in role" in {
      nonAdminBuiltins.filter(n => canAccess(builtin(n), ArchiveApi.Import)) must beEmpty
    }
  }

  "Archive export" should {
    "be allowed to archive_read" in {
      (canAccess(Rights(AuthorizationType.Archive.Read), ArchiveApi.ExportSimple) must beTrue)
    }
    "be refused without either" in {
      canAccess(Rights(AuthorizationType.Configuration.Write), ArchiveApi.ExportSimple) must beFalse
    }
  }
}
