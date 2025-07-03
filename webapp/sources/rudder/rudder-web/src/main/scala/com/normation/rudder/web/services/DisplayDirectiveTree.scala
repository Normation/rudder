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

package com.normation.rudder.web.services

import bootstrap.liftweb.RudderConfig
import com.normation.cfclerk.domain.Technique
import com.normation.inventory.domain.AgentType
import com.normation.rudder.AuthorizationType
import com.normation.rudder.domain.policies.Directive
import com.normation.rudder.domain.policies.DirectiveUid
import com.normation.rudder.domain.policies.GlobalPolicyMode
import com.normation.rudder.domain.policies.PolicyModeOverrides.*
import com.normation.rudder.repository.FullActiveTechnique
import com.normation.rudder.repository.FullActiveTechniqueCategory
import com.normation.rudder.web.model.JsTreeNode
import com.normation.rudder.web.snippet.WithNonce
import net.liftweb.common.Loggable
import net.liftweb.http.SHtml
import net.liftweb.http.js.JE.*
import net.liftweb.http.js.JsCmd
import net.liftweb.http.js.JsCmds.*
import net.liftweb.util.Helpers
import org.apache.commons.text.StringEscapeUtils
import scala.xml.Elem
import scala.xml.NodeSeq
import scala.xml.NodeSeq.seqToNodeSeq
import scala.xml.Text

/**
 *
 * A service that is able to render the node group tree.
 *
 */

sealed trait AgentCompat {
  def icon:          NodeSeq
  def techniqueText: NodeSeq
  def directiveText: NodeSeq
}

object AgentCompat {
  case object Windows extends AgentCompat {
    def icon:          NodeSeq = windowsIcon
    def techniqueText: NodeSeq = <p>This Technique is only compatible with <b class="dsc">Windows</b> agent.</p>
    def directiveText: NodeSeq =
      <p>This Directive is based on a Technique version compatible with <b class="dsc">Windows agent</b>.</p>
  }
  case object Linux   extends AgentCompat {
    def icon:          NodeSeq = linuxIcon
    def techniqueText: NodeSeq = <p>This Technique is only compatible with <b>Linux</b> agent.</p>
    def directiveText: NodeSeq = <p>This Directive is based on a Technique version compatible with <b>Linux agent</b>.</p>
  }
  case object All     extends AgentCompat {
    def icon:          NodeSeq = linuxIcon ++ windowsIcon
    def techniqueText: NodeSeq =
      <p>This Technique has at least a version compatible with both <b>Linux</b> and <b class="dsc">Windows</b> agents.</p>
    def directiveText: NodeSeq =
      <p>This Directive is based on a Technique version compatible with both <b>Linux</b> and <b class="dsc">Windows</b> agents.</p>
  }
  case object NoAgent extends AgentCompat {
    def icon:          NodeSeq = NodeSeq.Empty
    def techniqueText: NodeSeq = NodeSeq.Empty
    def directiveText: NodeSeq = NodeSeq.Empty
  }

  def apply(agentTypes: Iterable[AgentType]): AgentCompat = {
    agentTypes.foldRight(NoAgent: AgentCompat) {
      case (_, All)                 => All
      case (AgentType.Dsc, NoAgent) => Windows
      case (_, NoAgent)             => Linux
      case (AgentType.Dsc, Linux)   => All
      case (_, Windows)             => All
      case (_, x)                   => x
    }
  }

  val windowsIcon: Elem = <i class="dsc-icon tree-icon"></i>
  val linuxIcon:   Elem = <i class="fa fa-gear tree-icon"></i>
}

object DisplayDirectiveTree extends Loggable {

  private val linkUtil    = RudderConfig.linkUtil
  private val userService = RudderConfig.userService

