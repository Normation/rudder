package com.normation.rudder.ncf.eventlogs

import cats.implicits.*
import com.normation.box.PureResultToBox
import com.normation.cfclerk.domain.ReportingLogic
import com.normation.errors
import com.normation.errors.AccumulateErrors
import com.normation.errors.BoxToEither
import com.normation.errors.Chained
import com.normation.errors.PureResult
import com.normation.inventory.domain.InventoryError.Inconsistency
import com.normation.inventory.domain.Version
import com.normation.rudder.domain.Constants.XML_TAG_EDITOR_TECHNIQUE
import com.normation.rudder.domain.policies.PolicyMode
import com.normation.rudder.ncf.BundleName
import com.normation.rudder.ncf.Constraints
import com.normation.rudder.ncf.EditorTechnique
import com.normation.rudder.ncf.MethodBlock
import com.normation.rudder.ncf.MethodCall
import com.normation.rudder.ncf.MethodElem
import com.normation.rudder.ncf.ParameterId
import com.normation.rudder.ncf.ResourceFile
import com.normation.rudder.ncf.ResourceFileState
import com.normation.rudder.ncf.SelectOption
import com.normation.rudder.ncf.TechniqueParameter
import com.normation.rudder.services.marshalling.TestFileFormat
import com.normation.rudder.services.marshalling.TestFileFormat.check
import com.normation.rudder.services.marshalling.XmlUtils
import net.liftweb.common.*
import net.liftweb.common.Box.tryo
import scala.xml.Node
import zio.json.*
import zio.json.ast.Json

/**
 * That trait allow to unserialise
 * an editor technique from an XML file.
 */
trait EditorTechniqueXmlUnserialisation {

  def unserialise(xml:       Node): PureResult[EditorTechnique]
  def unserialiseDiff(entry: Node): Box[ModifyEditorTechniqueDiff]

}

class EditorTechniqueXmlUnserialisationImpl extends EditorTechniqueXmlUnserialisation {
  import XmlUtils.*

  def unserialiseForeachIteration(entry: Node): PureResult[(String, String)] = {
    implicit val entryType: XmlEntryType = XmlEntryType("iteration")
    for {
      key   <- getAndTransformChild(entry, "key", _.text.trim)
      value <- getAndTransformChild(entry, "value", _.text)
    } yield {
      (key, value)
    }
  }

  def unserialiseForeach(entry: Node): PureResult[List[Map[String, String]]] = {
    (entry \ "iterator").accumulatePure(it => (it \ "iteration").map(unserialiseForeachIteration).accumulateEither.map(_.toMap))
  }

  def unserialiseCallParameters(entry: Node): PureResult[(ParameterId, String)] = {
    implicit val entryType: XmlEntryType = XmlEntryType("parameter")

    for {
      key   <- getAndTransformChild(entry, "id", _.text.trim).map(ParameterId(_))
      value <- getAndTransformChild(entry, "value", _.text)
    } yield {
      (key, value)
    }
  }

