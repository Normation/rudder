/*
 *************************************************************************************
 * Copyright 2011 Normation SAS
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

package com.normation.rudder.web.components

import bootstrap.liftweb.RudderConfig
import com.normation.box._
import com.normation.inventory.ldap.core.LDAPConstants._
import com.normation.rudder.domain.RudderLDAPConstants.A_NODE_PROPERTY
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.domain.queries._
import com.normation.rudder.domain.queries.And
import com.normation.rudder.domain.queries.CriterionComposition
import com.normation.rudder.domain.queries.CriterionLine
import com.normation.rudder.domain.queries.Or
import com.normation.rudder.web.ChooseTemplate
import net.liftweb.common._
import net.liftweb.http.DispatchSnippet
import net.liftweb.http.S
import net.liftweb.http.SHtml
import net.liftweb.http.js._
import net.liftweb.http.js.JE._
import net.liftweb.http.js.JsCmds._
import net.liftweb.util.Helpers._
import scala.collection.mutable.{Map => MutMap}
import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable.Buffer
import scala.xml._

/**
 * The Search Nodes component
 * It is used in the standard search server page, and in the group page (and probably elsewhere)
 *
 * query and srvList are both var, because they will be manipulated by the component
 * we would have wanted to get back their value, but it seems it cannot be done
 *
 */
