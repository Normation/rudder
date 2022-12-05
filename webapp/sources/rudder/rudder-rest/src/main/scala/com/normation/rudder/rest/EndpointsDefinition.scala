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

import com.normation.rudder.api.HttpAction
import com.normation.rudder.api.HttpAction._
import com.normation.rudder.rest.EndpointSchema.syntax._
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

enum CampaignApi(val description: String, target: (HttpAction, ApiPath), val dataContainer: Option[String] = None)(using Line)
    extends EndpointSchema with GeneralApi with SortIndex {
  val (action: HttpAction, path: ApiPath) = target
  val z                                   = summon[Line].value
  case GetCampaigns extends CampaignApi(
        description = "Get all campaigns model",
        target = GET / "campaigns",
        dataContainer = Some("campaigns")
      ) with ZeroParam with StartsAtVersion16 with SortIndex

  case GetCampaignEvents extends CampaignApi(
        description = "Get all campaigns events",
        target = GET / "campaigns" / "events",
        dataContainer = Some("campaignEvents")
      ) with ZeroParam with StartsAtVersion16 with SortIndex

  case GetCampaignEventDetails extends CampaignApi(
        description = "Get all campaigns events",
        target = GET / "campaigns" / "events" / "{id}",
        dataContainer = Some("campaignEvents")
      ) with OneParam with StartsAtVersion16 with SortIndex

  case SaveCampaign extends CampaignApi(
        description = "Save a campaign model",
        target = POST / "campaigns",
        dataContainer = Some("campaigns")
      ) with ZeroParam with StartsAtVersion16 with SortIndex

  case ScheduleCampaign extends CampaignApi(
        description = "Save a campaign model",
        target = POST / "campaigns" / "{id}" / "schedule",
        dataContainer = Some("campaigns")
      ) with OneParam with StartsAtVersion16 with SortIndex

  case GetCampaignDetails extends CampaignApi(
        description = "Get a campaign model",
        target = GET / "campaigns" / "{id}",
        dataContainer = Some("campaigns")
      ) with OneParam with StartsAtVersion16 with SortIndex

  case DeleteCampaign extends CampaignApi(
        description = "Get a campaign model",
        target = DELETE / "campaigns" / "{id}",
        dataContainer = None
      ) with OneParam with StartsAtVersion16 with SortIndex

  case GetCampaignEventsForModel extends CampaignApi(
        description = "Get a campaign model",
        target = GET / "campaigns" / "{id}" / "events",
        dataContainer = Some("campaignEvents")
      ) with OneParam with StartsAtVersion16 with SortIndex

  case SaveCampaignEvent extends CampaignApi(
        description = "Save a campaign event",
        target = POST / "campaigns" / "events" / "{id}",
        dataContainer = Some("campaignEvents")
      ) with OneParam with StartsAtVersion16 with SortIndex

  case DeleteCampaignEvent extends CampaignApi(
        description = "Save a campaign event",
        target = DELETE / "campaigns" / "events" / "{id}",
        dataContainer = None
      ) with OneParam with StartsAtVersion16 with SortIndex
}
object CampaignApi extends ApiModuleProvider[CampaignApi] {
  def endpoints: List[CampaignApi] = CampaignApi.values.toList.sortBy(_.z)
}

enum ComplianceApi(val description: String, target: (HttpAction, ApiPath), val dataContainer: Option[String] = None)(using Line)
    extends EndpointSchema with GeneralApi with SortIndex {
  val (action: HttpAction, path: ApiPath) = target
  val z                                   = summon[Line].value
  case GetRulesCompliance   extends ComplianceApi(
        description = "Get compliance information for all rules",
        target = GET / "compliance" / "rules",
        dataContainer = Some("rules")
      ) with ZeroParam with StartsAtVersion7 with SortIndex
  case GetRulesComplianceId extends ComplianceApi(
        description = "Get compliance information for the given rule",
        target = GET / "compliance" / "rules" / "{id}",
        dataContainer = Some("rules")
      ) with OneParam with StartsAtVersion7 with SortIndex

  case GetNodesCompliance extends ComplianceApi(
        description = "Get compliance information for all nodes",
        target = GET / "compliance" / "nodes",
        dataContainer = Some("nodes")
      ) with ZeroParam with StartsAtVersion7 with SortIndex

  case GetNodeComplianceId extends ComplianceApi(
        description = "Get compliance information for the given node",
        target = GET / "compliance" / "nodes" / "{id}",
        dataContainer = Some("nodes")
      ) with OneParam with StartsAtVersion7 with SortIndex

  case GetGlobalCompliance extends ComplianceApi(
        description = "Get the global compliance (alike what one has on Rudder main dashboard)",
        target = GET / "compliance",
        dataContainer = Some("globalCompliance")
      ) with ZeroParam with StartsAtVersion10 with SortIndex
}

object ComplianceApi extends ApiModuleProvider[ComplianceApi] {
  def endpoints = ComplianceApi.values.toList.sortBy(_.z)
}

