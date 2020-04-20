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
* In accordance with OneParam with the terms of section 7 (7. Additional Terms.) of
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

import com.normation.rudder.rest.EndpointSchema.syntax._
import com.normation.rudder.api.HttpAction._
import sourcecode.Line


/*
 * This file contains the definition of all endpoint schema
 * in Rudder base.
 *
 * Any module wanting to contribute an API
 * More preciselly, it defines the data format of
 * an endpoint descriptor, an endpoint descriptor container,
 * and prefil all the known endpoints into the corner.
 *
 * It also defined interpretor for endpoint descriptor toward
 * Lift RestAPI objects.
 *
 */

// we need a marker trait to get endpoint in a sorted way. Bad, but nothing better
// safe rewriting sealerate
trait SortIndex {
  protected[rest] def z: Int
}

sealed trait ComplianceApi extends EndpointSchema with GeneralApi with SortIndex
object ComplianceApi extends ApiModuleProvider[ComplianceApi] {

  final case object GetRulesCompliance extends ComplianceApi with ZeroParam with StartsAtVersion7 with SortIndex { val z = implicitly[Line].value
    val description = "Get compliance information for all rules"
    val (action, path)  = GET / "compliance" / "rules"
  }
  final case object GetRulesComplianceId extends ComplianceApi with OneParam with StartsAtVersion7 with SortIndex { val z = implicitly[Line].value
    val description = "Get compliance information for the given rule"
    val (action, path)  = GET / "compliance" / "rules" / "{id}"
  }
  final case object GetNodesCompliance extends ComplianceApi with ZeroParam with StartsAtVersion7 with SortIndex { val z = implicitly[Line].value
    val description = "Get compliance information for all nodes"
    val (action, path)  = GET / "compliance" / "nodes"
  }
  final case object GetNodeComplianceId extends ComplianceApi with OneParam with StartsAtVersion7 with SortIndex { val z = implicitly[Line].value
    val description = "Get compliance information for the given node"
    val (action, path)  = GET / "compliance" / "nodes" / "{id}"
  }
  final case object GetGlobalCompliance extends ComplianceApi with ZeroParam with StartsAtVersion10 with SortIndex { val z = implicitly[Line].value
    val description = "Get the global compliance (alike what one has on Rudder main dashboard)"
    val (action, path)  = GET / "compliance"
  }

  def endpoints = ca.mrvisser.sealerate.values[ComplianceApi].toList.sortBy( _.z )
}

sealed trait GroupApi extends EndpointSchema with GeneralApi with SortIndex
object GroupApi extends ApiModuleProvider[GroupApi] {
  // API v2
  final case object ListGroups extends GroupApi with ZeroParam with StartsAtVersion2 with SortIndex { val z = implicitly[Line].value
    val description = "List all groups with their information"
    val (action, path)  = GET / "groups"
  }
  final case object CreateGroup extends GroupApi with ZeroParam with StartsAtVersion2 with SortIndex { val z = implicitly[Line].value
    val description = "Create a new group"
    val (action, path)  = PUT / "groups"
  }
  final case object GetGroupTree extends GroupApi with ZeroParam with StartsAtVersion6 with SortIndex { val z = implicitly[Line].value
    val description = "List all group categories and group in a tree format"
    val (action, path)  = GET / "groups" / "tree"
  }
  final case object GroupDetails extends GroupApi with OneParam with StartsAtVersion2 with SortIndex { val z = implicitly[Line].value
    val description = "Get information about the given group"
    val (action, path)  = GET / "groups" / "{id}"
  }
  final case object DeleteGroup extends GroupApi with OneParam with StartsAtVersion2 with SortIndex { val z = implicitly[Line].value
    val description = "Delete given group"
    val (action, path)  = DELETE / "groups" / "{id}"
  }
  final case object UpdateGroup extends GroupApi with OneParam with StartsAtVersion2 with SortIndex { val z = implicitly[Line].value
    val description = "Update given group"
    val (action, path)  = POST / "groups" / "{id}"
  }
  final case object ReloadGroup extends GroupApi with OneParam with StartsAtVersion2 with SortIndex { val z = implicitly[Line].value
    val description = "Update given dynamic group node list"
    val (action, path)  = GET / "groups" / "{id}" / "reload"
  }
  // API v5 updates 'Create' methods but no new endpoints
  // API v6