  def unserialiseBlock(entry: Node):      PureResult[MethodBlock] = {
    implicit val entryType: XmlEntryType = XmlEntryType("block")
    for {
      id             <- getAndTransformChild(entry, "id", _.text)
      component      <- getAndTransformChild(entry, "component", _.text.trim)
      condition      <- getAndTransformChild(entry, "condition", _.text)
      reportingLogic <- getAndTransformChild(entry, "reportingLogic", _.text).flatMap(ReportingLogic.parse(_))
      policyMode     <- getAndTransformChild(entry, "policyMode", _.text).flatMap(PolicyMode.parseDefault)
      calls          <- (entry \ "calls").flatMap(_.child).accumulatePure { t =>
                          unserialiseMethodElem(t).leftMap(err => Chained(s"Invalid attribute in 'block' entry: ${entry}", err))
                        }

      foreach <- (entry \ "foreach")
                   .accumulatePure(f => {
                     (f \ "iterator").accumulatePure(it =>
                       (it \ "iteration").map(unserialiseForeachIteration).accumulateEither.map(_.toMap)
                     )
                   })
                   .map(_.headOption)

      foreachName = (entry \ "foreachName").headOption.map(_.text)

    } yield {
      MethodBlock(
        id,
        component,
        reportingLogic,
        condition,
        calls,
        policyMode,
        foreach,
        foreachName
      )
    }
  }
  def unserialiseMethodCall(entry: Node): PureResult[MethodCall]  = {
    implicit val entryType: XmlEntryType = XmlEntryType("call")
    for {
      id                <- getAndTransformChild(entry, "id", _.text)
      method            <- getAndTransformChild(entry, "method", _.text).map(BundleName(_))
      component         <- getAndTransformChild(entry, "component", _.text.trim)
      condition         <- getAndTransformChild(entry, "condition", _.text)
      disabledReporting <- getAndTransformChild(entry, "disabledReporting", _.text.toBoolean)
      policyMode        <- getAndTransformChild(entry, "policyMode", _.text).flatMap(PolicyMode.parseDefault)
      parameters        <- (entry \ "parameters" \ "parameter").accumulatePure(unserialiseCallParameters).map(_.toMap)
      foreach           <- (entry \ "foreach").accumulatePure(unserialiseForeach).map(_.headOption)
      foreachName        = (entry \ "foreachName").headOption.map(_.text)
    } yield {
      MethodCall(
        method,
        id,
        parameters,
        condition,
        component,
        disabledReporting,
        policyMode,
        foreach,
        foreachName
      )
    }
  }
  def unserialiseMethodElem(entry: Node): PureResult[MethodElem]  = {
    entry.label match {
      case "block" => unserialiseBlock(entry)

      case "call" => unserialiseMethodCall(entry)

      case other => Left(Inconsistency(s"Invalid entry type '${other}' for method elem in techniqu entry, entry is ${entry}"))
    }
  }

  def unserialiseConstraints(entry: Node): Constraints = {
    val allowEmpty      = (entry \ "allowEmpty").headOption.map(_.text.toBoolean)
    val allowWhiteSpace = (entry \ "allowWhiteSpace").headOption.map(_.text.toBoolean)
    val minLength       = (entry \ "minLength").headOption.map(_.text.toInt)
    val maxLength       = (entry \ "maxLength").headOption.map(_.text.toInt)
    val regex           = (entry \ "regex").headOption.map(_.text)
    val notRegex        = (entry \ "notRegex").headOption.map(_.text)
    val select          = (entry \ "select").headOption.map(unserialiseSelectOption)
    Constraints(allowEmpty, allowWhiteSpace, minLength, maxLength, regex, notRegex, select)
  }

  def unserialiseSelectOption(entry: Node):       List[SelectOption]             = {
    (entry \ "option").toList.map { o =>
      SelectOption((o \ "value").headOption.map(_.text).getOrElse(""), (o \ "name").headOption.map(_.text))
    }
  }
  def unserialiseTechniqueParameter(entry: Node): PureResult[TechniqueParameter] = {
    implicit val entryType: XmlEntryType = XmlEntryType("parameter")
    for {
      id           <- getAndTransformChild(entry, "id", _.text).map(ParameterId(_))
      name         <- getAndTransformChild(entry, "name", _.text.trim)
      mayBeEmpty   <- getAndTransformChild(entry, "mayBeEmpty", _.text.toBoolean)
      description   = (entry \ "description").headOption.map(_.text)
      documentation = (entry \ "documentation").headOption.map(_.text)
      constraints   = (entry \ "constraints").headOption.map(unserialiseConstraints)
    } yield {
      TechniqueParameter(
        id,
        name,
        description,
        documentation,
        mayBeEmpty,
        constraints
      )
    }
  }

  def unserialiseTag(entry: Node): PureResult[(String, Json)] = {
    implicit val entryType: XmlEntryType = XmlEntryType("tag")
    for {
      key       <- getAndTransformChild(entry, "key", _.text.trim)
      jsonValue <- getAndTransformChild(entry, "state", _.text)
      json      <- jsonValue.toJsonAST.leftMap(Inconsistency(_))
    } yield {
      (key, json)
    }
  }

  def unserialiseResource(entry: Node): PureResult[ResourceFile] = {
    implicit val entryType: XmlEntryType = XmlEntryType("resource")
    for {
      path       <- getAndTransformChild(entry, "path", _.text.trim)
      stateValue <- getAndTransformChild(entry, "state", _.text)
      state      <- ResourceFileState.parse(stateValue)
    } yield {
      ResourceFile(path, state)
    }
  }

