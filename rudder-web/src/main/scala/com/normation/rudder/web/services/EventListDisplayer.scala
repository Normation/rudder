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
package com.normation.rudder.web.services

import scala.xml._
import com.normation.eventlog.EventLog
import com.normation.rudder.domain.eventlog._
import net.liftweb.common._
import net.liftweb.util._
import net.liftweb.util.Helpers._
import com.normation.rudder.domain.policies._
import com.normation.rudder.domain.nodes._
import com.normation.rudder.services.eventlog.EventLogDetailsService
import com.normation.rudder.web.components.DateFormaterService
import com.normation.cfclerk.domain.TechniqueName
import com.normation.rudder.web.model.JsInitContextLinkUtil._
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.queries.Query
import net.liftweb.http.js._
import net.liftweb.http.js.JE._
import net.liftweb.http.js.JsCmds._
import net.liftweb.http.SHtml
import net.liftweb.http.S
import com.normation.rudder.batch.SuccessStatus
import com.normation.rudder.batch.ErrorStatus
import com.normation.rudder.repository._
import bootstrap.liftweb.LiftSpringApplicationContext.inject
import com.normation.rudder.services.nodes.NodeInfoService

/**
 * Used to display the event list, in the pending modification (AsyncDeployment), 
 * or in the administration EventLogsViewer
 */
