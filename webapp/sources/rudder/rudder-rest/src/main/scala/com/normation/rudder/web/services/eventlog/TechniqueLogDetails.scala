package com.normation.rudder.web.services.eventlog

import com.normation.rudder.domain.policies.PolicyMode
import com.normation.rudder.domain.policies.SimpleDiff
import com.normation.rudder.ncf.*
import com.normation.rudder.ncf.eventlogs.*
import scala.xml.NodeSeq
import scala.xml.Text
import zio.json.*
import zio.json.ast.Json

object TechniqueLogDetails {

  def editorTechniqueDetails(technique: EditorTechnique): NodeSeq = {
    <div>
      <h5>Technique overview:</h5>
      <ul class="evlogviewpad">
        <li><b>ID:&nbsp;</b>{technique.id.value}</li>
        <li><b>Version:&nbsp;</b>{technique.version.value}</li>
        <li><b>Name:&nbsp;</b>{technique.name}</li>
        <li><b>Description:&nbsp;</b>{technique.description}</li>
        <li><b>Documentation:&nbsp;</b>{technique.documentation}</li>
        <li><b>Category:&nbsp;</b>{technique.category}</li>
        <li><b>Tags:&nbsp;</b>{techniqueTagsDetails(technique.tags)}</li>
        <li><b>Parameters:&nbsp;</b>{technique.parameters.map(techniqueParameterDetails)}</li>
        <li><b>Resources:&nbsp;</b>{technique.resources.map(techniqueResourceDetails)}</li>
        <li><b>Content:&nbsp;</b>{methodElemDetails(technique.calls)}
        </li>
      </ul>
    </div>
  }
  private def techniqueResourceDetails(resource: ResourceFile)         = {
    <ul>
      <li><b>Path:&nbsp;</b>{resource.path}</li>
      <li><b>State:&nbsp;</b>{resource.state.value}</li>
    </ul>
  }
  private def techniqueTagsDetails(tags: Map[String, Json])            = {
    tags.map { tag =>
      <ul>
        <li><b>Key:&nbsp;</b>{tag._1}</li>
        <li><b>Value:&nbsp;</b>{tag._2.toJsonPretty}</li>
      </ul>
    }
  }
  private def techniqueParameterDetails(parameter: TechniqueParameter) = {
    <ul class="ms-3 evlogviewpad">
      <li><b>ID:&nbsp;</b>{parameter.id.value}</li>
      <li><b>Name:&nbsp;</b>{parameter.name}</li>
      <li><b>Description:&nbsp;</b>{parameter.description.getOrElse("")}</li>
      <li><b>Documentation:&nbsp;</b>{parameter.documentation.getOrElse("")}</li>
      <li><b>May be empty:&nbsp;</b>{parameter.mayBeEmpty}</li>
      <li><b>Constraints:&nbsp;</b>{parameter.constraints.map(constraintsDetails).getOrElse(Text(""))}</li>
    </ul>
  }
  private def methodElemDetails(calls: Seq[MethodElem])                = {
    calls.map { call =>
      <li>
          {
        call match {
          case call: MethodCall  => callDetails(call)
          case b:    MethodBlock => blockDetails(b)
        }
      }
        </li>
    }
  }
  private def constraintAllowEmpty(allow: Boolean)                     = {
    <li>{if (allow) "Allow" else "Don't allow"} empty value</li>
  }
  private def constraintAllowWhitespace(allow: Boolean)                = {
    <li>{if (allow) "Allow" else "Don't allow"} trailling/leading whitespaces</li>
  }
  private def constraintMinLength(min: Int)                            = {
    <li>Must be at least {min} character{if (min == 1) "" else "s"} long</li>
  }
  private def constraintMaxLength(max: Int)                            = {
    <li>Must be at least {max} character{if (max == 1) "" else "s"} long</li>
  }
  private def constraintRegex(regex: String)                           = {
    <li>Must validate the following regex: {regex}</li>
  }
  private def constraintNotRegex(regex: String)                        = {
    <li>Must not validate the following regex: {regex}</li>
  }
  private def constraintSelect(select: List[SelectOption])             = {
    <li>Must be one of the following values:
      <ul class="ms-3">
        {
      select.map(option => <li>
          {option.value} {option.name.map(n => s"(name: ${n})").getOrElse("")}
        </li>)
    }
      </ul>
    </li>
  }
  private def constraintsDetails(constraints: Constraints)             = {
    <ul>
      {constraints.allowEmpty.map(constraintAllowEmpty).getOrElse(NodeSeq.Empty)}
      {constraints.allowWhiteSpace.map(constraintAllowWhitespace).getOrElse(NodeSeq.Empty)}
      {constraints.minLength.map(constraintMinLength).getOrElse(NodeSeq.Empty)}
      {constraints.maxLength.map(constraintMaxLength).getOrElse(NodeSeq.Empty)}
      {constraints.regex.map(constraintRegex).getOrElse(NodeSeq.Empty)}
      {constraints.notRegex.map(constraintNotRegex).getOrElse(NodeSeq.Empty)}
      {constraints.select.map(constraintSelect).getOrElse(NodeSeq.Empty)}
    </ul>
  }
  private def blockDetails(block: MethodBlock): NodeSeq = {
    <div>
      <ul class="ms-3 evlogviewpad">
        <li><b>ID:&nbsp;</b>{block.id}</li>
        <li><b>Component:&nbsp;</b>{block.component}</li>
        <li><b>Condition:&nbsp;</b>{block.condition}</li>
        <li><b>Reporting:&nbsp;</b>{block.reportingLogic.value}</li>
        <li><b>Policy mode:&nbsp;</b>{block.policyMode.map(_.name).getOrElse(PolicyMode.defaultValue)}</li>
        <li><b>Loop:&nbsp;</b>{block.foreach.map(f => foreachdetails(f, block.foreachName)).getOrElse(Text(""))}</li>
        <li><b>Children:</b>{methodElemDetails(block.calls)}</li>
      </ul>
    </div>
  }

