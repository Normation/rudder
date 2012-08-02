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

import com.normation.rudder.domain.queries._
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.services.nodes.NodeInfoService
import scala.collection.mutable.Buffer
import com.normation.rudder.domain.queries.{
  CriterionComposition,
  Or,And,
  CriterionLine,
  Query
}
import com.normation.rudder.services.queries.QueryProcessor
import net.liftweb.http.Templates
import net.liftweb.http.js._
import JsCmds._
import JE._
import net.liftweb.common._
import net.liftweb.http.{SHtml,S,DispatchSnippet}
import scala.xml._
import net.liftweb.util.Helpers
import net.liftweb.util.Helpers._
import com.normation.exceptions.TechnicalException
import com.normation.rudder.web.services.SrvGrid
import scala.collection.mutable.ArrayBuffer
import bootstrap.liftweb.LiftSpringApplicationContext.inject
import com.normation.inventory.ldap.core.LDAPConstants
import LDAPConstants._
import com.normation.rudder.domain.queries.OstypeComparator

/**
 * The Search Nodes component
 * It is used in the standard search server page, and in the group page (and probably elsewhere)
 * 
 * query and srvList are both var, because they will be manipulated by the component
 * we would have wanted to get back their value, but it seems it cannot be done
 *
 */
class SearchNodeComponent(
    htmlId : String, // unused ...
    _query : Option[Query],
    _srvList : Box[Seq[NodeInfo]],
    onUpdateCallback : () => JsCmd = { () => Noop }, // this one is not used yet
    onClickCallback :  (String) => JsCmd = { (x:String) => Noop }, // this callback is used when we click on an element in the grid
    onSearchCallback :  (Boolean) => JsCmd = { (x:Boolean) => Noop }, // this callback is used when a research is done and the state of the Search button changes
    saveButtonId : String = "" // the id of the save button, that gets disabled when one change the form
)extends DispatchSnippet {
  import SearchNodeComponent._
  
  //our local copy of things we work on
  private[this] var query = _query.map(x => x.copy())
  private[this] var srvList = _srvList.map(x => Seq() ++ x)
  
  
  private[this] val nodeInfoService = inject[NodeInfoService]
  private[this] val queryProcessor = inject[QueryProcessor]("queryProcessor")

  
  // The portlet for the server detail
  private[this] def serverPortletPath = List("templates-hidden", "server", "server_details")
  private[this] def serverPortletTemplateFile() =  Templates(serverPortletPath) match {
    case Empty | Failure(_,_,_) => 
      throw new TechnicalException("Template for server details not found. I was looking for %s.html".format(serverPortletPath.mkString("/")))
    case Full(n) => n
  }
  
  private[this] def searchNodes = chooseTemplate("query","SearchNodes",serverPortletTemplateFile)
  private[this] def content = chooseTemplate("content","query",searchNodes)
  private[this] def queryUpdate = chooseTemplate("update","query",content)

  
  
  /**
   * External exposition of the current state of server list.
   * Page/component which includes SearchNodeComponent can use it.
   * @return
   */
  def getSrvList() : Box[Seq[NodeInfo]] = srvList
  
  
  /**
   * External exposition of the current state of query.
   * Page/component which includes SearchNodeComponent can use it.
   * @return
   */
  def getQuery() : Option[Query] = query

  var dispatch : DispatchIt = {
    case "showQuery" => { _ => buildQuery }
    case "head" => { _ => head() }
  }
  
  var activateSubmitButton = true
  var initUpdate = true // this is true when we arrive on the page, or when we've done an search

  var errors = Buffer[Box[String]]()
  
  def head() : NodeSeq = {
    <head>
      {
        srvGrid.head
      }
    </head>
  }
  
  
  def buildQuery() : NodeSeq = {
    if(None == query) query = Some(Query(NodeReturnType,And,Seq(defaultLine))) 
    val lines = ArrayBuffer[CriterionLine]()
    var composition = query.get.composition 
    var rType = query.get.returnType //for now, don't move

   
    def addLine(i:Int) : JsCmd = {
      lines.insert(i+1, CriterionLine(ditQueryData.criteriaMap(OC_NODE),ditQueryData.criteriaMap(OC_NODE).criteria(0),ditQueryData.criteriaMap(OC_NODE).criteria(0).cType.comparators(0)))
      query = Some(Query(rType, composition, lines.toSeq))
      activateSubmitButton = true
      initUpdate = false
      ajaxCriteriaRefresh
    }
    
    def removeLine(i:Int) : JsCmd ={
      if(lines.size > i) {
        lines.remove(i)
        query = Some(Query(rType, composition, lines.toSeq))
      }
      activateSubmitButton = true
      initUpdate = false
      ajaxCriteriaRefresh
    }
    
    def processForm() : JsCmd = {
      //filter on non validate values
      errors.clear()
      lines.zipWithIndex.foreach { case (CriterionLine(ot,a,c,v),i) =>
        if(errors.size < i+1) errors.append(Empty)
        a.cType.validate(v,c.id) match {
          case Failure(m,_,_) => errors(i) = Full(m)
          case _ => errors(i) = Empty
        }
      }
      val newQuery = Query(rType, composition, lines.toSeq)
      query = Some(newQuery)
      if(errors.filter(_.isDefined).size == 0) {
        // ********* EXECUTE QUERY ***********
        srvList = queryProcessor.process(newQuery)
        activateSubmitButton = false
        initUpdate = true
      } else {
        // ********* ERRORS FOUND ***********"
        srvList = Empty
        activateSubmitButton = true
      }
      
      ajaxGridRefresh
    }
    
    /**
     * Refresh the query parameter part
     */
    def ajaxCriteriaRefresh : JsCmd = {
          Replace("queryParameters", displayQuery(queryUpdate))& activateButtonOnChange & JsRaw("correctButtons();")
    }
    
    /**
     * Display the query part 
     * Caution, we pass an html different at the init part (whole content:query) or at update (update:query)
     * 
     */
    def displayQuery(html: NodeSeq ) : NodeSeq = {
      val Query(otName,comp, criteria) = query.get
      SHtml.ajaxForm(bind("query", html,
          "typeQuery" ->  <label>Include policy servers: {SHtml.checkbox(rType==NodeAndPolicyServerReturnType, { value:Boolean =>
                if (value) 
                  rType = NodeAndPolicyServerReturnType 
                else 
                  rType = NodeReturnType}
              )}</label>,
          "composition" -> SHtml.radio(Seq("And", "Or"), Full(if(comp == Or) "Or" else "And"), {value:String => 
          composition = CriterionComposition.parse(value).getOrElse(And) //default to AND on unknow composition string
        }, ("class", "radio")).flatMap(e => <label>{e.xhtml} <span class="radioTextLabel">{e.key.toString}</span></label>),
        "lines" -> {(ns: NodeSeq) => 
          /*
           * General remark :
         * - bind parameter of closure to lines (so that they actually get the current value of the line when evaluated)
         * - bind parameter out of closure to ot/a/c/v so that they have the current value (and not a past one)
         */
      
        {
          criteria.zipWithIndex.flatMap { case (CriterionLine(ot,a,c,v),i) => 
          
          for(j <- lines.size to i) {
            lines.append(defaultLine)
          }
          for(j <- errors.size to i) {
            errors.append(Empty)
          }
          bind("line",ns,
            "removeLine" -> {if(criteria.size <= 1) NodeSeq.Empty else SHtml.ajaxSubmit("-", () => removeLine(i), ("class", "removeLineButton"))},
            "addline" -> SHtml.ajaxSubmit("+", () => addLine(i), ("class", "removeLineButton")),
            "objectType" -> objectTypeSelect(ot,lines,i),
            "attributeName" -> attributeNameSelect(ot,a,lines,i),
            "comparator" -> comparatorSelect(ot,a,c,lines,i),
            "inputValue" -> {
              var form = a.cType.toForm(v, (x => lines(i) = lines(i).copy(value=x)), ("id","v_"+i), ("class", "queryInputValue"))
              if(!c.hasValue) form = form % Attribute("disabled",Seq(Text("disabled")),Null)
              form ++ {
                if (activateSubmitButton)
                  SHtml.ajaxSubmit("Search", processForm,("style" -> "display:none"))
                else
                  SHtml.ajaxSubmit("Search", () => Focus("v_"+i), ("style" -> "display:none"))
              }
            } ,
            "error" -> { errors(i) match { 
              case Full(m) => <tr><td class="error" colspan="6">{m}</td></tr>
              case _ => NodeSeq.Empty 
            }}
          )
        }:NodeSeq} ++ { if(criteria.size > 0) { 
          //add a <script> tag to init all specific Js form renderer, like Jquery datepicker for date
          var initJs = criteria(0).attribute.cType.initForm("v_0")
          for(i <- 1 until criteria.size) { initJs = initJs & criteria(i).attribute.cType.initForm("v_"+i) }
          Script(OnLoad(initJs))
        } else NodeSeq.Empty}
      },
      "submit" -> {
        if (activateSubmitButton)
          SHtml.ajaxSubmit("Search", processForm, ("id" -> "SubmitSearch"), ("class" -> "submitButton"))
        else
          SHtml.ajaxSubmit("Search", processForm, ("disabled" -> "true"), ("id" -> "SubmitSearch"), ("class" -> "submitButton"))
        }
      ))
    }
    
    /**
     * Show the search engine and the grid
     */
    def showQueryAndGridContent() : NodeSeq = {
      bind("content",searchNodes, 
        "query" -> {x:NodeSeq => displayQuery(x)},
        "gridResult" -> srvGrid.display(Seq(), "serverGrid", Seq(), "") // we need to set something, or IE moans
      )
    }
    showQueryAndGridContent()  ++ Script(OnLoad(ajaxGridRefresh))
  }
  
  /**
   * Refresh the grid result
   * A small trick of the trade : since the Showing x of xx is moved out of the ajax refresh
   * zone, it must be removed before being added again, or problems will rises 
   * @return
   */
  def ajaxGridRefresh() : JsCmd = {
      val grid = gridResult
      JE.JsRaw("""$("#serverGrid_info").remove();""") &
      JE.JsRaw("""$("#serverGrid_length").remove();""") &
      SetHtml("gridResult", grid._1) & grid._2 & activateButtonOnChange  
      
  }
  
  
  
  /**
   * When we change the form, we can update the query
   * @return
   */
  def activateButtonOnChange() : JsCmd = {
    onSearchCallback(activateSubmitButton & !initUpdate) &
    JE.JsRaw("""activateButtonDeactivateGridOnFormChange("queryParameters", "SubmitSearch",  "serverGrid", "%s", "%s");  """.format(activateSubmitButton, saveButtonId))
  }
  
  /**
   * From the computed result, return the NodeSeq corresponding to the grid, plus the initialisation JS
   */
  def gridResult : (NodeSeq, JsCmd) = {
    // Ideally this would just check the size first ?
    srvList match {
      case Full(seq) => 
        (srvGrid.display(seq, "serverGrid", Seq(), ""),
        srvGrid.initJs("serverGrid", Seq(), "", false, true, onClickCallback))
      
      case Empty => 
        (srvGrid.display(Seq(), "serverGrid", Seq(), ""),
        srvGrid.initJs("serverGrid", Seq(), "", false, true, onClickCallback))
        
      case f@Failure(_,_,_) => (<div><h4>Error</h4>{f.messageChain}</div>, Noop)     
    }
  }  
  
}