  final case object GetGroupCategoryDetails extends GroupApi with OneParam with StartsAtVersion6 with SortIndex { val z = implicitly[Line].value
    val description = "Get information about the given group category"
    val (action, path)  = GET / "groups" / "categories" / "{id}"
  }
  final case object DeleteGroupCategory extends GroupApi with OneParam with StartsAtVersion6 with SortIndex { val z = implicitly[Line].value
    val description = "Delete given group category"
    val (action, path)  = DELETE / "groups" / "categories" / "{id}"
  }
  final case object UpdateGroupCategory extends GroupApi with OneParam with StartsAtVersion6 with SortIndex { val z = implicitly[Line].value
    val description = "Update information for given group category"
    val (action, path)  = POST / "groups" / "categories" / "{id}"
  }
  final case object CreateGroupCategory extends GroupApi with ZeroParam with StartsAtVersion6 with SortIndex { val z = implicitly[Line].value
    val description = "Create a new group category"
    val (action, path)  = PUT / "groups" / "categories"
  }

  def endpoints = ca.mrvisser.sealerate.values[GroupApi].toList.sortBy( _.z )
}

sealed trait DirectiveApi extends EndpointSchema with GeneralApi with SortIndex
object DirectiveApi extends ApiModuleProvider[DirectiveApi] {

  final case object ListDirectives extends DirectiveApi with ZeroParam with StartsAtVersion2 with SortIndex { val z = implicitly[Line].value
    val description = "List all directives"
    val (action, path)  = GET / "directives"
  }
  final case object DirectiveDetails extends DirectiveApi with OneParam with StartsAtVersion2 with SortIndex { val z = implicitly[Line].value
    val description = "Get information about given directive"
    val (action, path)  = GET / "directives" / "{id}"
  }
  final case object CreateDirective extends DirectiveApi with ZeroParam with StartsAtVersion2 with SortIndex { val z = implicitly[Line].value
    val description = "Create a new directive"
    val (action, path)  = PUT / "directives"
  }
  final case object DeleteDirective extends DirectiveApi with OneParam with StartsAtVersion2 with SortIndex { val z = implicitly[Line].value
    val description = "Delete given directive"
    val (action, path)  = DELETE / "directives" / "{id}"
  }
  final case object CheckDirective extends DirectiveApi with OneParam with StartsAtVersion2 with SortIndex { val z = implicitly[Line].value
    val description = "Check if the given directive can be migrated to target technique version"
    val (action, path)  = POST / "directives" / "{id}" / "check"
  }
  final case object UpdateDirective extends DirectiveApi with OneParam with StartsAtVersion2 with SortIndex { val z = implicitly[Line].value
    val description = "Update given directive information"
    val (action, path)  = POST / "directives" / "{id}"
  }

  def endpoints = ca.mrvisser.sealerate.values[DirectiveApi].toList.sortBy( _.z )
}

sealed trait NcfApi extends EndpointSchema with GeneralApi with SortIndex
object NcfApi extends ApiModuleProvider[NcfApi] {

  final case object UpdateTechnique extends NcfApi with ZeroParam with StartsAtVersion9 with SortIndex { val z = implicitly[Line].value
    val description = "Update technique created with technique editor"
    val (action, path)  = POST / "techniques"
  }
  final case object CreateTechnique extends NcfApi with ZeroParam with StartsAtVersion9 with SortIndex { val z = implicitly[Line].value
    val description = "Create a new technique in Rudder from a technique in the technique editor"
    val (action, path)  = PUT / "techniques"
  }
  final case object DeleteTechnique extends NcfApi with TwoParam with StartsAtVersion9 with SortIndex { val z = implicitly[Line].value
    val description = "Delete a technique from technique editor"
    val (action, path)  = DELETE / "techniques" / "{techniqueId}" / "{techniqueVersion}"
  }
  final case object GetResources extends NcfApi with TwoParam with StartsAtVersion15 with SortIndex { val z = implicitly[Line].value
    val description = "Get currently deployed resources of a technique"
    val (action, path)  = GET / "techniques" / "{techniqueId}" / "{techniqueVersion}" / "resources"
  }
  final case object GetNewResources extends NcfApi with TwoParam with StartsAtVersion15 with SortIndex { val z = implicitly[Line].value
    val description = "Get resources of a new technique"
    val (action, path)  = GET / "techniques" / "{techniqueId}" / "new" / "{techniqueVersion}" / "resources"
  }
  final case object ParameterCheck extends NcfApi with ZeroParam with StartsAtVersion15 with SortIndex { val z = implicitly[Line].value
    val description = "Get currently deployed resources of a technique"
    val (action, path)  = POST / "techniques" / "parameter" / "check"
  }
  final case object GetTechniques extends NcfApi with ZeroParam with StartsAtVersion15 with SortIndex { val z = implicitly[Line].value
    val description = "Get all Techniques metadata"
    val (action, path)  = GET / "ncf" / "techniques"
  }
  final case object GetMethods extends NcfApi with ZeroParam with StartsAtVersion15 with SortIndex { val z = implicitly[Line].value
    val description = "Get all methods metadata"
    val (action, path)  = GET / "ncf" / "methods"
  }
  final case object UpdateMethods extends NcfApi with ZeroParam with StartsAtVersion15 with SortIndex { val z = implicitly[Line].value
    val description = "reload methods metadata from file system"
    val (action, path)  = POST / "ncf" / "reload" / "methods"
  }
  final case object UpdateTechniques extends NcfApi with ZeroParam with StartsAtVersion15 with SortIndex { val z = implicitly[Line].value
    val description = "reload techniques metadata from file system"
    val (action, path)  = POST / "ncf" / "reload" / "techniques"
  }

