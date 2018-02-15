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
import com.normation.rudder.api.{ApiAuthorization => ApiAuthz}
import com.normation.rudder.AuthorizationType
import com.normation.rudder.api.ApiAclElement
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
  def getApiAclFromRights(rights: Rights): List[ApiAclElement] = {
    // we have two shortbreakers, no rights and all rigts
    if(rights.authorizationTypes.contains(AuthorizationType.NoRights)) {
      Nil
    } else if(rights.authorizationTypes.contains(AuthorizationType.AnyRights)) {
      ApiAuthz.allAuthz.acl
    } else {
      import cats.implicits._
      // problem: here, rights.authorizationTypes is a set, so not ordered. Acl ARE
      // ordered. But this is OK **IF** we don't user any double joker (exhaustive match)
      mergeToAcl(rights.authorizationTypes.toList.foldMap(mapAuthorization))
    }
  }

  def getApiAclFromRoles(roles: Seq[Role]): List[ApiAclElement] = {
    getApiAclFromRights(new Rights(roles.flatMap( _.rights.authorizationTypes):_*))
  }

  // a merge fonction that group action for identical path
  def mergeToAcl(authz: List[ApiAclElement]): List[ApiAclElement] = {
    authz.groupBy( _.path ).map { case (path, seq) =>
      ApiAclElement(path, seq.flatMap( _.actions ).toSet )
    }.toList
  }

  // shorthand to get authz for a given api
  private implicit class ToAuthz(api: EndpointSchema) {
    def x: ApiAclElement = AuthzForApi(api)
  }

  def mapAuthorization(authz: AuthorizationType): List[ApiAclElement] = {
    import AuthorizationType._

    authz match {
      case NoRights             => Nil
      case AnyRights            => ApiAuthz.allAuthz.acl
      // Administration is Rudder setting

      case Administration.Read  => SettingsApi.GetAllSettings.x :: SettingsApi.GetSetting.x :: Nil
      case Administration.Write => SettingsApi.ModifySettings.x :: SettingsApi.ModifySetting.x :: Nil
      case Administration.Edit  => SettingsApi.ModifySettings.x :: SettingsApi.ModifySetting.x :: Nil

      case Compliance.Read      => ComplianceApi.GetGlobalCompliance.x :: ComplianceApi.GetRulesCompliance.x ::
                                   ComplianceApi.GetRulesComplianceId.x :: ComplianceApi.GetNodesCompliance.x ::
                                   ComplianceApi.GetNodeComplianceId.x :: Nil
      case Compliance.Write     => Nil
      case Compliance.Edit      => Nil

      // Configuration doesn't give API rights.
      // But as nothing manage parameter API, I think it's the correct place
      // to add it.
      case Configuration.Read   => ParameterApi.ListParameters.x :: ParameterApi.ParameterDetails.x :: Nil
      case Configuration.Write  => ParameterApi.CreateParameter.x :: ParameterApi.DeleteParameter.x :: Nil
      case Configuration.Edit   => ParameterApi.UpdateParameter.x :: Nil

      case Deployment.Read      => Nil
      case Deployment.Write     => Nil
      case Deployment.Edit      => Nil

      case Deployer.Read        => ChangeRequestApi.ListChangeRequests.x :: ChangeRequestApi.ChangeRequestsDetails.x :: Nil
      case Deployer.Write       => ChangeRequestApi.DeclineRequestsDetails.x :: ChangeRequestApi.AcceptRequestsDetails.x :: Nil
      case Deployer.Edit        => ChangeRequestApi.UpdateRequestsDetails.x :: Nil

      case Directive.Read       => DirectiveApi.ListDirectives.x :: DirectiveApi.DirectiveDetails.x :: Nil
      case Directive.Write      => DirectiveApi.CreateDirective.x :: DirectiveApi.DeleteDirective.x ::
                                   DirectiveApi.CheckDirective.x :: Nil
      case Directive.Edit       => DirectiveApi.UpdateDirective.x :: Nil

      case Group.Read           => GroupApi.ListGroups.x :: GroupApi.GroupDetails.x :: GroupApi.GetGroupTree.x ::
                                   GroupApi.GetGroupCategoryDetails.x :: Nil
      case Group.Write          => GroupApi.CreateGroup.x :: GroupApi.DeleteGroup.x :: GroupApi.ReloadGroup.x ::
                                   GroupApi.DeleteGroupCategory.x :: GroupApi.CreateGroupCategory.x :: Nil
      case Group.Edit           => GroupApi.UpdateGroup.x :: GroupApi.UpdateGroupCategory.x :: Nil

      case Node.Read            => NodeApi.ListAcceptedNodes.x :: NodeApi.ListPendingNodes.x :: NodeApi.NodeDetails.x ::
                                   // node read also allows to read some settings
                                   AuthzForApi.withValues(SettingsApi.GetSetting, AclPathSegment.Segment("global_policy_mode") :: Nil ) ::
                                   AuthzForApi.withValues(SettingsApi.GetSetting, AclPathSegment.Segment("global_policy_mode_overridable") :: Nil ) ::
                                   Nil
      case Node.Write           => NodeApi.DeleteNode.x :: NodeApi.ChangePendingNodeStatus.x :: NodeApi.ChangePendingNodeStatus2.x ::
                                   NodeApi.ApplyPocicyAllNodes.x :: NodeApi.ApplyPolicy.x :: Nil
      case Node.Edit            => NodeApi.UpdateNode.x :: Nil

      case Rule.Read            => RuleApi.ListRules.x :: RuleApi.RuleDetails.x :: RuleApi.GetRuleTree.x ::
                                   RuleApi.GetRuleCategoryDetails.x :: Nil
      case Rule.Write           => RuleApi.CreateRule.x :: RuleApi.DeleteRule.x :: RuleApi.CreateRuleCategory.x ::
                                   RuleApi.DeleteRuleCategory.x :: Nil
      case Rule.Edit            => RuleApi.UpdateRule.x :: RuleApi.UpdateRuleCategory.x :: Nil

      case Technique.Read       => TechniqueApi.ListTechniques.x :: TechniqueApi.ListTechniquesDirectives.x ::
                                   TechniqueApi.ListTechniqueDirectives.x :: Nil
      case Technique.Write      => Nil
      case Technique.Edit       => Nil

      case UserAccount.Read     => UserApi.GetApiToken.x :: Nil
      case UserAccount.Write    => Nil
      case UserAccount.Edit     => UserApi.CreateApiToken.x :: UserApi.DeleteApiToken.x :: Nil

      case Validator.Read       => ChangeRequestApi.ListChangeRequests.x :: ChangeRequestApi.ChangeRequestsDetails.x :: Nil
      case Validator.Write      => ChangeRequestApi.DeclineRequestsDetails.x :: ChangeRequestApi.AcceptRequestsDetails.x :: Nil
      case Validator.Edit       => ChangeRequestApi.UpdateRequestsDetails.x :: Nil
    }
  }
}