/*
 * Structure of the Query:
 * var query = { 
 *   'objectType' : 'server' ,  //what we are looking for at the end (servers, software...)
 *   'composition' : 'and' ,  // or 'or'
 *   'criteria': [ 
 *     { 'objectType' : '....' , 'attribute': '....' , 'comparator': '.....' , 'value': '....' } ,  //value is optionnal, other are mandatory
 *     { 'objectType' : '....' , 'attribute': '....' , 'comparator': '.....' , 'value': '....' } ,
 *     ...
 *     { 'objectType' : '....' , 'attribute': '....' , 'comparator': '.....' , 'value': '....' } 
 *   ]
 * }
 */

//some global definition for our structure
object SearchNodeComponent {
  lazy val ditQueryData = inject[DitQueryData]("ditQueryData")
  lazy val srvGrid =  inject[SrvGrid]

  ////////
  //////// All the tools to handle the Ajax logic for 
  //////// dependent Select Box:
  //////// - passing string param between client and server (TODO : should be json)
  //////// - buildind the required JS to update the select box to its new value
  ////////
  
  def parseAttrParam(s:String) : Option[(String,String,String,String,String,String,String)] = {
    //expected "newObjectTypeValue,attributeSelectEltId,oldAttrValue,comparatorSelectEltId,oldCompValue,valueSelectEltId,oldValue
    val reg = """([^,]*),([^,]*),([^,]*),([^,]*),([^,]*),([^,]*),([^,]*)""".r
    s match {
      case reg(a,b,c,d,e,f,g) => Some((a,b,c,d,e,f,g))
      case _ => None
    }
  }
  def parseCompParam(s:String) : Option[(String,String,String,String,String,String)] = {
    //expect "objectTypeValue,newAttrValue,comparatorSelectEltId,oldCompValue,valueSelectEltId,oldValue
    val reg = """([^,]*),([^,]*),([^,]*),([^,]*),([^,]*),([^,]*)""".r
    s match {
      case reg(a,b,c,d,e,f) => Some((a,b,c,d,e,f))
      case _ => None
    }
  }
  def parseValParam(s:String) : Option[(String,String)] = {
    //expect "newCompValue,valueSelectEltId"
    val reg = """([^,]*),([^,]*)""".r
    s match {
      case reg(a,b) => Some((a,b))
      case _ => None
    }
  }

