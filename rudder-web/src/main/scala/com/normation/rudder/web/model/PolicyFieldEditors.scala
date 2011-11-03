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

package com.normation.rudder.web.model

import com.normation.utils.Utils._
import scala.xml._
import net.liftweb.common._
import net.liftweb.http._
import js._
import JsCmds._
import JE._
import net.liftweb.util.Helpers._
import java.io.File
import org.joda.time.{ DateTime, LocalDate, LocalTime, Duration, Period }
import org.joda.time.format.DateTimeFormatter
import net.liftweb.util.FieldError
import com.normation.cfclerk.domain._
import net.liftweb.http.SHtml.ChoiceHolder

/**
 * This field is a simple input text, without any
 * special validation nor anything.
 */
class TextField(val id: String) extends PolicyField {
  self =>
  type ValueType = String
  protected var _x: String = getDefaultValue

  def is = _x
  def set(x: String) = { if (null == x) _x = "" else _x = x; _x }
  def toForm() = Full(SHtml.text(toClient, { x => parseClient(x) }))
  def manifest = manifestOf[String]

  override val uniqueFieldId = Full(id)
  def name = id
  def validate = Nil
  def validations = Nil
  def setFilter = Nil
  def parseClient(s: String): Unit = if (null == s) _x = "" else _x = s
  def toClient: String = if (null == _x) "" else _x

  def getPossibleValues(filters: (ValueType => Boolean)*): Option[Set[ValueType]] = None // not supported in the general cases
  def getDefaultValue = ""
}

/**
 * A textarea field, with a css class 
 * "textareaField"
 *
 */
class TextareaField(override val id: String) extends TextField(id) {
  self =>

  override def toForm() = Full {
    SHtml.textarea(toClient, { x => parseClient(x) }) % ("class" -> "textareaField")
  }
  
}

class InputSizeField(override val id: String, val expectedUnit: String = "b") extends TextField(id) {
  val units = ValueLabel("b", "B") :: ValueLabel("kb", "KB") :: ValueLabel("mb", "MB") :: ValueLabel("gb", "GB") :: ValueLabel("tb", "TB") :: Nil

  override def toForm() = Full(<div>{ inputToForm ++ unitsToForm }</div>)

  private def inputToForm = SHtml.text(_x, { x => updateValue(x) })
  private def unitsToForm = SHtml.select(units.map(_.tuple), Full(expectedUnit), { selected => selectedUnit = selected; computeValue() })

  var value: BigDecimal = 0
  var selectedUnit: String = expectedUnit

  def unitBytes(unit: String): BigDecimal = {
    val oneBD: BigDecimal = 1
    unit match {
      case "b" => oneBD
      case "kb" => oneBD * 1024
      case "mb" => oneBD * 1024 * 1024
      case "gb" => oneBD * 1024 * 1024 * 1024
      case "tb" => oneBD * 1024 * 1024 * 1024 * 1024
    }
  }
  
  def computeValue() = {
    _x = (value * unitBytes(selectedUnit) / unitBytes(expectedUnit)).toString
    _x
  }

  def updateValue(s: String): Unit =
    try
      value = BigDecimal(s)
    catch {
      case e: NumberFormatException =>
        value = 0
        PolicyField.logger.debug("parse exception: " + e.getMessage)
    }

  def updateUnit(unit: String): Unit = selectedUnit = unit
}

/* Multiple values, display check boxes by default */
class SelectField(val id: String, items: Seq[ValueLabel]) extends PolicyField {
  self =>
  import collection.mutable.ListBuffer
  type ValueType = Seq[String]
  private var values: ValueType = getDefaultValue

  def is = values
  def set(x: ValueType) = { values = x; values }

  def toForm() = {
    val valuesByLabel = items.map(_.reverse.tuple).toMap
    val labelsByValue = items.map(_.tuple).toMap

    val defaultLabels = values.map(labelsByValue(_))
    val labels = items.map(_.label)
    Full(SHtml.checkbox[String](labels, defaultLabels,
      labelsSelected => set(labelsSelected.map(valuesByLabel(_)))).toForm)
  }

  def manifest = manifestOf[ValueType]

  override val uniqueFieldId = Full(id)
  def name = id
  def validate = Nil
  def validations = Nil
  def setFilter = Nil

  def parseClient(s: String): Unit =
    if (null == s) values = getDefaultValue
    else values = s.split(",")