enum GroupApi(val description: String, target: (HttpAction, ApiPath), val dataContainer: Option[String] = Some("groups"))(using
    Line
) extends EndpointSchema with SortIndex {
  val (action: HttpAction, path: ApiPath) = target
  val z                                   = summon[Line].value
  // API v2
  case ListGroups extends GroupApi(
        description = "List all groups with their information",
        target = GET / "groups"
      ) with GeneralApi with ZeroParam with StartsAtVersion2 with SortIndex

  case CreateGroup extends GroupApi(
        description = "Create a new group",
        target = PUT / "groups"
      ) with GeneralApi with ZeroParam with StartsAtVersion2 with SortIndex

  case GetGroupTree extends GroupApi(
        description = "List all group categories and group in a tree format",
        target = GET / "groups" / "tree"
      ) with GeneralApi with ZeroParam with StartsAtVersion6 with SortIndex

  case GroupDetails extends GroupApi(
        description = "Get information about the given group",
        target = GET / "groups" / "{id}"
      ) with GeneralApi with OneParam with StartsAtVersion2 with SortIndex

  case DeleteGroup extends GroupApi(
        description = "Delete given group",
        target = DELETE / "groups" / "{id}"
      ) with GeneralApi with OneParam with StartsAtVersion2 with SortIndex

  case UpdateGroup extends GroupApi(
        description = "Update given group",
        target = POST / "groups" / "{id}"
      ) with GeneralApi with OneParam with StartsAtVersion2 with SortIndex

  case ReloadGroup extends GroupApi(
        description = "Update given dynamic group node list",
        target = GET / "groups" / "{id}" / "reload"
      ) with GeneralApi with OneParam with StartsAtVersion2 with SortIndex

  case GroupInheritedProperties extends GroupApi(
        description = "Get all proporeties for that group, included inherited ones",
        target = GET / "groups" / "{id}" / "inheritedProperties"
      ) with GeneralApi with OneParam with StartsAtVersion11 with SortIndex

  // API v5 updates 'Create' methods but no new endpoints
  // API v6
  case GroupDisplayInheritedProperties extends GroupApi(
        description =
          "Get all proporeties for that group, included inherited ones, for displaying in group property tab (internal)",
        target = GET / "groups" / "{id}" / "displayInheritedProperties"
      ) with InternalApi with OneParam with StartsAtVersion13 with SortIndex
  case GetGroupCategoryDetails         extends GroupApi(
        description = "Get information about the given group category",
        target = GET / "groups" / "categories" / "{id}",
        dataContainer = Some("groupCategories")
      ) with GeneralApi with OneParam with StartsAtVersion6 with SortIndex

  case DeleteGroupCategory extends GroupApi(
        description = "Delete given group category",
        target = DELETE / "groups" / "categories" / "{id}",
        dataContainer = Some("groupCategories")
      ) with GeneralApi with OneParam with StartsAtVersion6 with SortIndex

  case UpdateGroupCategory extends GroupApi(
        description = "Update information for given group category",
        target = POST / "groups" / "categories" / "{id}",
        dataContainer = Some("groupCategories")
      ) with GeneralApi with OneParam with StartsAtVersion6 with SortIndex

  case CreateGroupCategory extends GroupApi(
        description = "Create a new group category",
        target = PUT / "groups" / "categories",
        dataContainer = Some("groupCategories")
      ) with GeneralApi with ZeroParam with StartsAtVersion6 with SortIndex
}

object GroupApi extends ApiModuleProvider[GroupApi] {
  def endpoints = GroupApi.values.toList.sortBy(_.z)
}

enum DirectiveApi(val description: String, target: (HttpAction, ApiPath))(using Line)
    extends EndpointSchema with GeneralApi with SortIndex {
  override def dataContainer              = Some("directives")
  val (action: HttpAction, path: ApiPath) = target
  val z                                   = summon[Line].value

  case ListDirectives extends DirectiveApi(
        description = "List all directives",
        target = GET / "directives"
      ) with ZeroParam with StartsAtVersion2 with SortIndex

  case DirectiveTree extends DirectiveApi(
        description = "Get Directive tree",
        target = GET / "directives" / "tree"
      ) with ZeroParam with StartsAtVersion14 with SortIndex

  case DirectiveDetails extends DirectiveApi(
        description = "Get information about given directive",
        target = GET / "directives" / "{id}"
      ) with OneParam with StartsAtVersion2 with SortIndex

  case DirectiveRevisions extends DirectiveApi(
        description = "Get revisions for given directive",
        target = GET / "directives" / "{id}" / "revisions"
      ) with OneParam with StartsAtVersion14 with SortIndex

  case CreateDirective extends DirectiveApi(
        description = "Create a new directive or clone an existing one",
        target = PUT / "directives"
      ) with ZeroParam with StartsAtVersion2 with SortIndex

  case DeleteDirective extends DirectiveApi(
        description = "Delete given directive",
        target = DELETE / "directives" / "{id}"
      ) with OneParam with StartsAtVersion2 with SortIndex

  case CheckDirective extends DirectiveApi(
        description = "Check if the given directive can be migrated to target technique version",
        target = POST / "directives" / "{id}" / "check"
      ) with OneParam with StartsAtVersion2 with SortIndex

  case UpdateDirective extends DirectiveApi(
        description = "Update given directive information",
        target = POST / "directives" / "{id}"
      ) with OneParam with StartsAtVersion2 with SortIndex
}

object DirectiveApi extends ApiModuleProvider[DirectiveApi] {
  def endpoints = DirectiveApi.values.toList.sortBy(_.z)
}

enum NodeApi(val description: String, target: (HttpAction, ApiPath))(using Line) extends EndpointSchema with SortIndex {
  override def dataContainer              = Some("nodes")
  val (action: HttpAction, path: ApiPath) = target
  val z                                   = summon[Line].value

  case ListAcceptedNodes extends NodeApi(
        description = "List all accepted nodes with configurable details level",
        target = GET / "nodes"
      ) with GeneralApi with ZeroParam with StartsAtVersion2 with SortIndex

  case GetNodesStatus extends NodeApi(
        description = "Get the status (pending, accepted, unknown) of the comma separated list of nodes given by `ids` parameter",
        target = GET / "nodes" / "status"
      ) with GeneralApi with ZeroParam with StartsAtVersion13 with SortIndex

  case ListPendingNodes extends NodeApi(
        description = "List all pending nodes with configurable details level",
        target = GET / "nodes" / "pending"
      ) with GeneralApi with ZeroParam with StartsAtVersion2 with SortIndex

