package com.normation.rudder.ncf.eventlogs

import com.normation.inventory.domain.Version
import com.normation.rudder.domain.Constants.XML_TAG_EDITOR_TECHNIQUE
import com.normation.rudder.domain.policies.{PolicyMode, SimpleDiff}
import com.normation.rudder.ncf.{Constraints, EditorTechnique, MethodBlock, MethodCall, MethodElem, TechniqueParameter}
import com.normation.rudder.services.marshalling.MarshallingUtil.createTrimedElem
import zio.json.*

import scala.xml.{Elem, NodeSeq, Null, Text, UnprefixedAttribute}


/**
 * That trait allows to serialise
 * techniques to an XML file.
 */
trait EditorTechniqueXmlSerialisation {
  def serialise(
                 editorTechnique:           EditorTechnique
               ): Elem
  def serialiseDiff(
                 modifyDiff:           ModifyEditorTechniqueDiff
               ): Elem
}

/**
 * That trait allows to serialise
 * EditorTechniques an XML file.
 */
class EditorTechniqueXmlSerialisationImpl(xmlVersion: String) extends EditorTechniqueXmlSerialisation {

  def serialise(
                 editorTechnique:           EditorTechnique
               ): Elem = {
    createTrimedElem(XML_TAG_EDITOR_TECHNIQUE, xmlVersion)(
      <id>{editorTechnique.id.value}</id>
        :: <displayName>{editorTechnique.name}</displayName>
        :: <version>{editorTechnique.version.value}</version>
        :: <description>{editorTechnique.description}</description>
        :: <documentation>{editorTechnique.documentation}</documentation>
        :: <category>{editorTechnique.category}</category>
        :: <parameters>{editorTechnique.parameters.map(serializeParameter)}</parameters>
        :: <resources>{editorTechnique.resources.map(resource => <resource><path>${resource.path}</path><state>${resource.state.value}</state></resource>)}</resources>
        :: <tags>{editorTechnique.tags.map(tag => <tag><key>{tag._1}</key><value>${tag._2.toJsonPretty}</value></tag>)}</tags>
        :: Nil
    )
  }
  
  def serializeParameter(parameter : TechniqueParameter): Elem  = {
    createTrimedElem("parameter", xmlVersion)(
      <id>${parameter.id.value}</id>
        :: <name>${parameter.name}</name>
        :: <mayBeEmpty>${parameter.mayBeEmpty}</mayBeEmpty>
      :: Nil :::
        parameter.description.map(d => <description>{d}</description>).toList :::
        parameter.documentation.map(d => <documentation>{d}</documentation>).toList:::
        parameter.constraints.map(d => <constraints>{serializeConstraint(d)}</constraints>).toList
    )
  }

  def serializeConstraint(constraints: Constraints) : NodeSeq = {
    createTrimedElem("constraints", xmlVersion)(
      constraints.allowEmpty.map(d => <allowEmpty>
          {d}
        </allowEmpty>).toList :::
        constraints.allowWhiteSpace.map(d => <allowWhiteSpace>
          {d}
        </allowWhiteSpace>).toList :::
        constraints.minLength.map(d => <minLength>
          {d}
        </minLength>).toList :::
        constraints.maxLength.map(d => <maxLength>
          {d}
        </maxLength>).toList :::
        constraints.regex.map(d => <regex>
          {d}
        </regex>).toList :::
        constraints.notRegex.map(d => <notRegex>
          {d}
        </notRegex>).toList :::
        constraints.select.map(d => <select>
          {d.map{ s => <option><value>{s.value}</value><name>${s.name.getOrElse(s.value)}</name></option>}}
        </select>).toList
    )
  }
  def serializeMethodElem(methodElem: MethodElem) : NodeSeq = {
    methodElem match {
      case block : MethodBlock =>
        createTrimedElem("block", xmlVersion)(
          <id>{block.id}</id>
            :: <component>{block.component}</component>
            :: <condition>{block.condition}</condition>
            :: <reportingLogic>{block.reportingLogic.value}</reportingLogic>
            :: <policyMode>{block.policyMode.map(_.name).getOrElse(PolicyMode.defaultValue)}</policyMode>
            :: <calls>{block.calls.map(serializeMethodElem)}</calls>
            :: Nil  :::
            block.foreachName.map(fn => <foreachName>{fn}</foreachName> ).toList :::
            block.foreach.map(fn => <foreach>{fn.map(iterator =>
              <iterator>{iterator.map(iteration =>
                <iteration>
                  <key>{iteration._1}</key>
                  <value>{iteration._2}</value>
                </iteration>)}
              </iterator>)}
            </foreach> ).toList
        )
      case call : MethodCall =>
        createTrimedElem("call", xmlVersion)(
          <id>{call.id}</id>
            :: <method>{call.method.value}</method>
            :: <component>{call.component}</component>
            :: <condition>{call.condition}</condition>
            :: <disabledReporting>{call.disabledReporting}</disabledReporting>
            :: <policyMode>{call.policyMode.map(_.name).getOrElse(PolicyMode.defaultValue)}</policyMode>
            :: <parameters>{call.parameters.map(parameter => <parameter><id>{parameter._1.value}</id><value>{parameter._2}</value></parameter>)}</parameters>
          :: Nil  :::
            call.foreachName.map(fn => <foreachName>{fn}</foreachName> ).toList :::
            call.foreach.map(fn => <foreach>{fn.map(iterator =>
                                     <iterator>{iterator.map(iteration =>
                                       <iteration>
                                         <key>{iteration._1}</key>
                                         <value>{iteration._2}</value>
                                       </iteration>)}
                                     </iterator>)}
                                  </foreach> ).toList
        )
    }
  }