  def endpoints = ca.mrvisser.sealerate.values[NcfApi].toList.sortBy( _.z )
}

sealed trait NodeApi extends EndpointSchema with GeneralApi with SortIndex
object NodeApi extends ApiModuleProvider[NodeApi] {

  final case object ListAcceptedNodes extends NodeApi with ZeroParam with StartsAtVersion2 with SortIndex { val z = implicitly[Line].value
    val description = "List all accepted nodes with configurable details level"
    val (action, path)  = GET / "nodes"
  }
  final case object ListPendingNodes extends NodeApi with ZeroParam with StartsAtVersion2 with SortIndex { val z = implicitly[Line].value
    val description = "List all pending nodes with configurable details level"
    val (action, path)  = GET / "nodes" / "pending"
  }
  final case object PendingNodeDetails extends NodeApi with OneParam with StartsAtVersion2 with SortIndex { val z = implicitly[Line].value
    val description = "Get information about the given pending node"
    val (action, path)  = GET / "nodes" / "pending" / "{id}"
  }
  final case object NodeDetails extends NodeApi with OneParam with StartsAtVersion2 with SortIndex { val z = implicitly[Line].value
    val description = "Get information about the given accepted node"
    val (action, path)  = GET / "nodes" / "{id}"
  }
  final case object ApplyPocicyAllNodes extends NodeApi with ZeroParam with StartsAtVersion8 with SortIndex { val z = implicitly[Line].value
    val description = "Ask all nodes to start a run with the given policy"
    val (action, path)  = POST / "nodes" / "applyPolicy"
  }
  final case object ChangePendingNodeStatus extends NodeApi with ZeroParam with StartsAtVersion2 with SortIndex { val z = implicitly[Line].value
    val description = "Accept or refuse pending nodes"
    val (action, path)  = POST / "nodes" / "pending"
  }
  final case object UpdateNode extends NodeApi with OneParam with StartsAtVersion5 with SortIndex { val z = implicitly[Line].value
    val description = "Update given node information (node properties, policy mode...)"
    val (action, path)  = POST / "nodes" / "{id}"
  }
  final case object DeleteNode extends NodeApi with OneParam with StartsAtVersion2 with SortIndex { val z = implicitly[Line].value
    val description = "Delete given node"
    val (action, path)  = DELETE / "nodes" / "{id}"
  }
  final case object ChangePendingNodeStatus2 extends NodeApi with OneParam with StartsAtVersion2 with SortIndex { val z = implicitly[Line].value
    override val name = "ChangePendingNodeStatus"
    val description = "Accept or refuse given pending node"
    val (action, path)  = POST / "nodes" / "pending" / "{id}"
  }
  final case object ApplyPolicy extends NodeApi with OneParam with StartsAtVersion8 with SortIndex { val z = implicitly[Line].value
    val description = "Ask given node to start a run with the given policy"
    val (action, path)  = POST / "nodes" / "{id}" / "applyPolicy"
  }

