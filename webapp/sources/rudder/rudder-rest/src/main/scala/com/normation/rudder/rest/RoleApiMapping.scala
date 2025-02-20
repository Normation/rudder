/*
 *************************************************************************************
 * Copyright 2017 Normation SAS
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

package com.normation.rudder.rest

import com.normation.rudder.AuthorizationType
import com.normation.rudder.Rights
import com.normation.rudder.Role
import com.normation.rudder.api.AclPathSegment
import com.normation.rudder.api.ApiAclElement
import com.normation.rudder.api.ApiAuthorization as ApiAuthz

/*
 * The goal of that class is to map Authorization to what API
 * they give access.
 * At some point, we will need AuthorizationType to be extensible, so
 * that a new module can provide new authorization. For now, a plugin
 * need to map its authorizations on the existing ones.
 */
trait AuthorizationApiMapping {
  def mapAuthorization(authz: AuthorizationType): List[ApiAclElement]
}

class AuthorizationMappingListEndpoint(endpoints: List[EndpointSchema]) extends AuthorizationApiMapping {
  val acls: Map[AuthorizationType, List[ApiAclElement]] =
    endpoints.flatMap(e => e.authz.map(a => (a, AuthzForApi(e)))).groupMap(_._1)(_._2)

  override def mapAuthorization(authz: AuthorizationType): List[ApiAclElement] = {
    acls.get(authz).getOrElse(Nil)
  }
}

/*
 * An extensible mapper that allows for plugins to contribute to
 * its mapper
 */
class ExtensibleAuthorizationApiMapping(base: List[AuthorizationApiMapping]) extends AuthorizationApiMapping {

  private var mappers: List[AuthorizationApiMapping] = base

  def addMapper(mapper: AuthorizationApiMapping): Unit = {
    // no need to add again and again the default mapper - it's ok, we have it.
    if (mapper != AuthorizationApiMapping.OnlyAdmin) {
      mappers = mappers :+ mapper
    }
  }

  override def mapAuthorization(authz: AuthorizationType): List[ApiAclElement] = {
    mappers.flatMap(_.mapAuthorization(authz))
  }
}

object AuthorizationApiMapping {
  implicit class ToAuthz(val api: EndpointSchema) extends AnyVal {
    def x: ApiAclElement = AuthzForApi(api)
  }

  /*
   * A default mapping for "only 'all rights' (ie admin) can access it"
   */
  case object OnlyAdmin extends AuthorizationApiMapping {
    override def mapAuthorization(authz: AuthorizationType): List[ApiAclElement] = Nil
  }

  /*
   * The core authorization/api mapping, ie the authorization for Rudder
   * default API.
   */
  object Core extends AuthorizationApiMapping {

