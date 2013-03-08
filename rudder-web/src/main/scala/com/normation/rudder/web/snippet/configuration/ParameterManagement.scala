/*
*************************************************************************************
* Copyright 2013 Normation SAS
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
package com.normation.rudder.web.snippet.configuration

import net.liftweb.common._
import net.liftweb.http.DispatchSnippet
import net.liftweb.util._
import net.liftweb.util.Helpers._
import scala.xml._
import net.liftweb.http._
import net.liftweb.http.SHtml._
import net.liftweb.http.js._
import net.liftweb.http.js.JsCmds._
import bootstrap.liftweb.RudderConfig
import com.normation.rudder.domain.parameters.GlobalParameter
import com.normation.rudder.domain.parameters.ParameterName
import com.normation.eventlog.ModificationId
import org.joda.time.DateTime
import com.normation.rudder.web.model.CurrentUser
import com.normation.rudder.web.model.{
  WBTextField, FormTracker, WBTextAreaField, WBRadioField
}
import java.util.regex.Pattern
import net.liftweb.http.js.JE.JsRaw
import com.normation.rudder.web.components.popup.CreateOrUpdateGlobalParameterPopup
import net.liftweb.http.js.JE.JsVar

class ParameterManagement extends DispatchSnippet with Loggable {

  private[this] val roParameterService = RudderConfig.roParameterService

  private[this] val gridName      = "globalParametersGrid"
  private[this] val gridContainer = "ParamGrid"
  private[this] val htmlId_form   = "globalParameterForm"

  // For the deletion popup
  private[this] val woParameterService = RudderConfig.woParameterService
  private[this] val uuidGen            = RudderConfig.stringUuidGenerator
  private[this] val userPropertyService= RudderConfig.userPropertyService


  //the current GlobalParameterForm component
  private[this] val parameterPopup = new LocalSnippet[CreateOrUpdateGlobalParameterPopup]

  def dispatch = {
    case "display" => { _ =>  display }
  }

  def display() : NodeSeq = {
    roParameterService.getAllGlobalParameters match {
      case Full(seq) => displayGridParameters(seq, gridName)
      case Empty     => displayGridParameters(Seq(), gridName)
      case f:Failure =>
        <div class="error">Error when trying to get global Parameters</div>
    }
  }

  def displayGridParameters(params:Seq[GlobalParameter], gridName:String) : NodeSeq  = {
    (
      "tbody *" #> ("tr" #> params.map { param =>
        val lineHtmlId = Helpers.nextFuncName
        ".parameterLine [jsuuid]" #> lineHtmlId &
        ".parameterLine [class]" #> Text("curspoint") &
        ".name *" #> <b>{param.name.value}</b> &
        ".value *" #> param.value &
        ".description *" #> <span><ul class="evlogviewpad"><li><b>Description:</b>{Text(param.description)}</li></ul></span> &
        ".description [id]" #> ("description-" + lineHtmlId) &
        ".overridable *" #> param.overridable &
        ".change *" #> <div >{
                       ajaxButton("Edit", () => showPopup(Some(param)), ("class", "mediumButton"), ("align", "left")) ++
                       ajaxButton("Delete", () => showRemovePopupForm(param), ("class", "mediumButton"), ("style", "margin-left:5px;"))
                       }</div>
      }) &
      ".createParameter *" #> ajaxButton("Add Parameter", () => showPopup(None))
     ).apply(dataTableXml(gridName)) ++ Script(initJs)
  }



  private[this] def dataTableXml(gridName:String) = {
    <div id={gridContainer}>
      <div id="actions_zone">
        <div class="createParameter"/>
      </div>
      <table id={gridName} class="display" cellspacing="0">
        <thead>
          <tr class="head">
            <th>Name</th>
            <th>Value</th>
            <th>Change</th>
          </tr>
        </thead>

        <tbody>
          <tr class="parameterLine">
            <td class="name listopen">[name of parameter]</td>
            <td class="value">[value of parameter]</td>
            <div class="description" style="display:none;" >[description]</div>
            <td class="change">[change / delete]</td>
          </tr>
        </tbody>
      </table>

      <div id="parametersGrid_paginate_area" class="paginate"></div>

    </div>

  }

  private[this] def jsVarNameForId(tableId:String) = "oTable" + tableId

  private[this] def initJs() : JsCmd = {
    OnLoad(
        JsRaw("""
          /* Event handler function */
          #table_var# = $('#%s').dataTable({
            "asStripeClasses": [ 'color1', 'color2' ],
            "bAutoWidth"   : false,
            "bFilter"      : true,
            "bPaginate"    : true,
            "bLengthChange": true,
            "sPaginationType": "full_numbers",
            "oLanguage": {
              "sZeroRecords": "No parameters!",
              "sSearch": ""
            },
            "bJQueryUI"    : true,
            "aaSorting"    : [[ 0, "asc" ]],
            "aoColumns": [
              { "sWidth": "300px" },
              { "sWidth": "600px" },
              { "sWidth": "140px" }
            ],
            "sDom": '<"dataTables_wrapper_top"fl>rt<"dataTables_wrapper_bottom"ip>'
          });
          $('.dataTables_filter input').attr("placeholder", "Search");
          """.format(gridName).replaceAll("#table_var#",jsVarNameForId(gridName))
        ) &
        JsRaw("""
        /* Formating function for row details */
          function fnFormatDetails(id) {
            var sOut = '<span id="'+id+'" class="parametersDescriptionDetails"/>';
            return sOut;
          };

          $(#table_var#.fnGetNodes() ).each( function () {
            $(this).click( function (event) {
              var jTr = $(this);
              var opened = jTr.prop("open");
              var source = event.target || event.srcElement;
              if (!( $(source).is("button"))) {
                if (opened && opened.match("opened")) {
                  jTr.prop("open", "closed");
                  $(this).find("td.listclose").removeClass("listclose").addClass("listopen");
                  #table_var#.fnClose(this);
                } else {
                  jTr.prop("open", "opened");
                  $(this).find("td.listopen").removeClass("listopen").addClass("listclose");
                  var jsid = jTr.attr("jsuuid");
                  var color = 'color1';
                  if(jTr.hasClass('color2'))
                    color = 'color2';
                  var row = $(#table_var#.fnOpen(this, fnFormatDetails(jsid), 'details'));
                  $(row).addClass(color + ' parametersDescription');
                  $('#'+jsid).html($('#description-'+jsid).html());
                }
               }
            } );
          })


      """.replaceAll("#table_var#",jsVarNameForId(gridName))
      )
    )
  }

  private[this] def showPopup(parameter : Option[GlobalParameter]) : JsCmd = {
    setCreationPopup(parameter)
    val popupHtml = createPopup
    SetHtml(CreateOrUpdateGlobalParameterPopup.htmlId_popupContainer, popupHtml) &
    JsRaw( """ createPopup("%s",300,600) """.format(CreateOrUpdateGlobalParameterPopup.htmlId_popup))

  }

  private[this] def setCreationPopup(parameter : Option[GlobalParameter]) : Unit = {
    parameterPopup.set(
        Full(
            new CreateOrUpdateGlobalParameterPopup(
                parameter
              , (String) => updateGrid() & successPopup
            )
        )
      )
  }

  /**
    * Create the creation popup
    */
  def createPopup : NodeSeq = {
    parameterPopup.is match {
      case Failure(m,_,_) =>  <span class="error">Error: {m}</span>
      case Empty => <div>The component is not set</div>
      case Full(popup) => popup.popupContent()
    }
  }



  private[this] def updateGrid() : JsCmd = {
    Replace(gridContainer, display()) & OnLoad(JsRaw("""correctButtons();"""))
  }

  ///////////// success pop-up ///////////////
  private[this] def successPopup : JsCmd = {
    JsRaw(""" callPopupWithTimeout(200, "successConfirmationDialog", 100, 350)
    """)
  }

  private[this] def onSuccessDeleteCallback() : JsCmd = {
    updateGrid() & successPopup
  }

  private[this] val reason = {
    import com.normation.rudder.web.services.ReasonBehavior._
    userPropertyService.reasonsFieldBehavior match {
      case Disabled => None
      case Mandatory => Some(buildReasonField(true, "subContainerReasonField"))
      case Optionnal => Some(buildReasonField(false, "subContainerReasonField"))
    }
  }

  def buildReasonField(mandatory:Boolean, containerClass:String = "twoCol") = {
    new WBTextAreaField("Message", "") {
      override def setFilter = notNull _ :: trim _ :: Nil
      override def inputField = super.inputField  %
        ("style" -> "height:5em;")  % ("tabindex","4")
      override def errorClassName = ""
      override def validations() = {
        if(mandatory){
          valMinLen(5, "The reason must have at least 5 characters.") _ :: Nil
        } else {
          Nil
        }
      }
    }
  }
  private[this] val formTrackerRemovePopup = new FormTracker(reason.toList)

  private[this] var notifications = List.empty[NodeSeq]

  private[this] def updateAndDisplayNotifications(formTracker : FormTracker) : NodeSeq = {
    val notifications = formTrackerRemovePopup.formErrors
    formTrackerRemovePopup.cleanErrors

    if(notifications.isEmpty) {
      NodeSeq.Empty
    }
    else {
      <div id="notifications" class="notify">
        <ul class="field_errors">{notifications.map( n => <li>{n}</li>) }</ul>
      </div>
    }
  }

  private[this] def updateRemoveFormClientSide(parameter : GlobalParameter) : JsCmd = {
    SetHtml("deletionPopupContainer", deletePopupContent(parameter)) & JsRaw("correctButtons();")
  }

  private[this] def onFailureRemovePopup(parameter : GlobalParameter) : JsCmd = {
    formTrackerRemovePopup.addFormError(error("The form contains some errors, please correct them."))
    updateRemoveFormClientSide(parameter)
  }

  private[this] def error(msg:String) = <span class="error">{msg}</span>

  private[this] def removeButton(parameter : GlobalParameter): Elem = {
    def removeParameters(): JsCmd = {
      if(formTrackerRemovePopup.hasErrors) {
        onFailureRemovePopup(parameter)
      } else {
        val modId = ModificationId(uuidGen.newUuid)
        woParameterService.delete(parameter.name, modId,  CurrentUser.getActor, reason.map(_.is)) match {
          case Full(x) => closePopup() & onSuccessDeleteCallback()
          case Empty =>
            logger.error("An error occurred while deleting the parameter")
            formTrackerRemovePopup.addFormError(error("An error occurred while deleting the parameter"))
            onFailureRemovePopup(parameter)
          case Failure(m,_,_) =>
              logger.error("An error occurred while deleting the parameter: " + m)
              formTrackerRemovePopup.addFormError(error(m))
              onFailureRemovePopup(parameter)
        }
      }
    }

    SHtml.ajaxSubmit("Delete", removeParameters _)
    }

  /**
   * Display a popup to confirm  deletion of parameter
   */
  private[this] def showRemovePopupForm(parameter : GlobalParameter) : JsCmd = {
    // we need to empty the reason
    reason.map(x => x.set(""))
    SetHtml("deletionPopup", deletePopupContent(parameter)) &
    JsRaw( """ createPopup("deletionPopup",140,150) """)
  }
  private[this] def closePopup() : JsCmd = {
    JsRaw(""" $.modal.close();""")
  }

  private[this] def deletePopupContent(parameter : GlobalParameter) : NodeSeq = {
    (
       "#deletionPopupContainer *" #> { (n:NodeSeq) => SHtml.ajaxForm(n) } andThen
       "#areYouSure *" #> s"Are you sure that you want to delete the Global Parameter: ${parameter.name.value} ?" &
       "#dialogRemoveButton" #> { removeButton(parameter) % ("id", "removeButton") } &
       ".reasonsFieldsetPopup" #> { reason.map { f =>
         "#explanationMessage" #> <div>{userPropertyService.reasonsFieldExplanation}</div> &
         "#reasonsField" #> f.toForm_!
       } } andThen
        ".notifications *" #> { updateAndDisplayNotifications(formTrackerRemovePopup) }
    )(deletePopupXml)
  }


  private[this] def deletePopupXml : NodeSeq = {
    <div id="deletionPopupContainer">
     <div class="simplemodal-title">
       <h1 id="title">Delete Global Parameter</h1>
       <hr/>
     </div>
     <div class="simplemodal-content">
       <div>
         <img src="/images/icWarn.png" alt="Warning!" height="32" width="32" class="warnicon"/>
         <h2 id="areYouSure">Are you sure that you want to delete this item?</h2>
       </div>
       <div class="notifications">Here comes validation messages</div>
       <br />
        <div class="reasonsFieldsetPopup">
        <div id="explanationMessage">
          Here comes the explanation to reasons field
        </div>
        <div id="reasonsField">
          Here comes the reasons field
        </div>
      </div>
       <br />
       <hr class="spacer" />
     </div>
     <div class="simplemodal-bottom">
       <hr/>
       <div class="popupButton">
         <span>
           <button class="simplemodal-close" onClick="$.modal.close();">Cancel</button>
           <button id="dialogRemoveButton">Delete</button>
         </span>
       </div>
     </div>
   </div>
  }
}