  def unserialise(entry: Node): PureResult[EditorTechnique] = {
    implicit val entryType: XmlEntryType = XmlEntryType(XML_TAG_EDITOR_TECHNIQUE)
    (for {
      technique     <- checkEntry(entry)
      _             <- check(technique)
      id            <- getAndTransformChild(technique, "id", _.text).map(BundleName(_))
      name          <- getAndTransformChild(technique, "displayName", _.text.trim)
      version       <- getAndTransformChild(technique, "version", _.text).map(Version(_))
      description   <- getAndTransformChild(technique, "description", _.text)
      documentation <- getAndTransformChild(technique, "documentation", _.text)
      category      <- getAndTransformChild(technique, "category", _.text)
      resources     <- (technique \ "resources" \ "resource").accumulatePure { t =>
                         unserialiseResource(t).leftMap(err => Chained(s"Invalid attribute in 'resource' entry: ${entry}", err))
                       }
      parameters    <- (technique \ "parameters" \ "parameter").accumulatePure { t =>
                         unserialiseTechniqueParameter(t).leftMap(err =>
                           Chained(s"Invalid attribute in 'resource' entry: ${entry}", err)
                         )
                       }
      tags          <- (technique \ "tags" \ "tag").accumulatePure { t =>
                         unserialiseTag(t).leftMap(err => Chained(s"Invalid attribute in 'resource' entry: ${entry}", err))
                       }.map(_.toMap)

      calls <- (technique \ "calls").flatMap(_.child).accumulatePure { t =>
                 unserialiseMethodElem(t).leftMap(err => Chained(s"Invalid attribute in 'calls' entry: ${entry}", err))
               }
    } yield {
      EditorTechnique(
        id,
        version,
        name,
        category,
        calls,
        description,
        documentation,
        parameters,
        resources,
        tags,
        None
      )
    }).chainError(s"${entryType} unserialisation failed")
  }

  def unserialiseDiff(entry: Node): Box[ModifyEditorTechniqueDiff] = {
    import com.normation.rudder.services.eventlog.EventLogDetailsService.*
    for {
      editorTechnique <- Box((entry \ "technique").headOption) ?~! ("Entry type is not EditorTechnique : " + entry.toString())
      changeTypeAddOk <- {
        if (editorTechnique.attribute("changeType").map(_.text).contains("modify"))
          Full("OK")
        else
          Failure("Editor technique attribute does not have changeType=modify: " + entry.toString())
      }
      fileFormatOk    <- TestFileFormat(editorTechnique)
      id              <- Box((editorTechnique \ "id").headOption.map(_.text).map(BundleName(_))) ?~!
                         ("Missing attribute 'id' in entry type editorTechnique : " + entry.toString())
      version         <- Box((editorTechnique \ "version").headOption.map(_.text).map(Version(_))) ?~!
                         ("Missing attribute 'id' in entry type editorTechnique : " + entry.toString())
      displayName     <- Box((editorTechnique \ "previousName").headOption.map(_.text)) ?~!
                         ("Missing attribute 'previousName' in entry type editorTechnique : " + entry.toString())
      name            <- getFromToString((editorTechnique \ "name").headOption)
      category        <- getFromToString(
                           (editorTechnique \ "category").headOption
                         )
      description     <- getFromToString((editorTechnique \ "description").headOption)
      documentation   <- getFromToString((editorTechnique \ "documentation").headOption)
      resources       <- getFromTo[Seq[ResourceFile]](
                           (editorTechnique \ "resources").headOption,
                           x => (x \ "resource").accumulatePure(unserialiseResource).toBox
                         )
      tags            <- getFromTo[Map[String, Json]](
                           (editorTechnique \ "tags").headOption,
                           x => (x \ "tag").accumulatePure(unserialiseTag).toBox.map(_.toMap)
                         )
      parameters      <- (editorTechnique \ "parameters" \ "parameter").accumulatePure(unserialiseTechniqueParameterDiff).toBox
      elems           <- (editorTechnique \ "calls").flatMap(_.child).accumulatePure(unserialiseTechniqueElemDiff).toBox
    } yield {
      ModifyEditorTechniqueDiff(
        id,
        version,
        displayName,
        name,
        category,
        description,
        documentation,
        elems,
        parameters,
        resources,
        tags
      )
    }
  }