  def toClient: String = if (null == values) "" else values.mkString(",")

  // not supported
  def getPossibleValues(filters: (ValueType => Boolean)*): Option[Set[ValueType]] = None

  def getDefaultValue = List[String]()
}

/**
 * This field is a select field, for constrained values
 */
class SelectOneField(val id: String, valueslabels: Seq[ValueLabel]) extends PolicyField {
  self =>
  type ValueType = String
  private var _x: String = getDefaultValue

  def is = _x
  def set(x: String) = { if (null == x) _x = "" else _x = x; _x }
  def toForm() = {
    if (valueslabels.size <= 3)
      radios
    else
      dropDownList
  }

  def radios = {
    // 
    val labels = valueslabels.map(_.label)
    val choiceHolder: ChoiceHolder[String] = SHtml.radio(valueslabels.map(_.value), Full(toClient), { x => parseClient(x) })
    Full(<div>{
      choiceHolder.flatMap {
        c =>
          (<span>{ c.xhtml }&nbsp;{ valueslabels.find(x => x.value == c.key).map(_.label).getOrElse("error") }<br/></span>)
      }
    }</div>)

  }

  def dropDownList = Full(SHtml.select(valueslabels.map(_.tuple), Full(toClient), { x => parseClient(x) }))

  def manifest = manifestOf[String]

  override val uniqueFieldId = Full(id)
  def name = id
  def validate = Nil
  def validations = Nil
  def setFilter = Nil
  def parseClient(s: String): Unit = if (null == s) _x = "" else _x = s
  def toClient: String = if (null == _x) "" else _x

  override def displayHtml = {
    Text({ valueslabels.filter(entry => (entry.value == _x)).headOption.map(entry => entry.label).getOrElse("") })
  }

  def getPossibleValues(filters: (ValueType => Boolean)*): Option[Set[ValueType]] = Some(valueslabels.map(x => x.value).toSet) // not supported in the general cases
  def getDefaultValue = ""
}

/**
 * This Field allows to select a file from all the
 * file available in the "basePath" directory.
 * Files are presented in a select drop box with there
 * file name.
 * No verification is done on basePath, so if it is not a directory
 * or does not exists, the select box will be empty.
 *
 * Set does not set the file is
 */
class UploadedFileField(basePath: String)(val id: String) extends PolicyField {
  require(null != basePath, "basePath can't be null")
  type ValueType = File
  private val root = new File(basePath)
  private var f: File = getDefaultValue
  private var errors = List[FieldError]()

  def is = f
  def set(x: File) = {
    errors = List[FieldError]()
    if (null == x) f = null
    else if (listFiles.exists(_._1 == x)) {
      f = x
    } else {
      errors = FieldError(this,
        "The chosen file %s is not in the parent directory %s".format(f.getName, basePath)) :: Nil
    }
    f
  }
  def toForm() = {
    val xml = SHtml.selectObj(
      listFiles,
      { if (null == f) Empty else Full(f) },
      { x: File => set(x) },
      ("id", id))
    Full(xml)
  }
  def manifest = manifestOf[File]

  override val uniqueFieldId = Full(id)
  def name = id
  def validate = Nil
  def validations = Nil
  def setFilter = Nil
  def parseClient(s: String): Unit = {
    if (isEmpty(s)) set(null)
    else set(new File(root, s))
  }
  def toClient: String = if (null == f) "" else f.getName

  def getPossibleValues(filters: (ValueType => Boolean)*): Option[Set[ValueType]] = {
    Some((listFiles.map(_._1).toSet /: filters) { (files, filter) => files.filter(f => filter(f)) })
  }
  def getDefaultValue = null

  private def listFiles: Seq[(File, String)] = {
    if (!root.exists || !root.isDirectory) Seq()
    else root.listFiles.toSeq.filter(_.isFile).map(x => (x, x.getName))
  }
}

class DateField(format: DateTimeFormatter)(val id: String) extends PolicyField {
  type ValueType = LocalDate

  private var _x: ValueType = getDefaultValue
  private var errors: List[FieldError] = Nil
  def is = _x
  def set(x: ValueType) = { _x = x; _x }
  def manifest = manifestOf[LocalDate]

  override val uniqueFieldId = Full(id)