  def setIsEnableFor(comparator:String,valueEltId:String) : JsCmd = {
    val e = OrderedComparators.comparatorForString(comparator) match {
      case None => true
      case Some(comp) => comp.hasValue
    }
    if(e) SetExp(ElemById(valueEltId,"disabled"), JsFalse)
    else SetExp(ElemById(valueEltId,"disabled"), JsTrue)
  }
  
  def updateCompAndValue(func: String => Any,ot:String,a:String,c_eltid:String,c_oldVal:String,v_eltid:String,v_old:String) : JsCmd = {
    //change input display
    val comp = ditQueryData.criteriaMap.get(ot) match {
      case None => StringComparator
      case Some(o) => o.criterionForName(a) match {
        case None => StringComparator
        case Some(comp) => comp.cType
      }
    }        
    val comparators = optionComparatorsFor(ot,a)
    val compNames = comparators.map(_._1)
    val selectedComp = compNames match {
      case a::_ => compNames.filter(_==c_oldVal) match {
        case x::_ => x 
        case Nil => a
      }
      case Nil => "" 
    }
    JsRaw("jQuery('#%s').replaceWith('%s')".format(v_eltid,comp.toForm(v_old,func,("id"->v_eltid), ("class" -> "queryInputValue")))) & comp.initForm(v_eltid) &
    JsCmds.ReplaceOptions(c_eltid,comparators,Full(selectedComp)) &
    setIsEnableFor(selectedComp,v_eltid)
  }
  
