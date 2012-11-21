/*
*************************************************************************************
* Copyright 2012 Normation SAS
*************************************************************************************
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Affero General Public License as
* published by the Free Software Foundation, either version 3 of the
* License, or (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU Affero GPL v3, the copyright holders add the following
* Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU Affero GPL v3
* licence, when you create a Related Module, this Related Module is
* not considered as a part of the work and may be distributed under the
* license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Affero General Public License for more details.
*
* You should have received a copy of the GNU Affero General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/agpl.html>.
*
*************************************************************************************
*/

package com.normation.rudder.web.components

import com.normation.eventlog.EventLogType
import net.liftweb.http._
import net.liftweb.http.js.JsCmds._
import net.liftweb.common._
import scala.xml._
import net.liftweb.http.js.JE._
import com.normation.rudder.domain.eventlog._
import com.normation.eventlog.EventLogType


class EventFilter (
    targetfilter: String
    ) extends DispatchSnippet with Loggable {

    def dispatch = {
    case "showForm" => { _ => <div id="filterZone">      <b> Display event types :</b> {filter()}</div>}
  }

    import EventLogFilter._

      def onclick ="""
      var actualLevel= levels[level];
      if(lesslevel.indexOf(actualLevel)==-1)
        $('#less').button('disable').blur().removeClass('ui-state-hover');
      else
        $('#less').button('enable');
      if (morelevel.indexOf(actualLevel)==-1)
        $('#more').button('disable').blur().removeClass('ui-state-hover');
      else
        $('#more').button('enable');
      $('.'+actualLevel).each(function(i,lev){
        $(lev).show();
      } );
      levels.filter( function(x) {
        return x!=actualLevel;
        } ).forEach(function(x) {
         $('.'+x).each(function(i,c){$(c).hide();
        } );
      } );
      """

    def filter(filters:List[EventLogFilter]= configuration :: inventory :: intern :: Nil) : NodeSeq = {
      <div>
        <div class="filterMainBlock">
          <span> Choose event type to display</span>
          <div style ="padding-top:58px">
            {SHtml.submit("Less...",()=>(),("id","less"),("type","button"),("onclick","level = level-1;"+onclick))}
            {SHtml.submit("More...",()=>(),("id","more"),("type","button"),("onclick","level = level+1;"+onclick))}
          </div>
        </div>
        <div style="filterBlock">
           <ul style = "text-align:right;width:182px;">
            {filters.map(filter => filter.display(targetfilter,1))}
           </ul>
        </div>
      </div>++Script(JsRaw("""
          $('#less').button();
          $('#more').button();
          var filter = [];
          var level=0;
          var levels=['level1','level2','level3'];
          var lesslevel=['level2','level3'];
          var morelevel=['level1','level2'];"""+onclick) )
    }

}


case class EventLogFilter(
    name : String
  , eventTypes : List[EventLogType]
    ){

  val id = name.replaceAll("""\s+""", "")
  def filter = eventTypes.map(evtT => S.?("rudder.log.eventType.names."+evtT.serialize)).mkString("['","','","']")

  def checkParent(check:Boolean) : String =
    parent.map(parent => """$('#%s').attr('checked',%b);
      $('#%s').parent().parent().css('background-color','%s');
      %s""".format(parent.id,check,parent.id,if (check) "#EEEEEE" else "",parent.checkParent(check))).mkString("\n")

  def checkChilds(check:Boolean) : String =
    childs.map(child => """$('#%s').attr('checked',%b);
      $('#%s').parent().parent().css('background-color','%s');
      %s""".format(child.id,check,child.id,if (check) "#EEEEEE" else "",child.checkChilds(check))).mkString("\n")

  def onclick(target:String) = {
    ("onclick",""" var tab = %s;
      if (this.checked){
        $(this).parent().parent().css('background-color','#EEEEEE');
        %s
      %s
        tab.forEach(function(x) {
          if(filter.indexOf(x)==-1)
            filter.push(x);
        } )
      }
      else {
        $(this).parent().parent().css('background-color','');
        tab.forEach(function(x) {
        %s
          filter = filter.filter( function(y) {
            return y!=x;
          } );
        } )
      }
      if (filter.length>0)
        $('#%s').dataTable().fnFilter(filter.join('|'),3,true,false);
      else
        $('#%s').dataTable().fnFilter('not_an_event',3,true,false);
      """.format(filter,checkParent(true),checkChilds(true),checkChilds(false),target,target))
  }

  def childs = EventLogFilter.childs(this)
  def parent = EventLogFilter.parent(this)
  def display(target:String, level:Int) : NodeSeq = {
    <li class={"level"+level} style="height:16px">
      <span style="vertical-align:middle"> {name} : {
        SHtml.checkbox(false, (bool) => (),onclick(target),("id",id),("style","vertical-align:middle;"))}
      </span>
    </li> ++ childs.flatMap{ child => child.display(target,level+1)}
  }
}