  private def iteratorDetails(iterator: Map[String, String])                                  = {
    <li>{
      iterator.map(iteration => <ul>
        <li><b>Key:&nbsp;</b>{iteration._1}</li>
        <li><b>Value:&nbsp;</b>{iteration._2}</li>
      </ul>)
    }
    </li>
  }
  private def foreachdetails(foreach: List[Map[String, String]], foreachName: Option[String]) = {
    <ul class="ms-3">
      <li><b>Iterator name</b>:&nbsp;{foreachName.getOrElse("item")}</li>
      {foreach.map(iteratorDetails)}
    </ul>
  }
  private def callParametersDetails(parameterId: ParameterId, value: String)                  = {
    <ul class="ms-3 ">
      <li><b>Parameter:&nbsp;</b>{parameterId.value}</li>
      <li><b>Value:&nbsp;</b>{value}</li>
    </ul>
  }
  private def callDetails(call: MethodCall)                                                   = {
    <div>
      <ul class="ms-3 evlogviewpad">
        <li><b>ID:&nbsp;</b>{call.id}</li>
        <li><b>Method:&nbsp;</b>{call.method.value}</li>
        <li><b>Component:&nbsp;</b>{call.component}</li>
        <li><b>Condition:&nbsp;</b>{call.condition}</li>
        <li><b>Reporting disabled:&nbsp;</b>{call.disabledReporting}</li>
        <li><b>Policy mode:&nbsp;</b>{call.policyMode.map(_.name).getOrElse(PolicyMode.defaultValue)}</li>
        <li><b>Loop:&nbsp;</b>{call.foreach.map(f => foreachdetails(f, call.foreachName)).getOrElse(Text(""))}</li>
        <li><b>Parameters:&nbsp;</b>{call.parameters.map(callParametersDetails.tupled)}</li>
      </ul>
    </div>
  }