  def endpoints = ca.mrvisser.sealerate.values[NodeApi].toList.sortBy( _.z )
}
sealed trait ParameterApi extends EndpointSchema with GeneralApi with SortIndex
object ParameterApi extends ApiModuleProvider[ParameterApi] {

  final case object ListParameters extends ParameterApi with ZeroParam with StartsAtVersion2 with SortIndex { val z = implicitly[Line].value
    val description = "List all global parameters"
    val (action, path)  = GET / "parameters"
  }
  final case object CreateParameter extends ParameterApi with ZeroParam with StartsAtVersion2 with SortIndex { val z = implicitly[Line].value
    val description = "Create a new parameter"
    val (action, path)  = PUT / "parameters"
  }
  final case object ParameterDetails extends ParameterApi with OneParam with StartsAtVersion2 with SortIndex { val z = implicitly[Line].value
    val description = "Get information about the given parameter"
    val (action, path)  = GET / "parameters" / "{id}"
  }
  final case object DeleteParameter extends ParameterApi with OneParam with StartsAtVersion2 with SortIndex { val z = implicitly[Line].value
    val description = "Delete given parameter"
    val (action, path)  = DELETE / "parameters" / "{id}"
  }
  final case object UpdateParameter extends ParameterApi with OneParam with StartsAtVersion2 with SortIndex { val z = implicitly[Line].value
    val description = "Update information about given parameter"
    val (action, path)  = POST / "parameters" / "{id}"
  }

  def endpoints = ca.mrvisser.sealerate.values[ParameterApi].toList.sortBy( _.z )
}

sealed trait SettingsApi extends EndpointSchema with GeneralApi with SortIndex
object SettingsApi extends ApiModuleProvider[SettingsApi] {
  final case object GetAllSettings extends SettingsApi with ZeroParam with StartsAtVersion6 with SortIndex { val z = implicitly[Line].value
    val description = "Get information about all Rudder settings"
    val (action, path)  = GET / "settings"
  }
  final case object GetAllAllowedNetworks extends SettingsApi with ZeroParam with StartsAtVersion11 with SortIndex { val z = implicitly[Line].value
    val description = "List all allowed networks"
    val (action, path)  = GET / "settings" / "allowed_networks"
  }
  final case object GetAllowedNetworks extends SettingsApi with OneParam with StartsAtVersion11 with SortIndex { val z = implicitly[Line].value
    val description = "List all allowed networks for one relay"
    val (action, path)  = GET / "settings" / "allowed_networks" / "{nodeId}"
  }
  final case object ModifyAllowedNetworks extends SettingsApi with OneParam with StartsAtVersion11 with SortIndex { val z = implicitly[Line].value
    val description = "Update all allowed networks for one relay"
    val (action, path)  = POST / "settings" / "allowed_networks" / "{nodeId}"
  }

  final case object GetSetting extends SettingsApi with OneParam with StartsAtVersion6 with SortIndex { val z = implicitly[Line].value
    val description = "Get information about given Rudder setting"
    val (action, path)  = GET / "settings" / "{key}"
  }
  final case object ModifySettings extends SettingsApi with ZeroParam with StartsAtVersion6 with SortIndex { val z = implicitly[Line].value
    val description = "Update Rudder settings"
    val (action, path)  = POST / "settings"
  }
  final case object ModifySetting extends SettingsApi with OneParam with StartsAtVersion6 with SortIndex { val z = implicitly[Line].value
    val description = "Update given Rudder setting"
    val (action, path)  = POST / "settings" / "{key}"
  }


  def endpoints = ca.mrvisser.sealerate.values[SettingsApi].toList.sortBy( _.z )
}

sealed trait TechniqueApi extends EndpointSchema with GeneralApi with SortIndex
object TechniqueApi extends ApiModuleProvider[TechniqueApi] {

  final case object ListTechniques extends TechniqueApi with ZeroParam with StartsAtVersion6 with SortIndex { val z = implicitly[Line].value
    val description = "List all techniques"
    val (action, path)  = GET / "techniques"
  }
  final case object ListTechniquesDirectives extends TechniqueApi with OneParam with StartsAtVersion6 with SortIndex { val z = implicitly[Line].value
    val description = "List directives derived from given technique"
    val (action, path)  = GET / "techniques" / "{name}" / "directives"
  }
  final case object ListTechniqueDirectives extends TechniqueApi with TwoParam with StartsAtVersion6 with SortIndex { val z = implicitly[Line].value
    val description = "List directives derived from given technique for given version"
    val (action, path)  = GET / "techniques" / "{name}" / "{version}" / "directives"
  }