    override def mapAuthorization(authz: AuthorizationType): List[ApiAclElement] = {
      import AuthorizationType.*
      // shorthand to get authz for a given api
      authz match {
        case NoRights  => Nil
        case AnyRights => ApiAuthz.allAuthz.acl
        // Administration is Rudder setting

        case Administration.Read  =>
          EventLogApi.GetEventLogs.x :: EventLogApi.GetEventLogDetails.x :: SettingsApi.GetAllSettings.x :: SettingsApi.GetSetting.x :: SystemApi.ArchivesDirectivesList.x ::
          SystemApi.ArchivesFullList.x :: SystemApi.ArchivesGroupsList.x :: SystemApi.ArchivesRulesList.x ::
          SystemApi.GetAllZipArchive.x :: SystemApi.GetDirectivesZipArchive.x :: SystemApi.GetGroupsZipArchive.x ::
          SystemApi.GetRulesZipArchive.x :: SystemApi.Info.x :: SystemApi.Status.x :: SystemApi.ArchivesParametersList.x ::
          SystemApi.GetParametersZipArchive.x :: SystemApi.GetHealthcheckResult.x :: PluginApi.GetPluginsSettings.x ::
          SettingsApi.GetAllowedNetworks.x :: SettingsApi.GetAllAllowedNetworks.x :: HookApi.GetHooks.x :: InfoApi.endpoints.map(
            _.x
          )
        case Administration.Write =>
          EventLogApi.RollbackEventLog.x :: PluginApi.UpdatePluginsSettings.x :: SettingsApi.ModifySettings.x :: SettingsApi.ModifySetting.x ::
          InventoryApi.FileWatcherRestart.x :: InventoryApi.FileWatcherStart.x :: InventoryApi.FileWatcherStop.x ::
          NodeApi.CreateNodes.x :: SystemApi.endpoints.map(_.x)
        case Administration.Edit  =>
          PluginApi.UpdatePluginsSettings.x :: SettingsApi.ModifySettings.x :: SettingsApi.ModifySetting.x ::
          SettingsApi.ModifyAllowedNetworks.x :: SettingsApi.ModifyDiffAllowedNetworks.x ::
          Nil

        case Compliance.Read  =>
          ComplianceApi.GetGlobalCompliance.x :: ComplianceApi.GetRulesCompliance.x :: ComplianceApi.GetRulesComplianceId.x ::
          ComplianceApi.GetNodesCompliance.x :: ComplianceApi.GetNodeComplianceId.x :: ChangesApi.GetRuleRepairedReports.x ::
          ChangesApi.GetRecentChanges.x :: ComplianceApi.GetDirectiveComplianceId.x :: ComplianceApi.GetNodeSystemCompliance.x ::
          ComplianceApi.GetDirectivesCompliance.x :: ComplianceApi.GetNodeGroupComplianceId.x :: ComplianceApi.GetNodeGroupComplianceTargetId.x ::
          ComplianceApi.GetNodeGroupComplianceSummary.x :: Nil
        case Compliance.Write => Nil
        case Compliance.Edit  => Nil

        case Configuration.Read  =>
          (Parameter.Read :: Technique.Read :: Directive.Read :: Rule.Read :: Nil).flatMap(c => mapAuthorization(c))
        case Configuration.Write =>
          (Parameter.Write :: Technique.Write :: Directive.Write :: Rule.Write :: Nil).flatMap(c => mapAuthorization(c))
        case Configuration.Edit  =>
          (Parameter.Edit :: Technique.Edit :: Directive.Edit :: Rule.Edit :: Nil).flatMap(c => mapAuthorization(c))

        case Deployment.Read  => Nil
        case Deployment.Write => Nil
        case Deployment.Edit  => Nil

        case Deployer.Read  => Nil // ChangeRequestApi.ListChangeRequests.x :: ChangeRequestApi.ChangeRequestsDetails.x :: Nil
        case Deployer.Write => Nil // ChangeRequestApi.DeclineRequestsDetails.x :: ChangeRequestApi.AcceptRequestsDetails.x :: Nil
        case Deployer.Edit  => Nil // ChangeRequestApi.UpdateRequestsDetails.x :: Nil

        case Parameter.Read  => ParameterApi.ListParameters.x :: ParameterApi.ParameterDetails.x :: Nil
        case Parameter.Write => ParameterApi.CreateParameter.x :: ParameterApi.DeleteParameter.x :: Nil
        case Parameter.Edit  => ParameterApi.UpdateParameter.x :: Nil

        case Directive.Read  =>
          DirectiveApi.ListDirectives.x :: DirectiveApi.DirectiveDetails.x ::
          DirectiveApi.DirectiveTree.x :: DirectiveApi.DirectiveRevisions.x ::
          Nil
        case Directive.Write =>
          DirectiveApi.CreateDirective.x :: DirectiveApi.DeleteDirective.x ::
          DirectiveApi.CheckDirective.x :: Nil
        case Directive.Edit  => DirectiveApi.UpdateDirective.x :: Nil

        case Group.Read  =>
          GroupApi.ListGroups.x :: GroupApi.GroupDetails.x :: GroupApi.GetGroupTree.x ::
          GroupApi.GetGroupCategoryDetails.x :: GroupApi.GroupInheritedProperties.x ::
          NodeApi.NodeDetailsTable.x :: GroupApi.GroupDisplayInheritedProperties.x ::
          GroupInternalApi.GetGroupCategoryTree.x :: Nil
        case Group.Write =>
          GroupApi.CreateGroup.x :: GroupApi.DeleteGroup.x :: GroupApi.ReloadGroup.x ::
          GroupApi.DeleteGroupCategory.x :: GroupApi.CreateGroupCategory.x :: Nil
        case Group.Edit  => GroupApi.UpdateGroup.x :: GroupApi.UpdateGroupCategory.x :: Nil

        case Node.Read  =>
          NodeApi.ListAcceptedNodes.x :: NodeApi.ListPendingNodes.x :: NodeApi.NodeDetails.x ::
          NodeApi.NodeInheritedProperties.x :: NodeApi.NodeDisplayInheritedProperties.x :: NodeApi.NodeDetailsTable.x ::
          NodeApi.PendingNodeDetails.x :: NodeApi.NodeDetailsSoftware.x :: NodeApi.NodeDetailsProperty.x ::
          NodeApi.GetNodesStatus.x :: InventoryApi.QueueInformation.x ::
          NodeApi.NodeGlobalScore.x :: NodeApi.NodeScoreDetail.x :: NodeApi.NodeScoreDetails.x ::
          NodeApi.GetNodesStatus.x ::
          // score about node
          NodeApi.NodeGlobalScore.x :: NodeApi.NodeScoreDetails.x :: NodeApi.NodeScoreDetail.x ::
          InventoryApi.QueueInformation.x ::
          // this compliance is more about how rudder works on that node, it's not really "compliance"
          ComplianceApi.GetNodeSystemCompliance.x ::
          // node read also allows to read some settings
          AuthzForApi.withValues(SettingsApi.GetSetting, AclPathSegment.Segment("global_policy_mode") :: Nil) ::
          AuthzForApi.withValues(SettingsApi.GetSetting, AclPathSegment.Segment("global_policy_mode_overridable") :: Nil) ::
          ScoreApi.GetScoreList.x ::
          Nil
        case Node.Write =>
          NodeApi.DeleteNode.x :: NodeApi.ChangePendingNodeStatus.x :: NodeApi.ChangePendingNodeStatus2.x ::
          NodeApi.ApplyPolicyAllNodes.x :: NodeApi.ApplyPolicy.x :: Nil
        case Node.Edit  => NodeApi.UpdateNode.x :: InventoryApi.UploadInventory.x :: Nil

        case Rule.Read  =>
          RuleApi.ListRules.x :: RuleApi.RuleDetails.x :: RuleApi.GetRuleTree.x ::
          RuleApi.GetRuleCategoryDetails.x :: RuleInternalApi.GetRuleNodesAndDirectives.x :: RuleInternalApi.GetGroupRelatedRules.x ::
          AuthzForApi.withValues(SettingsApi.GetSetting, AclPathSegment.Segment("enable_change_message") :: Nil) ::
          AuthzForApi.withValues(SettingsApi.GetSetting, AclPathSegment.Segment("enable_change_request") :: Nil) ::
          AuthzForApi.withValues(SettingsApi.GetSetting, AclPathSegment.Segment("enable_self_deployment") :: Nil) ::
          AuthzForApi.withValues(SettingsApi.GetSetting, AclPathSegment.Segment("enable_self_validation") :: Nil) ::
          AuthzForApi.withValues(SettingsApi.GetSetting, AclPathSegment.Segment("enable_validate_all") :: Nil) :: Nil
        case Rule.Write =>
          RuleApi.CreateRule.x :: RuleApi.DeleteRule.x :: RuleApi.CreateRuleCategory.x ::
          RuleApi.DeleteRuleCategory.x :: RuleApi.LoadRuleRevisionForGeneration.x :: RuleApi.UnloadRuleRevisionForGeneration.x ::
          Nil
        case Rule.Edit  => RuleApi.UpdateRule.x :: RuleApi.UpdateRuleCategory.x :: Nil

        case Technique.Read  =>
          TechniqueApi.ListTechniques.x :: TechniqueApi.ListTechniquesDirectives.x ::
          TechniqueApi.ListTechniqueDirectives.x :: TechniqueApi.TechniqueRevisions.x ::
          TechniqueApi.GetMethods.x :: TechniqueApi.GetTechniques.x ::
          TechniqueApi.GetAllTechniqueCategories.x :: TechniqueApi.GetResources.x :: TechniqueApi.GetNewResources.x ::
          TechniqueApi.GetTechniqueAllVersion.x :: TechniqueApi.GetTechnique.x :: Nil
        case Technique.Write =>
          TechniqueApi.CreateTechnique.x :: SystemApi.PoliciesUpdate.x :: SystemApi.PoliciesRegenerate.x ::
          TechniqueApi.DeleteTechnique.x :: Nil
        case Technique.Edit  =>
          TechniqueApi.UpdateTechnique.x :: SystemApi.PoliciesUpdate.x :: SystemApi.PoliciesRegenerate.x ::
          TechniqueApi.UpdateTechniques.x :: TechniqueApi.UpdateMethods.x :: Nil

        case UserAccount.Read  => UserApi.GetApiToken.x :: UserApi.GetTokenFeatureStatus.x :: Nil
        case UserAccount.Write => UserApi.CreateApiToken.x :: UserApi.DeleteApiToken.x :: Nil
        case UserAccount.Edit  => UserApi.UpdateApiToken.x :: Nil

        case Validator.Read =>
          AuthzForApi.withValues(SettingsApi.GetSetting, AclPathSegment.Segment("enable_change_message") :: Nil) ::
          AuthzForApi.withValues(SettingsApi.GetSetting, AclPathSegment.Segment("mandatory_change_message") :: Nil) ::
          AuthzForApi.withValues(SettingsApi.GetSetting, AclPathSegment.Segment("change_message_prompt") :: Nil) ::
          AuthzForApi.withValues(SettingsApi.GetSetting, AclPathSegment.Segment("enable_change_request") :: Nil) ::
          AuthzForApi.withValues(SettingsApi.GetSetting, AclPathSegment.Segment("enable_self_deployment") :: Nil) ::
          AuthzForApi.withValues(SettingsApi.GetSetting, AclPathSegment.Segment("enable_self_validation") :: Nil) ::
          AuthzForApi.withValues(SettingsApi.GetSetting, AclPathSegment.Segment("enable_validate_all") :: Nil) :: Nil

        case Validator.Write =>
          Nil // ChangeRequestApi.DeclineRequestsDetails.x :: ChangeRequestApi.AcceptRequestsDetails.x :: Nil
        case Validator.Edit  => Nil // ChangeRequestApi.UpdateRequestsDetails.x :: Nil
        case _               => Nil // Done within plugin
      }
    }
  }
}

