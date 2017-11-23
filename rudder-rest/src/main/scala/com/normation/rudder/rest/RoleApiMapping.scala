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

import com.normation.rudder.Rights
import com.normation.rudder.api.ApiAcl
import com.normation.rudder.AuthorizationType
import com.normation.rudder.api.ApiAuthz
import com.normation.rudder.Role
import com.normation.rudder.api.AclPathSegment

/*
 * This class keep the mapping between a role and the list
 * of authorization it gets on all endpoints
 *
 *
 *
 */
object RoleApiMapping {

  // get the access control list from the user rights.
  // Always succeeds,
  def getApiAclFromRights(rights: Rights): ApiAcl = {
    // we have to shortbreaker, no rights and all rigts
    if(rights.authorizationTypes.contains(AuthorizationType.NoRights)) {
      ApiAcl.noAuthz
    } else if(rights.authorizationTypes.contains(AuthorizationType.AnyRights)) {
      ApiAcl.allAuthz
    } else {
      import cats.implicits._
      // problem: here, rights.authorizationTypes is a set, so not ordered. Acl ARE
      // ordered. But this is OK **IF** we don't user any double joker (exhaustive match)
      mergeToAcl(rights.authorizationTypes.toList.foldMap(mapAuthorization))
    }
  }

  def getApiAclFromRoles(roles: Seq[Role]): ApiAcl = {
    getApiAclFromRights(new Rights(roles.flatMap( _.rights.authorizationTypes):_*))
  }

  // a merge fonction that group action for identical path
  def mergeToAcl(authz: List[ApiAuthz]): ApiAcl = {
    ApiAcl(authz.groupBy( _.path ).map { case (path, seq) =>
      ApiAuthz(path, seq.flatMap( _.actions ).toSet )
    }.toList)
  }

  // shorthand to get authz for a given api
  private implicit class ToAuthz(api: EndpointSchema) {
    def x: ApiAuthz = AuthzForApi(api)
  }