  case PendingNodeDetails extends NodeApi(
        description = "Get information about the given pending node",
        target = GET / "nodes" / "pending" / "{id}"
      ) with GeneralApi with OneParam with StartsAtVersion2 with SortIndex

  case NodeDetails extends NodeApi(
        description = "Get information about the given accepted node",
        target = GET / "nodes" / "{id}"
      ) with GeneralApi with OneParam with StartsAtVersion2 with SortIndex

  case NodeInheritedProperties extends NodeApi(
        description = "Get all proporeties for that node, included inherited ones",
        target = GET / "nodes" / "{id}" / "inheritedProperties"
      ) with GeneralApi with OneParam with StartsAtVersion11 with SortIndex

  case ApplyPolicyAllNodes extends NodeApi(
        description = "Ask all nodes to start a run with the given policy",
        target = POST / "nodes" / "applyPolicy"
      ) with GeneralApi with ZeroParam with StartsAtVersion8 with SortIndex

  case ChangePendingNodeStatus extends NodeApi(
        description = "Accept or refuse pending nodes",
        target = POST / "nodes" / "pending"
      ) with GeneralApi with ZeroParam with StartsAtVersion2 with SortIndex

  // WARNING: read_only user can access this endpoint
  //    No modifications are performed here
  //    POST over GET is required here because we can provide too many information to be passed as URL parameters
  case NodeDisplayInheritedProperties extends NodeApi(
        description =
          "Get all proporeties for that node, included inherited ones, for displaying in node property tab (internal)",
        target = GET / "nodes" / "{id}" / "displayInheritedProperties"
      ) with InternalApi with OneParam with StartsAtVersion13 with SortIndex

  case NodeDetailsTable extends NodeApi(
        description = "Getting data to build a Node table",
        target = POST / "nodes" / "details"
      ) with InternalApi with ZeroParam with StartsAtVersion13 with SortIndex

  case NodeDetailsSoftware extends NodeApi(
        description = "Getting a software version for a set of Nodes",
        target = POST / "nodes" / "details" / "software" / "{software}"
      ) with InternalApi with OneParam with StartsAtVersion13 with SortIndex

  case NodeDetailsProperty extends NodeApi(
        description = "Getting a property value for a set of Nodes",
        target = POST / "nodes" / "details" / "property" / "{property}"
      ) with InternalApi with OneParam with StartsAtVersion13 with SortIndex

  case UpdateNode extends NodeApi(
        description = "Update given node information (node properties, policy mode...)",
        target = POST / "nodes" / "{id}"
      ) with GeneralApi with OneParam with StartsAtVersion5 with SortIndex

  case DeleteNode extends NodeApi(
        description = "Delete given node",
        target = DELETE / "nodes" / "{id}"
      ) with GeneralApi with OneParam with StartsAtVersion2 with SortIndex

  case ChangePendingNodeStatus2 extends NodeApi(
        description = "Accept or refuse given pending node",
        target = POST / "nodes" / "pending" / "{id}"
      ) with GeneralApi with OneParam with StartsAtVersion2 with SortIndex with WithOverridenName(
        newName = "ChangePendingNodeStatus"
      )

  case ApplyPolicy extends NodeApi(
        description = "Ask given node to start a run with the given policy",
        target = POST / "nodes" / "{id}" / "applyPolicy"
      ) with GeneralApi with OneParam with StartsAtVersion8 with SortIndex

  case CreateNodes extends NodeApi(
        description = "Create one of more new nodes",
        target = PUT / "nodes"
      ) with GeneralApi with ZeroParam with StartsAtVersion16 with SortIndex
}

object NodeApi    extends ApiModuleProvider[NodeApi]    {
  def endpoints = NodeApi.values.toList.sortBy(_.z)
}

enum ChangesApi(val description: String, target: (HttpAction, ApiPath))(using Line)
    extends EndpointSchema with InternalApi with SortIndex {
  override def dataContainer              = None
  val (action: HttpAction, path: ApiPath) = target
  val z                                   = summon[Line].value

  case GetRecentChanges extends ChangesApi(
        description = "Get changes for all Rules over the last 3 days (internal)",
        target = GET / "changes"
      ) with ZeroParam with StartsAtVersion14 with SortIndex

  case GetRuleRepairedReports extends ChangesApi(
        description = "Get all repaired report for a Rule in a interval of time specified as parameter(internal)",
        target = GET / "changes" / "{ruleId}"
      ) with OneParam with StartsAtVersion14 with SortIndex
}
object ChangesApi extends ApiModuleProvider[ChangesApi] {
  def endpoints = ChangesApi.values.toList.sortBy(_.z)
}

enum ParameterApi(val description: String, target: (HttpAction, ApiPath))(using Line)
    extends EndpointSchema with GeneralApi with SortIndex {
  override def dataContainer              = Some("parameters")
  val (action: HttpAction, path: ApiPath) = target
  val z                                   = summon[Line].value

  case ListParameters extends ParameterApi(
        description = "List all global parameters",
        target = GET / "parameters"
      ) with ZeroParam with StartsAtVersion2 with SortIndex

  case CreateParameter extends ParameterApi(
        description = "Create a new parameter",
        target = PUT / "parameters"
      ) with ZeroParam with StartsAtVersion2 with SortIndex

  case ParameterDetails extends ParameterApi(
        description = "Get information about the given parameter",
        target = GET / "parameters" / "{id}"
      ) with OneParam with StartsAtVersion2 with SortIndex

  case DeleteParameter extends ParameterApi(
        description = "Delete given parameter",
        target = DELETE / "parameters" / "{id}"
      ) with OneParam with StartsAtVersion2 with SortIndex

  case UpdateParameter extends ParameterApi(
        description = "Update information about given parameter",
        target = POST / "parameters" / "{id}"
      ) with OneParam with StartsAtVersion2 with SortIndex
}