  def serialiseDiff(
                 modifyDiff: ModifyEditorTechniqueDiff
               ): Elem = {
    createTrimedElem(XML_TAG_EDITOR_TECHNIQUE, xmlVersion, "modify")(
      <id>
          {modifyDiff.techniqueId.value}
        </id> ::
        <previousName>
          {modifyDiff.name}
        </previousName> ::
          Nil ++
          modifyDiff.modName.map(x => SimpleDiff.stringToXml(<name/>, x)).getOrElse(NodeSeq.Empty) ++
        modifyDiff.modVersion.map(x =>
          SimpleDiff.toXml[Version](<version/>, x)(v => Text(v.value))
        ).getOrElse(NodeSeq.Empty) ++
        modifyDiff.modParameters.map(x => <parameters>{x.map(serialiseParameterDiff)}</parameters>).getOrElse(NodeSeq.Empty) ++
        modifyDiff.modDescription.map(x => SimpleDiff.stringToXml(<description/>, x)).getOrElse(NodeSeq.Empty) ++
            modifyDiff.modDocumentation.map(x => SimpleDiff.stringToXml(<documentation/>, x)).getOrElse(NodeSeq.Empty) ++
            modifyDiff.modCategory.map(x => SimpleDiff.stringToXml(<category/>, x)).getOrElse(NodeSeq.Empty) ++
            modifyDiff.modResources.map(x => SimpleDiff.toXml(<resources/>,x)(
              resources => resources.map(
                resource => <resource>
                              <path>{resource.path}</path>
                              <state>{resource.state.value}</state>
                            </resource> 
               ))).getOrElse(NodeSeq.Empty) ++
            modifyDiff.modTags.map(x => SimpleDiff.toXml(<tags/>,x)(
              resources => resources.toList.map(
                resource => <tag>
                              <name>{resource._1}</name>
                              <value>{resource._2.toJsonPretty}</value>
                            </tag>
              ))).getOrElse(NodeSeq.Empty)
    )
  }
  def serialiseParameterDiff(
                     diff: TechniqueParameterDiff
                   ): Elem = {
    diff match {
      case AddTechniqueParameterDiff(param) =>
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
    createTrimedElem("parameter", xmlVersion)(
      modifyDiff.modAllowEmpty.map(x => SimpleDiff.optionToXml(<allowEmpty/>, x)(b => Text(b.toString))).getOrElse(NodeSeq.Empty) ++
        modifyDiff.modAllowWhiteSpace.map(x => SimpleDiff.optionToXml(<allowWhiteSpace/>, x)(b => Text(b.toString))).getOrElse(NodeSeq.Empty) ++
          modifyDiff.modRegex.map(x => SimpleDiff.optionToXml(<regex/>, x)(Text(_))).getOrElse(NodeSeq.Empty) ++
          modifyDiff.modNotRegex.map(x => SimpleDiff.optionToXml(<notRegex/>, x)(Text(_))).getOrElse(NodeSeq.Empty) ++
          modifyDiff.modMaxLength.map(x => SimpleDiff.optionToXml(<maxLength/>, x)(b => Text(b.toString))).getOrElse(NodeSeq.Empty) ++
        modifyDiff.modMinLength.map(x => SimpleDiff.optionToXml(<minLength/>, x)(b => Text(b.toString))).getOrElse(NodeSeq.Empty) ++
        modifyDiff.modSelect.map(x => SimpleDiff.optionToXml(<select/>, x)(options => options.map(opt => <option><name>{opt.name.getOrElse("")}</name><value>{opt.value}</value></option>)) ).getOrElse(NodeSeq.Empty)
    )
  }
}


