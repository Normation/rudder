package com.normation.rudder.ncf.eventlogs

import com.normation.rudder.domain.Constants.XML_TAG_EDITOR_TECHNIQUE
import com.normation.rudder.domain.policies.PolicyMode
import com.normation.rudder.domain.policies.SimpleDiff
import com.normation.rudder.ncf.Constraints
import com.normation.rudder.ncf.EditorTechnique
import com.normation.rudder.ncf.MethodBlock
import com.normation.rudder.ncf.MethodCall
import com.normation.rudder.ncf.MethodElem
import com.normation.rudder.ncf.ParameterId
import com.normation.rudder.ncf.ResourceFile
import com.normation.rudder.ncf.SelectOption
import com.normation.rudder.ncf.TechniqueParameter
import com.normation.rudder.services.marshalling.MarshallingUtil.createTrimedElem
import scala.xml.Elem
import scala.xml.NodeSeq
import scala.xml.Null
import scala.xml.Text
import scala.xml.UnprefixedAttribute
import zio.json.*
import zio.json.ast.Json

/**
 * That trait allows to serialise
 * techniques to an XML file.
 */
trait EditorTechniqueXmlSerialisation {
  def serialise(
      editorTechnique: EditorTechnique
  ): Elem
  def serialiseDiff(
      modifyDiff: ModifyEditorTechniqueDiff
  ): Elem
}

/**
 * That trait allows to serialise
 * EditorTechniques an XML file.
 */
class EditorTechniqueXmlSerialisationImpl(xmlVersion: String) extends EditorTechniqueXmlSerialisation {

  def serialise(
      editorTechnique: EditorTechnique
  ): Elem = {
    createTrimedElem(XML_TAG_EDITOR_TECHNIQUE, xmlVersion)(
      <id>{editorTechnique.id.value}</id>
      :: <displayName>{editorTechnique.name}</displayName>
      :: <version>{editorTechnique.version.value}</version>
      :: <description>{editorTechnique.description}</description>
      :: <documentation>{editorTechnique.documentation}</documentation>
      :: <category>{editorTechnique.category}</category>
      :: <parameters>{editorTechnique.parameters.map(serializeParameter)}</parameters>
      :: <resources>{editorTechnique.resources.map(serialiseResource)}</resources>
      :: <calls>{editorTechnique.calls.map(serializeMethodElem)}</calls>
      :: <tags>{editorTechnique.tags.map(serialiseTag.tupled)}</tags>
      :: Nil
    )
  }

  def serialiseTag(key: String, value: Json) = {
    <tag>
      <key>
        {key}
      </key>
      <value>
        {value.toJsonPretty}
      </value>
    </tag>
  }

  def serializeParameter(parameter: TechniqueParameter): Elem = {
    createTrimedElem("parameter", xmlVersion)(
      <id>{parameter.id.value}</id>
      :: <name>{parameter.name}</name>
      :: <mayBeEmpty>{parameter.mayBeEmpty}</mayBeEmpty>
      :: Nil :::
      parameter.description.map(d => <description>{d}</description>).toList :::
      parameter.documentation.map(d => <documentation>{d}</documentation>).toList :::
      parameter.constraints.map(d => <constraints>{serializeConstraint(d)}</constraints>).toList
    )
  }

  def serializeConstraint(constraints: Constraints): NodeSeq = {
    createTrimedElem("constraints", xmlVersion)(
      constraints.allowEmpty.map(d => <allowEmpty>{d}</allowEmpty>).toList :::
      constraints.allowWhiteSpace.map(d => <allowWhiteSpace>{d}</allowWhiteSpace>).toList :::
      constraints.minLength.map(d => <minLength>{d}</minLength>).toList :::
      constraints.maxLength.map(d => <maxLength>{d}</maxLength>).toList :::
      constraints.regex.map(d => <regex>{d}</regex>).toList :::
      constraints.notRegex.map(d => <notRegex>{d}</notRegex>).toList :::
      constraints.select.map(d => <select>{d.map(serialiseSelectOption)}</select>).toList
    )
  }