  def endpoints = ca.mrvisser.sealerate.values[TechniqueApi].toList.sortBy( _.z )
}

sealed trait RuleApi extends EndpointSchema with GeneralApi with SortIndex
object RuleApi extends ApiModuleProvider[RuleApi] {

  final case object ListRules extends RuleApi with ZeroParam with StartsAtVersion2 with SortIndex { val z = implicitly[Line].value
    val description = "List all rules with their information"
    val (action, path)  = GET / "rules"
  }
  final case object CreateRule extends RuleApi with ZeroParam with StartsAtVersion2 with SortIndex { val z = implicitly[Line].value
    val description = "Create a new rule"
    val (action, path)  = PUT / "rules"
  }
  // must be before rule details, else it is never reached
  final case object GetRuleTree extends RuleApi with ZeroParam with StartsAtVersion6 with SortIndex { val z = implicitly[Line].value
    val description = "Get rule categories and rule structured in a tree format"
    val (action, path)  = GET / "rules" / "tree"
  }
  final case object RuleDetails extends RuleApi with OneParam with StartsAtVersion2 with SortIndex { val z = implicitly[Line].value
    val description = "Get information about given rule"
    val (action, path)  = GET / "rules" / "{id}"
  }
  final case object DeleteRule extends RuleApi with OneParam with StartsAtVersion2 with SortIndex { val z = implicitly[Line].value
    val description = "Delete given rule"
    val (action, path)  = DELETE / "rules" / "{id}"
  }
  final case object UpdateRule extends RuleApi with OneParam with StartsAtVersion2 with SortIndex { val z = implicitly[Line].value
    val description = "Update information about given rule"
    val (action, path)  = POST / "rules" / "{id}"
  }
  final case object GetRuleCategoryDetails extends RuleApi with OneParam with StartsAtVersion6 with SortIndex { val z = implicitly[Line].value
    val description = "Get information about given rule category"
    val (action, path)  = GET / "rules" / "categories" / "{id}"
  }
  final case object DeleteRuleCategory extends RuleApi with OneParam with StartsAtVersion6 with SortIndex { val z = implicitly[Line].value
    val description = "Delete given category"
    val (action, path)  = DELETE / "rules" / "categories" / "{id}"
  }
  final case object UpdateRuleCategory extends RuleApi with OneParam with StartsAtVersion6 with SortIndex { val z = implicitly[Line].value
    val description = "Update information about given rule category"
    val (action, path)  = POST / "rules" / "categories" / "{id}"
  }
  final case object CreateRuleCategory extends RuleApi with ZeroParam with StartsAtVersion6 with SortIndex { val z = implicitly[Line].value
    val description = "Create a new rule category"
    val (action, path)  = PUT / "rules" / "categories"
  }

  def endpoints = ca.mrvisser.sealerate.values[RuleApi].toList.sortBy( _.z )
}

sealed trait SystemApi extends EndpointSchema with GeneralApi with SortIndex
object SystemApi extends ApiModuleProvider[SystemApi] {

  final case object Info extends SystemApi with ZeroParam with StartsAtVersion10 with SortIndex { val z = implicitly[Line].value
    val description = "Get information about system installation (version, etc)"
    val (action, path) = GET / "system" / "info"
  }

  final case object Status extends SystemApi with ZeroParam with StartsAtVersion11 with SortIndex { val z = implicitly[Line].value
    val description = "Get Api status"
    val (action, path) = GET / "system" / "status"
  }

  final case object DebugInfo extends SystemApi with ZeroParam with StartsAtVersion11 with SortIndex { val z = implicitly[Line].value
    val description =  "Launch the support info script and get the result"
    val (action, path) = GET / "system" / "debug" / "info"

  }

  // For now, the techniques reload endpoint is implemented in the System API
  // but moving it inside the Techniques API should be discussed.

  final case object ReloadAll extends SystemApi with ZeroParam with StartsAtVersion11 with SortIndex { val z = implicitly[Line].value
    val description    = "reload both techniques and dynamic groups"
    val (action, path) = POST / "system" / "reload"
  }

  final case object TechniquesReload extends SystemApi with ZeroParam with StartsAtVersion11 with SortIndex { val z = implicitly[Line].value
    val description    = "reload all techniques" // automatically done every 5 minutes
    val (action, path) = POST / "system" / "reload" / "techniques"
  }