  /**
   * Display the directive tree, optionaly filtering out
   * some category or group by defining which one to
   * keep. By default, display everything.
   */
  def displayTree(
      directiveLib:     FullActiveTechniqueCategory,
      globalMode:       GlobalPolicyMode, // set of all directives at least used by one rule
      // the int is the number of rule which used it

      usedDirectiveIds: Seq[(DirectiveUid, Int)],
      onClickCategory:  Option[FullActiveTechniqueCategory => JsCmd],
      onClickTechnique: Option[FullActiveTechnique => JsCmd],
      onClickDirective: Option[(FullActiveTechniqueCategory, FullActiveTechnique, Directive) => JsCmd],
      createDirective:  Option[(Technique, FullActiveTechnique) => JsCmd],
      addEditLink:      Boolean,
      addActionBtns:    Boolean,
      included:         Set[DirectiveUid] = Set(),
      keepCategory:     FullActiveTechniqueCategory => Boolean = _ => true,
      keepTechnique:    FullActiveTechnique => Boolean = _ => true,
      keepDirective:    Directive => Boolean = _ => true
  ): NodeSeq = {

    def displayCategory(
        category: FullActiveTechniqueCategory,
        nodeId:   String
    ): JsTreeNode = new JsTreeNode {

      private val localOnClickDirective = onClickDirective.map(_.curried(category))

      private val tooltipContent = s"""
        <h4>${category.name}</h4>
        <div class="tooltip-content">
          <p>${category.description}</p>
        </div>
      """
      private val xml            = (
        <span class="treeActiveTechniqueCategoryName" data-bs-toggle="tooltip" data-bs-placement="top" title={
          tooltipContent
        }>
          {Text(category.name)}
        </span>
      )

      override def body = onClickCategory match {
        case None    => <a href="#">{xml}</a>
        case Some(f) => SHtml.a(() => f(category), xml)
      }

      // We don't want to display system Categories nor Directives
      override def children = (
        category.subCategories
          .filterNot(_.isSystem)
          .sortBy(_.name)
          .zipWithIndex
          .collect {
            case (node, i) if (keepCategory(node)) =>
              displayCategory(node, nodeId + "_" + i)
          }
          ++
          category.activeTechniques
            // We only want to keep technique that satisfies keepTechnique, that are not systems
            // and that have at least one version not deprecated (deprecationInfo empty)
            .filter(at => {
              keepTechnique(at) && at.policyTypes.isBase && (at.directives.length > 0 || at.techniques.values.exists {
                _.deprecrationInfo.isEmpty
              }) && at.techniques.exists(_._2.policyTypes.isBase)
            })
            // We want our technique sorty by human name, default to bundle name in case we don't have any version but that should not happen
            .sortBy(at => at.newestAvailableTechnique.map(_.name).getOrElse(at.techniqueName.value))
            .map(at => displayActiveTechnique(at, onClickTechnique, localOnClickDirective))
      )

      override val attrs =
        ("data-jstree" -> """{ "type" : "category" }""") :: ("id" -> nodeId) :: Nil
    }

    /////////////////////////////////////////////////////////////////////////////////////////////
    def displayActiveTechnique(
        activeTechnique:  FullActiveTechnique,
        onClickTechnique: Option[FullActiveTechnique => JsCmd],
        onClickDirective: Option[FullActiveTechnique => Directive => JsCmd]
    ): JsTreeNode = new JsTreeNode {

      private val localOnClickDirective = onClickDirective.map(f => f(activeTechnique))

      def isDeprecated = {
        activeTechnique.techniques.values.forall(t => t.deprecrationInfo.isDefined)
      }

      val agentTypes  = activeTechnique.techniques.values.flatMap(_.agentConfigs).map(_.agentType).toSet
      val agentCompat = AgentCompat(agentTypes)

      override val attrs = (
        ("data-jstree" -> s"""{ "type" : "template" , "state" : { "disabled" : ${!activeTechnique.isEnabled} } }""") :: ("class" -> s"""techniqueNode ${if (
            !activeTechnique.isEnabled
          ) "is-disabled"
          else ""}""") :: Nil
      )

      override def children = {
        activeTechnique.directives
          .sortBy(_.name)
          .collect {
            case x if (keepDirective(x)) =>
              displayDirective(
                x,
                activeTechnique.techniques.get(x.techniqueVersion),
                localOnClickDirective,
                activeTechnique
              )
          }
      }

      override def body = {
        // display information (name, etc) relative to last technique version
        val xml = activeTechnique.newestAvailableTechnique match {
          case Some(technique) =>
            val btnCreateDirective = createDirective match {
              case Some(newDirective) =>
                import net.liftweb.http.js.JsExp.*
                if (userService.getCurrentUser.checkRights(AuthorizationType.Directive.Write)) {
                  <span class="btn btn-success btn-xs create" style="opacity: 0;" onclick={
                    s"""event.preventDefault();event.stopPropagation();${SHtml.ajaxCall(
                        "",
                        _ => newDirective(technique, activeTechnique)
                      )}"""
                  } title="Create directive with latest version">Create <i class="fa fa-plus"></i></span>
                } else NodeSeq.Empty
              case None               => NodeSeq.Empty
            }
            val disabledNotice     = if (!activeTechnique.isEnabled) { <div>This Technique is currently <b>disabled</b>.</div> }
            else { NodeSeq.Empty }
            val tooltipContent     = {
              s"""
                <h4>${technique.name}</h4>
                <div class="tooltip-content">
                  <p>${technique.escapedDescription}</p>
                  ${agentCompat.techniqueText}
                  ${disabledNotice}
              </div>
              """
            }

            val className     = {
              val defaultClass    = "treeActiveTechniqueName"
              val disabledClass   = if (!activeTechnique.isEnabled) { "is-disabled" }
              else { "" }
              val deprecatedClass = if (isDeprecated) { "isDeprecated" }
              else { "" }
              s"${defaultClass} ${disabledClass} ${deprecatedClass}"
            }
            val disabledBadge = if (!activeTechnique.isEnabled) { <span class="badge-disabled"></span> }
            else { NodeSeq.Empty }
            <span class={
              className
            } data-bs-toggle="tooltip" data-bs-placement="top" title={
              tooltipContent
            }>{
              agentCompat.icon
            }{technique.name}</span> ++ disabledBadge ++ btnCreateDirective
          case None            =>
            <span class="error">The technique with id ''{activeTechnique.techniqueName}'' is missing from repository</span>
        }

        onClickTechnique match {
          case None                                       => <a style="cursor:default">{xml}</a>
          case _ if (!activeTechnique.policyTypes.isBase) => <a style="cursor:default">{xml}</a>
          case Some(f)                                    => SHtml.a(() => f(activeTechnique), xml)
        }
      }
    }

    def displayDirective(
        directive:        Directive,
        technique:        Option[Technique],
        onClickDirective: Option[Directive => JsCmd],
        activeTechnique:  FullActiveTechnique
    ): JsTreeNode = new JsTreeNode {

      val isAssignedTo = usedDirectiveIds.find { case (id, _) => id == directive.id.uid }.map(_._2).getOrElse(0)

      val agentTypes  = technique.toList.flatMap(_.agentConfigs.map(_.agentType))
      val agentCompat = AgentCompat(agentTypes)

      override def children: List[JsTreeNode] = Nil

      val classes = {
        val includedClass = if (included.contains(directive.id.uid)) { "included" }
        else ""
        val disabled      = if (directive.isEnabled) "" else "is-disabled"
        s"${disabled} ${includedClass} directiveNode"
      }

      val htmlId = s"jsTree-${directive.id.uid.value}"

      val directiveTags  = JsArray(
        directive.tags.map(tag => JsObj("key" -> tag.name.value, "value" -> Str(tag.value.value))).toList
      )
      override val attrs = (
        ("data-jstree" -> s"""${JsObj("type" -> "directive", "tags" -> directiveTags)}""") ::
          ("id"        -> htmlId) ::
          ("class"     -> classes) ::
          Nil
      )

      override def body = {

        val editButton = {
          if (addEditLink && !directive.isSystem) {
            val tooltipContent = s"""
              <h4>${StringEscapeUtils.escapeHtml4(directive.name)}</h4>
              <div class="tooltip-content">
                <p>Configure this Directive.</p>
              </div>
            """
            <span class="treeActions">
              <span class="treeAction noRight directiveDetails fa fa-pencil"  data-bs-toggle="tooltip" data-bs-placement="top" title={
              tooltipContent
            } onclick={linkUtil.redirectToDirectiveLink(directive.id.uid).toJsCmd}> </span>
            </span>
          } else {
            NodeSeq.Empty
          }
        }

        val directiveTagsListHtml = <div>{
          directive.tags.tags.map(tag => {
            <span class="tags-label"><i class="fa fa-tag"></i> <span class="tag-key">{
              StringEscapeUtils.escapeHtml4(tag.name.value)
            }</span><span class="tag-separator"> = </span><span class="tag-value">{
              StringEscapeUtils.escapeHtml4(tag.value.value)
            }</span></span>
          })
        }</div>
        val tagsTooltipContent    = s"""
          <h4 class='tags-tooltip-title'>Tags <span class='tags-label'><i class='fa fa-tag'></i> ${directive.tags.tags.size}</span></h4>
          <div class='tags-list'>
            ${directiveTagsListHtml}
          </div>
        """
        val directiveTagsTooltip  = {
          if (directive.tags.tags.size > 0) {
            <span class="tags-label"
                  data-bs-toggle="tooltip"
                  data-bs-placement="top"
                  title={tagsTooltipContent}>
              <i class="fa fa-tags"></i>
              <b> {directive.tags.tags.size}</b>
            </span>
          } else {
            NodeSeq.Empty
          }
        }
        val actionBtns            = if (addActionBtns) {
          onClickDirective match {
            case Some(f) =>
              <span class="treeActions">
                <span class="treeAction fa action-icon directive"  data-bs-toggle="tooltip" data-bs-placement="top" title="" onclick={
                f(directive)
              }> </span>
              </span>
            case None    => NodeSeq.Empty
          }
        } else {
          NodeSeq.Empty
        }

        val (_, deprecationInfo, deprecatedIcon) = {
          if (activeTechnique.techniques.values.forall(t => t.deprecrationInfo.isDefined)) {
            val message = <p><b>↳ Deprecated: </b>{
              technique.flatMap(_.deprecrationInfo).map(_.message).getOrElse("this technique is deprecated.")
            }</p>
            (true, message, { <i class="fa fa-times deprecation-icon"></i> })

          } else {
            technique.flatMap(_.deprecrationInfo) match {
              case Some(info) =>
                val message = <p><b>↳ Deprecated: </b>{info.message}</p>
                (true, message, { <i class="ion ion-arrow-up-a deprecation-icon"></i> })

              case None =>
                val newestVersion = activeTechnique.newestAvailableTechnique.get.id.version
                if (newestVersion != directive.techniqueVersion) {
                  val message = <p><b>↳ New version available: </b>This Directive can be updated to version <b>{
                    newestVersion.debugString
                  }</b></p>
                  (false, message, { <i class="ion ion-arrow-up-a deprecation-icon migration"></i> })

                } else {
                  (false, "", { NodeSeq.Empty })
                }
            }
          }
        }

        val xml = {
          val (policyMode, explanation) = {
            (globalMode.overridable, directive.policyMode) match {
              case (Always, Some(mode)) =>
                (
                  mode,
                  "<p>This mode is an override applied to this directive. You can change it in the <i><b>directive's settings</b></i>.</p>"
                )
              case (Always, None)       =>
                val expl =
                  """<p>This mode is the globally defined default. You can change it in <i><b>settings</b></i>.</p><p>You can also override it on this directive in the <i><b>directive's settings</b></i>.</p>"""
                (globalMode.mode, expl)
              case (Unoverridable, _)   =>
                (
                  globalMode.mode,
                  "<p>This mode is the globally defined default. You can change it in <i><b>Settings</b></i>.</p>"
                )
            }
          }

          val tooltipId = Helpers.nextFuncName

          val disableMessage = (activeTechnique.isEnabled, directive.isEnabled) match {
            case (false, false) =>
              <div>This Directive and its Technique are <b>disabled</b>.</div>
            case (false, true)  =>
              <div>The Technique of this Directive is <b>disabled</b>.</div>
            case (true, false)  =>
              <div>This Directive is <b>disabled</b>.</div>
            case (true, true)   =>
              NodeSeq.Empty
          }

          val assignWarning  = if (isAssignedTo == 0) {
            <span class="fa fa-warning text-warning-rudder min-size-icon"></span>
          } else {
            NodeSeq.Empty
          }
          val tooltipContent = s"""
            <h4>${StringEscapeUtils.escapeHtml4(directive.name)}</h4>
            <div class="tooltip-content directive">
              <p>${StringEscapeUtils.escapeHtml4(directive.shortDescription)}</p>
              <div>
                <b>Technique version:</b>
                ${directive.techniqueVersion.debugString}${deprecatedIcon}
                ${deprecationInfo}
              </div>
              ${assignWarning}
              <span>Used in <b>${isAssignedTo}</b> rule${if (isAssignedTo != 1) { "s" }
            else { "" }}</span>
              ${agentCompat.directiveText}
              ${disableMessage}
              <span class="d-none"> ${activeTechnique.techniqueName} </span>
            </div>

          """

          <span id={"badge-apm-" + tooltipId}>[BADGE]</span> ++
          <span class="treeDirective" data-bs-toggle="tooltip" data-bs-placement="top" title={
            tooltipContent
          }>
            <span class="techversion">

              {directive.techniqueVersion.debugString}
              {deprecatedIcon}
            </span>
            <span class="item-name">{directive.name}</span>
            {
            if (directive.isEnabled) {
              NodeSeq.Empty
            } else {
              <span class="badge-disabled"></span>
            }
          }
            {
            if (isAssignedTo <= 0) {
              <span class="fa fa-warning text-warning-rudder min-size-icon"></span>
            } else {
              NodeSeq.Empty
            }
          }
          </span> ++ { directiveTagsTooltip } ++
          <div class="treeActions-container"> {actionBtns} {editButton} </div> ++
          WithNonce.scriptWithNonce(
            Script(
              JsRaw(
                s"""$$('#badge-apm-${tooltipId}').replaceWith(createBadgeAgentPolicyMode('directive',"${policyMode}", "${explanation
                    .toString()}"));"""
              ) // JsRaw ok, const
            )
          )
        }

        (onClickDirective, addActionBtns) match {
          case (_, true)                      => <a style="cursor:default">{xml}</a>
          case (None, _)                      => <a style="cursor:default">{xml}</a>
          case (_, _) if (directive.isSystem) => <a style="cursor:default">{xml}</a>
          case (Some(f), _)                   => SHtml.a(() => f(directive), xml)
        }
      }
    }
    directiveLib.subCategories.filterNot(_.isSystem).sortBy(_.name).flatMap(cat => displayCategory(cat, cat.id.value).toXml)
  }
}