object ParameterApi extends ApiModuleProvider[ParameterApi] {
  def endpoints = ParameterApi.values.toList.sortBy(_.z)
}

enum SettingsApi(val description: String, target: (HttpAction, ApiPath))(using Line)
    extends EndpointSchema with GeneralApi with SortIndex {
  override def dataContainer              = Some("settings")
  val (action: HttpAction, path: ApiPath) = target
  val z                                   = summon[Line].value

  case GetAllSettings extends SettingsApi(
        description = "Get information about all Rudder settings",
        target = GET / "settings"
      ) with ZeroParam with StartsAtVersion6 with SortIndex

  case GetAllAllowedNetworks extends SettingsApi(
        description = "List all allowed networks",
        target = GET / "settings" / "allowed_networks"
      ) with ZeroParam with StartsAtVersion11 with SortIndex

  case GetAllowedNetworks extends SettingsApi(
        description = "List all allowed networks for one relay",
        target = GET / "settings" / "allowed_networks" / "{nodeId}"
      ) with OneParam with StartsAtVersion11 with SortIndex

  case ModifyAllowedNetworks extends SettingsApi(
        description = "Update all allowed networks for one relay",
        target = POST / "settings" / "allowed_networks" / "{nodeId}"
      ) with OneParam with StartsAtVersion11 with SortIndex

  case ModifyDiffAllowedNetworks extends SettingsApi(
        description = "Modify some allowed networks for one relay with a diff structure",
        target = POST / "settings" / "allowed_networks" / "{nodeId}" / "diff"
      ) with OneParam with StartsAtVersion11 with SortIndex

  case GetSetting extends SettingsApi(
        description = "Get information about given Rudder setting",
        target = GET / "settings" / "{key}"
      ) with OneParam with StartsAtVersion6 with SortIndex

  case ModifySettings extends SettingsApi(
        description = "Update Rudder settings",
        target = POST / "settings"
      ) with ZeroParam with StartsAtVersion6 with SortIndex

  case ModifySetting extends SettingsApi(
        description = "Update given Rudder setting",
        target = POST / "settings" / "{key}"
      ) with OneParam with StartsAtVersion6 with SortIndex
}

object SettingsApi  extends ApiModuleProvider[SettingsApi]  {
  def endpoints = SettingsApi.values.toList.sortBy(_.z)
}

enum PluginApi(val description: String, target: (HttpAction, ApiPath))(using Line)
    extends EndpointSchema with GeneralApi with SortIndex {
  override def dataContainer: Option[String] = Some("plugins")
  val (action: HttpAction, path: ApiPath) = target
  val z                                   = summon[Line].value
  case GetPluginsSettings extends PluginApi(
        description = "List plugin system settings",
        target = GET / "plugins" / "settings"
      ) with ZeroParam with StartsAtVersion14 with SortIndex

  case UpdatePluginsSettings extends PluginApi(
        description = "Update plugin system settings",
        target = POST / "plugins" / "settings"
      ) with ZeroParam with StartsAtVersion14 with SortIndex
}
object PluginApi    extends ApiModuleProvider[PluginApi]    {
  def endpoints = PluginApi.values.toList.sortBy(_.z)
}

enum TechniqueApi(val description: String, target: (HttpAction, ApiPath), val dataContainer: Option[String] = Some("techniques"))(
    using Line
) extends EndpointSchema with GeneralApi with SortIndex {
  val (action: HttpAction, path: ApiPath) = target
  val z                                   = summon[Line].value

  case GetTechniques extends TechniqueApi(
        description = "Get all Techniques metadata",
        target = GET / "techniques"
      ) with ZeroParam with StartsAtVersion6 with SortIndex

  case UpdateTechniques extends TechniqueApi(
        description = "reload techniques metadata from file system",
        target = POST / "techniques" / "reload"
      ) with ZeroParam with StartsAtVersion14 with SortIndex

  case GetAllTechniqueCategories extends TechniqueApi(
        description = "Get all technique categories",
        target = GET / "techniques" / "categories"
      ) with ZeroParam with StartsAtVersion14 with SortIndex

  case ListTechniques extends TechniqueApi(
        description = "List all techniques version",
        target = GET / "techniques" / "versions"
      ) with ZeroParam with StartsAtVersion14 with SortIndex

  case ListTechniquesDirectives extends TechniqueApi(
        description = "List directives derived from given technique",
        target = GET / "techniques" / "{name}" / "directives",
        dataContainer = Some("directives")
      ) with OneParam with StartsAtVersion6 with SortIndex

  case ListTechniqueDirectives extends TechniqueApi(
        description = "List directives derived from given technique for given version",
        target = GET / "techniques" / "{name}" / "{version}" / "directives",
        dataContainer = Some("directives")
      ) with TwoParam with StartsAtVersion6 with SortIndex

  case TechniqueRevisions extends TechniqueApi(
        description = "Get revisions for given technique",
        target = GET / "techniques" / "{name}" / "{version}" / "revisions"
      ) with TwoParam with StartsAtVersion14 with SortIndex

  case UpdateTechnique extends TechniqueApi(
        description = "Update technique created with technique editor",
        target = POST / "techniques" / "{techniqueId}" / "{version}"
      ) with TwoParam with StartsAtVersion14 with SortIndex

  case CreateTechnique extends TechniqueApi(
        description = "Create a new technique in Rudder from a technique in the technique editor",
        target = PUT / "techniques"
      ) with ZeroParam with StartsAtVersion14 with SortIndex

  case DeleteTechnique extends TechniqueApi(
        description = "Delete a technique from technique editor",
        target = DELETE / "techniques" / "{techniqueId}" / "{techniqueVersion}"
      ) with TwoParam with StartsAtVersion14 with SortIndex

  case GetResources extends TechniqueApi(
        description = "Get currently deployed resources of a technique",
        target = GET / "techniques" / "{techniqueId}" / "{techniqueVersion}" / "resources"
      ) with TwoParam with StartsAtVersion14 with SortIndex

  case GetNewResources extends TechniqueApi(
        description = "Get resources of a technique draft",
        target = GET / "drafts" / "{techniqueId}" / "{techniqueVersion}" / "resources"
      ) with TwoParam with StartsAtVersion14 with SortIndex

  case GetTechniqueAllVersion extends TechniqueApi(
        description = "Get all Techniques metadata",
        target = GET / "techniques" / "{techniqueId}"
      ) with OneParam with StartsAtVersion14 with SortIndex

  case GetTechnique extends TechniqueApi(
        description = "Get all Techniques metadata",
        target = GET / "techniques" / "{techniqueId}" / "{techniqueVersion}"
      ) with TwoParam with StartsAtVersion14 with SortIndex

  /*
   * Method are returned sorted alpha-numericaly
   */
  case GetMethods extends TechniqueApi(
        description = "Get all methods metadata",
        target = GET / "methods"
      ) with ZeroParam with StartsAtVersion14 with SortIndex

  case UpdateMethods extends TechniqueApi(
        description = "reload methods metadata from file system",
        target = POST / "methods" / "reload"
      ) with ZeroParam with StartsAtVersion14 with SortIndex
}
object TechniqueApi extends ApiModuleProvider[TechniqueApi] {
  def endpoints = TechniqueApi.values.toList.sortBy(_.z)
}