  def unserialiseTechniqueParameterModifyDiff(techniqueParameter: Node): Box[ModifyTechniqueParameterDiff] = {
    import com.normation.rudder.services.eventlog.EventLogDetailsService.*
    for {
      id            <- Box((techniqueParameter \ "id").headOption.map(_.text).map(ParameterId(_))) ?~!
                       ("Missing attribute 'id' in entry type techniqueParameter : " + techniqueParameter.toString())
      displayName   <- Box((techniqueParameter \ "previousName").headOption.map(_.text)) ?~!
                       ("Missing attribute 'previousName' in entry type techniqueParameter : " + techniqueParameter.toString())
      name          <- getFromToString((techniqueParameter \ "name").headOption)
      description   <- getFromToOption((techniqueParameter \ "description").headOption, s => Full(s.text))
      documentation <- getFromToOption((techniqueParameter \ "documentation").headOption, s => Full(s.text))
      mayBeEmpty    <- getFromTo((techniqueParameter \ "mayBeEmpty").headOption, s => tryo(s.text.toBoolean))
      constraints   <- unserialiseConstraintsDiff(techniqueParameter)
    } yield {
      ModifyTechniqueParameterDiff(
        id,
        displayName,
        name,
        description,
        documentation,
        mayBeEmpty,
        constraints
      )
    }
  }

  def unserialiseConstraintsDiff(entry: Node): Box[Option[ModifyConstraintsDiff]] = {
    import com.normation.rudder.services.eventlog.EventLogDetailsService.*
    (entry \ "constraints").headOption match {
      case None              => Full(None)
      case Some(constraints) =>
        for {
          allowEmpty      <- getFromToOption((constraints \ "allowEmpty").headOption, s => tryo(s.text.toBoolean))
          allowWhitespace <- getFromToOption((constraints \ "allowWhiteSpace").headOption, s => tryo(s.text.toBoolean))
          regex           <- getFromToOption((constraints \ "regex").headOption, s => Full(s.text))
          notRegex        <- getFromToOption((constraints \ "notRegex").headOption, s => Full(s.text))
          minLength       <- getFromToOption((constraints \ "minLength").headOption, s => tryo(s.text.toInt))
          maxLength       <- getFromToOption((constraints \ "maxLength").headOption, s => tryo(s.text.toInt))
          select          <- getFromToOption((constraints \ "select").headOption, s => s.headOption.map(unserialiseSelectOption))
        } yield {
          Some(ModifyConstraintsDiff(allowEmpty, allowWhitespace, minLength, maxLength, regex, notRegex, select))
        }
    }
  }

  def unserialiseTechniqueBlockModifyDiff(block: Node): Box[ModifyTechniqueBlockDiff] = {
    import com.normation.rudder.services.eventlog.EventLogDetailsService.*
    for {
      id             <- Box((block \ "id").headOption.map(_.text)) ?~!
                        ("Missing attribute 'id' in entry type editorTechnique : " + block.toString())
      displayName    <- Box((block \ "previousName").headOption.map(_.text)) ?~!
                        ("Missing attribute 'previousName' in entry type editorTechnique : " + block.toString())
      component      <- getFromToString((block \ "component").headOption)
      condition      <- getFromToString((block \ "name").headOption)
      reportingLogic <- getFromTo((block \ "reportingLogic").headOption, s => ReportingLogic.parse(s.text).toBox)
      policyMode     <- getFromToOption((block \ "policyMode").headOption, s => PolicyMode.parse(s.text).toBox)
      foreachName    <- getFromToOption((block \ "foreachName").headOption, s => Full(s.text))
      foreach        <- getFromToOption((block \ "foreach").headOption, s => unserialiseForeach(s.head).toBox)
    } yield {
      ModifyTechniqueBlockDiff(
        id,
        displayName,
        component,
        condition,
        reportingLogic,
        Nil,
        policyMode,
        foreach,
        foreachName
      )
    }
  }