  import com.normation.rudder.web.services.DiffDisplayer.*
  def techniqueModDetails(diff: ModifyEditorTechniqueDiff) = {
    <div class="evloglmargin">
    <h5>Technique overview:</h5>
    <ul class="evlogviewpad">
    <li>
    <b>ID: </b>{diff.techniqueId.value}
    </li>
    <li>
    <b>Name: </b>{diff.modName.map(diff => diff.newValue).getOrElse(diff.name)}
    </li>
      <div>{displaySimpleDiff(diff.modName, "Name").getOrElse(NodeSeq.Empty)}</div>
      <div>{displaySimpleDiff(diff.modDescription, "Description").getOrElse(NodeSeq.Empty)}</div>
      <div>{displaySimpleDiff(diff.modDocumentation, "Documentation").getOrElse(NodeSeq.Empty)}</div>
      <div>{displaySimpleDiff(diff.modCategory, "Category").getOrElse(NodeSeq.Empty)}</div>
      <div>{mapComplexDiff(diff.modTags, "Tags")(tagModDetails)}</div>
      <div>{mapComplexDiff(diff.modResources, "Resources")(resourceModDetails)}</div>
      <div>{mapComplexDiff(diff.modResources, "Resources")(resourceModDetails)}</div>
      <div>{parametersDiffDetails(diff.modParameters)}</div>
      <div>{methodElemDiffDetails(diff.modCalls)}</div>
        </ul>
    </div>
  }

  private def tagModDetails(tags: Map[String, Json])           = {
    <ul class="ms-3">
      {tags.toList.sortBy(_._1).map { case (k, v) => <li><ul><li>{k}</li><li>{v.toJsonPretty}</li></ul></li> }}
    </ul>
  }
  private def resourceModDetails(resources: Seq[ResourceFile]) = {
    <ul class="ms-3">
      {resources.sortBy(_.path).map(resource => <li><ul><li>{resource.path}</li><li>{resource.state.value}</li></ul></li>)}
    </ul>
  }

  def parameterModDetails(techniqueParameterDiff: TechniqueParameterDiff): NodeSeq = {
    techniqueParameterDiff match {
      case AddTechniqueParameterDiff(techniqueParameter)    => <li class="added">{techniqueParameterDetails(techniqueParameter)}</li>
      case DeleteTechniqueParameterDiff(techniqueParameter) =>
        <li class="deleted">{techniqueParameterDetails(techniqueParameter)}</li>
      case diff: ModifyTechniqueParameterDiff =>
        if (diff.isEmpty) {
          NodeSeq.Empty
        } else {
          <li>
          <ul class="ms-3">
          <li>
            <b>ID: </b>{diff.id.value}
          </li>
          <li>
            <b>Name: </b>{diff.modName.map(diff => diff.newValue).getOrElse(diff.name)}
          </li>
          <div>{displaySimpleDiff(diff.modName, "Name").getOrElse(NodeSeq.Empty)}</div>
          <div>{displaySimpleDiffT(diff.modDescription, "Description")(_.getOrElse(""))}</div>
          <div>{displaySimpleDiffT(diff.modDocumentation, "Documentation")(_.getOrElse(""))}</div>
          <div>{displaySimpleDiffT(diff.modMayBeEmpty, "May be empty")(_.toString)}</div>
          {"" /*<li><b>Constraint:&nbsp;</b>{methodElemDetails(technique.calls)}</li>*/}
        </ul>
          </li>
        }
    }
  }