enum RuleApi(val description: String, target: (HttpAction, ApiPath), val dataContainer: Option[String] = Some("rules"))(using
    Line
) extends EndpointSchema with GeneralApi with SortIndex {
  val (action: HttpAction, path: ApiPath) = target
  val z                                   = summon[Line].value

  case ListRules extends RuleApi(
        description = "List all rules with their information",
        target = GET / "rules"
      ) with ZeroParam with StartsAtVersion2 with SortIndex

  case CreateRule extends RuleApi(
        description = "Create a new rule",
        target = PUT / "rules"
      ) with ZeroParam with StartsAtVersion2 with SortIndex

  // must be before rule details, else it is never reached
  case GetRuleTree extends RuleApi(
        description = "Get rule categories and rule structured in a tree format",
        target = GET / "rules" / "tree",
        dataContainer = None
      ) with ZeroParam with StartsAtVersion6 with SortIndex

  case RuleDetails extends RuleApi(
        description = "Get information about given rule",
        target = GET / "rules" / "{id}"
      ) with OneParam with StartsAtVersion2 with SortIndex

  case DeleteRule extends RuleApi(
        description = "Delete given rule",
        target = DELETE / "rules" / "{id}"
      ) with OneParam with StartsAtVersion2 with SortIndex

  case UpdateRule             extends RuleApi(
        description = "Update information about given rule",
        target = POST / "rules" / "{id}"
      ) with OneParam with StartsAtVersion2 with SortIndex
  case GetRuleCategoryDetails extends RuleApi(
        description = "Get information about given rule category",
        target = GET / "rules" / "categories" / "{id}",
        dataContainer = None
      ) with OneParam with StartsAtVersion6 with SortIndex

  case DeleteRuleCategory extends RuleApi(
        description = "Delete given category",
        target = DELETE / "rules" / "categories" / "{id}",
        dataContainer = Some("rulesCategories")
      ) with OneParam with StartsAtVersion6 with SortIndex

  case UpdateRuleCategory extends RuleApi(
        description = "Update information about given rule category",
        target = POST / "rules" / "categories" / "{id}",
        dataContainer = None
      ) with OneParam with StartsAtVersion6 with SortIndex

  case CreateRuleCategory extends RuleApi(
        description = "Create a new rule category",
        target = PUT / "rules" / "categories",
        dataContainer = None
      ) with ZeroParam with StartsAtVersion6 with SortIndex

  // internal, because non definitive, API to load/unload a specific revision from git to ldap
  case LoadRuleRevisionForGeneration extends RuleApi(
        description = "Load a revision of a rule from config-repo to ldap, ready for next generation",
        target = POST / "rules" / "revision" / "load" / "{id}",
        dataContainer = None
      ) with OneParam with StartsAtVersion14 with SortIndex

  case UnloadRuleRevisionForGeneration extends RuleApi(
        description =
          "Unload a revision of a rule from ldap, it will not be used in next generation. Only rule with a revision can be unloaded",
        target = POST / "rules" / "revision" / "unload" / "{id}",
        dataContainer = None
      ) with OneParam with StartsAtVersion14 with SortIndex
}
object RuleApi      extends ApiModuleProvider[RuleApi]      {
  def endpoints = RuleApi.values.toList.sortBy(_.z)

}

enum RuleInternalApi(
    val description:   String,
    target:            (HttpAction, ApiPath),
    val dataContainer: Option[String] = Some("rulesinternal")
)(using Line)
    extends EndpointSchema with InternalApi with SortIndex {
  val (action: HttpAction, path: ApiPath) = target
  val z                                   = summon[Line].value

  // For the rule detail page
  case GetRuleNodesAndDirectives extends RuleInternalApi(
        description = "Get the list of nodes and directives of a rule",
        target = GET / "rulesinternal" / "nodesanddirectives" / "{id}",
        dataContainer = None
      ) with OneParam with StartsAtVersion14 with SortIndex
}
object RuleInternalApi extends ApiModuleProvider[RuleInternalApi] {
  def endpoints = RuleInternalApi.values.toList.sortBy(_.z)
}