  def serialiseResource(resource: ResourceFile) = {
    <resource>
      <path>
        {resource.path}
      </path>
      <state>
        {resource.state.value}
      </state>
    </resource>
  }

  def serializeForeach(foreach: List[Map[String, String]]): Elem = {
    <foreach>
        {
      foreach.map(iterator => {
        <iterator>
          {
          iterator.map(iteration => <iteration>
          <key>
            {iteration._1}
          </key>
          <value>
            {iteration._2}
          </value>
        </iteration>)
        }
        </iterator>
      })
    }
      </foreach>
  }

  def serialiseSelectOption(selectOption: SelectOption) = {
    <option>
      <name>
        {selectOption.name.getOrElse("")}
      </name>
      <value>
        {selectOption.value}
      </value>
    </option>
  }

  def serializeMethodElem(methodElem: MethodElem): Elem = {
    methodElem match {
      case block: MethodBlock =>
        createTrimedElem("block", xmlVersion)(
          <id>{block.id}</id>
          :: <component>{block.component}</component>
          :: <condition>{block.condition}</condition>
          :: <reportingLogic>{block.reportingLogic.value}</reportingLogic>
          :: <policyMode>{block.policyMode.map(_.name).getOrElse(PolicyMode.defaultValue)}</policyMode>
          :: <calls>{block.calls.map(serializeMethodElem)}</calls>
          :: Nil :::
          block.foreachName.map(fn => <foreachName>{fn}</foreachName>).toList :::
          block.foreach.map(serializeForeach).toList
        )
      case call:  MethodCall  =>
        createTrimedElem("call", xmlVersion)(
          <id>{call.id}</id>
          :: <method>{call.method.value}</method>
          :: <component>{call.component}</component>
          :: <condition>{call.condition}</condition>
          :: <disabledReporting>{call.disabledReporting}</disabledReporting>
          :: <policyMode>{call.policyMode.map(_.name).getOrElse(PolicyMode.defaultValue)}</policyMode>
          :: <parameters>{call.parameters.map(serializeMethodParameter.tupled)}</parameters>
          :: Nil :::
          call.foreachName.map(fn => <foreachName>{fn}</foreachName>).toList :::
          call.foreach.map(serializeForeach).toList
        )
    }
  }