  def replaceAttributes(func: String => Any)(ajaxParam:String):JsCmd = {
    parseAttrParam(ajaxParam) match {
      case None => Alert("Can't parse for attribute: " + ajaxParam)
      case Some((ot,a_eltid,a_oldVal,c_eltid,c_oldVal,v_eltid,v_old)) => 
        //change attribute list
        val attributes = optionAttributesFor(ot)
        val attrNames = attributes.map(_._1)
        val selectedAttr = attrNames match {
          case a::_ => attrNames.filter(_==a_oldVal) match {
            case x::_ => x 
            case Nil => a
          }
          case Nil => "" 
        }
        
        JsCmds.ReplaceOptions(a_eltid,attributes,Full(selectedAttr)) &
        updateCompAndValue(func,ot,selectedAttr,c_eltid,c_oldVal,v_eltid,v_old)
    }
  }
  
  def replaceComp(func: String => Any)(ajaxParam:String):JsCmd = { 
    parseCompParam(ajaxParam) match {
      case None => Alert("Can't parse for comparator: " + ajaxParam)
      case Some((ot,a,c_eltid,c_oldVal,v_eltid,v_old)) => 
        updateCompAndValue(func,ot,a,c_eltid,c_oldVal,v_eltid,v_old)
    }
  }
  
  def replaceValue(ajaxParam:String):JsCmd = {  //elementId:String, comp:String, oldValue:String) : JsCmd = {
    parseValParam(ajaxParam) match {
      case None => Alert("Can't parse for value: " + ajaxParam)
      case Some((c_val,v_eltid)) => setIsEnableFor(c_val,v_eltid)
    }
  }
  

  //expected "newObjectTypeValue,attributeSelectEltId,oldAttrValue,comparatorSelectEltId,oldCompValue,valueSelectEltId,oldValue
  def ajaxAttr(lines: Buffer[CriterionLine], i:Int) = { SHtml.ajaxCall( //we we change the attribute, we want to reset the value, see issue #1199
      JE.JsRaw("this.value+',at_%s,'+%s+',ct_%s,'+ %s +',v_%s,'+%s".format(i, ValById("at_"+i).toJsCmd,i,ValById("ct_"+i).toJsCmd,i,Str("").toJsCmd)), 
      s => After(200, replaceAttributes(x => lines(i) = lines(i).copy(value=x))(s))) }
  //expect "objectTypeValue,newAttrValue,comparatorSelectEltId,oldCompValue,valueSelectEltId
  def ajaxComp(lines: Buffer[CriterionLine], i:Int)= { SHtml.ajaxCall( //we we change the attribute, we want to reset the value, see issue #1199
      JE.JsRaw("%s+','+this.value+',ct_%s,'+ %s +',v_%s,'+%s".format(ValById("ot_"+i).toJsCmd, i, ValById("ct_"+i).toJsCmd,i,Str("").toJsCmd)), 
      s => After(200, replaceComp(x => lines(i) = lines(i).copy(value=x))(s))) }
  //expect "newCompValue,valueSelectEltId"
  def ajaxVal(lines: Buffer[CriterionLine], i:Int) = { SHtml.ajaxCall(
      JE.JsRaw("this.value+',v_%s'".format(i)), 
      s => After(200, replaceValue(s))) }
  
