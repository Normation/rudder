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

package com.normation.rudder.web.components

import com.normation.rudder.domain.policies._
import com.normation.rudder.services.policies.PolicyInstanceTargetService
import com.normation.rudder.repository._
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.policies._
import net.liftweb.http.js._
import JsCmds._
import com.normation.rudder.services.reports.ReportingService
import com.normation.inventory.domain.NodeId
import com.normation.rudder.services.nodes.NodeInfoService
import JE._
import net.liftweb.common._
import net.liftweb.http._
import scala.xml._
import net.liftweb.util._
import net.liftweb.util.Helpers._
import com.normation.rudder.web.model._
import bootstrap.liftweb.LiftSpringApplicationContext.inject
import com.normation.utils.StringUuidGenerator
import com.normation.exceptions.TechnicalException
import com.normation.utils.Control.sequence
import com.normation.utils.HashcodeCaching


object ConfigurationRuleGrid {
  def staticInit =     
    <head>
      <script type="text/javascript" language="javascript" src="/javascript/datatables/js/jquery.dataTables.js"></script>
    </head>
}

class ConfigurationRuleGrid(
    htmlId_rulesGridZone : String,
    configurationRules : Seq[ConfigurationRule],
    //JS callback to call when clicking on a line 
    detailsCallbackLink : Option[ConfigurationRule => JsCmd],
    showCheckboxColumn:Boolean = true
) extends DispatchSnippet with Loggable {
  
  private[this] val targetInfoService = inject[PolicyInstanceTargetService]
  private[this] val policyInstanceRepository = inject[PolicyInstanceRepository]

  private[this] val reportingService = inject[ReportingService]
  private[this] val nodeInfoService = inject[NodeInfoService]

  private[this] val htmlId_rulesGridId = "grid_" + htmlId_rulesGridZone

  private[this] val htmlId_reportsPopup = "popup_" + htmlId_rulesGridZone
  private[this] val htmlId_modalReportsPopup = "modal_" + htmlId_rulesGridZone
  private[this] val tableId_reportsPopup = "reportsGrid"

  def templatePath = List("templates-hidden", "reports_grid")
  def template() =  Templates(templatePath) match {
    case Empty | Failure(_,_,_) =>
      throw new TechnicalException("Template for report grid not found. I was looking for %s.html".format(templatePath.mkString("/")))
    case Full(n) => n
  }
  def reportTemplate = chooseTemplate("reports", "report", template)

  def dispatch = { 
    case "configurationRulesGrid" => { _:NodeSeq => configurationRulesGrid() }
  }
  

  def jsVarNameForId(tableId:String) = "oTable" + tableId
  
  def configurationRulesGrid(linkCompliancePopup:Boolean = true) : NodeSeq = {
    (
        <div id={htmlId_rulesGridZone}>
          <div id={htmlId_modalReportsPopup} class="nodisplay">
            <div id={htmlId_reportsPopup} ></div>
          </div>
          <table id={htmlId_rulesGridId} cellspacing="0">
            <thead>
              <tr class="head">
                <th>Name<span/></th>
                <th>Description<span/></th>
                <th>Status<span/></th>
                <th>Deployment status<span/></th>
                <th>Compliance<span/></th>
                <th>Policy instance<span/></th>
                <th>Target node group<span/></th>
                { if(showCheckboxColumn) <th></th> else NodeSeq.Empty }
              </tr>
            </thead>
            <tbody>   
            {showConfigurationRulesDetails(configurationRules,linkCompliancePopup)}
            </tbody>
          </table> 
          <div class={htmlId_rulesGridId +"_pagination, paginatescala"} >
            <div id={htmlId_rulesGridId +"_paginate_area"}></div>
          </div>
        </div>
    ) ++ Script(
      JsRaw("""
        var #table_var#;
      """.replaceAll("#table_var#",jsVarNameForId(htmlId_rulesGridId))) &      
      //pop-ups for multiple policy instances
      JsRaw( """var openMultiPiPopup = function(popupid) {
          $("#" + popupid).modal({
            minHeight:300,
            minWidth: 520
          });
          $('#simplemodal-container').css('height', 'auto');
          correctButtons();
     }""") &
     OnLoad(JsRaw("""
      /* Event handler function */
      #table_var# = $('#%1$s').dataTable({
        "asStripClasses": [ 'color1', 'color2' ],
        "bAutoWidth": false,
        "bFilter" : false,
        "bPaginate" : true,
        "bLengthChange": false,
        "sPaginationType": "full_numbers",
        "bJQueryUI": false,
        "oLanguage": {
          "sZeroRecords": "No item involved"
        },
        "aaSorting": [[ 0, "asc" ]],
        "aoColumns": [ 
          { "sWidth": "95px" },
          { "sWidth": "95px"  },
          { "sWidth": "60px" },
          { "sWidth": "115px" },
          { "sWidth": "60px" },
          { "sWidth": "100px"  },
          { "sWidth": "100px" } %2$s
        ]
      });moveFilterAndFullPaginateArea('#%1$s'); createTooltip();""".format(
          htmlId_rulesGridId,
          { if(showCheckboxColumn) """, { "sWidth": "30px" }""" else "" }
      ).replaceAll("#table_var#",jsVarNameForId(htmlId_rulesGridId))
    )))
  }


  /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  
  private[this] def showConfigurationRulesDetails(crs:Seq[ConfigurationRule],linkCompliancePopup:Boolean) : NodeSeq = {
    case class Line(
        cr:ConfigurationRule,
        compliance:Option[ComplianceLevel],
        trackerVariables: Seq[(PolicyInstance,UserPolicyTemplate)],
        target:Option[PolicyInstanceTargetInfo]
    ) extends HashcodeCaching 
    
    //is a cr applied for real ?
    def isApplied(
        cr:ConfigurationRule,
        trackerVariables: Seq[(PolicyInstance,UserPolicyTemplate)],
        target:Option[PolicyInstanceTargetInfo]
    ) : Boolean = {
      cr.isActivated && target.isDefined && target.get.isActivated && trackerVariables.size > 0 && 
      trackerVariables.forall { case (pi,upt) => pi.isActivated && upt.isActivated }        
    }
    
    /*
     * For the policy instance:
     * - if none defined => "None"
     * - if one define => <a href="policy">policy name</a>
     * - if more than one => <a href="policy">policy name</a>, ... + tooltip with the full list
     */
    def displayPis(seq:Seq[(PolicyInstance,UserPolicyTemplate)]) : NodeSeq = {
      def piLink(pi:PolicyInstance) = <a href={"""/secure/configurationManager/policyInstanceManagement#{"piId":"%s"}""".format(pi.id.value)}>{
          pi.name + (if (pi.isActivated) "" else " (disabled)")
        }</a>
      
      if(seq.size < 1) <i>None</i>
      else if(seq.size == 1) piLink(seq.head._1)
      else { 
      	val popupId = Helpers.nextFuncName
      	val tableId_listPI = Helpers.nextFuncName
        <span class="popcurs" onclick={"openMultiPiPopup('"+popupId+"') ; return false;"}>{seq.head._1.name +  ", ..."}</span> ++
        <div id={popupId} class="nodisplay">
          <div class="simplemodal-title">
            <h1>List of policy instances</h1>
            <hr/>
          </div>
          <div class="simplemodal-content">
            <br/>
            <h2>Click on a policy name to go to its configuration screen</h2>
            <hr class="spacer"/>
            <br/>
            <br/>
        		<table id={tableId_listPI} cellspacing="0">
        			<thead>
        				<tr class="head">
        				 <th>Policy Instance<span/></th>
        				 <th>Policy Template<span/></th>
        				</tr>
        			</thead>
        			<tbody>
            { 
              (
                "span" #> seq.map { case(pi,upt) => "#link" #> <tr><td>{piLink(pi)}</td><td>{upt.referencePolicyTemplateName}</td></tr> }
              )(<span id="link"/>
              )
            }
            	</tbody>
            </table>
            <hr class="spacer" />
          </div>
          <div class="simplemodal-bottom">
           	<hr/>
           	<div class="popupButton">
           		<span>
             		<button class="simplemodal-close" onClick="return false;">
               		Close
             		</button>
           		</span>
           </div>
         </div>
        </div> ++
        Script(OnLoad(JsRaw("""
          %1$s_tableId = $('#%2$s').dataTable({
        		"asStripClasses": [ 'color1', 'color2' ],
            "bAutoWidth": false,
            "bFilter" : false,
            "bPaginate" : true,
            "bLengthChange": false,

            "bJQueryUI": false,
            "aaSorting": [[ 0, "asc" ]],
            "aoColumns": [
              { "sWidth": "200px" },
              { "sWidth": "300px" }
            ]
          });dropFilterAndPaginateArea('#%2$s');""".format( tableId_listPI, tableId_listPI))) )
      }
    }
    
    //for each rule, get all the required info and display them
    val lines = crs.flatMap { cr =>
      (for {
        trackerVariables: Seq[(PolicyInstance,UserPolicyTemplate)] <- sequence(cr.policyInstanceIds.toSeq) { id =>
          policyInstanceRepository.getPolicyInstance(id) match {
            case Full(pi) => policyInstanceRepository.getUserPolicyTemplate(id) match {
              case Full(upt) => Full((pi,upt))
              case e:EmptyBox => //it's an error if the pi ID is defined and such be is not found
                e ?~! "Can not find User Policy Template for policy instance with ID %s referenced in configuration rule with ID %s".format(id, cr.id)
            }
            case e:EmptyBox => //it's an error if the pi ID is defined and such pi is not found
              e ?~! "Can not find Policy Instance with ID %s referenced in configuration rule with ID %s".format(id, cr.id)
          }
        }
        targetInfo <- cr.target match {
          case None => Full(None)
          case Some(target) => targetInfoService.getTargetInfo(target) match {
            case Full(targetInfo) => Full(Some(targetInfo))
            case Empty => 
              //TODO: it can't be an error, because in that case the CR just "disapear" for the user, without log message nor anything else. 
              //but we really should have a "TargetError" case or something to let the user know that the target was not found
              logger.warn("Can not find requested target: '%s', it seems to be a database inconsistency.".format(target.target))
              Full(None)             
            case f:Failure => //it's an error if the pi ID is defined and such id is not found
              val error = f ?~! "Can not find Target information for target %s referenced in configuration rule with ID %s".format(target.target, cr.id)
              logger.debug(error.messageChain, error)
              error
          }
        }
        compliance = if(isApplied(cr, trackerVariables, targetInfo)) computeCompliance(cr) else None
      } yield {
        Line(cr, compliance, trackerVariables, targetInfo)
      }) match {
        case e:EmptyBox => 
          logger.warn((e ?~! "Error when fetching details for configuration rule %s %s, remove it from the list of configuration rules".format(cr.name, cr.id)).messageChain)
          e
        case x => x  
      }
    }
    
    //now, build html lines
    if(lines.isEmpty) {
      NodeSeq.Empty
    } else {
      lines.map { line =>
      <tr>
        <td>{ // NAME 
          detailsLink(line.cr, line.cr.name)
        }</td>
        <td>{ // DESCRIPTION
          detailsLink(line.cr, line.cr.shortDescription)
        }</td>
        <td>{ // OWN STATUS
          if (line.cr.isActivatedStatus) "Enabled" else "Disabled"
        }</td>
        <td><b>{ // EFFECTIVE STATUS
          if(isApplied(line.cr, line.trackerVariables, line.target)) Text("In application")
          else {
              val conditions = Seq(
                  (line.cr.isActivated, "Configuration rule disabled" ), 
                  ( line.trackerVariables.size > 0, "No policy defined"),
                  ( line.target.isDefined && line.target.get.isActivated, "Group disabled")
               ) ++ line.trackerVariables.flatMap { case (pi, upt) => Seq(
                  ( pi.isActivated , "Policy " + pi.name + " disabled") , 
                  ( upt.isActivated, "Policy template for '" + pi.name + "' disabled") 
               )}
               
              val why =  conditions.collect { case (ok, label) if(!ok) => label }.mkString(", ") 
              <span class="tooltip tooltipable" tooltipid={line.cr.id.value}>Not applied</span>
               <div class="tooltipContent" id={line.cr.id.value}><h3>Reason(s)</h3><div>{why}</div></div>
          }
        }</b></td>
        <td>{ //  COMPLIANCE
          buildComplianceChart(line.compliance, line.cr, linkCompliancePopup)
        }</td>
        <td>{ //  POLICY INSTANCE: <not defined> or PIName [(disabled)]
          displayPis(line.trackerVariables)
         }</td>
        <td>{ //  TARGET NODE GROUP
          line.target match {
            case None => <i>None</i>
            case Some(targetInfo) => targetInfo.target match {
              case GroupTarget(groupId) => <a href={ """/secure/assetManager/groups#{"groupId":"%s"}""".format(groupId.value)}>{ 
                  targetInfo.name + (if (targetInfo.isActivated) "" else " (disabled)") 
                }</a>
              case _ => Text({ targetInfo.name + (if (targetInfo.isActivated) "" else " (disabled)") })
             }
          }
        }</td>
        { // CHECKBOX 
          if(showCheckboxColumn) <td><input type="checkbox" name={line.cr.id.value} /></td> else NodeSeq.Empty 
        }
      </tr>  
    }
    }
  }

  private[this] def computeCompliance(cr: ConfigurationRule) : Option[ComplianceLevel] = {
    reportingService.findImmediateReportsByConfigurationRule(cr.id) match {
      case None => Some(Applying) // when we have a cr but nothing in the database, it means that it is currentluy being deployed
      case Some(x) if (x.allExpectedServer.size==0) => None
      case Some(x) if (x.getPendingServer.size>0) => Some(Applying)
      case Some(x) =>  Some(new Compliance((100 * x.getSuccessServer.size) / x.allExpectedServer.size))
    }
  }

  private[this] def detailsLink(cr:ConfigurationRule, text:String) : NodeSeq = {
    detailsCallbackLink match {
      case None => Text(text)
      case Some(callback) => SHtml.a( () => callback(cr), Text(text))
    }
  }  
  
  private[this] def buildComplianceChart(level:Option[ComplianceLevel], cr: ConfigurationRule, linkCompliancePopup:Boolean) : NodeSeq = {
    level match {
      case None => Text("N/A")
      case Some(Applying) => Text("Applying")
      case Some(NoAnswer) => Text("No answer")
      case Some(Compliance(percent)) =>  {
        val text = Text(percent.toString + "%")
        if(linkCompliancePopup) SHtml.a({() => showPopup(cr)}, text)
        else text
      }
    }
  }

/*********************************************
  Popup for the reports
 ************************************************/
  private[this] def createPopup(cr: ConfigurationRule) : NodeSeq = {
    val batch = reportingService.findImmediateReportsByConfigurationRule(cr.id)

    <div class="simplemodal-title">
      <h1>List of nodes having the {Text(cr.name)} configuration rule</h1>
      <hr/>
    </div>
    <div class="simplemodal-content"> { bind("lastReportGrid",reportTemplate,
        "crName" -> Text(cr.name),
        "lines" -> (
          batch match {
            case None => Text("No Reports")
            case Some(reports) =>
            ((reports.getSuccessServer().map ( x =>  ("Success" , x)) ++
            	 reports.getRepairedServer().map ( x => ("Repaired" , x)) ++
               //reports.getWarnServer().map ( x => ("Warn" , x)) ++
               reports.getErrorServer().map ( x =>  ("Error" , x)) ++
               reports.getPendingServer().map ( x =>  ("Applying" , x)) ++
               reports.getServerWithNoReports().map ( x => ("No answer" , x)) ) ++
               reports.getUnknownNodes().map ( x =>  ("Unknown" , x)) :Seq[(String, NodeId)]).flatMap {
                 case s@(severity:String, uuid:NodeId) if (uuid != null) =>
                   nodeInfoService.getNodeInfo(uuid) match {
                     case Full(nodeInfo)  => {
                        <tr class={severity.replaceAll(" ", "")}>
                        {bind("line",chooseTemplate("lastReportGrid","lines",reportTemplate),
                         "hostname" -> <a href={"""secure/assetManager/searchServers#{"nodeId":"%s"}""".format(uuid.value)}><span class="curspoint" jsuuid={uuid.value.replaceAll("-","")} serverid={uuid.value}>{nodeInfo.hostname}</span></a>,
                         "severity" -> severity )}
                        </tr>
                     }
                     case x:EmptyBox => 
                       logger.error( (x?~! "An error occured when trying to load node %s".format(uuid.value)),x)
                       <div class="error">Node with ID "{uuid.value}" is invalid</div>
                   }

               }
            }
         )
      )
    }<hr class="spacer" />
    </div>
    <div class="simplemodal-bottom">
			<hr/>
			<div class="popupButton">
				<span>
	        <button class="simplemodal-close" onClick="return false;">
          Close
  	      </button>
  	    </span>
      </div>
    </div>

  }
  
  private[this] def showPopup(cr: ConfigurationRule) : JsCmd = {
    val popupHtml = createPopup(cr)
    SetHtml(htmlId_reportsPopup, popupHtml) &
    JsRaw("""
        var #table_var#;
        /* Formating function for row details */
        function fnFormatDetails ( id ) {
          var sOut = '<div id="'+id+'" class="reportDetailsGroup"/>';
          return sOut;
        }
      """.replaceAll("#table_var#",jsVarNameForId(tableId_reportsPopup))
    ) & OnLoad(
        JsRaw("""
          /* Event handler function */
          #table_var# = $('#%1$s').dataTable({
            "bAutoWidth": false,
            "bFilter" : false,
            "bPaginate" : true,
            "bLengthChange": false,
            "sPaginationType": "full_numbers",
            "bJQueryUI": false,
            "aaSorting": [[ 3, "asc" ]],
            "aoColumns": [
              { "sWidth": "200px" },
              { "sWidth": "300px" }
            ]
          });moveFilterAndFullPaginateArea('#%1$s');""".format( tableId_reportsPopup).replaceAll("#table_var#",jsVarNameForId(tableId_reportsPopup))
        ) //&  initJsCallBack(tableId)
    ) &
    JsRaw( """ $("#%s").modal({
      minHeight:300,
  	  minWidth: 500
     });
    $('#simplemodal-container').css('height', 'auto');
    correctButtons();
     """.format(htmlId_modalReportsPopup))

  }

}



trait ComplianceLevel 


case object Applying extends ComplianceLevel
case object NoAnswer extends ComplianceLevel
case class Compliance(val percent:Int) extends ComplianceLevel with HashcodeCaching 

  
  