  def serializeMethodParameter(parameterId: ParameterId, value: String): Elem = {
    <parameter>
      <id>
        {parameterId.value}
      </id>
      <value>
        {value}
      </value>
    </parameter>
  }
  def serialiseDiff(
      modifyDiff: ModifyEditorTechniqueDiff
  ): Elem = {
    createTrimedElem(XML_TAG_EDITOR_TECHNIQUE, xmlVersion, "modify")(
      <id>
        {modifyDiff.techniqueId.value}
      </id> :: <version>
        {modifyDiff.version.value}
      </version> ::
      <previousName>
          {modifyDiff.name}
        </previousName> ::
      (if (modifyDiff.modParameters.nonEmpty) {
         <parameters>{modifyDiff.modParameters.map(serialiseParameterDiff)}</parameters> :: Nil
       } else {
         Nil
       }) ++
      (if (modifyDiff.modCalls.nonEmpty) {
         <calls>{modifyDiff.modCalls.map(serialiseMethodElemDiff)}</calls> :: Nil
       } else {
         Nil
       }) ++
      modifyDiff.modName.map(x => SimpleDiff.stringToXml(<name/>, x)).getOrElse(NodeSeq.Empty) ++
      modifyDiff.modDescription.map(x => SimpleDiff.stringToXml(<description/>, x)).getOrElse(NodeSeq.Empty) ++
      modifyDiff.modDocumentation.map(x => SimpleDiff.stringToXml(<documentation/>, x)).getOrElse(NodeSeq.Empty) ++
      modifyDiff.modCategory.map(x => SimpleDiff.stringToXml(<category/>, x)).getOrElse(NodeSeq.Empty) ++
      modifyDiff.modResources
        .map(x => SimpleDiff.toXml(<resources/>, x)(resources => resources.map(serialiseResource)))
        .getOrElse(NodeSeq.Empty) ++
      modifyDiff.modTags.map(x => SimpleDiff.toXml(<tags/>, x)(_.map(serialiseTag.tupled).toList)).getOrElse(NodeSeq.Empty)
    )
  }
  def serialiseParameterDiff(
      diff: TechniqueParameterDiff
  ): Elem = {
    diff match {
      case AddTechniqueParameterDiff(param)    =>
        serializeParameter(param) % new UnprefixedAttribute("changeType", "added", Null)
      case DeleteTechniqueParameterDiff(param) =>
        serializeParameter(param) % new UnprefixedAttribute("changeType", "deleted", Null)

      case modifyDiff: ModifyTechniqueParameterDiff =>
        createTrimedElem("parameter", xmlVersion, "modify")(
          <id>
            {modifyDiff.id.value}
          </id> ::
          <previousName>
              {modifyDiff.name}
            </previousName> ::
          Nil ++
          modifyDiff.modName.map(x => SimpleDiff.stringToXml(<name/>, x)).getOrElse(NodeSeq.Empty) ++
          modifyDiff.modDescription.map(x => SimpleDiff.optionToXml(<description/>, x)(Text(_))).getOrElse(NodeSeq.Empty) ++
          modifyDiff.modDocumentation.map(x => SimpleDiff.optionToXml(<documentation/>, x)(Text(_))).getOrElse(NodeSeq.Empty) ++
          modifyDiff.modMayBeEmpty.map(x => SimpleDiff.booleanToXml(<mayBeEmpty/>, x)).getOrElse(NodeSeq.Empty) ++
          modifyDiff.modConstraints.map(serialiseConstraintsDiff).getOrElse(NodeSeq.Empty)
        )
    }
  }

  def serialiseConstraintsDiff(
      modifyDiff: ModifyConstraintsDiff
  ): Elem = {
    createTrimedElem("constraints", xmlVersion)(
      modifyDiff.modAllowEmpty
        .map(x => SimpleDiff.optionToXml(<allowEmpty/>, x)(b => Text(b.toString)))
        .getOrElse(NodeSeq.Empty) ++
      modifyDiff.modAllowWhiteSpace
        .map(x => SimpleDiff.optionToXml(<allowWhiteSpace/>, x)(b => Text(b.toString)))
        .getOrElse(NodeSeq.Empty) ++
      modifyDiff.modRegex.map(x => SimpleDiff.optionToXml(<regex/>, x)(Text(_))).getOrElse(NodeSeq.Empty) ++
      modifyDiff.modNotRegex.map(x => SimpleDiff.optionToXml(<notRegex/>, x)(Text(_))).getOrElse(NodeSeq.Empty) ++
      modifyDiff.modMaxLength.map(x => SimpleDiff.optionToXml(<maxLength/>, x)(b => Text(b.toString))).getOrElse(NodeSeq.Empty) ++
      modifyDiff.modMinLength.map(x => SimpleDiff.optionToXml(<minLength/>, x)(b => Text(b.toString))).getOrElse(NodeSeq.Empty) ++
      modifyDiff.modSelect
        .map(x => { SimpleDiff.optionToXml(<select/>, x)(options => options.map(serialiseSelectOption)) })
        .getOrElse(NodeSeq.Empty)
    )
  }