enum SystemApi(val description: String, target: (HttpAction, ApiPath))(using Line)
    extends EndpointSchema with GeneralApi with SortIndex {
  override def dataContainer              = None // nothing normalized here ?
  val (action: HttpAction, path: ApiPath) = target
  val z                                   = summon[Line].value

  case Info extends SystemApi(
        description = "Get information about system installation (version, etc)",
        target = GET / "system" / "info"
      ) with ZeroParam with StartsAtVersion10 with SortIndex

  case Status extends SystemApi(
        description = "Get Api status",
        target = GET / "system" / "status"
      ) with ZeroParam with StartsAtVersion11 with SortIndex

  case DebugInfo extends SystemApi(
        description = "Launch the support info script and get the result",
        target = GET / "system" / "debug" / "info"
      ) with ZeroParam with StartsAtVersion11 with SortIndex

  // For now, the techniques reload endpoint is implemented in the System API
  // but moving it inside the Techniques API should be discussed.
  case ReloadAll extends SystemApi(
        description = "reload both techniques and dynamic groups",
        target = POST / "system" / "reload"
      ) with ZeroParam with StartsAtVersion11 with SortIndex

  case TechniquesReload extends SystemApi(
        description = "reload all techniques", // automatically done every 5 minutes
        target = POST / "system" / "reload" / "techniques"
      ) with ZeroParam with StartsAtVersion11 with SortIndex

  case DyngroupsReload extends SystemApi(
        description = "reload all dynamic groups",
        target = POST / "system" / "reload" / "groups"
      ) with ZeroParam with StartsAtVersion11 with SortIndex

  case PoliciesUpdate extends SystemApi(
        description = "update policies",
        target = POST / "system" / "update" / "policies"
      ) with ZeroParam with StartsAtVersion11 with SortIndex

  case PoliciesRegenerate extends SystemApi(
        description = "regenerate all policies",
        target = POST / "system" / "regenerate" / "policies"
      ) with ZeroParam with StartsAtVersion11 with SortIndex

  // Archive list endpoints
  case ArchivesGroupsList extends SystemApi(
        description = "list groups archives",
        target = GET / "system" / "archives" / "groups"
      ) with ZeroParam with StartsAtVersion11 with SortIndex

  case ArchivesDirectivesList extends SystemApi(
        description = "list directives archives",
        target = GET / "system" / "archives" / "directives"
      ) with ZeroParam with StartsAtVersion11 with SortIndex

  case ArchivesRulesList extends SystemApi(
        description = "list rules archives",
        target = GET / "system" / "archives" / "rules"
      ) with ZeroParam with StartsAtVersion11 with SortIndex

  case ArchivesParametersList extends SystemApi(
        description = "list parameters archives",
        target = GET / "system" / "archives" / "parameters"
      ) with ZeroParam with StartsAtVersion11 with SortIndex

  case ArchivesFullList extends SystemApi(
        description = "list all archives",
        target = GET / "system" / "archives" / "full"
      ) with ZeroParam with StartsAtVersion11 with SortIndex

  // Archive restore endpoints

  // Latest archive
  case RestoreGroupsLatestArchive extends SystemApi(
        description = "restore groups latest archive",
        target = POST / "system" / "archives" / "groups" / "restore" / "latestArchive"
      ) with ZeroParam with StartsAtVersion11 with SortIndex

  case RestoreDirectivesLatestArchive extends SystemApi(
        description = "restore directives latest archive",
        target = POST / "system" / "archives" / "directives" / "restore" / "latestArchive"
      ) with ZeroParam with StartsAtVersion11 with SortIndex

  case RestoreRulesLatestArchive extends SystemApi(
        description = "restore rules latest archive",
        target = POST / "system" / "archives" / "rules" / "restore" / "latestArchive"
      ) with ZeroParam with StartsAtVersion11 with SortIndex

  case RestoreParametersLatestArchive extends SystemApi(
        description = "restore parameters latest archive",
        target = POST / "system" / "archives" / "parameters" / "restore" / "latestArchive"
      ) with ZeroParam with StartsAtVersion11 with SortIndex

  case RestoreFullLatestArchive extends SystemApi(
        description = "restore all latest archive",
        target = POST / "system" / "archives" / "full" / "restore" / "latestArchive"
      ) with ZeroParam with StartsAtVersion11 with SortIndex

  // Latest commit
  case RestoreGroupsLatestCommit extends SystemApi(
        description = "restore groups latest commit",
        target = POST / "system" / "archives" / "groups" / "restore" / "latestCommit"
      ) with ZeroParam with StartsAtVersion11 with SortIndex

  case RestoreDirectivesLatestCommit extends SystemApi(
        description = "restore directives latest commit",
        target = POST / "system" / "archives" / "directives" / "restore" / "latestCommit"
      ) with ZeroParam with StartsAtVersion11 with SortIndex

  case RestoreRulesLatestCommit extends SystemApi(
        description = "restore rules latest commit",
        target = POST / "system" / "archives" / "rules" / "restore" / "latestCommit"
      ) with ZeroParam with StartsAtVersion11 with SortIndex

  case RestoreParametersLatestCommit extends SystemApi(
        description = "restore parameters latest commit",
        target = POST / "system" / "archives" / "parameters" / "restore" / "latestCommit"
      ) with ZeroParam with StartsAtVersion11 with SortIndex

  case RestoreFullLatestCommit extends SystemApi(
        description = "restore full latest commit",
        target = POST / "system" / "archives" / "full" / "restore" / "latestCommit"
      ) with ZeroParam with StartsAtVersion11 with SortIndex

  // Restore a particular entity base on its datetime
  case ArchiveGroupDateRestore extends SystemApi(
        description = "restore a group archive created on date passed as parameter",
        target = POST / "system" / "archives" / "groups" / "restore" / "{dateTime}"
      ) with OneParam with StartsAtVersion11 with SortIndex

  case ArchiveDirectiveDateRestore extends SystemApi(
        description = "restore a directive archive created on date passed as parameter",
        target = POST / "system" / "archives" / "directives" / "restore" / "{dateTime}"
      ) with OneParam with StartsAtVersion11 with SortIndex

  case ArchiveRuleDateRestore extends SystemApi(
        description = "restore a rule archive created on date passed as parameter",
        target = POST / "system" / "archives" / "rules" / "restore" / "{dateTime}"
      ) with OneParam with StartsAtVersion11 with SortIndex

  case ArchiveParameterDateRestore extends SystemApi(
        description = "restore a parameter archive created on date passed as parameter",
        target = POST / "system" / "archives" / "parameters" / "restore" / "{dateTime}"
      ) with OneParam with StartsAtVersion11 with SortIndex

  case ArchiveFullDateRestore extends SystemApi(
        description = "restore a full archive created on date passed as parameter",
        target = POST / "system" / "archives" / "full" / "restore" / "{dateTime}"
      ) with OneParam with StartsAtVersion11 with SortIndex

  // Archive endpoints
  case ArchiveGroups extends SystemApi(
        description = "archive groups",
        target = POST / "system" / "archives" / "groups"
      ) with ZeroParam with StartsAtVersion11 with SortIndex

  case ArchiveDirectives extends SystemApi(
        description = "archive directives",
        target = POST / "system" / "archives" / "directives"
      ) with ZeroParam with StartsAtVersion11 with SortIndex

  case ArchiveRules extends SystemApi(
        description = "archive rules",
        target = POST / "system" / "archives" / "rules"
      ) with ZeroParam with StartsAtVersion11 with SortIndex

  case ArchiveParameters extends SystemApi(
        description = "archive parameters",
        target = POST / "system" / "archives" / "parameters"
      ) with ZeroParam with StartsAtVersion11 with SortIndex

  case ArchiveFull extends SystemApi(
        description = "archive full",
        target = POST / "system" / "archives" / "full"
      ) with ZeroParam with StartsAtVersion11 with SortIndex

  // ZIP Archive endpoints
  case GetGroupsZipArchive extends SystemApi(
        description = "Get a groups zip archive based on its commit id",
        target = GET / "system" / "archives" / "groups" / "zip" / "{commitId}"
      ) with OneParam with StartsAtVersion11 with SortIndex

  case GetDirectivesZipArchive extends SystemApi(
        description = "Get a directives zip archive based on its commit id",
        target = GET / "system" / "archives" / "directives" / "zip" / "{commitId}"
      ) with OneParam with StartsAtVersion11 with SortIndex

  case GetRulesZipArchive extends SystemApi(
        description = "Get a rules zip archive based on its commit id",
        target = GET / "system" / "archives" / "rules" / "zip" / "{commitId}"
      ) with OneParam with StartsAtVersion11 with SortIndex

  case GetParametersZipArchive extends SystemApi(
        description = "Get a parameters zip archive based on its commit id",
        target = GET / "system" / "archives" / "parameters" / "zip" / "{commitId}"
      ) with OneParam with StartsAtVersion11 with SortIndex

  case GetAllZipArchive extends SystemApi(
        description = "Get a full zip archive based on its commit id",
        target = GET / "system" / "archives" / "full" / "zip" / "{commitId}"
      ) with OneParam with StartsAtVersion11 with SortIndex

  // Health check endpoints

  // This endpoint run all checks to return the result
  case GetHealthcheckResult extends SystemApi(
        description = "Result of a health check run",
        target = GET / "system" / "healthcheck"
      ) with ZeroParam with StartsAtVersion13 with SortIndex

  case PurgeSoftware extends SystemApi(
        description = "Trigger an async purge of softwares",
        target = POST / "system" / "maintenance" / "purgeSoftware"
      ) with ZeroParam with StartsAtVersion13 with SortIndex

}
object SystemApi       extends ApiModuleProvider[SystemApi]       {
  def endpoints = SystemApi.values.toList.sortBy(_.z)
}