class SearchNodeComponent(
    htmlId: String, // unused ...

    _query:           Option[Query],
    _srvList:         Box[Seq[NodeInfo]],
    onUpdateCallback: () => JsCmd = { () => Noop }, // on grid refresh

    onClickCallback: Option[(String, Boolean) => JsCmd] = None, // this callback is used when we click on an element in the grid

    onSearchCallback: (Boolean, Option[Query]) => JsCmd = { (_, _) =>
      Noop
    }, // this callback is used when a research is done and the state of the Search button changes

    saveButtonId: String = "", // the id of the save button, that gets disabled when one change the form

    groupPage: Boolean
) extends DispatchSnippet with Loggable {
  import SearchNodeComponent._

  // our local copy of things we work on
  private[this] var query   = _query.map(x => x.copy())
  private[this] var srvList = _srvList.map(x => Seq() ++ x)

  private[this] val queryProcessor = RudderConfig.acceptedNodeQueryProcessor

  private[this] val nodeInfoService = RudderConfig.nodeInfoService

  // The portlet for the server detail
  private[this] def searchNodes: NodeSeq = ChooseTemplate(
    List("templates-hidden", "server", "server_details"),
    "query-searchnodes"
  )

  private[this] def nodesTable = ChooseTemplate(
    List("templates-hidden", "server", "server_details"),
    "nodes-table"
  )
  private[this] def queryline: NodeSeq = {
    <tr class="error"></tr>
  <tr class="query_line">
    <td class="first objectType"></td>
    <td class="attributeName"></td>
    <td class="comparator"></td>
    <td class="inputValue"></td>
    <td class="removeLine"></td>
    <td class="last addLine"></td>
  </tr>
  }
  private[this] def content = {
    val select = ("content-query ^*" #> "not relevant for ^* operator")
    select(searchNodes)
  }

  /**
   * External exposition of the current state of server list.
   * Page/component which includes SearchNodeComponent can use it.
   * @return
   */
  def getSrvList(): Box[Seq[NodeInfo]] = srvList

  /**
   * External exposition of the current state of query.
   * Page/component which includes SearchNodeComponent can use it.
   * @return
   */
  def getQuery(): Option[Query] = query

  var dispatch: DispatchIt = { case "showQuery" => { _ => buildQuery(false) } }

  var initUpdate = true // this is true when we arrive on the page, or when we've done an search

  // used in callback to know if we have to
  // activate the global save button
  var searchFormHasError = false

  val errors = MutMap[CriterionLine, String]()

  def buildQuery(isGroupsPage: Boolean): NodeSeq = {

    if (None == query) query = Some(Query(NodeReturnType, And, ResultTransformation.Identity, List(defaultLine)))
    val lines       = ArrayBuffer[CriterionLine]()
    var composition = query.get.composition
    var rType       = query.get.returnType // for now, don't move
    var transform   = query.get.transform

    def addLine(i: Int): JsCmd = {
      if (i >= 0) {
        // used same info than previous line
        lines.insert(i + 1, lines(i).copy(value = ""))
      } else {
        // defaults values
        lines.insert(i + 1, defaultLine)
      }
      query = Some(Query(rType, composition, transform, lines.toList))
      initUpdate = false
      ajaxCriteriaRefresh(isGroupsPage)
    }

    def removeLine(i: Int): JsCmd = {
      if (lines.size > i) {

        val line = lines(i)
        lines.remove(i)
        // Remove error notifications if there is no more occurrences of the line
        // Or new lines with that will always have th error message (ie An empty hostname)
        if (!lines.contains(line)) {
          errors remove line
        }

        query = Some(Query(rType, composition, transform, lines.toList))
      }
      initUpdate = false
      ajaxCriteriaRefresh(isGroupsPage)
    }

    def processForm(isGroupPage: Boolean): JsCmd = {
      // filter on non validate values
      errors.clear()
      lines.zipWithIndex.foreach {
        case (cl @ CriterionLine(_, a, c, v), i) =>
          a.cType.validate(v, c.id).toBox match {
            case Failure(m, _, _) => errors.put(cl, m)
            case _                =>
          }
      }
      val newQuery = Query(rType, composition, transform, lines.toList)
      query = Some(newQuery)
      if (errors.isEmpty) {
        // ********* EXECUTE QUERY ***********
        srvList = (for {
          nodeIds   <- queryProcessor.process(newQuery)
          nodeInfos <- nodeInfoService.getNodeInfosSeq(nodeIds).toBox
        } yield {
          nodeInfos
        })
        initUpdate = true
        searchFormHasError = false
      } else {
        // ********* ERRORS FOUND ***********"
        srvList = Empty
        searchFormHasError = true
      }
      ajaxCriteriaRefresh(isGroupsPage) & ajaxGridRefresh(isGroupsPage)
      // ajaxGroupCriteriaRefresh & ajaxNodesTableRefresh()
    }

    /**
     * Refresh the query parameter part
     */
    def ajaxCriteriaRefresh(isGroupPage: Boolean):                           JsCmd   = {
      lines.clear()
      SetHtml("SearchForm", displayQuery(content, isGroupPage)) & activateButtonOnChange()
    }
    def displayQueryLine(cl: CriterionLine, index: Int, addRemove: Boolean): NodeSeq = {

      lines.append(cl)

      val initJs          = cl.attribute.cType.initForm("v_" + index)
      val inputAttributes = ("id", "v_" + index) :: ("class", "queryInputValue form-control input-sm") :: {
        if (cl.comparator.hasValue) Nil else ("disabled", "disabled") :: Nil
      }
      val input           = cl.attribute.cType.toForm(cl.value, (x => lines(index) = lines(index).copy(value = x)), inputAttributes: _*)
      (".removeLine *" #> {
        if (addRemove)
          SHtml.ajaxSubmit("-", () => removeLine(index), ("class", "removeLineButton btn btn-danger btn-xs"))
        else
          NodeSeq.Empty
      } &
      ".addLine *" #> SHtml.ajaxSubmit("+", () => addLine(index), ("class", "removeLineButton btn btn-success btn-xs")) &
      ".objectType *" #> objectTypeSelect(cl.objectType, lines, index) &
      ".attributeName *" #> attributeNameSelect(cl.objectType, cl.attribute, lines, index) &
      ".comparator *" #> comparatorSelect(cl.objectType, cl.attribute, cl.comparator, lines, index) &
      ".inputValue *" #> input &
      ".error" #> {
        errors.get(cl) match {
          case Some(m) =>
            <tr><td class="error" colspan="6">{m}</td></tr>
          case _       => NodeSeq.Empty
        }
      }).apply(queryline) ++ Script(OnLoad(initJs))

    }

    /**
     * Display the query part
     * Caution, we pass an html different at the init part (whole content-query)
     *
     */

    def displayQuery(html: NodeSeq, isGroupPage: Boolean): NodeSeq = {
      val Query(otName, comp, trans, criteria) = query.get
      val checkBox                             = {
        SHtml.checkbox(
          rType == NodeAndRootServerReturnType,
          { value: Boolean =>
            if (value)
              rType = NodeAndRootServerReturnType
            else
              rType = NodeReturnType
          },
          ("id", "typeQuery"),
          ("class", "compositionCheckbox")
        )
      }

      val radio = {
        SHtml
          .radio(
            Seq("AND", "OR"),
            Full(if (comp == Or) "OR" else "AND"),
            { value: String =>
              composition = CriterionComposition.parse(value).getOrElse(And)
            } // default to AND on unknown composition string
          )
          .flatMap(radio => <label>
            {radio.xhtml}
            <span class="radioTextLabel">{radio.key.toString}</span>
          </label>)
      }

      val transformCheckbox = {
        SHtml.checkbox(
          transform == ResultTransformation.Invert,
          { value: Boolean =>
            if (value)
              transform = ResultTransformation.Invert
            else
              transform = ResultTransformation.Identity
          },
          ("id", "transformResult"),
          ("class", "compositionCheckbox")
        )
      }

      ("#typeQuery" #> checkBox &
      "#composition" #> radio &
      "#transformResult" #> transformCheckbox &
      "#submitSearch * " #> SHtml.ajaxSubmit(
        "Search",
        () => processForm(isGroupsPage),
        ("id"    -> "SubmitSearch"),
        ("class" -> "submitButton btn btn-primary")
      ) &
      "#query_lines *" #> criteria.zipWithIndex.flatMap { case (cl, i) => displayQueryLine(cl, i, criteria.size > 1) }).apply(
        html
        /*}:NodeSeq} ++ { if(criteria.size > 0) {
          //add a <script> tag to init all specific Js form renderer, like Jquery datepicker for date
          val initJs = criteria.zipWithIndex.map { case (criteria,index) => criteria.attribute.cType.initForm("v_"+index)}
          Script(OnLoad(initJs))
        } else NodeSeq.Empty}
      },*/
      ) ++ Script(OnLoad(JsVar("""
          $(".queryInputValue").keydown( function(event) {
            processKey(event , 'SubmitSearch')
          } );
          """)))
    }

    /**
      * Display the query part for group criteria
      * Caution, we pass an html different at the init part (whole content-query)
      *
      */

    /*
     * Show the search engine and the grid
     */
    def showQueryAndGridContent(): NodeSeq = {
      (
        "content-query" #> { x: NodeSeq => displayQuery(x, false) }
        & "update-gridresult" #> srvGrid.displayAndInit(Some(Seq()), "serverGrid") // we need to set something, or IE moans
      )(searchNodes)
    }

    showQueryAndGridContent() ++ Script(OnLoad(ajaxGridRefresh(false)))
  }

  def displayNodesTable: NodeSeq = {
    def showQueryAndGridContent(): NodeSeq = {
      (
        "content-query" #> NodeSeq.Empty
        & "update-nodestable" #> srvGrid.displayAndInit(Some(Seq()), "groupNodesTable") // we need to set something, or IE moans
      )(nodesTable)
    }
    showQueryAndGridContent() ++ Script(OnLoad(ajaxGridRefresh(true)))
  }

  /**
   * Refresh the grid result
   * A small trick of the trade : since the Showing x of xx is moved out of the ajax refresh
   * zone, it must be removed before being added again, or problems will rises
   * @return
   */
  def ajaxGridRefresh(isGroupPage: Boolean): JsCmd = {
    activateButtonOnChange() &
    gridResult(isGroupPage)
  }

  /**
   * When we change the form, we can update the query
   * @return
   */
  def activateButtonOnChange(): JsCmd = {
    // If saved button id is not defined do not disable save button
    val disableGridOnChange: JsCmd = if (saveButtonId != "") {
      JE.JsRaw(
        s"""activateButtonDeactivateGridOnFormChange("queryParameters", "SubmitSearch",  "serverGrid", "${saveButtonId}");"""
      )
    } else {
      Noop
    }
    onSearchCallback(searchFormHasError, query) & disableGridOnChange
  }

  /**
   * From the computed result, return the NodeSeq corresponding to the grid, plus the initialisation JS
   */
  def gridResult(isGroupsPage: Boolean): JsCmd = {
    // Ideally this would just check the size first ?
    val tableId = if (isGroupsPage) { "groupNodesTable" }
    else { "serverGrid" }
    srvList match {
      case Full(seq) =>
        val refresh = srvGrid.refreshData(() => Some(seq), onClickCallback, tableId)
        JsRaw(s"""(${refresh.toJsCmd}());createTooltip();""") & onUpdateCallback()

      case Empty =>
        Noop

      case f @ Failure(_, _, _) =>
        logger.error(s"Could not update node table result cause is: ${f.msg}")
        Noop
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
  val ditQueryData = RudderConfig.ditQueryData
  val srvGrid      = RudderConfig.srvGrid

  ////////
  //////// All the tools to handle the Ajax logic for
  //////// dependent Select Box:
  //////// - passing string param between client and server (TODO : should be json)
  //////// - buildind the required JS to update the select box to its new value
  ////////

  def parseAttrParam(s: String): Option[(String, String, String, String, String, String, String)] = {
    // expected "newObjectTypeValue,attributeSelectEltId,oldAttrValue,comparatorSelectEltId,oldCompValue,valueSelectEltId,oldValue
    val reg = """([^,]*),([^,]*),([^,]*),([^,]*),([^,]*),([^,]*),([^,]*)""".r
    s match {
      case reg(a, b, c, d, e, f, g) => Some((a, b, c, d, e, f, g))
      case _                        => None
    }
  }
  def parseCompParam(s: String): Option[(String, String, String, String, String, String)]         = {
    // expect "objectTypeValue,newAttrValue,comparatorSelectEltId,oldCompValue,valueSelectEltId,oldValue
    val reg = """([^,]*),([^,]*),([^,]*),([^,]*),([^,]*),([^,]*)""".r
    s match {
      case reg(a, b, c, d, e, f) => Some((a, b, c, d, e, f))
      case _                     => None
    }
  }
  def parseValParam(s: String):  Option[(String, String)]                                         = {
    // expect "newCompValue,valueSelectEltId"
    val reg = """([^,]*),([^,]*)""".r
    s match {
      case reg(a, b) => Some((a, b))
      case _         => None
    }
  }

  def setIsEnableFor(comparator: String, valueEltId: String): JsCmd = {
    val e = OrderedComparators.comparatorForString(comparator) match {
      case None       => true
      case Some(comp) => comp.hasValue
    }
    if (e) SetExp(ElemById(valueEltId, "disabled"), JsFalse)
    else SetExp(ElemById(valueEltId, "disabled"), JsTrue)
  }

  def updateCompAndValue(
      func:     String => Any,
      ot:       String,
      a:        String,
      c_eltid:  String,
      c_oldVal: String,
      v_eltid:  String,
      v_old:    String
  ): JsCmd = {
    // change input display
    val comp         = ditQueryData.criteriaMap.get(ot) match {
      case None    => StringComparator
      case Some(o) =>
        o.criterionForName(a) match {
          case None       => StringComparator
          case Some(comp) => comp.cType
        }
    }
    val comparators  = optionComparatorsFor(ot, a)
    val compNames    = comparators.map(_._1)
    val selectedComp = compNames match {
      case a :: _ =>
        compNames.filter(_ == c_oldVal) match {
          case x :: _ => x
          case Nil    => a
        }
      case Nil    => ""
    }
    comp.destroyForm(v_eltid) &
    JsRaw(
      "jQuery('#%s').replaceWith('%s')".format(
        v_eltid,
        comp.toForm(v_old, func, ("id" -> v_eltid), ("class" -> "queryInputValue form-control input-sm"))
      )
    ) &
    comp.initForm(v_eltid) &
    JsCmds.ReplaceOptions(c_eltid, comparators, Full(selectedComp)) &
    setIsEnableFor(selectedComp, v_eltid) &
    OnLoad(JsVar("""
        $(".queryInputValue").keydown( function(event) {
          processKey(event , 'SubmitSearch')
        } );
        """))
  }

  def replaceAttributes(func: String => Any)(ajaxParam: String): JsCmd = {
    parseAttrParam(ajaxParam) match {
      case None                                                             => Alert("Can't parse for attribute: " + ajaxParam)
      case Some((ot, a_eltid, a_oldVal, c_eltid, c_oldVal, v_eltid, v_old)) =>
        // change attribute list
        val attributes   = optionAttributesFor(ot)
        val attrNames    = attributes.map(_._1)
        val selectedAttr = attrNames match {
          case a :: _ =>
            attrNames.filter(_ == a_oldVal) match {
              case x :: _ => x
              case Nil    => a
            }
          case Nil    => ""
        }

        JsCmds.ReplaceOptions(a_eltid, attributes, Full(selectedAttr)) &
        updateCompAndValue(func, ot, selectedAttr, c_eltid, c_oldVal, v_eltid, v_old)
    }
  }

  def replaceComp(func: String => Any)(ajaxParam: String): JsCmd = {
    parseCompParam(ajaxParam) match {
      case None                                             => Alert("Can't parse for comparator: " + ajaxParam)
      case Some((ot, a, c_eltid, c_oldVal, v_eltid, v_old)) =>
        updateCompAndValue(func, ot, a, c_eltid, c_oldVal, v_eltid, v_old)
    }
  }

  def replaceValue(ajaxParam: String): JsCmd = { // elementId:String, comp:String, oldValue:String) : JsCmd = {
    parseValParam(ajaxParam) match {
      case None                   => Alert("Can't parse for value: " + ajaxParam)
      case Some((c_val, v_eltid)) => setIsEnableFor(c_val, v_eltid)
    }
  }

  // expected "newObjectTypeValue,attributeSelectEltId,oldAttrValue,comparatorSelectEltId,oldCompValue,valueSelectEltId,oldValue
  def ajaxAttr(lines: Buffer[CriterionLine], i: Int) = {
    SHtml.ajaxCall( // we we change the attribute, we want to reset the value, see issue #1199
      JE.JsRaw(
        "this.value+',at_%s,'+%s+',ct_%s,'+ %s +',v_%s,'+%s"
          .format(i, ValById("at_" + i).toJsCmd, i, ValById("ct_" + i).toJsCmd, i, Str("").toJsCmd)
      ),
      s => After(TimeSpan(200), replaceAttributes(x => lines(i) = lines(i).copy(value = x))(s))
    )
  }
  // expect "objectTypeValue,newAttrValue,comparatorSelectEltId,oldCompValue,valueSelectEltId
  def ajaxComp(lines: Buffer[CriterionLine], i: Int) = {
    SHtml.ajaxCall( // we we change the attribute, we want to reset the value, see issue #1199
      JE.JsRaw(
        "%s+','+this.value+',ct_%s,'+ %s +',v_%s,'+%s"
          .format(ValById("ot_" + i).toJsCmd, i, ValById("ct_" + i).toJsCmd, i, Str("").toJsCmd)
      ),
      s => After(TimeSpan(200), replaceComp(x => lines(i) = lines(i).copy(value = x))(s))
    )
  }
  // expect "newCompValue,valueSelectEltId"
  def ajaxVal(lines: Buffer[CriterionLine], i: Int)  = {
    SHtml.ajaxCall(JE.JsRaw("this.value+',v_%s'".format(i)), s => After(TimeSpan(200), replaceValue(s)))
  }

  ////////
  //////// Build require select box for a line
  ////////

  // how to present the first select box
  val otOptions: List[(String, String)] = {
    val opts                             = Buffer[(String, String)]()
    def add(s: String, pre: String = "") = opts += ((s, pre + S.?("ldap.object." + s)))

    add(OC_NODE)
    add("group", " ├─ ")
    add(OC_NET_IF, " ├─ ")
    add(OC_FS, " ├─ ")
    add(A_PROCESS, " ├─ ")
    add(OC_VM_INFO, " ├─ ")
    add(A_NODE_PROPERTY, " ├─ ")
    add(A_EV, " └─ ")
    add(OC_MACHINE)
    add(OC_BIOS, " ├─ ")
    add(OC_CONTROLLER, " ├─ ")
    add(OC_MEMORY, " ├─ ")
    add(OC_PORT, " ├─ ")
    add(OC_PROCESSOR, " ├─ ")
    add(OC_SLOT, " ├─ ")
    add(OC_SOUND, " ├─ ")
    add(OC_STORAGE, " ├─ ")
    add(OC_VIDEO, " └─ ")
    add(OC_SOFTWARE)
    opts.toList
  }

  def optionAttributesFor(objectType: String): List[(String, String)] = {
    ditQueryData.criteriaMap.get(objectType) match {
      case None     => List()
      case Some(ot) => ot.criteria.map(x => (x.name, S.?("ldap.attr." + x.name))).toList
    }
  }

  def optionComparatorsFor(objectType: String, attribute: String): List[(String, String)] = {
    ditQueryData.criteriaMap.get(objectType) match {
      case None     => List()
      case Some(ot) =>
        ot.criterionForName(attribute) match {
          case None    => List()
          case Some(a) => a.cType.comparators.map(x => (x.id, S.?("ldap.comp." + x.id))).toList
        }
    }
  }

  def objectTypeSelect(ot: ObjectCriterion, lines: Buffer[CriterionLine], i: Int): NodeSeq = {
    SHtml.untrustedSelect(
      otOptions,
      Full(ot.objectType),
      ({ x =>
        ditQueryData.criteriaMap.get(x) foreach { o => if (i >= 0 && i < lines.size) lines(i) = lines(i).copy(objectType = o) }
      }),
      ("id", "ot_" + i),
      ("onchange", ajaxAttr(lines, i)._2.toJsCmd),
      ("class", "selectField form-control input-sm")
    )
  }

  def attributeNameSelect(ot: ObjectCriterion, a: Criterion, lines: Buffer[CriterionLine], i: Int): NodeSeq = {
    SHtml.untrustedSelect(
      optionAttributesFor(ot.objectType),
      Full(a.name),
      (x => { // check that x is really a value of ot
        if (i >= 0 && i < lines.size) lines(i).objectType.criterionForName(x) foreach { y =>
          lines(i) = lines(i).copy(attribute = y)
        }
      }),
      ("id", "at_" + i),
      ("onchange", ajaxComp(lines, i)._2.toJsCmd),
      ("class", "selectField form-control input-sm")
    )
  }

  def comparatorSelect(
      ot:    ObjectCriterion,
      a:     Criterion,
      c:     CriterionComparator,
      lines: Buffer[CriterionLine],
      i:     Int
  ): NodeSeq = {
    SHtml.untrustedSelect(
      optionComparatorsFor(ot.objectType, a.name),
      Full(c.id),
      (x => {
        if (i >= 0 && i < lines.size) lines(i).attribute.cType.comparatorForString(x) foreach { y =>
          lines(i) = lines(i).copy(comparator = y)
        }
      }),
      ("id", "ct_" + i),
      ("onchange", ajaxVal(lines, i)._2.toJsCmd),
      ("class", "selectComparator form-control input-sm")
    )
  }

  val defaultLine = {
    // in case of further modification in ditQueryData
    require(
      ditQueryData.criteriaMap(OC_NODE).criteria(0).name == "OS",
      "Error in search node criterion default line, did you change DitQueryData ?"
    )
    require(
      ditQueryData.criteriaMap(OC_NODE).criteria(0).cType.isInstanceOf[NodeOstypeComparator.type],
      "Error in search node criterion default line, did you change DitQueryData ?"
    )
    CriterionLine(
      objectType = ditQueryData.criteriaMap(OC_NODE),
      attribute = ditQueryData.criteriaMap(OC_NODE).criteria(0),
      comparator = ditQueryData.criteriaMap(OC_NODE).criteria(0).cType.comparators(0),
      value = "Linux"
    )
  }
}