case object EventLogFilter{
  def apply(name:String,eventType:EventLogType):EventLogFilter= EventLogFilter(name,List(eventType))
  implicit def toEventType(evtFilters:List[EventLogFilter]):List[EventLogType] = evtFilters.flatMap(_.eventTypes)

  /*
   * Configuration filters
   */
  val addRule = EventLogFilter("Add Rule",AddRuleEventType)
  val modRule = EventLogFilter("Modify Rule",ModifyRuleEventType)
  val delRule = EventLogFilter("Delete Rule",DeleteRuleEventType)
  val rules = List(addRule,modRule,delRule)
  val rule = EventLogFilter("Rule",rules)
  val addDirective = EventLogFilter("Add Directive",AddDirectiveEventType)
  val modDirective = EventLogFilter("Modify Directive",ModifyDirectiveEventType)
  val delDirective = EventLogFilter("Delete Directive",DeleteDirectiveEventType)
  val directives = List(addDirective,modDirective,delDirective)
  val directive = EventLogFilter("Directive",directives)
  val actRedButton = EventLogFilter("Activate Red button",ActivateRedButtonEventType)
  val relRedButton = EventLogFilter("Release Ref button",ReleaseRedButtonEventType)
  val redButtons   =  List(actRedButton,relRedButton)
  val RedButton = EventLogFilter("Red button",redButtons)
  val reloadTechLib = EventLogFilter("Reload Technique library",ReloadTechniqueLibraryType)
  val modTech = EventLogFilter("Modify Technique",ModifyTechniqueEventType)
  val delTech = EventLogFilter("Delete Technique",DeleteTechniqueEventType)
  val techniques = List(reloadTechLib,modTech,delTech)
  val technique = EventLogFilter("Technique",techniques)
  val confs = List(rule,directive,technique)
  val configuration = EventLogFilter("Configuration",confs)

  /*
   * Inventory Filters
   */
  val accNode = EventLogFilter("Accept Node",AcceptNodeEventType)
  val refNode = EventLogFilter("Refuse Node",RefuseNodeEventType)
  val delNode = EventLogFilter("Delete Node",DeleteNodeEventType)
  val nodes = List(accNode,refNode,delNode)
  val node = EventLogFilter("Node",nodes)
  val addGroup = EventLogFilter("Add node Group",AddNodeGroupEventType)
  val modGroup = EventLogFilter("Modify node Group",ModifyNodeGroupEventType)
  val delGroup = EventLogFilter("Delete node Group",DeleteNodeGroupEventType)
  val groups = List(addGroup,modGroup,delGroup)
  val group = EventLogFilter("Node Group",groups)
  val inventories = List(node,group)
  val inventory = EventLogFilter("Inventory",inventories)

  /*
   * Internal Filters
   */
  val login = EventLogFilter("Login",LoginEventType)
  val logout = EventLogFilter("Logout",LogoutEventType)
  val badCred = EventLogFilter("Bad Credentials",BadCredentialsEventType)
  val users = List(login,logout,badCred)
  val user = EventLogFilter("User",users)
  val appliStart = EventLogFilter("Application Start",ApplicationStartedEventType)
  val autodep = EventLogFilter("Automatic deployment start",AutomaticStartDeployementEventType)
  val successfulDep = EventLogFilter("Successful deployment",SuccessfulDeploymentEventType)
  val failedDep = EventLogFilter("Failed deployment",FailedDeploymentEventType)
  val manualDep = EventLogFilter("Manual deployment start",ManualStartDeployementEventType)
  val deployments = List(successfulDep,failedDep,manualDep,autodep)
  val deployment = EventLogFilter("Deployment",deployments)
  val clearCache = EventLogFilter("Clear cache",ClearCacheEventType)
  val policyServer = EventLogFilter("Update policy server",UpdatePolicyServerEventType)
  val importGroup = EventLogFilter("Import Groups",ImportGroupsEventType)
  val importRule  = EventLogFilter("Import Rules",ImportRulesEventType)
  val importTechLib  = EventLogFilter("Import Technique library",ImportTechniqueLibraryEventType)
  val importAll  = EventLogFilter("Import everything",ImportFullArchiveEventType)
  val imports = List(importGroup,importRule,importTechLib,importAll)
  val exportGroup = EventLogFilter("Export Groups",ExportGroupsEventType)
  val exportRule  = EventLogFilter("Export Rules",ExportRulesEventType)
  val exportTechLib  = EventLogFilter("Export Technique library",ExportTechniqueLibraryEventType)
  val exportAll  = EventLogFilter("Export everything",ExportFullArchiveEventType)
  val exports = List(exportAll,exportGroup,exportRule,exportTechLib)
  val archives = EventLogFilter("Archive",exports ++ imports)
  val interns = List(user,appliStart,deployment,clearCache,policyServer,archives)
  val intern = EventLogFilter("Internal",interns)

  def parent(filter:EventLogFilter): Option[EventLogFilter] = filter match {
    case _ if confs.contains(filter) => Some(configuration)
    case _ if techniques.contains(filter) => Some(technique)
    case _ if directives.contains(filter) => Some(directive)
    case _ if rules.contains(filter) => Some(rule)

    case _ if inventories.contains(filter) => Some(inventory)
    case _ if groups.contains(filter) => Some(group)
    case _ if nodes.contains(filter) => Some(node)

    case _ if deployments.contains(filter) => Some(deployment)
    case _ if interns.contains(filter) => Some(intern)
    case _ if users.contains(filter) => Some(user)
    case _ => None
  }

  def childs(filter:EventLogFilter): List[EventLogFilter] = filter match {
    case this.configuration => confs
    case this.technique => techniques
    case this.deployment => deployments
    case this.directive => directives
    case this.rule => rules
    case this.inventory =>  inventories
    case this.node => nodes
    case this.group => groups
    case this.intern => interns
    case this.user => users
    case _ => List(filter)
  }
}