  def mapAuthorization(authz: AuthorizationType): List[ApiAuthz] = {
    authz match {
      case AuthorizationType.NoRights  => Nil
      case AuthorizationType.AnyRights => ApiAcl.allAuthz.acl
      // Administration is Rudder setting
      case AuthorizationType.Administration.Read  =>
        SettingsApi.GetAllSettings.x :: SettingsApi.GetSetting.x :: Nil
      case AuthorizationType.Administration.Write =>
        SettingsApi.ModifySettings.x :: SettingsApi.ModifySetting.x :: Nil
      case AuthorizationType.Administration.Edit  =>
        SettingsApi.ModifySettings.x :: SettingsApi.ModifySetting.x :: Nil
      // Configuration doesn't give API rights.
      // But as nothing manage parameter API, I think it's the correct place
      // to add it.
      case AuthorizationType.Configuration.Read   =>
        ParameterApi.ListParameters.x :: ParameterApi.ParameterDetails.x :: Nil
      case AuthorizationType.Configuration.Write  =>
        ParameterApi.CreateParameter.x :: ParameterApi.DeleteParameter.x :: Nil
      case AuthorizationType.Configuration.Edit   =>
        ParameterApi.UpdateParameter.x :: Nil
      case AuthorizationType.Node.Read   =>
        NodeApi.ListAcceptedNodes.x :: NodeApi.ListPendingNodes.x :: NodeApi.NodeDetails.x ::
        // node read also allows to read some settings
        AuthzForApi.withValues(SettingsApi.GetSetting, AclPathSegment.Segment("global_policy_mode") :: Nil ) ::
        AuthzForApi.withValues(SettingsApi.GetSetting, AclPathSegment.Segment("global_policy_mode_overridable") :: Nil ) ::
        Nil
      case AuthorizationType.Node.Write  =>
        NodeApi.DeleteNode.x :: NodeApi.ChangePendingNodeStatus.x :: NodeApi.ChangePendingNodeStatus2.x ::
        NodeApi.ApplyPocicyAllNodes.x :: NodeApi.ApplyPolicy.x :: Nil
      case AuthorizationType.Node.Edit   =>
        NodeApi.UpdateNode.x :: Nil
      case AuthorizationType.Directive.Read   =>
        DirectiveApi.ListDirectives.x :: DirectiveApi.DirectiveDetails.x :: Nil
      case AuthorizationType.Directive.Write  =>
        DirectiveApi.CreateDirective.x :: DirectiveApi.DeleteDirective.x :: DirectiveApi.CheckDirective.x :: Nil
      case AuthorizationType.Directive.Edit   =>
        DirectiveApi.UpdateDirective.x :: Nil
      case AuthorizationType.Technique.Read   =>
        TechniqueApi.ListTechniques.x :: TechniqueApi.ListTechniquesDirectives.x :: TechniqueApi.ListTechniqueDirectives.x :: Nil
      case AuthorizationType.Technique.Write  =>
        Nil
      case AuthorizationType.Technique.Edit   =>
        Nil
      case AuthorizationType.Group.Read   =>
        GroupApi.ListGroups.x :: GroupApi.GroupDetails.x :: GroupApi.GetGroupTree.x :: GroupApi.GetGroupCategoryDetails.x :: Nil
      case AuthorizationType.Group.Write  =>
        GroupApi.CreateGroup.x :: GroupApi.DeleteGroup.x :: GroupApi.ReloadGroup.x ::
        GroupApi.DeleteGroupCategory.x :: GroupApi.CreateGroupCategory.x :: Nil
      case AuthorizationType.Group.Edit   =>
        GroupApi.UpdateGroup.x :: GroupApi.UpdateGroupCategory.x :: Nil
      case AuthorizationType.Validator.Read   =>
        ChangeRequestApi.ListChangeRequests.x :: ChangeRequestApi.ChangeRequestsDetails.x :: Nil
      case AuthorizationType.Validator.Write  =>
        ChangeRequestApi.DeclineRequestsDetails.x :: ChangeRequestApi.AcceptRequestsDetails.x :: Nil
      case AuthorizationType.Validator.Edit   =>
        ChangeRequestApi.UpdateRequestsDetails.x :: Nil
      case AuthorizationType.Deployer.Read   =>
        ChangeRequestApi.ListChangeRequests.x :: ChangeRequestApi.ChangeRequestsDetails.x :: Nil
      case AuthorizationType.Deployer.Write  =>
        ChangeRequestApi.DeclineRequestsDetails.x :: ChangeRequestApi.AcceptRequestsDetails.x :: Nil
      case AuthorizationType.Deployer.Edit   =>
        ChangeRequestApi.UpdateRequestsDetails.x :: Nil
      case AuthorizationType.Deployment.Read   => Nil
      case AuthorizationType.Deployment.Write  => Nil
      case AuthorizationType.Deployment.Edit   => Nil
      case AuthorizationType.Rule.Read   =>
        RuleApi.ListRules.x :: RuleApi.RuleDetails.x :: RuleApi.GetRuleTree.x :: RuleApi.GetRuleCategoryDetails.x :: Nil
      case AuthorizationType.Rule.Write  =>
        RuleApi.CreateRule.x :: RuleApi.DeleteRule.x :: RuleApi.CreateRuleCategory.x :: RuleApi.DeleteRuleCategory.x :: Nil
      case AuthorizationType.Rule.Edit   =>
        RuleApi.UpdateRule.x :: RuleApi.UpdateRuleCategory.x :: Nil
      case AuthorizationType.UserAccount.Read =>
        UserApi.GetApiToken.x :: Nil
      case AuthorizationType.UserAccount.Write =>
        Nil
      case AuthorizationType.UserAccount.Edit =>
        UserApi.CreateApiToken.x :: UserApi.DeleteApiToken.x :: Nil
    }
  }
}