enum InfoApi(val description: String, target: (HttpAction, ApiPath))(using Line)
    extends EndpointSchema with GeneralApi with SortIndex {
  override def dataContainer              = None
  val (action: HttpAction, path: ApiPath) = target
  val z                                   = summon[Line].value

  case ApiGeneralInformations extends InfoApi(
        description = "Get information about Rudder public API",
        target = GET / "info"
      ) with ZeroParam with StartsAtVersion6 with SortIndex

  case ApiInformations extends InfoApi(
        description = "Get detailed information about Rudder public API with the given name",
        target = GET / "info" / "details" / "{id}"
      ) with OneParam with StartsAtVersion10 with SortIndex

  case ApiSubInformations extends InfoApi(
        description = "Get information about Rudder public API starting with given path",
        target = GET / "info" / "{id}"
      ) with OneParam with StartsAtVersion10 with SortIndex

}
object InfoApi         extends ApiModuleProvider[InfoApi]         {
  def endpoints = InfoApi.values.toList.sortBy(_.z)
}

enum HookApi(val description: String, target: (HttpAction, ApiPath))(using Line)
    extends EndpointSchema with InternalApi with SortIndex {
  override def dataContainer              = None // nothing normalized here ?
  val (action: HttpAction, path: ApiPath) = target
  val z                                   = summon[Line].value

  case GetHooks extends HookApi(
        description = "Get all hooks",
        target = GET / "hooks"
      ) with ZeroParam with StartsAtVersion16 with SortIndex

}
object HookApi         extends ApiModuleProvider[HookApi]         {
  def endpoints = HookApi.values.toList.sortBy(_.z)
}