class EventListDisplayer(
      logDetailsService   : EventLogDetailsService
    , repos               : EventLogRepository
    , nodeGroupRepository : NodeGroupRepository
    , directiveRepository : DirectiveRepository
    , nodeInfoService     : NodeInfoService
) extends Loggable {
  
  private[this] val xmlPretty = new scala.xml.PrettyPrinter(80, 2)
 // private[this] val gridName = "eventLogsGrid"
 // private[this] val jsGridName = "oTable" + gridName
  
  def display(events:Seq[EventLog], gridName:String) : NodeSeq  = {
    (
      "tbody *" #> ("tr" #> events.map { event => 
        ".eventLine [jsuuid]" #> Text(gridName + "-" + event.id.getOrElse(0).toString) &
        ".eventLine [class]" #> {
          if (event.details != <entry></entry> ) 
            Text("curspoint")
          else
            NodeSeq.Empty
        } &
        ".logId [class]" #> {
          if (event.details != <entry></entry> ) 
            Text("listopen")
          else
            Text("listEmpty")
        } &
        ".logId *" #> event.id.getOrElse(0).toString &
        ".logDatetime *" #> DateFormaterService.getFormatedDate(event.creationDate) &
        ".logActor *" #> event.principal.name &
        ".logType *" #> S.?("rudder.log.eventType.names." + event.eventType.serialize) &
        ".logDescription *" #> displayDescription(event) 
      })
     )(dataTableXml(gridName)) 
  }
  
  
  def initJs(gridName : String) : JsCmd = {
    val jsGridName = "oTable" + gridName
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
            ]
          });moveFilterAndFullPaginateArea('#%s');""".format(gridName,gridName).replaceAll("#table_var#",jsGridName)
        )  &
        JsRaw("""
        /* Formating function for row details */          
          function fnFormatDetails(id) {
            var sOut = '<span id="'+id+'" class="sgridbph"/>';
            return sOut;
          };
          
          $('td', #table_var#.fnGetNodes() ).each( function () {
            $(this).click( function () {
              var nTr = this.parentNode;
              var jTr = jQuery(nTr);
              if (jTr.hasClass('curspoint')) {
                var opened = jTr.prop("open");
                if (opened && opened.match("opened")) {
                  jTr.prop("open", "closed");
                  jQuery(nTr).find("td.listclose").removeClass("listclose").addClass("listopen");
                  #table_var#.fnClose(nTr);
                } else {
                  jTr.prop("open", "opened");
                  jQuery(nTr).find("td.listopen").removeClass("listopen").addClass("listclose");
                  var jsid = jTr.attr("jsuuid");
                  #table_var#.fnOpen( nTr, fnFormatDetails(jsid), 'details' );
                  %s;
                }
              }
            } );
          })
            
          function showParameters(){
            document.getElementById("show_parameters_info").style.display = "block";
          }
            
      """.format(
          SHtml.ajaxCall(JsVar("jsid"), details _)._2.toJsCmd).replaceAll("#table_var#",
             jsGridName)
     )
    )    
  }
  
  /*
   * Expect something like gridName-eventId 
   */
  private def details(jsid:String) : JsCmd = {
    val arr = jsid.split("-")
    if (arr.length != 2) {
      Alert("Called ID is not valid: %s".format(jsid))
    } else {
      val eventId = arr(1).toInt
      repos.getEventLog(eventId) match {
        case Full(event) => 
          SetHtml(jsid,displayDetails(event))
        case e:EmptyBox =>
          logger.debug((e ?~! "error").messageChain)
          Alert("Called id is not valid: %s".format(jsid))
      }
    }
  }
  
  
  private[this] def dataTableXml(gridName:String) = {
    <div>
      <table id={gridName} cellspacing="0">
        <thead>
          <tr class="head">
            <th class="titleId">ID</th>
            <th>Date</th>
            <th>Actor</th>
            <th>Event Type</th>
            <th>Description</th>
          </tr>
        </thead>
    
        <tbody>
          <tr class="eventLine" jsuuid="id">
            <td class="logId">[ID of event]</td>
            <td class="logDatetime">[Date and time of event]</td>
            <td class="logActor">[actor of the event]</td>
            <td class="logType">[type of event]</td>
            <td class="logDescription">[some user readable info]</td>
          </tr>
        </tbody>
      </table>
      
      <div id="logsGrid_paginate_area" class="paginate"></div>
    
    </div>   
     
  }
  
  
    //////////////////// Display description/details of ////////////////////
  
  //convention: "X" means "ignore"
  
  def displayDescription(event:EventLog) = {
    def crDesc(x:EventLog, actionName: NodeSeq) = {
        val id = (x.details \ "rule" \ "id").text
        val name = (x.details \ "rule" \ "displayName").text
        Text("Rule ") ++ {
          if(id.size < 1) Text(name)
          else <a href={ruleLink(RuleId(id))}>{name}</a> ++ actionName
        }
    }
    
    def piDesc(x:EventLog, actionName: NodeSeq) = {
        val id = (x.details \ "directive" \ "id").text
        val name = (x.details \ "directive" \ "displayName").text
        Text("Directive ") ++ {
          if(id.size < 1) Text(name)
          else <a href={directiveLink(DirectiveId(id))}>{name}</a> ++ actionName
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
          if ((id.size < 1)||(actionName==Text(" deleted"))) Text(name)
          else <a href={nodeLink(NodeId(id))}>{name}</a> ++ actionName
        }
    }
    
    def techniqueDesc(x:EventLog, actionName: NodeSeq) = {
        val name = (x.details \ "activeTechnique" \ "techniqueName").text
        Text("Technique %s".format(name)) ++ actionName
    }
    
    event match {
      case x:ActivateRedButton => Text("Stop Rudder agents on all nodes")
      case x:ReleaseRedButton => Text("Start again Rudder agents on all nodes")
      case x:AcceptNodeEventLog => nodeDesc(x, Text(" accepted"))
      case x:RefuseNodeEventLog => nodeDesc(x, Text(" refused"))
      case x:DeleteNodeEventLog => nodeDesc(x, Text(" deleted"))
      case x:LoginEventLog => Text("User '%s' login".format(x.principal.name))
      case x:LogoutEventLog => Text("User '%s' logout".format(x.principal.name))
      case x:BadCredentialsEventLog => Text("User '%s' failed to login: bad credentials".format(x.principal.name))
      case x:AutomaticStartDeployement => Text("Automatically deploy Directive on nodes")
      case x:ManualStartDeployement => Text("Manually deploy Directive on nodes")
      case x:ApplicationStarted => Text("Rudder starts")
      case x:ModifyRule => crDesc(x,Text(" modified"))
      case x:DeleteRule => crDesc(x,Text(" deleted"))
      case x:AddRule    => crDesc(x,Text(" added"))
      case x:ModifyDirective => piDesc(x,Text(" modified"))
      case x:DeleteDirective => piDesc(x,Text(" deleted"))
      case x:AddDirective    => piDesc(x,Text(" added"))
      case x:ModifyNodeGroup => groupDesc(x,Text(" modified"))
      case x:DeleteNodeGroup => groupDesc(x,Text(" deleted"))
      case x:AddNodeGroup    => groupDesc(x,Text(" added"))
      case x:ClearCacheEventLog => Text("Clear caches of all nodes")
      case x:UpdatePolicyServer => Text("Change Policy Server authorized network")
      case x:ReloadTechniqueLibrary => Text("Technique library reloaded")
      case x:ModifyTechnique => techniqueDesc(x, Text(" modified"))
      case x:DeleteTechnique => techniqueDesc(x, Text(" deleted"))
      case x:SuccessfulDeployment => Text("Successful deployment")
      case x:FailedDeployment => Text("Failed deployment")
      case x:ExportGroupsArchive => Text("New groups archive")
      case x:ExportTechniqueLibraryArchive => Text("New Directive library archive")
      case x:ExportRulesArchive => Text("New Rules archives")
      case x:ExportFullArchive => Text("New full archive")
      case x:ImportGroupsArchive => Text("Restoring group archive")
      case x:ImportTechniqueLibraryArchive => Text("Restoring Directive library archive")
      case x:ImportRulesArchive => Text("Restoring Rules archive")
      case x:ImportFullArchive => Text("Restoring full archive")
      case _ => Text("Unknow event type")
      
    }
  }

  def displayDetails(event:EventLog) = {
    
    def xmlParameters(eventId: Option[Int]) = {
      eventId match {
        case None => NodeSeq.Empty
        case Some(id) =>
          <h4 id={"showParameters%s".format(id)}
          class="curspoint showParameters" 
          onclick={"showParameters(%s)".format(id)}>Raw Technical Details</h4>
          <pre id={"showParametersInfo%s".format(id) } 
          style="display:none;width:200px;">{ event.details.map { n => xmlPretty.format(n) + "\n"} }</pre>
      }
    }
    
    val reasonHtml = {
      val r = event.eventDetails.reason.getOrElse("")  
      if(r == "") NodeSeq.Empty 
      else <div style="margin-top:2px;"><b>Reason: </b>{r}</div>
    }
    
    def errorMessage(e:EmptyBox) = {
      logger.debug(e ?~! "Error when parsing details.", e)
      <xml:group>
        <div class="evloglmargin">
          <h4>Details for that node were not in a recognized format. 
            Raw data are displayed next:</h4>
          <pre style="display:none;width:200px;">{ event.details.map { n => xmlPretty.format(n) + "\n"} }</pre>
        </div>
      </xml:group>
    }
                
    (event match {
    
      case add:AddRule =>
        "*" #> (logDetailsService.getRuleAddDetails(add.details) match {
          case Full(addDiff) => 
            <div class="evloglmargin">
              { ruleDetails(crDetailsXML, addDiff.rule)}
              { reasonHtml }
              { xmlParameters(event.id) }
            </div>
          case Failure(m,_,_) => <p>{m}</p>
          case e:EmptyBox => errorMessage(e)
        })
      
      case del:DeleteRule =>
        "*" #> (logDetailsService.getRuleDeleteDetails(del.details) match {
          case Full(delDiff) =>
            <div class="evloglmargin">
              { ruleDetails(crDetailsXML, delDiff.rule) }
              { reasonHtml }
              { xmlParameters(event.id) }
            </div>
          case e:EmptyBox => errorMessage(e)
        })
        
      case mod:ModifyRule =>
        "*" #> (logDetailsService.getRuleModifyDetails(mod.details) match {
          case Full(modDiff) =>            
            <div class="evloglmargin">
              <h4>Rule overview:</h4>
              <ul class="evlogviewpad">
                <li><b>Rule ID:</b> { modDiff.id.value }</li>
                <li><b>Name:</b> { 
                  modDiff.modName.map(diff => diff.newValue).getOrElse(modDiff.name)
               }</li>
              </ul>
              {(
                "#name" #>  mapSimpleDiff(modDiff.modName) &
                "#isEnabled *" #> mapSimpleDiff(modDiff.modIsActivatedStatus) &
                "#isSystem *" #> mapSimpleDiff(modDiff.modIsSystem) &
                "#shortDescription *" #> mapSimpleDiff(modDiff.modShortDescription) &
                "#longDescription *" #> mapSimpleDiff(modDiff.modLongDescription) &
                "#target" #> (
                  modDiff.modTarget.map { diff =>
                    ".diffOldValue *" #> groupTargetDetails(diff.oldValue) &
                    ".diffNewValue *" #> groupTargetDetails(diff.newValue)
                  }
                ) &
                "#policies" #> (
                   modDiff.modDirectiveIds.map { diff =>
                     ".diffOldValue *" #> directiveTargetDetails(diff.oldValue) &
                     ".diffNewValue *" #> directiveTargetDetails(diff.newValue)
                   }
                )
              )(crModDetailsXML)
              }
              { reasonHtml }
              { xmlParameters(event.id) }
            </div>
          case e:EmptyBox => errorMessage(e)
        })

      ///////// Directive /////////
        
      case x:ModifyDirective =>   
        "*" #> (logDetailsService.getDirectiveModifyDetails(x.details) match {
          case Full(modDiff) =>
            <div class="evloglmargin">
              <h4>Directive overview:</h4>
              <ul class="evlogviewpad">
                <li><b>Directive ID:</b> { modDiff.id.value }</li>
                <li><b>Name:</b> {
                  modDiff.modName.map(diff => diff.newValue.toString).getOrElse(modDiff.name)
                }</li>
              </ul>
              {(
                "#name" #> mapSimpleDiff(modDiff.modName, modDiff.id) &
                "#priority *" #> mapSimpleDiff(modDiff.modPriority) &
                "#isEnabled *" #> mapSimpleDiff(modDiff.modIsActivated) &
                "#isSystem *" #> mapSimpleDiff(modDiff.modIsSystem) &
                "#shortDescription *" #> mapSimpleDiff(modDiff.modShortDescription) &
                "#longDescription *" #> mapSimpleDiff(modDiff.modLongDescription) &
                "#ptVersion *" #> mapSimpleDiff(modDiff.modTechniqueVersion) &
                "#parameters" #> (
                  modDiff.modParameters.map { diff =>
                    ".diffOldValue *" #> 
                      <pre style="width:200px;">{xmlPretty.format(SectionVal.toXml(diff.oldValue))}</pre> &
                    ".diffNewValue *" #> 
                      <pre style="width:200px;">{xmlPretty.format(SectionVal.toXml(diff.newValue))}</pre>
                  }
                ) 
              )(piModDetailsXML)}
              { reasonHtml }
              { xmlParameters(event.id) }
            </div>
          case Failure(m, _, _) => <p>{m}</p>
          case e:EmptyBox => errorMessage(e)
        })
        
    
      case x:AddDirective =>   
        "*" #> (logDetailsService.getDirectiveAddDetails(x.details) match {
          case Full((diff,sectionVal)) =>
            <div class="evloglmargin">
              { directiveDetails(piDetailsXML, diff.techniqueName, 
                  diff.directive, sectionVal) }
              { reasonHtml }
              { xmlParameters(event.id) }
            </div>
          case e:EmptyBox => errorMessage(e)
        })
        
    
      case x:DeleteDirective =>   
        "*" #> (logDetailsService.getDirectiveDeleteDetails(x.details) match {
          case Full((diff,sectionVal)) =>
            <div class="evloglmargin">
              { directiveDetails(piDetailsXML, diff.techniqueName, 
                  diff.directive, sectionVal) }
              { reasonHtml }
              { xmlParameters(event.id) }
            </div>
          case e:EmptyBox => errorMessage(e)
        })
        
      ///////// Node Group /////////
        
      case x:ModifyNodeGroup =>   
        "*" #> (logDetailsService.getNodeGroupModifyDetails(x.details) match {
          case Full(modDiff) => 
            <div class="evloglmargin">
              <h4>Group overview:</h4>
              <ul class="evlogviewpad">
                <li><b>Node ID:</b> { modDiff.id.value }</li>
                <li><b>Name:</b> {
                  modDiff.modName.map(diff => diff.newValue.toString).getOrElse(modDiff.name)
                }</li>
              </ul>
              {(
                "#name" #> mapSimpleDiff(modDiff.modName) &
                "#isEnabled *" #> mapSimpleDiff(modDiff.modIsActivated) &
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
                      
                     ".diffOldValue *" #> mapOptionQuery(diff.oldValue) &
                     ".diffNewValue *" #> mapOptionQuery(diff.newValue)
                    }
                ) &
                "#nodes" #> (
                   modDiff.modNodeList.map { diff =>
                     ".diffOldValue *" #> nodeGroupDetails(diff.oldValue) &
                     ".diffNewValue *" #> nodeGroupDetails(diff.newValue)
                   }
                ) 
              )(groupModDetailsXML)}
              { reasonHtml }
              { xmlParameters(event.id) }
            </div>
          case e:EmptyBox => errorMessage(e)
        })
        
    
      case x:AddNodeGroup =>   
        "*" #> (logDetailsService.getNodeGroupAddDetails(x.details) match {
          case Full(diff) =>
            <div class="evloglmargin">
              { groupDetails(groupDetailsXML, diff.group) }
              { reasonHtml }
              { xmlParameters(event.id) }
            </div>
          case e:EmptyBox => errorMessage(e)
        })
        
    
      case x:DeleteNodeGroup =>   
        "*" #> (logDetailsService.getNodeGroupDeleteDetails(x.details) match {
          case Full(diff) =>
            <div class="evloglmargin">
            { groupDetails(groupDetailsXML, diff.group) }
            { reasonHtml }
              { xmlParameters(event.id) }
            </div>
          case e:EmptyBox => errorMessage(e)
        })
        
      ///////// Node Group /////////
        
      case x:AcceptNodeEventLog =>   
        "*" #> (logDetailsService.getAcceptNodeLogDetails(x.details) match {
          case Full(details) =>
            <div class="evloglmargin">
              <h4>Node accepted overview:</h4>
              { nodeDetails(details) }
              { reasonHtml }
              { xmlParameters(event.id) }
            </div>
          case e:EmptyBox => errorMessage(e)
        })
        
      case x:RefuseNodeEventLog =>   
        "*" #> (logDetailsService.getRefuseNodeLogDetails(x.details) match {
          case Full(details) =>
            <div class="evloglmargin">
              <h4>Node refused overview:</h4>
              { nodeDetails(details) }
              { reasonHtml }
              { xmlParameters(event.id) }
            </div>
          case e:EmptyBox => errorMessage(e)
        })
        
      case x:DeleteNodeEventLog =>   
        "*" #> (logDetailsService.getDeleteNodeLogDetails(x.details) match {
          case Full(details) =>
            <div class="evloglmargin">
              <h4>Node deleted overview:</h4>
              { nodeDetails(details) }
              { reasonHtml }
              { xmlParameters(event.id) }
            </div>
          case e:EmptyBox => errorMessage(e)
        })
        
      ////////// deployment //////////
      case x:SuccessfulDeployment => 
        "*" #> (logDetailsService.getDeploymentStatusDetails(x.details) match {
          case Full(SuccessStatus(id,started,ended,_)) =>
            <div class="evloglmargin">
              <h4>Successful deployment:</h4>
              <ul class="evlogviewpad">
                <li><b>ID:</b>&nbsp;{id}</li>
                <li><b>Start time:</b>&nbsp;{DateFormaterService.getFormatedDate(started)}</li>
                <li><b>End Time:</b>&nbsp;{DateFormaterService.getFormatedDate(ended)}</li>
              </ul>
              { reasonHtml }
              { xmlParameters(event.id) }
            </div>
          case Full(_) => errorMessage(Failure("Unconsistant deployment status"))
          case e:EmptyBox => errorMessage(e)
        })
        
      case x:FailedDeployment => 
        "*" #> (logDetailsService.getDeploymentStatusDetails(x.details) match {
          case Full(ErrorStatus(id,started,ended,failure)) =>
            <div class="evloglmargin">
              <h4>Failed deployment:</h4>
              <ul class="evlogviewpad">
                <li><b>ID:</b>&nbsp;{id}</li>
                <li><b>Start time:</b>&nbsp;{DateFormaterService.getFormatedDate(started)}</li>
                <li><b>End Time:</b>&nbsp;{DateFormaterService.getFormatedDate(ended)}</li>
                <li><b>Error stack trace:</b>&nbsp;{failure.messageChain}</li>
              </ul>
             { reasonHtml }
              { xmlParameters(event.id) }
            </div>
          case Full(_) => errorMessage(Failure("Unconsistant deployment status"))
          case e:EmptyBox => errorMessage(e)
        })
        
      case x:AutomaticStartDeployement => 
        "*" #> 
            <div class="evloglmargin">
              { xmlParameters(event.id) }
            </div>
        
      ////////// change authorized networks //////////
      
      case x:UpdatePolicyServer =>
        "*" #> (logDetailsService.getUpdatePolicyServerDetails(x.details) match {
          case Full(details) => 
            
            def networksToXML(nets:Seq[String]) = {
              <ul>{ nets.map { n => <li class="eventLogUpdatePolicy">{n}</li> } }</ul>
            }
            
            <div class="evloglmargin">{
              (
                  ".diffOldValue *" #> networksToXML(details.oldNetworks) &
                  ".diffNewValue *" #> networksToXML(details.newNetworks)
              )(authorizedNetworksXML)
            }
            { reasonHtml }
              { xmlParameters(event.id) }
            </div>
          case e:EmptyBox => errorMessage(e)
        })
      
      // Technique library reloaded
      
      case x:ReloadTechniqueLibrary =>
        "*" #> (logDetailsService.getTechniqueLibraryReloadDetails(x.details) match {
          case Full(details) => 
              <div class="evloglmargin">
                <b>The Technique library was reloaded and following Techniques were updated:</b>
                <ul>{ details.map {technique => 
                  <li class="eventLogUpdatePolicy">{ "%s (version %s)".format(technique.name.value, technique.version.toString)}</li>
                } }</ul>
                { reasonHtml }
              { xmlParameters(event.id) }
              </div>
            
          case e:EmptyBox => errorMessage(e)
        })
        
      // Technique modified
      case x:ModifyTechnique =>
        "*" #> (logDetailsService.getTechniqueModifyDetails(x.details) match {
          case Full(modDiff) =>            
            <div class="evloglmargin">
              <h4>Technique overview:</h4>
              <ul class="evlogviewpad">
                <li><b>Technique ID:</b> { modDiff.id.value }</li>
                <li><b>Name:</b> { modDiff.name }</li>
              </ul>
              {(
                "#isEnabled *" #> mapSimpleDiff(modDiff.modIsEnabled)
              )(liModDetailsXML("isEnabled", "Activation status"))
              }
              { reasonHtml }
              { xmlParameters(event.id) }
            </div>
          case e:EmptyBox => errorMessage(e)
        })
        
      // Technique modified
      case x:DeleteTechnique =>
        "*" #> (logDetailsService.getTechniqueDeleteDetails(x.details) match {
          case Full(techDiff) =>
            <div class="evloglmargin">
              { techniqueDetails(techniqueDetailsXML, techDiff.technique) }
              { reasonHtml }
              { xmlParameters(event.id) }
            </div>
          case e:EmptyBox => errorMessage(e)
        })
        
      // archiving & restoring 
        
      case x:ExportEventLog => 
        "*" #> (logDetailsService.getNewArchiveDetails(x.details, x) match {
          case Full(gitArchiveId) => 
              { displayExportArchiveDetails(gitArchiveId, xmlParameters(event.id)) }
            
          case e:EmptyBox => errorMessage(e)
        })
        
      case x:ImportEventLog => 
        "*" #> (logDetailsService.getRestoreArchiveDetails(x.details, x) match {
          case Full(gitArchiveId) => 
              { displayImportArchiveDetails(gitArchiveId, xmlParameters(event.id)) }
          case e:EmptyBox => errorMessage(e)
        })

      // other case: do not display details at all
      case _ => "*" #> ""
    
    })(event.details)
  }
  
  private[this] def displayExportArchiveDetails(gitArchiveId: GitArchiveId, rawData: NodeSeq) = 
    <div class="evloglmargin">
      <h4>Details of the new archive:</h4>
      <ul class="evlogviewpad">
        <li><b>Git path of the archive: </b>{gitArchiveId.path.value}</li>
        <li><b>Commit ID (hash): </b>{gitArchiveId.commit.value}</li>
        <li><b>Commiter name: </b>{gitArchiveId.commiter.getName}</li>
        <li><b>Commiter email: </b>{gitArchiveId.commiter.getEmailAddress}</li>
      </ul>
      {rawData}
    </div>
        
  private[this] def displayImportArchiveDetails(gitCommitId: GitCommitId, rawData: NodeSeq) = 
    <div class="evloglmargin">
      <h4>Details of the restored archive:</h4>
      <ul class="evlogviewpad">
        <li><b>Commit ID (hash): </b>{gitCommitId.value}</li>
      </ul>
      {rawData}
    </div>

  private[this] def displayDiff(tag:String)(xml:NodeSeq) = 
      "From value '%s' to value '%s'".format(
          ("from ^*" #> "X" & "* *" #> (x => x))(xml) 
        , ("to ^*" #> "X" & "* *" #> (x => x))(xml)
      )
  
  private[this] def directiveIdDetails(directiveIds:Seq[DirectiveId]) = directiveIds.map { id => 
    (<b>ID:</b><a href={directiveLink(id)}>{id.value}</a>):NodeSeq
  }
  
  private[this] def groupNodeSeqLink(id: NodeGroupId): NodeSeq = {
    nodeGroupRepository.getNodeGroup(id) match {
      case t: EmptyBox => 
        <span>group({id.value})</span>
      case Full(nodeGroup) => 
        <span>group(<a href={groupLink(id)}>{nodeGroup.name}</a>)</span>
    }
  }
  
  private[this] def groupTargetDetails(targets: Set[RuleTarget]): NodeSeq = {
    val res = targets.toSeq match {
      case Seq() => NodeSeq.Empty
      case t =>
        targets
        .toSeq
        .map { target =>
          target match {
            case GroupTarget(id@NodeGroupId(g)) => 
              groupNodeSeqLink(id)
            case x => 
              <span>{Text("group_special(" + x.toString + ")")}</span>
          }
        }
        .reduceLeft[NodeSeq]((a,b) => a ++ <span class="groupSeparator" /> ++ b)
    }
    (
      ".groupSeparator" #> ", "
    )(res)
  }
  
  private[this] def nodeNodeSeqLink(id: NodeId): NodeSeq = {
    nodeInfoService.getNodeInfo(id) match {
      case t: EmptyBox => 
        <span>node({id.value})</span>
      case Full(node) => 
        <span>node(<a href={nodeLink(id)}>{node.name}</a>)</span>
    }
  }
  
  private[this] def nodeGroupDetails(nodes: Set[NodeId]): NodeSeq = {
    val res = nodes.toSeq match {
      case Seq() => NodeSeq.Empty
      case t =>
        nodes
        .toSeq
        .map { id => nodeNodeSeqLink(id: NodeId)
        }
        .reduceLeft[NodeSeq]((a,b) => a ++ <span class="groupSeparator" /> ++ b)
    }
    (
      ".groupSeparator" #> ", "
    )(res)
  }
  
  private[this] def directiveTargetDetails(set: Set[DirectiveId]): NodeSeq = {
    if(set.size < 1) 
      Text("None")
    else { 
      set match {
        case Seq() => NodeSeq.Empty
        case _ =>
          val res = {
            set
              .toSeq
              .sortWith( _.value < _.value )
              .map { id =>
                directiveRepository.getDirective(id) match {
                  case t: EmptyBox => 
                    <span>directive({id.value})</span>
                  case Full(directive) => 
                    <span>directive(<a href={directiveLink(id)}>{directive.name}</a>)</span>
                }
              }
              .reduceLeft[NodeSeq]((a,b) => a ++ <span class="groupSeparator" /> ++ b)
          }
          (
            ".groupSeparator" #> ", "
          )(res)
      }
    }
  }
  
  private[this] def ruleDetails(xml:NodeSeq, rule:Rule) = (
      "#ruleID" #> rule.id.value &
      "#ruleName" #> rule.name &
      "#target" #> groupTargetDetails(rule.targets) &
      "#policy" #> (xml =>  
        if(rule.directiveIds.size < 1) Text("None") else {
          (".techniqueId *" #> directiveIdDetails(rule.directiveIds.toSeq))(xml)              
        } ) &
      "#isEnabled" #> rule.isEnabled &
      "#isSystem" #> rule.isSystem &
      "#shortDescription" #> rule.shortDescription &
      "#longDescription" #> rule.longDescription
  )(xml)
  
  private[this] def directiveDetails(xml:NodeSeq, ptName: TechniqueName, directive:Directive, sectionVal:SectionVal) = (
      "#directiveID" #> directive.id.value &
      "#directiveName" #> directive.name &
      "#ptVersion" #> directive.techniqueVersion.toString &
      "#ptName" #> ptName.value &
      "#ptVersion" #> directive.techniqueVersion.toString &
      "#ptName" #> ptName.value &
      "#priority" #> directive.priority &
      "#isEnabled" #> directive.isEnabled &
      "#isSystem" #> directive.isSystem &
      "#shortDescription" #> directive.shortDescription &
      "#longDescription" #> directive.longDescription
  )(xml)
  
  private[this] def groupDetails(xml:NodeSeq, group: NodeGroup) = (
      "#groupID" #> group.id.value &
      "#groupName" #> group.name &
      "#shortDescription" #> group.description &
      "#shortDescription" #> group.description &
      "#query" #> (group.query match {
        case None => Text("None")
        case Some(q) => Text(q.toJSONString)
      } ) &
      "#isDynamic" #> group.isDynamic &
      "#nodes" #>( 
                   {
                     val l = group.serverList.toList
                       l match {
                         case Nil => Text("None")
                         case _ => l
                           .map(id => <a href={nodeLink(id)}>{id.value}</a>)
                           .reduceLeft[NodeSeq]((a,b) => a ++ <span>,&nbsp;</span> ++ b)
                       }
                     }
                  ) &
      "#isEnabled" #> group.isEnabled &
      "#isSystem" #> group.isSystem
  )(xml)
  
  private[this] def techniqueDetails(xml: NodeSeq, technique: ActiveTechnique) = (
      "#techniqueID" #> technique.id.value &
      "#techniqueName" #> technique.techniqueName.value &
      "#isEnabled" #> technique.isEnabled &
      "#isSystem" #> technique.isSystem
  )(xml)
  
 private[this] def mapSimpleDiff[T](opt:Option[SimpleDiff[T]]) = opt.map { diff =>
   ".diffOldValue *" #> diff.oldValue.toString &
   ".diffNewValue *" #> diff.newValue.toString
  }  
  
 private[this] def mapSimpleDiff[T](opt:Option[SimpleDiff[T]], id: DirectiveId) = opt.map { diff =>
   ".diffOldValue *" #> diff.oldValue.toString &
   ".diffNewValue *" #> diff.newValue.toString &
   "#directiveID" #> id.value
  }  
  
  private[this] def nodeDetails(details:InventoryLogDetails) = (
     "#nodeID" #> details.nodeId.value &
     "#nodeName" #> details.hostname &
     "#os" #> details.fullOsName &
     "#version" #> DateFormaterService.getFormatedDate(details.inventoryVersion)
  )( 
    <ul class="evlogviewpad">
      <li><b>Node ID: </b><value id="nodeID"/></li>
      <li><b>name: </b><value id="nodeName"/></li>
      <li><b>Operating System: </b><value id="os"/></li>
      <li><b>Inventory Version: </b><value id="version"/></li>
    </ul>
  )
  
  private[this] def nodeDetails(details:NodeLogDetails) = (
     "#id" #> details.node.id.value &
     "#name" #> details.node.name &
     "#hostname" #> details.node.hostname &
     "#description" #> details.node.description &
     "#os" #> details.node.os &
     "#ips" #> details.node.ips.mkString("\n") &
     "#inventoryDate" #> DateFormaterService.getFormatedDate(details.node.inventoryDate) &
     "#publicKey" #> details.node.publicKey &
     "#agentsName" #> details.node.agentsName.mkString("\n") &
     "#policyServerId" #> details.node.policyServerId.value &
     "#localAdministratorAccountName" #> details.node.localAdministratorAccountName &
     "#isBroken" #> details.node.isBroken &
     "#isSystem" #> details.node.isSystem &
     "#creationDate" #> DateFormaterService.getFormatedDate(details.node.creationDate)
  )(nodeDetailsXML)
  
  private[this] val crDetailsXML = 
    <div>
      <h4>Rule overview:</h4>
      <ul class="evlogviewpad">
        <li><b>ID:&nbsp;</b><value id="ruleID"/></li>
        <li><b>Name:&nbsp;</b><value id="ruleName"/></li>
        <li><b>Description:&nbsp;</b><value id="shortDescription"/></li>
        <li><b>Target:&nbsp;</b><value id="target"/></li>
        <li><b>Directives:&nbsp;</b><value id="policy"/></li>
        <li><b>Enabled:&nbsp;</b><value id="isEnabled"/></li>
        <li><b>System:&nbsp;</b><value id="isSystem"/></li>
        <li><b>Details:&nbsp;</b><value id="longDescription"/></li>
      </ul>
    </div>
      
  private[this] val piDetailsXML = 
    <div>
      <h4>Directive overview:</h4>
      <ul class="evlogviewpad">
        <li><b>ID:&nbsp;</b><value id="directiveID"/></li>
        <li><b>Name:&nbsp;</b><value id="directiveName"/></li>
        <li><b>Description:&nbsp;</b><value id="shortDescription"/></li>
        <li><b>Technique name:&nbsp;</b><value id="ptName"/></li>
        <li><b>Technique version:&nbsp;</b><value id="ptVersion"/></li>
        <li><b>Priority:&nbsp;</b><value id="priority"/></li>
        <li><b>Enabled:&nbsp;</b><value id="isEnabled"/></li>
        <li><b>System:&nbsp;</b><value id="isSystem"/></li>
        <li><b>Details:&nbsp;</b><value id="longDescription"/></li>
      </ul>
    </div>
    
  private[this] val groupDetailsXML = 
    <div>
      <h4>Group overview:</h4>
      <ul class="evlogviewpad">
        <li><b>ID: </b><value id="groupID"/></li>
        <li><b>Name: </b><value id="groupName"/></li>
        <li><b>Description: </b><value id="shortDescription"/></li>
        <li><b>Enabled: </b><value id="isEnabled"/></li>
        <li><b>Dynamic: </b><value id="isDynamic"/></li>
        <li><b>System: </b><value id="isSystem"/></li>
        <li><b>Query: </b><value id="query"/></li>
        <li><b>Node list: </b><value id="nodes"/></li>
      </ul>
    </div>
  
  private[this] val nodeDetailsXML = 
    <div>
      <h4>Node overview:</h4>
      <ul class="evlogviewpad">
        <li><b>Rudder ID: </b><value id="id"/></li>
        <li><b>Name: </b><value id="name"/></li>
        <li><b>Hostname: </b><value id="hostname"/></li>
        <li><b>Description: </b><value id="description"/></li>
        <li><b>Operating System: </b><value id="os"/></li>
        <li><b>IPs addresses: </b><value id="ips"/></li>
        <li><b>Date inventory last received: </b><value id="inventoryDate"/></li>
        <li><b>Agent name: </b><value id="agentsName"/></li>
        <li><b>Administrator account :</b><value id="localAdministratorAccountName"/></li>
        <li><b>Date first accepted in Rudder :</b><value id="creationDate"/></li>
        <li><b>Broken: </b><value id="isBroken"/></li>
        <li><b>System: </b><value id="isSystem"/></li>
      </ul>
    </div>
    
  private[this] val techniqueDetailsXML = 
    <div>
      <h4>Technique overview:</h4>
      <ul class="evlogviewpad">
        <li><b>ID:&nbsp;</b><value id="techniqueID"/></li>
        <li><b>Name:&nbsp;</b><value id="techniqueName"/></li>
        <li><b>Enabled:&nbsp;</b><value id="isEnabled"/></li>
        <li><b>System:&nbsp;</b><value id="isSystem"/></li>
      </ul>
    </div>
      
    
    
  private[this] def liModDetailsXML(id:String, name:String) = (
      <div id={id}>
        <b>{name} changed:</b>
        <ul class="evlogviewpad">
          <li><b>Old value:&nbsp;</b><span class="diffOldValue">old value</span></li>
          <li><b>New value:&nbsp;</b><span class="diffNewValue">new value</span></li>
        </ul>
      </div>
  )
  
  private[this] val groupModDetailsXML = 
    <xml:group>
      {liModDetailsXML("name", "Name")}
      {liModDetailsXML("shortDescription", "Description")}
      {liModDetailsXML("nodes", "Node list")}
      {liModDetailsXML("query", "Query")}
      {liModDetailsXML("isDynamic", "Dynamic group")}
      {liModDetailsXML("isEnabled", "Activation status")}
      {liModDetailsXML("isSystem", "System")}
    </xml:group>      

  private[this] val crModDetailsXML = 
    <xml:group>
      {liModDetailsXML("name", "Name")}
      {liModDetailsXML("shortDescription", "Description")}
      {liModDetailsXML("longDescription", "Details")}
      {liModDetailsXML("target", "Target")}
      {liModDetailsXML("policies", "Directives")}
      {liModDetailsXML("isEnabled", "Activation status")}
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
      {liModDetailsXML("isEnabled", "Activation status")}
      {liModDetailsXML("isSystem", "System")}
    </xml:group>
      
      
  private[this] def authorizedNetworksXML() = (
    <div>
      <b>Networks authorized on policy server were updated:</b>
      <table class="eventLogUpdatePolicy">
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

}
