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

package com.normation.rudder.web.snippet

import com.normation.rudder.domain.policies._
import com.normation.rudder.services.policies._
import com.normation.cfclerk.domain.PolicyPackage
import com.normation.cfclerk.services.PolicyPackageService
import net.liftweb.http.LocalSnippet
import net.liftweb.common._
import Box._
import net.liftweb.http.{SHtml,S}
import scala.xml._
import net.liftweb.http.DispatchSnippet
import net.liftweb.http.js._
import JsCmds._
import com.normation.rudder.web.components.popup.CreateConfigurationRulePopup

// For implicits
import JE._
import net.liftweb.util.Helpers
import net.liftweb.util.Helpers._
import com.normation.rudder.web.components.{
  ConfigurationRuleEditForm,
  ComponentInitializationException,
  ConfigurationRuleGrid
}
import com.normation.rudder.domain.policies.{GroupTarget,ConfigurationRule}
import com.normation.rudder.repository._
import com.normation.utils.StringUuidGenerator

import bootstrap.liftweb.LiftSpringApplicationContext.inject

import com.normation.plugins.{SpringExtendableSnippet,SnippetExtensionKey}

/**
 * Snippet for managing Configuration Rules.
 * It allows to see what Configuration Rules are available,
 * remove or edit them, 
 * and add new ones. 
 */
class ConfigurationRuleManagement extends DispatchSnippet with SpringExtendableSnippet[ConfigurationRuleManagement] with Loggable {
  import ConfigurationRuleManagement._
   
  private[this] val configurationRuleRepository = inject[ConfigurationRuleRepository]
  private[this] val targetInfoService = inject[PolicyInstanceTargetService]
  private[this] val uuidGen = inject[StringUuidGenerator]

  //the popup component
  private[this] val creationPopup = new LocalSnippet[CreateConfigurationRulePopup]


  val extendsAt = SnippetExtensionKey(classOf[ConfigurationRuleManagement].getSimpleName)

  
  override def mainDispatch = Map(
      "head" -> { _:NodeSeq => head }
    , "editConfigurationRule" -> { _:NodeSeq => editConfigurationRule }
    , "viewConfigurationRules" -> { _:NodeSeq => viewConfigurationRules }
  )


  private def setCreationPopup : Unit = {
  		 	creationPopup.set(Full(new CreateConfigurationRulePopup(
            onSuccessCallback = onCreateConfigurationRule )))
   }
  
   /**
    * Create the popup
    */
  def createPopup : NodeSeq = {
  	creationPopup.is match {
  		case Failure(m,_,_) =>  <span class="error">Error: {m}</span>
  		case Empty => <div>The component is not set</div>
  		case Full(popup) => popup.popupContent(NodeSeq.Empty)
  	}
  }

  private[this] val currentConfigurationRuleForm = new LocalSnippet[ConfigurationRuleEditForm] 

  
  def head() : NodeSeq = {
    ConfigurationRuleEditForm.staticInit ++ 
    ConfigurationRuleGrid.staticInit ++
    {<head>
      <script type="text/javascript" src="/javascript/jstree/jquery.jstree.js" id="jstree"></script>
      <script type="text/javascript" src="/javascript/tree.js" id="tree"></script>
      {Script(
        JsRaw("""
$.fn.dataTableExt.oStdClasses.sPageButtonStaticDisabled="paginate_button_disabled";
        function updateTips( t ) {
          tips
            .text( t )
            .addClass( "ui-state-highlight" );
          setTimeout(function() {
            tips.removeClass( "ui-state-highlight", 1500 );
          }, 500 );
        }
        function checkLength( o, n, min, max ) {
          if ( o.val().length > max || o.val().length < min ) {
            o.addClass( "ui-state-error" );
            updateTips( "Length of " + n + " must be between " +
              min + " and " + max + "." );
            return false;
          } else {
            return true;
          }
        }
        """) & 
        OnLoad(JsRaw("""correctButtons();""") & parseJsArg)
      ) }
    </head>
    }
  }