  def name = id
  def validate = Nil
  def validations = Nil
  def setFilter = Nil
  def parseClient(s: String): Unit = {
    try {
      _x = format.parseDateTime(s).toLocalDate
    } catch {
      case e: IllegalArgumentException =>
        PolicyField.logger.debug("parse exception: " + e.getMessage)
        errors = errors ::: List(FieldError(this, "Bad date format"))
    }
  }
  def toClient: String = if (null == _x) "" else _x.toString(format)

  def toForm() = {
    val xml = (<head>
                 <script type="text/javascript" src="/javascript/ui/jquery.ui.datepicker.js"></script>
                 <script type="text/javascript" src="/javascript/ui/i18n/jquery.ui.datepicker-fr.js"></script>
               </head>) ++ (SHtml.text(toClient, { x => parseClient(x) }) % ("id" -> this.id)) ++
      Script(OnLoad(JsRaw("var init%s = $.datepicker.regional['%s']; init%s['showOn'] = 'both';jQuery('#%s').datepicker(init%s)".
        format(id, format.getLocale.getLanguage, id, id, id))))

    Full(xml)
  }

  def getPossibleValues(filters: (ValueType => Boolean)*): Option[Set[ValueType]] = None // not supported in the general cases
  def getDefaultValue = new DateTime().toLocalDate //default datetime
}

class TimeField(format: DateTimeFormatter)(val id: String) extends PolicyField {
  type ValueType = LocalTime

  private var _x: ValueType = getDefaultValue
  private var errors: List[FieldError] = Nil
  def is = _x
  def set(x: ValueType) = { _x = x; _x }
  def manifest = manifestOf[LocalTime]

  override val uniqueFieldId = Full(id)

  def name = id
  def validate = Nil
  def validations = Nil
  def setFilter = Nil
  def parseClient(s: String): Unit = {
    try {
      _x = format.parseDateTime(s).toLocalTime
    } catch {
      case e: IllegalArgumentException =>
        PolicyField.logger.debug("parse exception: " + e.getMessage)
        errors = errors ::: List(FieldError(this, "Bad time format"))
    }
  }
  def toClient: String = if (null == _x) "" else _x.toString(format)

  def toForm() = {
    val xml = (<head>
                 <script type="text/javascript" src="/javascript/ui/jquery.ui.datepicker.js"></script>
                 <script type="text/javascript" src="/javascript/ui/i18n/jquery.ui.datepicker-fr.js"></script>
               </head>) ++ (SHtml.text(toClient, { x => parseClient(x) }) % ("id" -> this.id)) ++
      Script(OnLoad(JsRaw("var init%s = $.datepicker.regional['%s']; init%s['showOn'] = 'both';jQuery('#%s').datepicker(init%s)".
        format(id, format.getLocale.getLanguage, id, id, id))))

    Full(xml)
  }

  def getPossibleValues(filters: (ValueType => Boolean)*): Option[Set[ValueType]] = None // not supported in the general cases
  def getDefaultValue = new DateTime().toLocalTime //default datetime
}

class PeriodField(
  showSeconds: Boolean = true,
  showMinutes: Boolean = true,
  showHours: Boolean = true,
  showDays: Boolean = true)(val id: String) extends PolicyField {
  type ValueType = Period

  private var _x: ValueType = getDefaultValue
  private var errors: List[FieldError] = Nil
  def is = _x
  def set(x: ValueType) = { _x = x; _x }
  def manifest = manifestOf[Period]

  override val uniqueFieldId = Full(id)

  def name = id
  def validate = Nil
  def validations = Nil
  def setFilter = Nil
  //awaiting string: a duration in milliseconds
  def parseClient(s: String): Unit = {
    try {
      _x = new Period(s.toLong)
    } catch {
      case e: NumberFormatException =>
        PolicyField.logger.debug("parse exception: " + e.getMessage)
        errors = errors ::: List(FieldError(this, "Bad duration format"))
      case e: IllegalArgumentException =>
        PolicyField.logger.debug("parse exception: " + e.getMessage)
        errors = errors ::: List(FieldError(this, "Bad time format"))
    }
  }
  def toClient: String = if (null == _x) "0" else _x.getMillis.toString

  def toForm() = {
      def intOpts(until: Int, by: Int = 1): Seq[(Int, String)] =
        new Range(0, until, by).map(x => (x, x.toString))

    val xml =
      <div>
        {
          if (showDays) SHtml.selectObj[Int](intOpts(365), Full(0), { t => set(is.withDays(t)) }) ++
            Text("day(s)")
          else NodeSeq.Empty
        }
        {
          if (showHours) SHtml.selectObj[Int](intOpts(24), Full(0), { t => set(is.withHours(t)) }) ++
            Text("hour(s)")
          else NodeSeq.Empty
        }
        {
          if (showMinutes) SHtml.selectObj[Int](intOpts(60), Full(0), { t => set(is.withMinutes(t)) }) ++
            Text("minute(s)")
          else NodeSeq.Empty
        }
        {
          if (showSeconds) SHtml.selectObj[Int](intOpts(60), Full(0), { t => set(is.withSeconds(t)) }) ++
            Text("second(s)")
          else NodeSeq.Empty
        }
      </div>

    Full(xml)
  }

  def getPossibleValues(filters: (ValueType => Boolean)*): Option[Set[ValueType]] = None // not supported in the general cases
  def getDefaultValue = Period.ZERO
}