/*
 * Porting the old "inventory endpoints" APIs to rudder.
 * You have an endpoint for inventory processing status,
 * one to send inventory if you don't want to use file watcher parsing,
 * and control start/stop/restart of file watcher.
 */
enum InventoryApi(val description: String, target: (HttpAction, ApiPath))(using Line)
    extends EndpointSchema with GeneralApi with SortIndex {
  override def dataContainer: Option[String] = None
  val (action: HttpAction, path: ApiPath) = target
  val z                                   = summon[Line].value

  case QueueInformation extends InventoryApi(
        description = "Get information about inventory current processing status",
        target = GET / "inventories" / "info"
      ) with ZeroParam with StartsAtVersion12 with SortIndex

  case UploadInventory extends InventoryApi(
        description =
          "Upload an inventory (parameter 'file' and its signature (parameter 'signature') with 'content-disposition:file' attachement format",
        target = POST / "inventories" / "upload"
      ) with ZeroParam with StartsAtVersion12 with SortIndex

  case FileWatcherStart extends InventoryApi(
        description = "Start inventory file watcher (inotify)",
        target = POST / "inventories" / "watcher" / "start"
      ) with ZeroParam with StartsAtVersion12 with SortIndex

  case FileWatcherStop extends InventoryApi(
        description = "Stop inventory file watcher (inotify)",
        target = POST / "inventories" / "watcher" / "stop"
      ) with ZeroParam with StartsAtVersion12 with SortIndex

  case FileWatcherRestart extends InventoryApi(
        description = "Restart inventory file watcher (inotify)",
        target = POST / "inventories" / "watcher" / "restart"
      ) with ZeroParam with StartsAtVersion12 with SortIndex

}
object InventoryApi extends ApiModuleProvider[InventoryApi] {
  def endpoints = InventoryApi.values.toList.sortBy(_.z)
}

/*
 * This API definition need to be in Rudder core because it defines specific
 * user API rights, which is a special thing. The actual implementation will
 * be defined in the API Authorization plugin.
 * Note that these endpoint don't have token ID has parameter because an user can only manage
 * its own token, and Rudder will make the mapping server side.
 */
enum UserApi(val description: String, target: (HttpAction, ApiPath))(using Line)
    extends EndpointSchema with InternalApi with SortIndex {
  override def dataContainer: Option[String] = None
  val (action: HttpAction, path: ApiPath) = target
  val z                                   = summon[Line].value

  case GetApiToken extends UserApi(
        description = "Get information about user personal UserApi token",
        target = GET / "user" / "api" / "token"
      ) with ZeroParam with StartsAtVersion10 with SortIndex

  case CreateApiToken extends UserApi(
        description = "Create user personal UserApi token",
        target = PUT / "user" / "api" / "token"
      ) with ZeroParam with StartsAtVersion10 with SortIndex

  case DeleteApiToken extends UserApi(
        description = "Delete user personal UserApi token",
        target = DELETE / "user" / "api" / "token"
      ) with ZeroParam with StartsAtVersion10 with SortIndex

  case UpdateApiToken extends UserApi(
        description = "Update user personal UserApi token",
        target = POST / "user" / "api" / "token"
      ) with ZeroParam with StartsAtVersion10 with SortIndex

}
object UserApi extends ApiModuleProvider[UserApi] {
  def endpoints = UserApi.values.toList.sortBy(_.z)
}

/*
 * An API for import & export of archives of objects with their dependencies
 */
enum ArchiveApi(val description: String, target: (HttpAction, ApiPath))(using Line)
    extends EndpointSchema with GeneralApi with SortIndex {
  override def dataContainer: Option[String] = None
  val (action: HttpAction, path: ApiPath) = target
  val z                                   = summon[Line].value

  /*
   * Request format:
   *   ../archives/export?rules=rule_ids&directives=dir_ids&techniques=tech_ids&groups=group_ids&include=scope
   * Where:
   * - rule_ids = xxxx-xxxx-xxx-xxx[,other ids]
   * - dir_ids = xxxx-xxxx-xxx-xxx[,other ids]
   * - group_ids = xxxx-xxxx-xxx-xxx[,other ids]
   * - tech_ids = techniqueName/1.0[,other tech ids]
   * - scope = all (default), none, directives, techniques (implies directive), groups
   */
  case ExportSimple extends ArchiveApi(
        description = "Export the list of objects with their dependencies in a policy archive",
        target = GET / "archives" / "export"
      ) with ZeroParam with StartsAtVersion16 with SortIndex

  case Import extends ArchiveApi(
        description = "Import policy archive",
        target = POST / "archives" / "import"
      ) with ZeroParam with StartsAtVersion16 with SortIndex

}
object ArchiveApi extends ApiModuleProvider[ArchiveApi] {
  def endpoints = ArchiveApi.values.toList.sortBy(_.z)
}

sealed trait WithOverridenName(newName: String) { self: EndpointSchema =>
  override val name: String = newName
}

/*
 * All API.
 */
object AllApi {
  val api: List[EndpointSchema] = {
    ComplianceApi.endpoints :::
    GroupApi.endpoints :::
    DirectiveApi.endpoints :::
    NodeApi.endpoints :::
    ParameterApi.endpoints :::
    SettingsApi.endpoints :::
    SystemApi.endpoints :::
    TechniqueApi.endpoints :::
    RuleApi.endpoints :::
    InventoryApi.endpoints :::
    ArchiveApi.endpoints :::
    InfoApi.endpoints :::
    HookApi.endpoints :::
    // UserApi is not declared here, it will be contributed by plugin
    Nil
  }
}