  ////////
  //////// Build require select box for a line
  ////////
  
  //how to present the first select box
  val otOptions : List[(String,String)] = {
    val opts = Buffer[(String,String)]()
    def add(s:String, pre:String="") = opts += ((s,pre + S.?("ldap.object."+s)))
    
    add(OC_NODE)
    add(OC_NET_IF, " ├─ ")
    add(OC_FS,     " ├─ ")
    add(A_PROCESS, " ├─ ")
    add(OC_VM_INFO," ├─ ")
    add(A_EV,      " └─ ")
    add(OC_MACHINE)
    add(OC_BIOS,       " ├─ ")
    add(OC_CONTROLLER, " ├─ ")
    add(OC_MEMORY,     " ├─ ")
    add(OC_PORT,       " ├─ ")
    add(OC_PROCESSOR,  " ├─ ")
    add(OC_SLOT,       " ├─ ")
    add(OC_SOUND,      " ├─ ")
    add(OC_STORAGE,    " ├─ ")
    add(OC_VIDEO,      " └─ ")
    add(OC_SOFTWARE)
    opts.toList
  }
  
  def optionAttributesFor(objectType:String) : List[(String,String)] = {
    ditQueryData.criteriaMap.get(objectType) match {
      case None => List()
      case Some(ot) => ot.criteria.map(x => (x.name,S.?("ldap.attr."+x.name))).toList
    }
  }
  
  def optionComparatorsFor(objectType:String,attribute:String) : List[(String,String)] = {
    ditQueryData.criteriaMap.get(objectType) match {
      case None => List()
      case Some(ot) => ot.criterionForName(attribute) match {
        case None => List()
        case Some(a) => a.cType.comparators.map(x => (x.id,S.?("ldap.comp."+x.id))).toList
      }
    }
  }
  
  def objectTypeSelect(ot:ObjectCriterion,lines: Buffer[CriterionLine],i:Int) : NodeSeq = {
    SHtml.untrustedSelect( 
      otOptions,
      Full(ot.objectType), 
      ({ x =>
        ditQueryData.criteriaMap.get(x) foreach { o => if(i >= 0 && i < lines.size) lines(i) = lines(i).copy(objectType=o) }
      }),
      ("id","ot_"+i),
      ("onchange", ajaxAttr(lines,i)._2.toJsCmd)
    )
  }
  
  def attributeNameSelect(ot:ObjectCriterion,a:Criterion,lines: Buffer[CriterionLine],i:Int) : NodeSeq =  {
    SHtml.untrustedSelect( 
      optionAttributesFor(ot.objectType), 
      Full(a.name), 
      (x => {  //check that x is really a value of ot
        if(i >= 0 && i < lines.size) lines(i).objectType.criterionForName(x) foreach { y => lines(i) = lines(i).copy(attribute=y) }
      }),
      ("id","at_"+i),
      ("onchange", ajaxComp(lines,i)._2.toJsCmd)
    )
  }
  
  def comparatorSelect(ot:ObjectCriterion,a:Criterion,c:CriterionComparator,lines: Buffer[CriterionLine],i:Int) : NodeSeq =  {
    SHtml.untrustedSelect( 
      optionComparatorsFor(ot.objectType,a.name), 
      Full(c.id), 
      (x => { 
        if(i >= 0 && i < lines.size) lines(i).attribute.cType.comparatorForString(x) foreach { y => lines(i) = lines(i).copy(comparator=y) }
      }),
      ("id","ct_"+i),
      ("onchange", ajaxVal(lines,i)._2.toJsCmd),
      ("class","selectComparator")
    )
  }
  
  val defaultLine : CriterionLine = {
    //in case of further modification in ditQueryData
    require(ditQueryData.criteriaMap(OC_NODE).criteria(0).name == "OS", "Error in search node criterion default line, did you change DitQueryData ?")
    require(ditQueryData.criteriaMap(OC_NODE).criteria(0).cType.isInstanceOf[OstypeComparator.type], "Error in search node criterion default line, did you change DitQueryData ?")
    CriterionLine(
      objectType = ditQueryData.criteriaMap(OC_NODE)
    , attribute  = ditQueryData.criteriaMap(OC_NODE).criteria(0)
    , comparator = ditQueryData.criteriaMap(OC_NODE).criteria(0).cType.comparators(0)
    , value      = "Linux"
  )
  }
}