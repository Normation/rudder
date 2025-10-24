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
import com.normation.box.*
import com.normation.inventory.domain.BsdType
import com.normation.inventory.domain.LinuxType
import com.normation.inventory.domain.OsType
import com.normation.inventory.ldap.core.LDAPConstants.*
import com.normation.rudder.domain.RudderLDAPConstants.A_NODE_PROPERTY
import com.normation.rudder.domain.nodes.NodeState
import com.normation.rudder.domain.queries.*
import com.normation.rudder.domain.queries.CriterionComposition
import com.normation.rudder.domain.queries.CriterionComposition.And
import com.normation.rudder.domain.queries.CriterionComposition.Or
import com.normation.rudder.domain.queries.CriterionLine
import com.normation.rudder.domain.queries.QueryReturnType.*
import com.normation.rudder.facts.nodes.CoreNodeFact
import com.normation.rudder.facts.nodes.QueryContext
import com.normation.rudder.users.CurrentUser
import com.normation.rudder.web.ChooseTemplate
import net.liftweb.common.*
import net.liftweb.http.DispatchSnippet
import net.liftweb.http.GUIDJsExp
import net.liftweb.http.S
import net.liftweb.http.SHtml
import net.liftweb.http.js.*
import net.liftweb.http.js.JE.*
import net.liftweb.http.js.JsCmds.*
import net.liftweb.util.Helpers.*
import org.apache.commons.text.StringEscapeUtils
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable.Buffer
import scala.collection.mutable.Map as MutMap
import scala.xml.*

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
    _srvList:         Box[Seq[CoreNodeFact]],
    onUpdateCallback: () => JsCmd = { () => Noop }, // on grid refresh

    onClickCallback: Option[(String, Boolean) => JsCmd] = None, // this callback is used when we click on an element in the grid

    onSearchCallback: (Boolean, Option[Query]) => JsCmd = { (_, _) =>
      Noop
    }, // this callback is used when a research is done and the state of the Search button changes

    saveButtonId:     String = "", // the id of the save button, that gets disabled when one change the form

    groupPage: Boolean
) extends DispatchSnippet with Loggable {
  import SearchNodeComponent.*

  // our local copy of things we work on
  private var query   = _query.map(x => x.copy())
  private var srvList = _srvList.map(x => Seq() ++ x)

  private val queryProcessor = RudderConfig.acceptedNodeQueryProcessor

  private val nodeFactRepo = RudderConfig.nodeFactRepository

  implicit private val qc: QueryContext = CurrentUser.queryContext // bug https://issues.rudder.io/issues/26605

  // The portlet for the server detail
  private def searchNodes: NodeSeq = ChooseTemplate(
    List("templates-hidden", "server", "server_details"),
    "query-searchnodes"
  )

  private def nodesTable = ChooseTemplate(
    List("templates-hidden", "server", "server_details"),
    "nodes-table"
  )
  private def queryline: NodeSeq = {
    <tr class="error"></tr>
  <tr class="query_line">
    <td class="first objectType"></td>
    <td class="attributeName"></td>
    <td class="comparator"></td>
    <td class="inputValue d-flex"></td>
    <td class="removeLine"></td>
    <td class="last addLine"></td>
  </tr>
  }
  private def content = {
    val select = ("content-query ^*" #> "not relevant for ^* operator")
    select(searchNodes)
  }

  /**
   * External exposition of the current state of server list.
   * Page/component which includes SearchNodeComponent can use it.
   * @return
   */
  def getSrvList(): Box[Seq[CoreNodeFact]] = srvList

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

  val errors: mutable.Map[CriterionLine, String] = MutMap[CriterionLine, String]()

  def buildQuery(isGroupsPage: Boolean)(implicit qc: QueryContext): NodeSeq = {

    if (None == query) query = Some(Query(NodeReturnType, And, ResultTransformation.Identity, List()))
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
      ajaxCriteriaRefresh(isGroupsPage, preventSave = true)
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
      ajaxCriteriaRefresh(isGroupsPage, preventSave = true)
    }

    def processForm(isGroupPage: Boolean)(implicit qc: QueryContext): JsCmd = {
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
          nodeInfos <-
            nodeFactRepo
              .getAll()
              .map(_.collect { case (id, f) if nodeIds.contains(id) => f }.toSeq)
              .toBox
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
      ajaxCriteriaRefresh(isGroupsPage, preventSave = false) & gridResult(isGroupPage)
      // ajaxGroupCriteriaRefresh & ajaxNodesTableRefresh()
    }

    /**
     * Refresh the query parameter part
     */
    def ajaxCriteriaRefresh(isGroupPage: Boolean, preventSave: Boolean):     JsCmd   = {
      lines.clear()
      SetHtml("SearchForm", displayQuery(content, isGroupPage)) & activateButtonOnChange(preventSave)
    }
    def displayQueryLine(cl: CriterionLine, index: Int, addRemove: Boolean): NodeSeq = {

      lines.append(cl)

      val form            = asForm(cl.attribute.cType)
      val initJs          = form.initForm("v_" + index)
      val inputAttributes = ("id", "v_" + index) :: ("class", "queryInputValue form-control input-sm") :: {
        if (cl.comparator.hasValue) Nil else ("disabled", "disabled") :: Nil
      }
      val input           = form.toForm(cl.value, (x => lines(index) = lines(index).copy(value = x)), inputAttributes*)
      (".removeLine *" #> {
        if (addRemove)
          SHtml.ajaxSubmit("-", () => removeLine(index), ("class", "btn btn-danger btn-sm fw-bold"))
        else
          NodeSeq.Empty
      } &
      ".addLine *" #> addLineButton(index) &
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
      }).apply(queryline) ++ Script(OnLoad(initJs)) ++ (if (lines.nonEmpty) {
                                                          Script(
                                                            OnLoad(JsRaw("""$("#SubmitSearch").removeClass("d-none")"""))
                                                          )
                                                        } else NodeSeq.Empty)

    }

    def addLineButton(index: Int): Elem = SHtml.ajaxSubmit("+", () => addLine(index), ("class", "btn btn-success btn-sm fw-bold"))

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
          { (value: Boolean) =>
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
            (value: String) => composition = CriterionComposition.parse(value).getOrElse(And)
            // default to AND on unknown composition string
          )
          .flatMap(radio => <label>
            {radio.xhtml}
            <span class="radioTextLabel">{radio.key.toString}</span>
          </label>)
      }

      val transformCheckbox = {
        SHtml.checkbox(
          transform == ResultTransformation.Invert,
          { (value: Boolean) =>
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
        ("class" -> s"btn btn-primary ${if (criteria.isEmpty) "d-none" else ""}")
      ) &
      "#query_lines *" #> (if (criteria.isEmpty) <div><span>Add a first criterion to define your group: </span>{
                             addLineButton(-1)
                           }</div>
                           else
                             criteria.zipWithIndex.flatMap { case (cl, i) => displayQueryLine(cl, i, criteria.size > 1) })).apply(
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
        "content-query" #> { (x: NodeSeq) => displayQuery(x, isGroupPage = false) }
        & "update-gridresult" #> srvGrid.displayAndInit(Some(Seq()), "serverGrid")
      )(searchNodes)
    }

    showQueryAndGridContent() ++ Script(OnLoad(ajaxGridRefresh(false)))
  }

  def displayNodesTable: NodeSeq = {
    def showQueryAndGridContent(): NodeSeq = {
      (
        "content-query" #> NodeSeq.Empty
        & "update-nodestable" #> srvGrid.displayAndInit(
          Some(Seq()),
          "groupNodesTable",
          Some(showNodesTableByTab())
        )
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
    activateButtonOnChange(preventSave = false) &
    gridResult(isGroupPage)
  }

  /**
   * Display the nodes tables by default only for some known tabs
   * @return
   */
  def showNodesTableByTab(): JsCmd = {
    val tabs = List("groupParametersTab", "groupCriteriaTab")
    JE.JsRaw(s"""
        var tabs = ${tabs.map(s => s"'${s}'").mkString("[", ",", "]")};
        $$('#groupTabMenu').ready(function () {
          $$('#groupTabMenu [role="tab"]').on("show.bs.tab", function (e) {
            var isNextTabShowing = tabs.includes(e.target.getAttribute('aria-controls'));
            var isPreviousTabShowing =
              !e.relatedTarget || tabs.includes(e.relatedTarget.getAttribute('aria-controls')); // initial tab shows nodes table
            if (!isPreviousTabShowing && isNextTabShowing) {
              handleNodesTableDisplayByGroupTab(true);
            }
            if (isPreviousTabShowing && !isNextTabShowing) {
              handleNodesTableDisplayByGroupTab(false);
            }
          })
        })
      """)
  }

  /**
   * When we change the form, we can update the query
   * @return
   */
  def activateButtonOnChange(preventSave: Boolean): JsCmd = {
    // If saved button id is not defined do not disable save button
    val disableGridOnChange: JsCmd = if (saveButtonId != "") {
      JE.JsRaw(
        s"""activateButtonDeactivateGridOnFormChange("queryParameters", "SubmitSearch",  "serverGrid", "${StringEscapeUtils
            .escapeEcmaScript(saveButtonId)}");"""
      ) // JsRaw ok, escaped
    } else {
      Noop
    }
    val disableSave = searchFormHasError || preventSave
    onSearchCallback(disableSave, query) & disableGridOnChange
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
        JsRaw(s"""(${refresh.toJsCmd}());initBsTooltips();""") & onUpdateCallback() // JsRaw ok, escaped

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
    val e = isComparatorHasValue(comparator)
    if (e) SetExp(ElemById(valueEltId, "disabled"), JsFalse)
    else SetExp(ElemById(valueEltId, "disabled"), JsTrue)
  }

  def isComparatorHasValue(comparator: String): Boolean = {
    OrderedComparators.comparatorForString(comparator) match {
      case None       => true
      case Some(comp) => comp.hasValue
    }
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
      case b :: _ =>
        compNames.filter(_ == c_oldVal) match {
          case x :: _ => x
          case Nil    => b
        }
      case Nil    => ""
    }
    val form         = asForm(comp)
    val newForm      = form.toForm(v_old, func, ("id" -> v_eltid), ("class" -> "queryInputValue form-control input-sm"))
    form.destroyForm(v_eltid) &
    JsCmds.Replace(v_eltid, newForm) &
    form.initForm(v_eltid) &
    JsCmds.ReplaceOptions(c_eltid, comparators, Full(selectedComp)) &
    setIsEnableFor(selectedComp, v_eltid) &
    OnLoad(JsVar("""
        $(".queryInputValue").keydown( function(event) {
          processKey(event , 'SubmitSearch')
        } );
        """))
  }

  def updateValue(
      comparator: String,
      valueEltId: String
  ): JsCmd = {
    val update = {
      if (isComparatorHasValue(comparator)) Noop
      else SetExp(ElemById(valueEltId, "value"), "")
    }
    setIsEnableFor(comparator, valueEltId) &
    update
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
      case Some((c_val, v_eltid)) => updateValue(c_val, v_eltid)
    }
  }

  // expected "newObjectTypeValue,attributeSelectEltId,oldAttrValue,comparatorSelectEltId,oldCompValue,valueSelectEltId,oldValue
  def ajaxAttr(lines: Buffer[CriterionLine], i: Int): GUIDJsExp = {
    SHtml.ajaxCall( // we we change the attribute, we want to reset the value, see issue #1199
      JE.JsRaw(
        "this.value+',at_%s,'+%s+',ct_%s,'+ %s +',v_%s,'+%s"
          .format(i, ValById("at_" + i).toJsCmd, i, ValById("ct_" + i).toJsCmd, i, Str("").toJsCmd)
      ),            // JsRaw ok, escaped
      s => After(TimeSpan(200), replaceAttributes(x => lines(i) = lines(i).copy(value = x))(s))
    )
  }
  // expect "objectTypeValue,newAttrValue,comparatorSelectEltId,oldCompValue,valueSelectEltId
  def ajaxComp(lines: Buffer[CriterionLine], i: Int): GUIDJsExp = {
    SHtml.ajaxCall( // we we change the attribute, we want to reset the value, see issue #1199
      JE.JsRaw(
        "%s+','+this.value+',ct_%s,'+ %s +',v_%s,'+%s"
          .format(ValById("ot_" + i).toJsCmd, i, ValById("ct_" + i).toJsCmd, i, Str("").toJsCmd)
      ),            // JsRaw ok, escaped
      s => After(TimeSpan(200), replaceComp(x => lines(i) = lines(i).copy(value = x))(s))
    )
  }
  // expect "newCompValue,valueSelectEltId"
  def ajaxVal(lines: Buffer[CriterionLine], i: Int):  GUIDJsExp = {
    SHtml.ajaxCall(
      JE.JsRaw("this.value+',v_%s'".format(i)),
      s => After(TimeSpan(200), replaceValue(s))
    ) // JsRaw ok, no user input
  }

  ////////
  //////// Build require select box for a line
  ////////

  // how to present the first select box
  val otOptions: List[(String, String)] = {
    val opts = Buffer[(String, String)]()
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
      (x => {
        ditQueryData.criteriaMap.get(x) foreach { o => if (i >= 0 && i < lines.size) lines(i) = lines(i).copy(objectType = o) }
      }),
      ("id", "ot_" + i),
      ("onchange", ajaxAttr(lines, i)._2.toJsCmd),
      ("class", "selectField form-select form-select-sm")
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
      ("class", "selectField form-select form-select-sm")
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
      ("class", "selectComparator form-select form-select-sm")
    )
  }

  val defaultLine: CriterionLine = {
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

  sealed private class AsForm private {
    /*
     * validate the value and returns a normalized one
     * for the field.
     * DO NOT FORGET TO USE attrs ! (especially 'id')
     */
    def toForm(value:    String, func: String => Any, attrs: (String, String)*): Elem = SHtml.text(value, func, attrs*)
    def initForm(formId: String): JsCmd = Noop
    // In case any previous form was a date, we should clean up including eventual time-pickers
    // (search for DateComparator and see https://issues.rudder.io/issues/27737)
    // Note that this runs every time for every kind of form, because we don't know what the previous form kind was !
    def destroyForm(formId: String): JsCmd = {
      OnLoad(
        JsRaw(
          s"""$$('#${formId}').datepicker( "destroy" ); $$('label:has(#include-time-${formId})').remove();"""
        )
      )
    }
  }

  private object AsForm {

    val default:        AsForm = new AsForm {}
    val dateComparator: AsForm = {
      new AsForm {

        // Init a jquery datepicker.
        // It can be a "datetime" picker : use a checkbox, change the options accordingly
        // (but we need to sync the datetime state with the checkbox).
        // We assume the format has no millis, and as jquery picker seems to have no direct ISO8601 support,
        // we append offset 'Z' for now (and fix the value with the offset when closing).
        // Later, we could use the "input" browser tz
        override def initForm(formId: String): JsCmd = OnLoad(
          JsRaw(
            s"""|const init = $$.datepicker.regional['en'];
                |const formValue = () => $$('#${formId}').val();
                |const dateHasTime = (date) => date.split('T').length > 1;
                |const dateOpts = { dateFormat:'yy-mm-dd', showOn:'focus' };
                |const timeOpts = {
                |    ...dateOpts,
                |    dateFormat:'yy-mm-dd',
                |    showOn:'focus',
                |    timeFormat:'HH:mm:ss',
                |    separator: 'T',
                |    onSelect: (d, input) => { $$('#${formId}').val(d+'Z') },
                |    onClose: () => { if (dateHasTime(formValue()) && !formValue().endsWith('Z')) $$('#${formId}').val(formValue()+'Z'); },
                |};
                |
                |$$('<label class="d-flex text-nowrap align-items-center user-select-none mx-1"><input type="checkbox" class="mx-1" id="include-time-${formId}">Include time (in UTC)</label>').insertAfter('#${formId}');
                |const initValue = formValue();
                |if (initValue && dateHasTime(initValue)) {
                |  $$('#${formId}').datetimepicker(timeOpts);
                |  $$('#include-time-${formId}').prop("checked", "checked");
                  } else {
                |  $$('#${formId}').datepicker(dateOpts);
                |}
                |
                |$$('#include-time-${formId}').on('change', function() {
                |  const check = this.checked;
                |  const date = formValue();
                |  if (check && date && !dateHasTime(date)) {
                |    $$('#${formId}').val(date+'T00:00:00Z');
                |  }
                |  if (!check && dateHasTime(date)) {
                |    $$('#${formId}').val(date.split('T')[0]);
                |  }
                |  $$('#${formId}').datetimepicker('destroy').datetimepicker(check ? timeOpts : dateOpts);
                |});""".stripMargin
          )
        )
      }
    }

    def apply(toFormFunc: ((String, String => Any, Seq[(String, String)])) => Elem): AsForm = {
      new AsForm {
        override def toForm(value: String, func: String => Any, attrs: (String, String)*): Elem = toFormFunc((value, func, attrs))
      }
    }
  }

  private def asForm(criterionType: CriterionType): AsForm = {
    criterionType match {
      case NodeStateComparator  =>
        val nodeStates: List[(String, String)] = NodeState.labeledPairs.map { case (x, label) => (x.name, S.?(label)) }

        AsForm {
          case (value, func, attrs) =>
            SHtml.select(nodeStates, Box(nodeStates.find(_._1 == value).map(_._1)), func, attrs*)
        }
      case NodeOstypeComparator =>
        import NodeOstypeComparator.*
        AsForm {
          case (value, func, attrs) =>
            SHtml.select(
              (osTypes map (e => (e, e))).toSeq,
              if (osTypes.contains(value)) Full(value) else Empty,
              func,
              attrs*
            )
        }

      case NodeOsNameComparator =>
        AsForm {
          case (value, func, attrs) =>
            import NodeOsNameComparator.*

            def distribName(x: OsType): String = {
              x match {
                // add linux: for linux
                case _: LinuxType => "Linux - " + S.?("os.name." + x.name)
                case _: BsdType   => "BSD - " + S.?("os.name." + x.name)
                // nothing special for windows, Aix and Solaris
                case _ => S.?("os.name." + x.name)
              }
            }

            SHtml.select(
              osNames.map(e => (e.name, distribName(e))).toSeq,
              osNames.find(x => x.name == value).map(_.name),
              func,
              attrs*
            )
        }

      case SubGroupComparator(subGroupComparatorRepo) =>
        import com.normation.zio.*
        import net.liftweb.http.SHtml.SelectableOption
        import com.normation.rudder.domain.logger.ApplicationLogger

        AsForm {
          case (value, func, attrs) =>
            // we need to query for the list of groups here
            val subGroups: Seq[SelectableOption[String]] = {
              (for {
                res <- subGroupComparatorRepo().getGroups
              } yield {
                val g = res.map { case SubGroupChoice(id, name) => SelectableOption(id.serialize, name) }
                // if current value is defined but not in the list, add it with a "missing group" label
                if (value != "") {
                  g.find(_.value == value) match {
                    case None    => SelectableOption(value, "Missing group") +: g
                    case Some(_) => g
                  }
                } else {
                  g
                }
              }).either.runNow match {
                case Right(list) => list.sortBy(_.label)
                case Left(error) => // if an error occure, log and display the error in place of the label
                  ApplicationLogger.error(
                    s"An error happens when trying to find the list of groups to use in sub-groups: ${error.fullMsg}"
                  )
                  SelectableOption(value, "Error when looking for available groups") :: Nil
              }
            }

            SHtml.selectObj[String](
              subGroups,
              Box(subGroups.find(_.value == value).map(_.value)),
              func,
              attrs*
            )
        }
      case DateComparator                             =>
        AsForm.dateComparator

      case MachineComparator =>
        import MachineComparator.*
        AsForm {
          case (value, func, attrs) =>
            SHtml.select(
              (machineTypes map (e => (e, e))).toSeq,
              if (machineTypes.contains(value)) Full(value) else Empty,
              func,
              attrs*
            )
        }
      case VmTypeComparator  =>
        import VmTypeComparator.*
        AsForm {
          case (value, func, attrs) =>
            SHtml.select(
              (vmTypes map (e => (e._1, e._2))),
              Box(vmTypes.find(_._1 == value).map(_._1)),
              func,
              attrs*
            )
        }
      case AgentComparator   =>
        import AgentComparator.*
        AsForm {
          case (value, func, attrs) =>
            SHtml.select(
              agentTypes,
              Box(agentTypes.find(_._1 == value)).map(_._1),
              func,
              attrs*
            )
        }
      case EditorComparator  =>
        import EditorComparator.*
        AsForm {
          case (value, func, attrs) =>
            SHtml.select(
              (editors map (e => (e, e))).toSeq,
              if (editors.contains(value)) Full(value) else Empty,
              func,
              attrs*
            )
        }

      case InstanceIdComparator(instanceId) =>
        AsForm {
          case (value, func, attrs) =>
            // empty value is not allowed so the input is filled with the current instance ID as default
            val defaultOrValue = if (value.isEmpty) instanceId.value else value
            SHtml.text(
              defaultOrValue,
              func,
              attrs*
            )
        }
      case _                                => AsForm.default
    }
  }
}