class FilePermsField(val id: String) extends PolicyField {
  type ValueType = FilePerms

  private val _x: ValueType = getDefaultValue
  private var errors: List[FieldError] = Nil

  def is = _x
  def set(x: ValueType) = { _x.set(x); _x }
  def manifest = manifestOf[FilePerms]

  override val uniqueFieldId = Full(id)
  def name = id
  def validate = Nil
  def validations = Nil
  def setFilter = Nil
  def parseClient(s: String): Unit = {
    if (nonEmpty(s)) FilePerms(s).map(_x.set(_))
  }
  def toClient: String = if (null == _x) "" else _x.octal

  def toForm() = {
    val xml = <table>
                <tr><th></th><th>Read</th><th>Write</th><th>Exec</th></tr>
                <tr><td>User</td><td><check:ur/></td><td><check:uw/></td><td><check:ux/></td></tr>
                <tr><td>Group</td><td><check:gr/></td><td><check:gw/></td><td><check:gx/></td></tr>
                <tr><td>Other</td><td><check:or/></td><td><check:ow/></td><td><check:ox/></td></tr>
              </table> % ("id" -> id)

    val fxml = bind("check", xml,
      "ur" -> SHtml.checkbox(_x.u.read, { _x.u.read = _ }),
      "uw" -> SHtml.checkbox(_x.u.write, { _x.u.write = _ }),
      "ux" -> SHtml.checkbox(_x.u.exec, { _x.u.exec = _ }),
      "gr" -> SHtml.checkbox(_x.g.read, { _x.g.read = _ }),
      "gw" -> SHtml.checkbox(_x.g.write, { _x.g.write = _ }),
      "gx" -> SHtml.checkbox(_x.g.exec, { _x.g.exec = _ }),
      "or" -> SHtml.checkbox(_x.o.read, { _x.o.read = _ }),
      "ow" -> SHtml.checkbox(_x.o.write, { _x.o.write = _ }),
      "ox" -> SHtml.checkbox(_x.o.exec, { _x.o.exec = _ }))

    Full(fxml)
  }
  def getPossibleValues(filters: (ValueType => Boolean)*): Option[Set[ValueType]] =
    Some((FilePerms.allPerms /: filters) { (filePerms, filter) =>
      filePerms.filter(p => filter(p))
    })

  def getDefaultValue = new FilePerms() //default perms to 000
}

class CheckboxField(val id: String) extends PolicyField {
  type ValueType = String // yes, this is truly a String

  private var _x: String = getDefaultValue
  private var description: String = ""
  override def displayName = description
  override def displayName_=(s: String): Unit = description = s

  def is = _x
  def set(x: String) = { if (null == x) _x = "" else _x = x; _x }
  def manifest = manifestOf[String]

  override val uniqueFieldId = Full(id)
  def name = id
  def validate = Nil
  def validations = Nil
  def setFilter = Nil
  def parseClient(s: String): Unit = if (null == s) _x = "false" else _x = s
  def toClient: String = if (null == _x) "false" else _x

  override def displayHtml = { if ((null == _x) || ("false" == _x)) Text("No") else Text("Yes") }
  def toForm() = Full(SHtml.checkbox(toClient.equalsIgnoreCase("true"), { x => parseClient(x.toString) }))

  def getPossibleValues(filters: (ValueType => Boolean)*): Option[Set[ValueType]] = None

  def getDefaultValue = "false"
}