  final case object DyngroupsReload extends SystemApi with ZeroParam with StartsAtVersion11 with SortIndex { val z = implicitly[Line].value
    val description    = "reload all dynamic groups"
    val (action, path) = POST / "system" / "reload" / "groups"
  }

  final case object PoliciesUpdate extends SystemApi with ZeroParam with StartsAtVersion11 with SortIndex { val z = implicitly[Line].value
    val description    = "update policies"
    val (action, path) = POST / "system" / "update" / "policies"
  }

  final case object PoliciesRegenerate extends SystemApi with ZeroParam with StartsAtVersion11 with SortIndex { val z = implicitly[Line].value
    val description    = "regenerate all policies"
    val (action, path) = POST / "system" / "regenerate" / "policies"
  }

  // Archive list endpoints

  final case object ArchivesGroupsList extends SystemApi with ZeroParam with StartsAtVersion11 with SortIndex { val z = implicitly[Line].value
    val description    = "list groups archives"
    val (action, path) = GET / "system" / "archives" / "groups"
  }

  final case object ArchivesDirectivesList extends SystemApi with ZeroParam with StartsAtVersion11 with SortIndex { val z = implicitly[Line].value
    val description    = "list directives archives"
    val (action, path) = GET / "system" / "archives" / "directives"
  }

  final case object ArchivesRulesList extends SystemApi with ZeroParam with StartsAtVersion11 with SortIndex { val z = implicitly[Line].value
    val description    = "list rules archives"
    val (action, path) = GET / "system" / "archives" / "rules"
  }

  final case object ArchivesFullList extends SystemApi with ZeroParam with StartsAtVersion11 with SortIndex { val z = implicitly[Line].value
    val description    = "list all archives"
    val (action, path) = GET / "system" / "archives" / "full"
  }

  //Archive restore endpoints

  // Latest archive

  final case object RestoreGroupsLatestArchive extends SystemApi with ZeroParam with StartsAtVersion11 with SortIndex { val z = implicitly[Line].value
    val description    = "restore groups latest archive"
    val (action, path) = POST / "system" / "archives" / "groups" / "restore" / "latestArchive"
  }

  final case object RestoreDirectivesLatestArchive extends SystemApi with ZeroParam with StartsAtVersion11 with SortIndex { val z = implicitly[Line].value
    val description    = "restore directives latest archive"
    val (action, path) = POST / "system" / "archives" / "directives" / "restore" / "latestArchive"
  }

  final case object RestoreRulesLatestArchive extends SystemApi with ZeroParam with StartsAtVersion11 with SortIndex { val z = implicitly[Line].value
    val description    = "restore rules latest archive"
    val (action, path) = POST / "system" / "archives" / "rules" / "restore" / "latestArchive"
  }

  final case object RestoreFullLatestArchive extends SystemApi with ZeroParam with StartsAtVersion11 with SortIndex { val z = implicitly[Line].value
    val description    = "restore all latest archive"
    val (action, path) = POST / "system" / "archives" / "full" / "restore" / "latestArchive"
  }

  // Latest commit
  final case object RestoreGroupsLatestCommit extends SystemApi with ZeroParam with StartsAtVersion11 with SortIndex { val z = implicitly[Line].value
    val description    = "restore groups latest commit"
    val (action, path) = POST / "system" / "archives" / "groups" / "restore" / "latestCommit"
  }

  final case object RestoreDirectivesLatestCommit extends SystemApi with ZeroParam with StartsAtVersion11 with SortIndex { val z = implicitly[Line].value
    val description    = "restore directives latest commit"
    val (action, path) = POST / "system" / "archives" / "directives" / "restore" / "latestCommit"
  }

  final case object RestoreRulesLatestCommit extends SystemApi with ZeroParam with StartsAtVersion11 with SortIndex { val z = implicitly[Line].value
    val description    = "restore rules latest commit"
    val (action, path) = POST / "system" / "archives" / "rules" / "restore" / "latestCommit"
  }

  final case object RestoreFullLatestCommit extends SystemApi with ZeroParam with StartsAtVersion11 with SortIndex { val z = implicitly[Line].value
    val description    = "restore full latest commit"
    val (action, path) = POST / "system" / "archives" / "full" / "restore" / "latestCommit"
  }

  //Restore a particular entity base on its datetime

