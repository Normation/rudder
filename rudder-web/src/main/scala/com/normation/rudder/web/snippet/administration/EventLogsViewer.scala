/*
*************************************************************************************
* Copyright 2011 Normation SAS
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

package com.normation.rudder.web.snippet.administration

import scala.xml._
import net.liftweb.http.{DispatchSnippet,S}
import net.liftweb.common._
import net.liftweb.util._
import net.liftweb.util.Helpers._
import bootstrap.liftweb.LiftSpringApplicationContext.inject
import com.normation.rudder.repository.EventLogRepository
import com.normation.eventlog.EventLog
import com.normation.rudder.domain.log._
import com.normation.rudder.web.components.DateFormaterService
import net.liftweb.http.js._
import net.liftweb.http.js.JE._
import net.liftweb.http.js.JsCmds._
import net.liftweb.http.SHtml
import com.normation.rudder.services.log.EventLogDetailsService
import com.normation.rudder.domain.policies._
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.cfclerk.domain.PolicyPackageName
import com.normation.rudder.domain.queries.Query
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.log.InventoryLogDetails
import com.normation.rudder.web.model.JsInitContextLinkUtil._

class EventLogsViewer extends DispatchSnippet with Loggable {
  private[this] val repos = inject[EventLogRepository]
  private[this] val logDetailsService = inject[EventLogDetailsService]
  
  private[this] val xmlPretty = new scala.xml.PrettyPrinter(80, 2)
  
 
  
  def getLastEvents : Box[Seq[EventLog]] = {
    repos.getEventLogByCriteria(None, Some(1000), Some("id DESC")) 
  }
    
  def dispatch = { 
    case "display" => xml => getLastEvents match {
      case Full(seq) => display(seq,xml)
      case Empty => display(Seq(),xml)
      case f:Failure => 
        <div class="error">Error when trying to get last event logs. Error message was: {f.msg}</div>
    } 
  } 
  
  
  def display(events:Seq[EventLog], xml:NodeSeq) : NodeSeq  = {
    (
      "tbody *" #> ("tr" #> events.map { event => 
        ".logId *" #> event.id.getOrElse(0).toString &
        ".logDatetime *" #> DateFormaterService.getFormatedDate(event.creationDate) &
        ".logActor *" #> event.principal.name &
        ".logType *" #> event.eventType &
        ".logCategory *" #> S.?("event.log.category."+event.eventLogCategory.getClass().getSimpleName()) &
        ".logDescription *" #> displayDescription(event) &
        ".logDetails *" #> { 
        	if (event.details != <entry></entry> ) 
        	  SHtml.a(Text("Details"))(showEventsDetail(event.id.getOrElse(0), event.eventType)) 
        	else 
        	  NodeSeq.Empty
        	}
      })
     )(xml) ++ initJs
  }
  
  private[this] val gridName = "eventLogsGrid"
  private[this] val jsGridName = "oTable" + gridName
  private[this] val eventDetailPopupHtmlId = "eventDetailPopup" 
    
  private[this] def initJs : NodeSeq = Script(
    JsRaw("var %s;".format(jsGridName)) &
    OnLoad(
        JsRaw("""
          /* Event handler function */
          #table_var# = $('#%s').dataTable({
            "asStripClasses": [ 'color1', 'color2' ],
            "bAutoWidth": false,
            "bFilter" :true,
            "bPaginate" :true,
            "bLengthChange": false,
            "sPaginationType": "full_numbers",
            "oLanguage": {
              "sSearch": "Filter:"
            },
            "bJQueryUI": false,
            "aaSorting":[],
            "aoColumns": [
                { "sWidth": "30px" }
              , { "sWidth": "110px" }
              , { "sWidth": "110px" }
              , { "sWidth": "110px" }
              , { "sWidth": "100px" }
              , { "sWidth": "150px" }
              , { "sWidth": "80px" }
            ]
          });moveFilterAndFullPaginateArea('#%s');""".format(gridName,gridName).replaceAll("#table_var#",jsGridName)
        )
    )    
  )
    
  private[this] def showEventsDetail(id : Int, eventType : String) : JsCmd = {
    ( repos.getEventLog(id) match {
      case Full(event) =>
        SetHtml(eventDetailPopupHtmlId, (
            "#popupTitle" #> "Details for event %s".format(eventType) &
            "#popupContent" #>  displayDetails(event)
            )(popupContentDetail))
      case _ => 
        SetHtml(eventDetailPopupHtmlId, (
            "#popupTitle" #> "Error" &
            "#popupContent" #> "Could not fetch the event details for event %s".format(eventType)
            )(popupContentDetail))
    } ) & 
    JsRaw("createPopup('%s', 150, 400)".format(eventDetailPopupHtmlId))
    
    
  }
  
  //////////////////// Display description/details of ////////////////////
  
  //convention: "X" means "ignore"
  
  private[this] def displayDescription(event:EventLog) = {
    def crDesc(x:EventLog, actionName: NodeSeq) = {
        val id = (x.details \ "configurationRule" \ "id").text
        val name = (x.details \ "configurationRule" \ "displayName").text
        Text("Configuration rule ") ++ {
          if(id.size < 1) Text(name)
          else <a href={configurationRuleLink(ConfigurationRuleId(id))}>{name}</a> ++ actionName
        }
    }
    
    def piDesc(x:EventLog, actionName: NodeSeq) = {
        val id = (x.details \ "policyInstance" \ "id").text
        val name = (x.details \ "policyInstance" \ "displayName").text
        Text("Policy instance ") ++ {
          if(id.size < 1) Text(name)
          else <a href={policyInstanceLink(PolicyInstanceId(id))}>{name}</a> ++ actionName
        }
    }
    
    def groupDesc(x:EventLog, actionName: NodeSeq) = {
        val id = (x.details \ "nodeGroup" \ "id").text
        val name = (x.details \ "nodeGroup" \ "displayName").text
        Text("Group ") ++ {
          if(id.size < 1) Text(name)
          else <a href={groupLink(NodeGroupId(id))}>{name}</a> ++ actionName
        }
    }
    
    def nodeDesc(x:EventLog, actionName: NodeSeq) = {
        val id = (x.details \ "node" \ "id").text
        val name = (x.details \ "node" \ "hostname").text
        Text("Node ") ++ {
          if(id.size < 1) Text(name)
          else <a href={nodeLink(NodeId(id))}>{name}</a> ++ actionName
        }
    }
    
    event match {
      case x:ActivateRedButton => Text("Stop Rudder agents on all nodes")
      case x:ReleaseRedButton => Text("Start again Rudder agents on all nodes")
      case x:AcceptNodeEventLog => nodeDesc(x, Text(" accepted"))
      case x:RefuseNodeEventLog => nodeDesc(x, Text(" refused"))
      case x:LoginEventLog => Text("User '%s' login".format(x.principal.name))
      case x:LogoutEventLog => Text("User '%s' logout".format(x.principal.name))
      case x:BadCredentialsEventLog => Text("User '%s' failed to login: bad credentials".format(x.principal.name))
      case x:AutomaticStartDeployement => Text("Automatically deploy policy on nodes")
      case x:ManualStartDeployement => Text("Manually deploy policy on nodes")
      case x:ApplicationStarted => Text("Rudder starts")
      case x:ModifyConfigurationRule => crDesc(x,Text(" modified"))
      case x:DeleteConfigurationRule => crDesc(x,Text(" deleted"))
      case x:AddConfigurationRule    => crDesc(x,Text(" added"))
      case x:ModifyPolicyInstance => piDesc(x,Text(" modified"))
      case x:DeletePolicyInstance => piDesc(x,Text(" deleted"))
      case x:AddPolicyInstance    => piDesc(x,Text(" added"))
      case x:ModifyNodeGroup => groupDesc(x,Text(" modified"))
      case x:DeleteNodeGroup => groupDesc(x,Text(" deleted"))
      case x:AddNodeGroup    => groupDesc(x,Text(" added"))
      case _ => Text("Unknow event type")
      
    }
  }

  private[this] def displayDetails(event:EventLog) = {
    def errorMessage(e:EmptyBox) = {
      logger.debug(e ?~! "Error when parsing details.", e)
      <xml:group>Details for that node were not in a recognized format. Raw data are displayed next:
                        <pre>{event.details.map { n => xmlPretty.format(n) + "\n"} }</pre></xml:group>
    }
    
    (event match {
    
      case add:AddConfigurationRule =>
        "*" #> (logDetailsService.getConfigurationRuleAddDetails(add.details) match {
          case Full(addDiff) => 
            <div class="evloglmargin"><p>Configuration rule <b>"{addDiff.cr.name}"</b> (ID:{addDiff.cr.id.value}) added with parameters:</p>{
              configurationRuleDetails(crDetailsXML,addDiff.cr)
            }</div>
          case e:EmptyBox => errorMessage(e)
        })
      
      case del:DeleteConfigurationRule =>
        "*" #> (logDetailsService.getConfigurationRuleDeleteDetails(del.details) match {
          case Full(delDiff) =>
            <div class="evloglmargin"><p>Configuration rule <b>"{delDiff.cr.name}"</b> (ID:{delDiff.cr.id.value}) deleted with parameters:</p>{
              configurationRuleDetails(crDetailsXML,delDiff.cr)
            }</div>
          case e:EmptyBox => errorMessage(e)
        })
        
      case mod:ModifyConfigurationRule =>
        "*" #> (logDetailsService.getConfigurationRuleModifyDetails(mod.details) match {
          case Full(modDiff) =>            
            <div class="evloglmargin"><p>Configuration rule <b>"{modDiff.name}"</b>(ID:{modDiff.id.value}) was modified:</p>{
              (
                "#name" #> mapSimpleDiff(modDiff.modName) &
                "#isActivated *" #> mapSimpleDiff(modDiff.modIsActivatedStatus) &
                "#isSystem *" #> mapSimpleDiff(modDiff.modIsSystem) &
                "#shortDescription *" #> mapSimpleDiff(modDiff.modShortDescription) &
                "#longDescription *" #> mapSimpleDiff(modDiff.modLongDescription) &
                "#target" #> (
                  modDiff.modTarget.map { diff =>
                   ".diffOldValue" #> groupTargetDetails(diff.oldValue) &
                   ".diffNewValue" #> groupTargetDetails(diff.newValue)               
                  }
                ) &
                "#policies" #> (
                   modDiff.modPolicyInstanceIds.map { diff =>
                   val mapList = (set:Set[PolicyInstanceId]) => {
                     if(set.size < 1) Text("None")
                     else <ul class="evlogviewpad">{ set.toSeq.sortWith( _.value < _.value ).map { id =>
                            <li><a href={policyInstanceLink(id)}>{id.value}</a></li>
                          } }</ul>
                   }
                   
                   ".diffOldValue" #> mapList(diff.oldValue) &
                   ".diffNewValue" #> mapList(diff.newValue)
                   }
                )
              )(crModDetailsXML)
            }</div>
          case e:EmptyBox => errorMessage(e)
        })

      ///////// Policy Instance /////////
        
      case x:ModifyPolicyInstance =>   
        "*" #> (logDetailsService.getPolicyInstanceModifyDetails(x.details) match {
          case Full(modDiff) =>
            <div class="evloglmargin"><p>Policy Instance <b>"{modDiff.name}"</b>(ID:{modDiff.id.value}) was modified:</p>{
              (
                "#name" #> mapSimpleDiff(modDiff.modName) &
                "#priority *" #> mapSimpleDiff(modDiff.modPriority) &
                "#isActivated *" #> mapSimpleDiff(modDiff.modIsActivated) &
                "#isSystem *" #> mapSimpleDiff(modDiff.modIsSystem) &
                "#shortDescription *" #> mapSimpleDiff(modDiff.modShortDescription) &
                "#longDescription *" #> mapSimpleDiff(modDiff.modLongDescription) &
                "#ptVersion *" #> mapSimpleDiff(modDiff.modPolicyTemplateVersion) &
                "#parameters" #> (
                  modDiff.modParameters.map { diff =>
                   ".diffOldValue" #> <pre>{xmlPretty.format(SectionVal.toXml(diff.oldValue))}</pre> &
                   ".diffNewValue" #> <pre>{xmlPretty.format(SectionVal.toXml(diff.newValue))}</pre>
                   }
                ) 
              )(piModDetailsXML)
            }</div>
          case e:EmptyBox => errorMessage(e)
        })
        
    
      case x:AddPolicyInstance =>   
        "*" #> (logDetailsService.getPolicyInstanceAddDetails(x.details) match {
          case Full((diff,sectionVal)) =>
            <div class="evloglmargin"><p>Policy Instance <b>"{diff.pi.name}"</b> (ID:{diff.pi.id.value}) added. Parameters were:</p>{
              policyInstanceDetails(piDetailsXML,diff.policyTemplateName, diff.pi,sectionVal)
            }</div>
          case e:EmptyBox => errorMessage(e)
        })
        
    
      case x:DeletePolicyInstance =>   
        "*" #> (logDetailsService.getPolicyInstanceDeleteDetails(x.details) match {
          case Full((diff,sectionVal)) =>
            <div class="evloglmargin"><p>Policy Instance <b>"{diff.pi.name}"</b> (ID:{diff.pi.id.value}) deleted. Parameters were:</p>{
              policyInstanceDetails(piDetailsXML,diff.policyTemplateName, diff.pi,sectionVal)
            }</div>
          case e:EmptyBox => errorMessage(e)
        })
        
      ///////// Node Group /////////
        
      case x:ModifyNodeGroup =>   
        "*" #> (logDetailsService.getNodeGroupModifyDetails(x.details) match {
          case Full(modDiff) => 
            <div class="evloglmargin"><p>Node group <b>"{modDiff.name}"</b> (ID:{modDiff.id.value}) modified with parameters:</p>{
              (
                "#name" #> mapSimpleDiff(modDiff.modName) &
                "#isActivated *" #> mapSimpleDiff(modDiff.modIsActivated) &
                "#isSystem *" #> mapSimpleDiff(modDiff.modIsSystem) &
                "#isDynamic *" #> mapSimpleDiff(modDiff.modIsDynamic) &
                "#shortDescription *" #> mapSimpleDiff(modDiff.modDescription) &
                "#query" #> (
                    modDiff.modQuery.map { diff =>
                    val mapOptionQuery = (opt:Option[Query]) =>
                      opt match {
                        case None => Text("None")
                        case Some(q) => Text(q.toJSONString)
                      }
                      
                     ".diffOldValue" #> mapOptionQuery(diff.oldValue) &
                     ".diffNewValue" #> mapOptionQuery(diff.newValue)
                    }
                ) &
                "#nodes" #> (
                   modDiff.modServerList.map { diff =>
                   val mapList = (set:Set[NodeId]) =>
                     if(set.size == 0) Text("None")
                     else <ul class="evlogviewpad">{ set.toSeq.sortWith( _.value < _.value ).map { id =>
                       <li><a href={nodeLink(id)}>{id.value}</a></li>
                     } }</ul>
                     
                   ".diffOldValue" #> mapList(diff.oldValue) &
                   ".diffNewValue" #> mapList(diff.newValue)
                   }
                ) 
              )(groupModDetailsXML)
            }</div>
          case e:EmptyBox => errorMessage(e)
        })
        
    
      case x:AddNodeGroup =>   
        "*" #> (logDetailsService.getNodeGroupAddDetails(x.details) match {
          case Full(diff) =>
            <div class="evloglmargin"><p>Node group <b>"{diff.group.name}"</b> (ID:{diff.group.id.value}) added with parameters:</p>{
              groupDetails(groupDetailsXML,diff.group)
            }</div>
          case e:EmptyBox => errorMessage(e)
        })
        
    
      case x:DeleteNodeGroup =>   
        "*" #> (logDetailsService.getNodeGroupDeleteDetails(x.details) match {
          case Full(diff) =>
            <div class="evloglmargin"><p>Node group <b>"{diff.group.name}"</b> (ID:{diff.group.id.value}) deleted with parameters:</p>{
              groupDetails(groupDetailsXML,diff.group)
            }</div>
          case e:EmptyBox => errorMessage(e)
        })
        
      ///////// Node Group /////////
        
      case x:AcceptNodeEventLog =>   
        "*" #> (logDetailsService.getAcceptNodeLogDetails(x.details) match {
          case Full(details) =>
            <div class="evloglmargin"><p>Node <b>"{details.hostname}"</b> (ID:{details.nodeId.value}) accepted:</p>{
              nodeDetails(details)
            }</div>
          case e:EmptyBox => errorMessage(e)
        })
        
      case x:RefuseNodeEventLog =>   
        "*" #> (logDetailsService.getRefuseNodeLogDetails(x.details) match {
          case Full(details) =>
            <div class="evloglmargin"><p>Node <b>"{details.hostname}"</b> (ID:{details.nodeId.value}) refused:</p>{
              nodeDetails(details)
            }</div>
          case e:EmptyBox => errorMessage(e)
        })
        
        
      // other case: do not display details at all
      case _ => "*" #> ""
    
    })(event.details)
  
  }
  
  
  private[this] def displayDiff(tag:String)(xml:NodeSeq) = 
      "From value '%s' to value '%s'".format(
          ("from ^*" #> "X" & "* *" #> (x => x))(xml) 
        , ("to ^*" #> "X" & "* *" #> (x => x))(xml)
      )
  
  private[this] def policyInstanceIdDetails(piIds:Seq[PolicyInstanceId]) = piIds.map { id => 
    (<b>ID:</b><a href={policyInstanceLink(id)}>{id.value}</a>):NodeSeq
  }
  
  private[this] def groupTargetDetails(target:Option[PolicyInstanceTarget]):NodeSeq = target match {
    case Some(GroupTarget(id@NodeGroupId(g))) => (
      <b>Group:</b><a href={groupLink(id)}>{g}</a>  
    )
    case Some(x) => Text("Special group: " + x.toString)
    case None => Text("None")
  }
      
  
  private[this] def configurationRuleDetails(xml:NodeSeq, cr:ConfigurationRule) = (
      "#id" #> cr.id.value &
      "#name" #> cr.name &
      "#target" #> groupTargetDetails(cr.target) &
      "#policy" #> (xml =>  
        if(cr.policyInstanceIds.size < 1) Text("None") else {
          (".policyId *" #> policyInstanceIdDetails(cr.policyInstanceIds.toSeq))(xml)              
        } ) &
      "#isActivated" #> cr.isActivated &
      "#isSystem" #> cr.isSystem &
      "#shortDescription" #> cr.shortDescription &
      "#longDescription" #> cr.longDescription
  )(xml)
  
  private[this] def policyInstanceDetails(xml:NodeSeq, ptName: PolicyPackageName, pi:PolicyInstance, sectionVal:SectionVal) = (
      "#parameters" #> <pre>{xmlPretty.format(SectionVal.toXml(sectionVal))}</pre> &
      "#ptVersion" #> pi.policyTemplateVersion.toString &
      "#ptName" #> ptName.value &
      "#priority" #> pi.priority &
      "#isActivated" #> pi.isActivated &
      "#isSystem" #> pi.isSystem &
      "#shortDescription" #> pi.shortDescription &
      "#longDescription" #> pi.longDescription
  )(xml)
  
  private[this] def groupDetails(xml:NodeSeq, group: NodeGroup) = (
      "#shortDescription" #> group.description &
      "#query" #> (group.query match {
        case None => Text("None")
        case Some(q) => Text(q.toJSONString)
      } ) &
      "#isDynamic" #> group.isDynamic &
      "#nodes" #>( if(group.serverList.size < 1) Text("None")
                   else <ul class="evlogviewpad">{group.serverList.map { id => 
                         <li><a href={nodeLink(id)}>{id.value}</a></li>
                        } }</ul>) &
      "#isActivated" #> group.isActivated &
      "#isSystem" #> group.isSystem
  )(xml)
  
 private[this] def mapSimpleDiff[T](opt:Option[SimpleDiff[T]]) = opt.map { diff =>
   ".diffOldValue" #> diff.oldValue.toString &
   ".diffNewValue" #> diff.newValue.toString
  }  
  
  private[this] def nodeDetails(details:InventoryLogDetails) = (
     "#os" #> details.fullOsName &
     "#version" #> DateFormaterService.getFormatedDate(details.inventoryVersion)
  )( 
    <ul class="evlogviewpad">
      <li><b>Operating System:</b>'<value id="os"/>'</li>
      <li><b>Inventory Version:</b>'<value id="version"/>'</li>
    </ul>
  )
  
  private[this] val crDetailsXML = 
    <ul class="evlogviewpad">
      <li><b>target:</b>'<value id="target"/>'</li>
      <li><b>policies:</b>'<value id="policy"/>'</li>
      <li><b>enabled:</b>'<value id="isActivated"/>'</li>
      <li><b>system:</b>'<value id="isSystem"/>'</li>
      <li><b>description:</b>'<value id="shortDescription"/>'</li>
      <li><b>details:</b>'<value id="longDescription"/>'</li>
    </ul>
    
  private[this] val piDetailsXML = 
    <ul class="evlogviewpad">
      <li><b>policy template name:</b><value id="ptName"/></li>
      <li><b>policy template version:</b><value id="ptVersion"/></li>
      <li><b>priority:</b>'<value id="priority"/>'</li>
      <li><b>enabled:</b>'<value id="isActivated"/>'</li>
      <li><b>system:</b>'<value id="isSystem"/>'</li>
      <li><b>description:</b>'<value id="shortDescription"/>'</li>
      <li><b>details:</b>'<value id="longDescription"/>'</li>
      <li><b>policy parameters:</b><value id="parameters"/></li>
    </ul>
    
  private[this] val groupDetailsXML = 
    <ul class="evlogviewpad">
      <li><b>description:</b>'<value id="shortDescription"/>'</li>
      <li><b>enabled:</b>'<value id="isActivated"/>'</li>
      <li><b>dynamic:</b>'<value id="isDynamic"/>'</li>
      <li><b>system:</b>'<value id="isSystem"/>'</li>
      <li><b>query:</b><value id="query"/></li>
      <li><b>node list:</b><value id="nodes"/></li>
    </ul>
    
  private[this] def liModDetailsXML(id:String, name:String) = (
      <div id={id}>
        <b>{name}</b> changed
        <table>
          <thead><tr><th>from:</th><th>to:</th></tr></thead>
          <tbody>
            <tr>
              <td><span class="diffOldValue">old value</span></td>
              <td><span class="diffNewValue">new value</span></td>
            </tr>
          </tbody>
        </table>
      </div>
  )
  
  private[this] val groupModDetailsXML = 
    <xml:group>
      {liModDetailsXML("name", "Name")}
      {liModDetailsXML("shortDescription", "Description")}
      {liModDetailsXML("nodes", "Server list")}
      {liModDetailsXML("query", "Query")}
      {liModDetailsXML("isDynamic", "Dynamic group")}
      {liModDetailsXML("isActivated", "Activation status")}
      {liModDetailsXML("isSystem", "System")}
    </xml:group>      

  private[this] val crModDetailsXML = 
    <xml:group>
      {liModDetailsXML("name", "Name")}
      {liModDetailsXML("shortDescription", "Description")}
      {liModDetailsXML("longDescription", "Details")}
      {liModDetailsXML("target", "Target")}
      {liModDetailsXML("policies", "Policies")}
      {liModDetailsXML("isActivated", "Activation status")}
      {liModDetailsXML("isSystem", "System")}
    </xml:group>
      
  private[this] val piModDetailsXML = 
    <xml:group>
      {liModDetailsXML("name", "Name")}
      {liModDetailsXML("shortDescription", "Description")}
      {liModDetailsXML("longDescription", "Details")}
      {liModDetailsXML("ptVersion", "Target")}
      {liModDetailsXML("parameters", "Policy parameters")}
      {liModDetailsXML("priority", "Priority")}
      {liModDetailsXML("isActivated", "Activation status")}
      {liModDetailsXML("isSystem", "System")}
    </xml:group>
      
      
  private[this] val popupContentDetail = <div class="simplemodal-title">
      <h1><value id="popupTitle"/></h1>
      <hr/>
    </div>
    <div class="simplemodal-content">
      <br />
      <div id="popupContent"/>
	</div>
	<div class="simplemodal-bottom">
      <hr/>
      <p align="right">
        <button class="simplemodal-close" onClick="return false;">
          Close
        </button>
      </p>
    </div>
}