/*
 * This class keep the mapping between a role and the list
 * of authorization it gets on all endpoints
 */
class RoleApiMapping(mapper: AuthorizationApiMapping) {

  // get the access control list from the user rights.
  // Always succeeds,
  def getApiAclFromRights(rights: Rights): List[ApiAclElement] = {
    // we have two shortbreakers, no rights and all rights
    if (rights.authorizationTypes.contains(AuthorizationType.NoRights)) {
      Nil
    } else if (rights.authorizationTypes.contains(AuthorizationType.AnyRights)) {
      ApiAuthz.allAuthz.acl
    } else {
      import cats.implicits.*
      // problem: here, rights.authorizationTypes is a set, so not ordered. Acl ARE
      // ordered. But this is OK **IF** we don't user any double joker (exhaustive match)
      mergeToAcl(rights.authorizationTypes.toList.foldMap(mapAuthorization))
    }
  }

  def getApiAclFromRoles(roles: Seq[Role]): List[ApiAclElement] = {
    getApiAclFromRights(Rights(roles.flatMap(_.rights.authorizationTypes)))
  }

  // a merge function that groups action for identical path
  def mergeToAcl(authz: List[ApiAclElement]): List[ApiAclElement] = {
    authz
      .groupBy(_.path)
      .map {
        case (path, seq) =>
          ApiAclElement(path, seq.flatMap(_.actions).toSet)
      }
      .toList
  }

  def mapAuthorization(authz: AuthorizationType): List[ApiAclElement] = {
    mapper.mapAuthorization(authz)
  }
}

/**
  * A role extension that can be used to define the priority of how roles should be composed from different role providers.
  */
sealed trait ProviderRoleExtension {
  def name:     String
  def priority: Int = this match {
    case ProviderRoleExtension.None         => 0
    case ProviderRoleExtension.NoOverride   => 1
    case ProviderRoleExtension.WithOverride => 2
  }
}
object ProviderRoleExtension       {
  case object None         extends ProviderRoleExtension { override val name: String = "none"        }
  case object NoOverride   extends ProviderRoleExtension { override val name: String = "no-override" }
  case object WithOverride extends ProviderRoleExtension { override val name: String = "override"    }

  implicit val ordering: Ordering[ProviderRoleExtension] = Ordering.by(_.priority)
}