  def unserialiseTechniqueCallModifyDiff(call: Node): Box[ModifyTechniqueCallDiff] = {
    import com.normation.rudder.services.eventlog.EventLogDetailsService.*
    for {

      id               <- Box((call \ "id").headOption.map(_.text)) ?~!
                          ("Missing attribute 'id' in entry type editorTechnique : " + call.toString())
      method           <- Box((call \ "method").headOption.map(s => BundleName(s.text))) ?~!
                          ("Missing attribute 'method' in entry type editorTechnique : " + call.toString())
      displayName      <- Box((call \ "previousName").headOption.map(_.text)) ?~!
                          ("Missing attribute 'previousName' in entry type editorTechnique : " + call.toString())
      component        <- getFromToString((call \ "component").headOption)
      condition        <- getFromToString((call \ "name").headOption)
      disableReporting <- getFromTo((call \ "disableReporting").headOption, s => tryo(s.text.toBoolean))
      policyMode       <- getFromToOption((call \ "policyMode").headOption, s => PolicyMode.parse(s.text).toBox)
      foreachName      <- getFromToOption((call \ "foreachName").headOption, s => Full(s.text))
      foreach          <- getFromToOption((call \ "foreach").headOption, s => unserialiseForeach(s.head).toBox)
      parameters       <- getFromTo(
                            (call \ "parameters").headOption,
                            s => (s \ "parameter").accumulatePure(unserialiseCallParameters).toBox.map(_.toMap)
                          )
    } yield {
      ModifyTechniqueCallDiff(
        method,
        id,
        displayName,
        component,
        condition,
        disableReporting,
        parameters,
        policyMode,
        foreach,
        foreachName
      )
    }
  }

  def unserialiseTechniqueParameterDiff(entry: Node): PureResult[TechniqueParameterDiff] = {

    if (entry.label == "parameter") {
      entry.attribute("changeType").map(_.text) match {
        case Some("modify")  => unserialiseTechniqueParameterModifyDiff(entry).toPureResult
        case Some("added")   =>
          val techniqueParameter = unserialiseTechniqueParameter(entry)
          techniqueParameter.map(AddTechniqueParameterDiff(_))
        case Some("deleted") =>
          val techniqueParameter = unserialiseTechniqueParameter(entry)
          techniqueParameter.map(DeleteTechniqueParameterDiff(_))
        case s               =>
          Left(
            Inconsistency(
              s"Technique parameter attribute does not have a valid changeType: ${s.getOrElse("<no changeType attribute>")}"
            )
          )
      }
    } else {
      Left(Inconsistency("Entry type is not a technique parameter : " + entry.toString()))
    }
  }

  def unserialiseTechniqueElemDiff(entry: Node): PureResult[TechniqueBlockDiff | TechniqueCallDiff] = {
    entry.label match {
      case "block" =>
        entry.attribute("changeType").map(_.text) match {
          case Some("modify")  => unserialiseTechniqueBlockModifyDiff(entry).toPureResult
          case Some("added")   =>
            val techniqueParameter = unserialiseBlock(entry)
            techniqueParameter.map(AddTechniqueBlockDiff(_))
          case Some("deleted") =>
            val techniqueParameter = unserialiseBlock(entry)
            techniqueParameter.map(DeleteTechniqueBlockDiff(_))
          case s               =>
            Left(
              Inconsistency(
                s"Technique block attribute does not have a valid changeType: ${s.getOrElse("<no changeType attribute>")}"
              )
            )

        }

      case "call" =>
        entry.attribute("changeType").map(_.text) match {
          case Some("modify")  => unserialiseTechniqueCallModifyDiff(entry).toPureResult
          case Some("added")   =>
            val techniqueParameter = unserialiseMethodCall(entry)
            techniqueParameter.map(AddTechniqueCallDiff(_))
          case Some("deleted") =>
            val techniqueParameter = unserialiseMethodCall(entry)
            techniqueParameter.map(DeleteTechniqueCallDiff(_))
          case s               =>
            Left(
              Inconsistency(
                s"Technique call attribute does not have a valid changeType: ${s.getOrElse("<no changeType attribute>")}"
              )
            )

        }
    }

  }
}