  def viewConfigurationRules() : NodeSeq = {
    val configurationRuleGrid = new ConfigurationRuleGrid(
        "rules_grid_zone",
        configurationRuleRepository.getAll().openOr(Seq()),
        Some(detailsCallbackLink),
        showCheckboxColumn = false
    )

            <div id={htmlId_viewAll}>
              <div id="actions_zone" style="margin:15px;">
                {SHtml.ajaxButton("Add a new rule", () => showPopup(), ("class" -> "newRule")) ++ Script(OnLoad(JsRaw("correctButtons();")))}
              </div> 
             {configurationRuleGrid.configurationRulesGrid() }
           </div>

  }
  
  def editConfigurationRule() : NodeSeq = {
    def errorDiv(f:Failure) = <div id={htmlId_editCrDiv} class="error">Error in the form: {f.messageChain}</div>

    currentConfigurationRuleForm.is match {
      case f:Failure => errorDiv(f)
      case Empty => <div id={htmlId_editCrDiv} class="info">Add a new configuration rule or click on an existing one in the grid to edit its parameters</div>
      case Full(form) => form.dispatch("showForm")(NodeSeq.Empty)
    }
  }

  def onCreateConfigurationRule(cr : ConfigurationRule) : JsCmd = {
    updateEditComponent(cr)

    //update UI
    Replace(htmlId_viewAll,  viewConfigurationRules()) &
    Replace(htmlId_editCrDiv, editConfigurationRule()) 
  }
   
  /**
   * If a query is passed as argument, try to dejoniffy-it, in a best effort
   * way - just don't take of errors. 
   * 
   * We want to look for #{ "crId":"XXXXXXXXXXXX" }
   */
  private[this] def parseJsArg(): JsCmd = {
    def displayDetails(crId:String) = {
      configurationRuleRepository.get(ConfigurationRuleId(crId)) match {
        case Full(cr) => 
          onCreateConfigurationRule(cr)
        case _ => Noop
      }
    }
    
    JsRaw("""
        var crId = null;
        try {
          crId = JSON.parse(window.location.hash.substring(1)).crId ;
        } catch(e) {
          crId = null
        }
        if( crId != null && crId.length > 0) { 
          %s;
        }
    """.format(SHtml.ajaxCall(JsVar("crId"), displayDetails _ )._2.toJsCmd)
    )
  }
  
  private[this] def showPopup() : JsCmd = {
    setCreationPopup
    val popupHtml = createPopup
    SetHtml(CreateConfigurationRulePopup.htmlId_popupContainer, popupHtml) &
    JsRaw( """ $("#%s").modal({
      minHeight:300,
  	  minWidth: 400
     });
    $('#simplemodal-container').css('height', 'auto');
    correctButtons();
     """.format(CreateConfigurationRulePopup.htmlId_popup))

  }

  /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  private[this] def updateEditComponent(cr:ConfigurationRule) : Unit = {
    val form = new ConfigurationRuleEditForm(
        htmlId_editCrDiv+"Form",
        cr,
        onSuccessCallback = { () => 
          //update UI
          Replace(htmlId_viewAll,  viewConfigurationRules ) 
        }
    )
    currentConfigurationRuleForm.set(Full(form))    
  }
  
  private[this] def detailsCallbackLink(cr:ConfigurationRule) : JsCmd = {
    updateEditComponent(cr)
    //update UI
    Replace(htmlId_editCrDiv, editConfigurationRule()) &
    JsRaw("""this.window.location.hash = "#" + JSON.stringify({'crId':'%s'})""".format(cr.id.value)) 
  }
  
}


object ConfigurationRuleManagement {
  val htmlId_editCrDiv = "editCrZone"
  val htmlId_viewAll = "viewAllCrZone"
  val htmlId_addPopup = "add-cr-popup"
  val htmlId_addCrButton = "add-cr-button"
}