  def serialiseMethodElemDiff(diff: TechniqueBlockDiff | TechniqueCallDiff): Elem = {
    diff match {
      case AddTechniqueBlockDiff(param)    =>
        serializeMethodElem(param) % new UnprefixedAttribute("changeType", "added", Null)
      case DeleteTechniqueBlockDiff(param) =>
        serializeMethodElem(param) % new UnprefixedAttribute("changeType", "deleted", Null)
      case AddTechniqueCallDiff(param)     =>
        serializeMethodElem(param) % new UnprefixedAttribute("changeType", "added", Null)
      case DeleteTechniqueCallDiff(param)  =>
        serializeMethodElem(param) % new UnprefixedAttribute("changeType", "deleted", Null)

      case modifyDiff: ModifyTechniqueBlockDiff =>
        createTrimedElem("block", xmlVersion, "modify")(
          <id>
            {modifyDiff.id}
          </id> ::
          <previousName>
              {modifyDiff.component}
            </previousName> ::
          Nil ++
          modifyDiff.modComponent.map(x => SimpleDiff.stringToXml(<component/>, x)).getOrElse(NodeSeq.Empty) ++
          modifyDiff.modCondition.map(x => SimpleDiff.stringToXml(<condition/>, x)).getOrElse(NodeSeq.Empty) ++
          modifyDiff.modReportingLogic
            .map(x => SimpleDiff.toXml(<reportingLogic/>, x)(rl => Text(rl.value)))
            .getOrElse(NodeSeq.Empty) ++
          modifyDiff.modPolicyMode
            .map(x => SimpleDiff.toXml(<policyMode/>, x)(rl => Text(rl.map(_.name).getOrElse(PolicyMode.defaultValue))))
            .getOrElse(NodeSeq.Empty) ++
          (if (modifyDiff.modCalls.isEmpty) NodeSeq.Empty
           else <calls>{modifyDiff.modCalls.map(serialiseMethodElemDiff)}</calls>) ++
          modifyDiff.modForeachName.map(x => SimpleDiff.optionToXml(<foreachName/>, x)(Text(_))).getOrElse(NodeSeq.Empty) ++
          modifyDiff.modForeach.map(x => SimpleDiff.optionToXml(<foreach/>, x)(serializeForeach)).getOrElse(NodeSeq.Empty)
        )
      case modifyDiff: ModifyTechniqueCallDiff  =>
        createTrimedElem("call", xmlVersion, "modify")(
          <id>
            {modifyDiff.id}
          </id> ::
          <previousName>
              {modifyDiff.component}
            </previousName> ::
          <method>
              {modifyDiff.method}
            </method> ::
          Nil ++
          modifyDiff.modComponent.map(x => SimpleDiff.stringToXml(<component/>, x)).getOrElse(NodeSeq.Empty) ++
          modifyDiff.modCondition.map(x => SimpleDiff.stringToXml(<condition/>, x)).getOrElse(NodeSeq.Empty) ++
          modifyDiff.modDisableReporting.map(x => SimpleDiff.booleanToXml(<disableReporting/>, x)).getOrElse(NodeSeq.Empty) ++
          modifyDiff.modPolicyMode
            .map(x => SimpleDiff.toXml(<policyMode/>, x)(rl => Text(rl.map(_.name).getOrElse(PolicyMode.defaultValue))))
            .getOrElse(NodeSeq.Empty) ++
          (if (modifyDiff.modParameters.isEmpty) NodeSeq.Empty
           else
             <parameters>{
               modifyDiff.modParameters.flatMap {
                 case (pid, optDiff) =>
                   optDiff.map(diff => <parameter>
                    <id>{pid.value}</id>
                    {SimpleDiff.stringToXml(<value/>, diff)}
                  </parameter>)
               }
             }</parameters>) ++
          modifyDiff.modForeachName.map(x => SimpleDiff.optionToXml(<foreachName/>, x)(Text(_))).getOrElse(NodeSeq.Empty) ++
          modifyDiff.modForeach.map(x => SimpleDiff.optionToXml(<foreach/>, x)(serializeForeach)).getOrElse(NodeSeq.Empty)
        )
    }
  }

}