  final case object ArchiveGroupDateRestore extends SystemApi with OneParam with StartsAtVersion11 with SortIndex { val z = implicitly[Line].value
    val description    = "restore a group archive created on date passed as parameter"
    val (action, path) = POST / "system" / "archives" / "group" / "restore" / "{dateTime}"
  }

  final case object ArchiveDirectiveDateRestore extends SystemApi with OneParam with StartsAtVersion11 with SortIndex { val z = implicitly[Line].value
    val description    = "restore a directive archive created on date passed as parameter"
    val (action, path) = POST / "system" / "archives" / "directive" / "restore" / "{dateTime}"
  }

  final case object ArchiveRuleDateRestore extends SystemApi with OneParam with StartsAtVersion11 with SortIndex { val z = implicitly[Line].value
    val description    = "restore a rule archive created on date passed as parameter"
    val (action, path) = POST / "system" / "archives" / "rule" / "restore" / "{dateTime}"
  }

  final case object ArchiveFullDateRestore extends SystemApi with OneParam with StartsAtVersion11 with SortIndex { val z = implicitly[Line].value
    val description    = "restore a full archive created on date passed as parameter"
    val (action, path) = POST / "system" / "archives" / "full" / "restore" / "{dateTime}"
  }

  //Archive endpoints

  final case object ArchiveGroups extends SystemApi with ZeroParam with StartsAtVersion11 with SortIndex { val z = implicitly[Line].value
    val description    = "archive groups"
    val (action, path) = POST / "system" / "archives" / "groups"
  }

  final case object ArchiveDirectives extends SystemApi with ZeroParam with StartsAtVersion11 with SortIndex { val z = implicitly[Line].value
    val description    = "archive directives"
    val (action, path) = POST / "system" / "archives" / "directives"
  }

  final case object ArchiveRules extends SystemApi with ZeroParam with StartsAtVersion11 with SortIndex { val z = implicitly[Line].value
    val description    = "archive rules"
    val (action, path) = POST / "system" / "archives" / "rules"
  }

  final case object ArchiveFull extends SystemApi with ZeroParam with StartsAtVersion11 with SortIndex { val z = implicitly[Line].value
    val description    = "archive full"
    val (action, path) = POST / "system" / "archives" / "full"
  }

 // ZIP Archive endpoints

  final case object GetGroupsZipArchive extends SystemApi with OneParam with StartsAtVersion11 with SortIndex { val z = implicitly[Line].value
    val description    = "Get a groups zip archive based on its commit id"
    val (action, path) = GET / "system" / "archives" / "groups" / "zip" / "{commitId}"
  }

  final case object GetDirectivesZipArchive extends SystemApi with OneParam with StartsAtVersion11 with SortIndex { val z = implicitly[Line].value
    val description    = "Get a directives zip archive based on its commit id"
    val (action, path) = GET / "system" / "archives" / "directives" / "zip" / "{commitId}"
  }

  final case object GetRulesZipArchive extends SystemApi with OneParam with StartsAtVersion11 with SortIndex { val z = implicitly[Line].value
    val description    = "Get a rules zip archive based on its commit id"
    val (action, path) = GET / "system" / "archives" / "rules" / "zip" / "{commitId}"
  }

  final case object GetAllZipArchive extends SystemApi with OneParam with StartsAtVersion11 with SortIndex { val z = implicitly[Line].value
    val description    = "Get a full zip archive based on its commit id"
    val (action, path) = GET / "system" / "archives" / "full" / "zip" / "{commitId}"
  }

  def endpoints = ca.mrvisser.sealerate.values[SystemApi].toList.sortBy( _.z )
}

sealed trait InfoApi extends EndpointSchema with GeneralApi with SortIndex
object InfoApi extends ApiModuleProvider[InfoApi] {

  final case object ApiGeneralInformations extends InfoApi with ZeroParam with StartsAtVersion6 with SortIndex { val z = implicitly[Line].value
    val description = "Get information about Rudder public API"
    val (action, path)  = GET / "info"
  }

  final case object ApiInformations extends InfoApi with OneParam with StartsAtVersion10 with SortIndex { val z = implicitly[Line].value
    val description = "Get detailed information about Rudder public API with the given name"
    val (action, path)  = GET / "info" / "details" / "{id}"
  }