  def methodElemModDetails(elemDiff: TechniqueCallDiff | TechniqueBlockDiff): NodeSeq = {
    elemDiff match {
      case AddTechniqueCallDiff(call)      => <li class="added">{callDetails(call)}</li>
      case DeleteTechniqueCallDiff(call)   => <li class="deleted">{callDetails(call)}</li>
      case AddTechniqueBlockDiff(block)    => <li class="added">{blockDetails(block)}</li>
      case DeleteTechniqueBlockDiff(block) => <li class="deleted">{blockDetails(block)}</li>
      case diff: ModifyTechniqueCallDiff  =>
        if (diff.isEmpty) {
          NodeSeq.Empty
        } else {
          <li><ul class="ms-3">
          <li>
            <b>Call ID: </b>{diff.id}
          </li>
          <li>
            <b>Name: </b>{diff.modComponent.map(diff => diff.newValue).getOrElse(diff.component)}
          </li>
          <div>{displaySimpleDiff(diff.modComponent, "Name").getOrElse(NodeSeq.Empty)}</div>
          <div>{displaySimpleDiff(diff.modCondition, "Condition").getOrElse(NodeSeq.Empty)}</div>
          <div>{displaySimpleDiffT(diff.modPolicyMode, "PolicyMode")(_.map(_.name).getOrElse(PolicyMode.defaultValue))}</div>
          <div>{displaySimpleDiffT(diff.modDisableReporting, "Reporting disabled")(_.toString)}</div>
          <div>{
            mapComplexDiff(diff.modForeach, "Loop")(
              _.map(foreachdetails(_, diff.modForeachName.flatMap(_.oldValue))).getOrElse(NodeSeq.Empty)
            )
          }</div>
          <div>{callParameterDetails(diff.modParameters)}</div>

        </ul>
        </li>
        }
      case diff: ModifyTechniqueBlockDiff =>
        if (diff.isEmpty) {
          NodeSeq.Empty
        } else {
          <li><ul class="ms-3">
          <li>
            <b>Block ID: </b>{diff.id}
          </li>
          <li>
            <b>Name: </b>{diff.modComponent.map(diff => diff.newValue).getOrElse(diff.component)}
          </li>
          <div>{displaySimpleDiff(diff.modComponent, "Name").getOrElse(NodeSeq.Empty)}</div>
          <div>{displaySimpleDiff(diff.modCondition, "Condition").getOrElse(NodeSeq.Empty)}</div>
          <div>{displaySimpleDiffT(diff.modPolicyMode, "PolicyMode")(_.map(_.name).getOrElse(PolicyMode.defaultValue))}</div>
          <div>{displaySimpleDiffT(diff.modReportingLogic, "Reporting")(_.value)}</div>
          <div>{
            mapComplexDiff(diff.modForeach, "Loop")(
              _.map(foreachdetails(_, diff.modForeachName.flatMap(_.oldValue))).getOrElse(NodeSeq.Empty)
            )
          }</div>
          <div>{methodElemDiffDetails(diff.modCalls)}</div>

        </ul></li>
        }
    }
  }

  private def callParameterDetails(params: Map[ParameterId, Option[SimpleDiff[String]]]) = {
    if (params.isEmpty) {
      NodeSeq.Empty
    } else {
      <b>Parameters:</b> ++ <ul class="ms-3">
        {
        params.flatMap { param =>
          param._2.map(diff => <li><b>Parameter:&nbsp;</b>{param._1.value}</li>
              <li>{displaySimpleDiff(Some(diff), "Value").getOrElse(Text(""))}</li>)
        }
      }
      </ul>
    }

  }

  private def parametersDiffDetails(params: Seq[TechniqueParameterDiff]) = {
    if (params.isEmpty) { NodeSeq.Empty }
    else {
      <b>Parameters:</b> ++ <ul class="ms-3">
        {
        params.map(param => { parameterModDetails(param) })
      }</ul>
    }

  }

  private def methodElemDiffDetails(calls: Seq[TechniqueCallDiff | TechniqueBlockDiff]) = {
    if (calls.isEmpty) { NodeSeq.Empty }
    else {
      <b>Calls:</b> ++ <ul class="ms-3">
      {
        calls.map(call => methodElemModDetails(call))
      }</ul>
    }

  }
}