  final case object ApiSubInformations extends InfoApi with OneParam with StartsAtVersion10 with SortIndex { val z = implicitly[Line].value
    val description = "Get information about Rudder public API starting with given path"
    val (action, path)  = GET / "info" / "{id}"
  }

  def endpoints = ca.mrvisser.sealerate.values[InfoApi].toList.sortBy( _.z )
}

/*
 * Porting the old "inventory endpoints" APIs to rudder.
 * You have an endpoint for inventory processing status,
 * one to send inventory if you don't want to use file watcher parsing,
 * and control start/stop/restart of file watcher.
 */
sealed trait InventoryApi extends EndpointSchema with GeneralApi with SortIndex
object InventoryApi extends ApiModuleProvider[InventoryApi] {

  final case object QueueInformation extends InventoryApi with ZeroParam with StartsAtVersion12 with SortIndex { val z = implicitly[Line].value
    val description = "Get information about inventory current processing status"
    val (action, path)  = GET / "inventories" / "info"
  }

  final case object UploadInventory extends InventoryApi with ZeroParam with StartsAtVersion12 with SortIndex { val z = implicitly[Line].value
    val description = "Upload an inventory (parameter 'file' and its signature (parameter 'signature') with 'content-disposition:file' attachement format"
    val (action, path)  = POST / "inventories" / "upload"
  }

  final case object FileWatcherStart extends InventoryApi with ZeroParam with StartsAtVersion12 with SortIndex { val z = implicitly[Line].value
    val description = "Start inventory file watcher (inotify)"
    val (action, path)  = POST / "inventories" / "watcher" / "start"
  }

  final case object FileWatcherStop extends InventoryApi with ZeroParam with StartsAtVersion12 with SortIndex { val z = implicitly[Line].value
    val description = "Stop inventory file watcher (inotify)"
    val (action, path)  = POST / "inventories" / "watcher" / "stop"
  }

  final case object FileWatcherRestart extends InventoryApi with ZeroParam with StartsAtVersion12 with SortIndex { val z = implicitly[Line].value
    val description = "Restart inventory file watcher (inotify)"
    val (action, path)  = POST / "inventories" / "watcher" / "restart"
  }

  def endpoints = ca.mrvisser.sealerate.values[InventoryApi].toList.sortBy( _.z )
}


/*
 * This API definition need to be in Rudder core because it defines specific
 * user API rights, which is a special thing. The actual implementation will
 * be defined in the API Authorization plugin.
 * Note that these endpoint don't have token ID has parameter because an user can only manage
 * its own token, and Rudder will make the mapping server side.
 */
sealed trait UserApi extends EndpointSchema with InternalApi with SortIndex
object UserApi extends ApiModuleProvider[UserApi] {
  final case object GetApiToken extends UserApi with ZeroParam with StartsAtVersion10 with SortIndex { val z = implicitly[Line].value
    val description = "Get information about user personal UserApi token"
    val (action, path)  = GET / "user" / "api" / "token"
  }
  final case object CreateApiToken extends UserApi with ZeroParam with StartsAtVersion10 with SortIndex { val z = implicitly[Line].value
    val description = "Create user personal UserApi token"
    val (action, path)  = PUT / "user" / "api" / "token"
  }
  final case object DeleteApiToken extends UserApi with ZeroParam with StartsAtVersion10 with SortIndex { val z = implicitly[Line].value
    val description = "Delete user personal UserApi token"
    val (action, path)  = DELETE / "user" / "api" / "token"
  }

  final case object UpdateApiToken extends UserApi with ZeroParam with StartsAtVersion10 with SortIndex { val z = implicitly[Line].value
    val description = "Update user personal UserApi token"
    val (action, path)  = POST / "user" / "api" / "token"
  }

  def endpoints = ca.mrvisser.sealerate.values[UserApi].toList.sortBy( _.z )
}

/*
 * All API.
 */
object AllApi {
  val api: List[EndpointSchema] =
    ComplianceApi.endpoints :::
    GroupApi.endpoints :::
    DirectiveApi.endpoints :::
    NcfApi.endpoints :::
    NodeApi.endpoints :::
    ParameterApi.endpoints :::
    SettingsApi.endpoints :::
    SystemApi.endpoints :::
    TechniqueApi.endpoints :::
    RuleApi.endpoints :::
    InventoryApi.endpoints :::
    InfoApi.endpoints :::
    // UserApi is not declared here, it will be contributed by plugin
    Nil
}
