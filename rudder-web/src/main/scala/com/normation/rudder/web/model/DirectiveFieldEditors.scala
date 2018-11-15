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
import org.joda.time.{ DateTime, LocalDate, LocalTime, Period }
import org.joda.time.format.DateTimeFormatter
import net.liftweb.util.FieldError
import com.normation.cfclerk.domain._
import net.liftweb.http.SHtml.ChoiceHolder
import com.normation.cfclerk.domain.HashAlgoConstraint.PLAIN
import com.normation.cfclerk.domain.HashAlgoConstraint.PreHashed
import net.liftweb.util.CssSel
import net.liftweb.util.Helpers
import com.normation.rudder.domain.appconfig.FeatureSwitch
import com.normation.rudder.domain.appconfig.FeatureSwitch.Disabled
import com.normation.rudder.domain.appconfig.FeatureSwitch.Enabled
import com.normation.rudder.services.policies.JsEngine
import com.normation.rudder.web.ChooseTemplate

/**
 * This field is a simple input text, without any
 * special validation nor anything.
 */

object TextField {

  def textInput(kind : String)(id : String) = {
      // Html template
    def textInput = ChooseTemplate(List("templates-hidden", "components", "directiveInput") , s"input-$kind")

    val css: CssSel =  ".tw-bs [id]" #> id &
    ".text-section [id]" #>  (id+"-controller")
    css(textInput)
  }
}

class TextField(
    val id           : String
  , scriptSwitch : () => Box[FeatureSwitch]
) extends DirectiveField {
  self =>
  type ValueType = String
  protected var _x: String = getDefaultValue

  def get = _x
  def set(x: String) = { if (null == x) _x = "" else _x = x; _x }

  def toForm() = display(TextField.textInput("text") )

  def display( xml: String => NodeSeq) = {
    val formId = Helpers.nextFuncName
    val valueInput = SHtml.textarea("", {s =>  parseClient(s)}, ("ng-model","result"), ("ng-hide", "true") )
    val (scriptEnabled, currentPrefix, currentValue) = scriptSwitch().getOrElse(Disabled) match {
      case Disabled => (JsFalse, "",toClient)
      case Enabled  =>
        val (currentPrefix, currentValue) =
          if (toClient.startsWith(JsEngine.EVALJS)) {
            (JsEngine.EVALJS, toClient.substring(JsEngine.EVALJS.length()))
          } else {
            ("",toClient)
          }
        ( JsTrue, currentPrefix, currentValue)
    }
    val initScript = {
      Script(OnLoad( JsRaw(s"""
       angular.bootstrap("#${formId}", ['text']);
       var scope = angular.element($$("#${formId}-controller")).scope();
       scope.$$apply(function(){
         scope.init(
             ${Str(currentValue).toJsCmd}
           , ${Str(currentPrefix).toJsCmd}
           , ${scriptEnabled.toJsCmd}
         );
       });""")))
    }

    val form = (".text-section *+" #> valueInput).apply(xml(formId)) ++ initScript
    Full(form)

  }
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

class ReadOnlyTextField(val id:String) extends DirectiveField {
  self =>
  type ValueType = String
  protected var _x: String = getDefaultValue

  val readOnly = true

  def get = _x
  def set(x: String) = { if (null == x) _x = "" else _x = x; _x }
  def toForm() = {
    val attrs = if(isReadOnly) Seq(("readonly" -> "readonly")) else Seq()
    Full(SHtml.text(toClient, { x => parseClient(x) }, attrs:_*))
  }
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

class TextareaField(
    override val id: String
  , scriptSwitch   : () => Box[FeatureSwitch]
) extends TextField(id,scriptSwitch) {
  self =>

  override def toForm() = display(TextField.textInput("textarea") )

}

class InputSizeField(
    override val id: String
  , scriptSwitch   : () => Box[FeatureSwitch]
  , expectedUnit   : String = "b"
) extends TextField(id,scriptSwitch) {
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
        DirectiveField.logger.debug("parse exception: " + e.getMessage)
    }

  def updateUnit(unit: String): Unit = selectedUnit = unit
}

/* Multiple values, display check boxes by default */
class SelectField(val id: String, items: Seq[ValueLabel]) extends DirectiveField {
  self =>
  type ValueType = Seq[String]
  private var values: ValueType = getDefaultValue

  def get = values
  def set(x: ValueType) = { values = x; values }

  def toForm() = {
    val valuesByLabel = items.map(_.reverse.tuple).toMap
    val labelsByValue = items.map(_.tuple).toMap

    val defaultLabels = values.map(labelsByValue.getOrElse(_,""))
    val labels = items.map(_.label)
    Full(SHtml.checkbox[String](labels, defaultLabels,
      labelsSelected => set(labelsSelected.map(valuesByLabel.getOrElse(_,"")))).toForm)
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
class SelectOneField(val id: String, valueslabels: Seq[ValueLabel]) extends DirectiveField {
  self =>
  type ValueType = String
  private var _x: String = getDefaultValue

  def get = _x
  def set(x: String) = { if (null == x) _x = "" else _x = x; _x }
  def toForm() = {
    if (valueslabels.size <= 3)
      radios
    else
      dropDownList
  }

  def radios = {
    val choiceHolder: ChoiceHolder[String] = SHtml.radio(valueslabels.map(_.value), Full(toClient), { x => parseClient(x) })
    Full(<div class="tw-bs">{
      choiceHolder.flatMap {
        c =>
          ( <div class="radio">
              <label>
                { c.xhtml }
                { valueslabels.find(x => x.value == c.key).map(_.label).getOrElse("error")}
              </label>
            </div>
          )
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
 * or does not exist, the select box will be empty.
 *
 * Set does not set the file is
 */
class UploadedFileField(basePath: String)(val id: String) extends DirectiveField {
  require(null != basePath, "basePath can't be null")
  type ValueType = File
  private val root = new File(basePath)
  private var f: File = getDefaultValue
  private var errors = List[FieldError]()

  def get = f
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

class DateField(format: DateTimeFormatter)(val id: String) extends DirectiveField {
  type ValueType = LocalDate

  private var _x: ValueType = getDefaultValue
  private var errors: List[FieldError] = Nil
  def get = _x
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
        DirectiveField.logger.debug("parse exception: " + e.getMessage)
        errors = errors ::: List(FieldError(this, "Bad date format"))
    }
  }
  def toClient: String = if (null == _x) "" else _x.toString(format)

  def toForm() = {
    val xml = (SHtml.text(toClient, { x => parseClient(x) }) % ("id" -> this.id)) ++
      Script(OnLoad(JsRaw("var init%s = $.datepicker.regional['%s']; init%s['showOn'] = 'both';jQuery('#%s').datepicker(init%s)".
        format(id, format.getLocale.getLanguage, id, id, id))))

    Full(xml)
  }

  def getPossibleValues(filters: (ValueType => Boolean)*): Option[Set[ValueType]] = None // not supported in the general cases
  def getDefaultValue = DateTime.now().toLocalDate //default datetime
}

class TimeField(format: DateTimeFormatter)(val id: String) extends DirectiveField {
  type ValueType = LocalTime

  private var _x: ValueType = getDefaultValue
  private var errors: List[FieldError] = Nil
  def get = _x
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
        DirectiveField.logger.debug("parse exception: " + e.getMessage)
        errors = errors ::: List(FieldError(this, "Bad time format"))
    }
  }
  def toClient: String = if (null == _x) "" else _x.toString(format)

  def toForm() = {
    val xml = (SHtml.text(toClient, { x => parseClient(x) }) % ("id" -> this.id)) ++
      Script(OnLoad(JsRaw("var init%s = $.datepicker.regional['%s']; init%s['showOn'] = 'both';jQuery('#%s').datepicker(init%s)".
        format(id, format.getLocale.getLanguage, id, id, id))))

    Full(xml)
  }

  def getPossibleValues(filters: (ValueType => Boolean)*): Option[Set[ValueType]] = None // not supported in the general cases
  def getDefaultValue = DateTime.now().toLocalTime //default datetime
}

class PeriodField(
  showSeconds: Boolean = true,
  showMinutes: Boolean = true,
  showHours: Boolean = true,
  showDays: Boolean = true)(val id: String) extends DirectiveField {
  type ValueType = Period

  private var _x: ValueType = getDefaultValue
  private var errors: List[FieldError] = Nil
  def get = _x
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
        DirectiveField.logger.debug("parse exception: " + e.getMessage)
        errors = errors ::: List(FieldError(this, "Bad duration format"))
      case e: IllegalArgumentException =>
        DirectiveField.logger.debug("parse exception: " + e.getMessage)
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

class FilePermsField(val id: String) extends DirectiveField {
  type ValueType = FilePerms

  private val _x: ValueType = getDefaultValue

  def get = _x
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
                <tr><td>User</td><td><span id="check-ur"/></td><td><span id="check-uw"/></td><td><span id="check-ux"/></td></tr>
                <tr><td>Group</td><td><span id="check-gr"/></td><td><span id="check-gw"/></td><td><span id="check-gx"/></td></tr>
                <tr><td>Other</td><td><span id="check-or"/></td><td><span id="check-ow"/></td><td><span id="check-ox"/></td></tr>
              </table> % ("id" -> id)

    Full( (
        "#check-ur" #> SHtml.checkbox(_x.u.read, { _x.u.read = _ })
      & "#check-uw" #> SHtml.checkbox(_x.u.write, { _x.u.write = _ })
      & "#check-ux" #> SHtml.checkbox(_x.u.exec, { _x.u.exec = _ })
      & "#check-gr" #> SHtml.checkbox(_x.g.read, { _x.g.read = _ })
      & "#check-gw" #> SHtml.checkbox(_x.g.write, { _x.g.write = _ })
      & "#check-gx" #> SHtml.checkbox(_x.g.exec, { _x.g.exec = _ })
      & "#check-or" #> SHtml.checkbox(_x.o.read, { _x.o.read = _ })
      & "#check-ow" #> SHtml.checkbox(_x.o.write, { _x.o.write = _ })
      & "#check-ox" #> SHtml.checkbox(_x.o.exec, { _x.o.exec = _ })
    )(xml) )
  }

  def getPossibleValues(filters: (ValueType => Boolean)*): Option[Set[ValueType]] =
    Some((FilePerms.allPerms /: filters) { (filePerms, filter) =>
      filePerms.filter(p => filter(p))
    })

  def getDefaultValue = new FilePerms() //default perms to 000
}

class CheckboxField(val id: String) extends DirectiveField {
  type ValueType = String // yes, this is truly a String

  private var _x: String = getDefaultValue
  private var description: String = ""
  override def displayName = description
  override def displayName_=(s: String): Unit = description = s

  def get = _x
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

/**
 * A password field has two parts:
 *
 * One part display the current field, with possible action. If there is no current field, this part is not displayed
 * One part display how to change the passsword with the possible action (hash, cleartext, prehashed, script ...)
 *
 */

class PasswordField(
    val id: String
  , algos:Seq[HashAlgoConstraint]
  , canBeDeleted : Boolean
  , scriptSwitch : () => Box[FeatureSwitch]
) extends DirectiveField {
  self =>
  type ValueType = String
  def getPossibleValues(filters: (ValueType => Boolean)*): Option[Set[ValueType]] = None // not supported in the general cases
  def getDefaultValue = ""
  def manifest = manifestOf[String]
  override val uniqueFieldId = Full(id)
  def name = id
  def validate = Nil
  def validations = Nil
  def setFilter = Nil
  private var errors : List[FieldError] = Nil

  override def usedFields_=(fields: Seq[DirectiveField]): Unit = {
    _usedFields = fields
    slaves = fields.collect { case f: DerivedPasswordField => f }.toList
  }

  protected var slaves = List[DerivedPasswordField]() // will be init on usedField update - I hate mutable state

  //the actual backend value like: sha1:XXXXXX
  protected var _x: String = getDefaultValue

  //the algo to use
  private[this] var currentAlgo: Option[HashAlgoConstraint] =
    if (algos.contains(HashAlgoConstraint.LinuxShadowSHA256)) {
      Some(HashAlgoConstraint.LinuxShadowSHA256)
    }
    else {
      if (algos.contains(HashAlgoConstraint.SHA256)) {
        Some(HashAlgoConstraint.SHA256)
      }
      else {
        algos.headOption
      }
    }
  private[this] var currentHash: Option[String] = None
  private[this] var currentAction: String = "keep"
  //to store the result
  private[this] var currentValue: Option[String] = None

  private[this] var previousAlgo: Option[HashAlgoConstraint] = None
  private[this] var previousHash: Option[String] = None

  /*
   * find the new internal value of the hash given:
   * - past value
   * - blank required
   * - new value
   */
  private[this] def newInternalValue(keepCurrentPwd: Boolean, blankPwd:Boolean, pastValue:String, newInput:String, chosenAlgo:HashAlgoConstraint) : String = {
    slaves.foreach { s =>
      s.updateValue(keepCurrentPwd, blankPwd, pastValue, newInput, chosenAlgo)
    }

    currentValue = Some(newInput)

    if(keepCurrentPwd) pastValue
    else if(blankPwd) ""
    else if(newInput == "") ""
    else chosenAlgo.serialize(newInput.getBytes())
  }

  /* This will parse the json received from the form
   * format is:
   * { "action"  : delete/keep/change
   *   "hash"    : hash type used
   *   "password : new password, or undefined/missing field if deleting
   * }
   *
   */
  def parseClient(s: String) = {
    import net.liftweb.json._
    errors = Nil
    val json = parse(s)
    (for {
      action <- json \ "action" match {
                  case JString(action) => Full(action)
                  case _ => Failure("Could not parse 'action' field in password input")
                }
      (keep,blank) = action match {
        case "delete" => (false,true)
        case "keep"   => (true,false)
        case _        => (false,false)
      }
      password <- json \"password" match {
                    case JString(password) => Full(password)
                    case JNothing if canBeDeleted => Full("")
                    case _ => Failure("Could not parse 'password' field in password input")
                  }
      hash     <- json \ "hash" match {
                    case JString(hash) => Full(hash)
                    case _ => Failure("Could not parse 'hash' field in password input")
                  }
      algo = HashAlgoConstraint.fromString(hash).getOrElse(currentAlgo.getOrElse(PLAIN))
    } yield {
      currentAction = action
      if (!keep) {
        previousAlgo = currentAlgo
        previousHash = currentHash
      }
      newInternalValue(keep,blank,toClient, password, algo)
    }) match {
      case Full(newValue) => set(newValue)
      case eb: EmptyBox =>
        val fail = eb ?~! s"Error while parsing password input, value received is: ${compactRender(json)}"
        logger.error(fail.messageChain)
        errors = errors ::: List(FieldError(this, fail.messageChain))
    }
  }
  def toClient: String = if (null == _x) "" else _x

  def get = {
    _x
  }

  //initialize the field
  def set(x: String) = {
    if (null == x || "" == x) _x = ""
    else {
      _x = x
      val r = {
          HashAlgoConstraint.unserialize( x) match {
            case Full((a,hash)) =>
              //update the hash algo to use only if not specified.
              //we don't check if previous hash and current algo matches: we only enforce
              //that new passwords use the new specified algo
              (Some(a),hash)
            case eb:EmptyBox =>
              //we don't have a password with the correct format.
              //report an error, assume the default (first) algo
              logger.error((eb ?~! "Error when reading stored password hash").messageChain)
              (None, x)
          }
      }
      currentAlgo = r._1
      currentHash = Some(r._2)
    }

    //initialise what to display for "current value" the first time
    _x
  }

  def slavesValues () : Map[String,String]= {
    (for {
      (derivedType,value) <- slaves.map(slave => (slave.derivedType,slave.toClient))
      password  = HashAlgoConstraint.unserialize(value).map(_._2).getOrElse(value)
    } yield {
      (derivedType.name,password)
    }).toMap
  }

  //add a mapping between algo names and what is displayed, because having
  //linux-... or aix-... does not make sense in that context
  implicit class AlgoToDisplayName(a: HashAlgoConstraint) {
    import com.normation.cfclerk.domain.HashAlgoConstraint._

    def name = a match {
      case PLAIN                         => "Verbatim text"
      case PreHashed                     => "Pre hashed"
      case MD5                           => "MD5 (Non salted)"
      case SHA1                          => "SHA1 (Non salted)"
      case SHA256                        => "SHA256 (Non salted)"
      case SHA512                        => "SHA512 (Non salted)"
      case LinuxShadowMD5    | AixMD5    => "MD5"
      case LinuxShadowSHA256 | AixSHA256 => "SHA256"
      case LinuxShadowSHA512 | AixSHA512 => "SHA512"
      case UnixCryptDES                  => "DES"
    }
  }

  def toForm = {
    val hashes = JsObj(algos.filterNot { x => x == PLAIN || x == PreHashed }.map(a => (a.prefix,  Str(a.name))):_*)
    val formId = Helpers.nextFuncName
    val valueInput = SHtml.text("", {s =>  parseClient(s)}, ("ng-model","result"), ("ng-hide", "true") )
    val otherPasswords = if (slavesValues.size == 0) "undefined" else JsObj(slavesValues().mapValues(Str(_)).toSeq:_*).toJsCmd
    val (scriptEnabled,isScript, currentValue) = scriptSwitch().getOrElse(Disabled) match {
      case Disabled => (false,false, currentHash)
      case Enabled =>
        val (isAScript,value) = {
          if (currentHash.getOrElse("").startsWith(JsEngine.DEFAULT_EVAL)) {
            (true,currentHash.map { _.substring(JsEngine.DEFAULT_EVAL.length()) })
          } else if(currentHash.getOrElse("").startsWith(JsEngine.EVALJS)) {
            (true,currentHash.map { _.substring(JsEngine.EVALJS.length()) })
          } else {
            (false,currentHash)
          }
        }
        (true,isAScript,value)
    }

    val (previousScript,prevHash) = {
      if (previousHash.getOrElse("").startsWith(JsEngine.DEFAULT_EVAL)) {
        (true,previousHash.map { _.substring(JsEngine.DEFAULT_EVAL.length()) })
      } else if(previousHash.getOrElse("").startsWith(JsEngine.EVALJS)) {
        (true,previousHash.map { _.substring(JsEngine.EVALJS.length()) })
      } else {
        (false,previousHash)
      }
    }
    val prevAlgo = previousAlgo match {
      case None => currentAlgo match {
        case None => algos.headOption
        case current => current
      }
      case previous => previous
    }

    val initScript = {
      Script(OnLoad( JsRaw(s"""
       angular.bootstrap("#${formId}", ['password']);
       var scope = angular.element($$("#${formId}-controller")).scope();
       scope.$$apply(function(){
         scope.init(
             ${currentValue.map(Str(_).toJsCmd).getOrElse("undefined")}
           , ${currentAlgo.map(x =>Str(x.prefix).toJsCmd).getOrElse("undefined")}
           , ${isScript}
           , ${Str(currentAction).toJsCmd}
           , ${hashes.toJsCmd}
           , ${otherPasswords}
           , ${canBeDeleted}
           , ${scriptEnabled}
           , ${prevHash.map(Str(_).toJsCmd).getOrElse("undefined")}
           , ${prevAlgo.map(x =>Str(x.prefix).toJsCmd).getOrElse("undefined")}
           , ${previousScript}
         );
       });""")))
    }

    val form = (".password-section *+" #> valueInput).apply(PasswordField.xml(formId)) ++ initScript
    Full(form)
  }
}

object PasswordField {

  def xml(id : String) = {
      // Html template
    def agentScheduleTemplate = ChooseTemplate(List("templates-hidden", "components", "passwordInput"), "password-input")

    val css: CssSel =  ".tw-bs [id]" #> id &
    ".password-section [id]" #>  (id+"-controller")

    css(agentScheduleTemplate)

  }
}
class DerivedPasswordField(val id: String, val derivedType : HashAlgoConstraint.DerivedPasswordType) extends DirectiveField {
  self =>
  type ValueType = String
  def getPossibleValues(filters: (ValueType => Boolean)*): Option[Set[ValueType]] = None // not supported in the general cases
  def getDefaultValue = ""
  def manifest = manifestOf[String]
  override val uniqueFieldId = Full(id)
  def name = id
  def validate = Nil
  def validations = Nil
  def setFilter = Nil

  //the actual backend value like: sha1:XXXXXX
  private[this] var _x: String = getDefaultValue

  //the mapping between source algo and corresponding algo

  /*
   * Update internal value thanks to a new value from the
   * master passwords field
   */
  def updateValue(keepCurrentPwd: Boolean, blankPwd:Boolean, pastValue:String, newInput:String, chosenAlgo:HashAlgoConstraint) : String = {
    if(keepCurrentPwd) {
      /*nothing*/
    } else if(blankPwd || newInput == "") {
      _x = ""
    } else {
      _x = derivedType.hash(chosenAlgo).serialize(newInput.getBytes())
    }
    _x
  }

  def parseClient(s: String): Unit = {
    if (null == s) _x = "" else _x = s
  }
  def toClient: String = if (null == _x) "" else _x
  def get = _x

  //initialize the field
  def set(x: String) = {
    if (null == x || "" == x) _x = ""
    else _x = x
    _x
  }

  //we don't want to display ANYTHING for that field
           def toForm        = Full(NodeSeq.Empty)
  override def toFormNodeSeq = NodeSeq.Empty
  override def toHtmlNodeSeq = <span></span>
  override def displayValue  = <span></span>
  override def displayHtml   = Text("")
}

object FileField {

  def fileInput(kind : String)(id : String) = {
      // Html template
    def xml = ChooseTemplate(List("templates-hidden", "components", "directiveInput"), s"input-$kind")

    val css: CssSel =  ".tw-bs [id]" #> id  &
    "#fileInput [id]" #>  (id+"-fileInput") &
    "#browserFile [onclick]" #> ("showFileManager('"+id+"')") &
    "#browserFile [id]"  #> (id+"-browserFile")
    css(xml)
  }
}

class FileField(
  val id : String
) extends DirectiveField {
  self =>
  type ValueType = String
  protected var _x: String = getDefaultValue

  def get = _x
  def set(x: String) = { if (null == x) _x = "" else _x = x; _x }

  def toForm() = display(FileField.fileInput("shared"))

  def display( xml: String => NodeSeq) = {
    val formId = Helpers.nextFuncName
    val valueInput = SHtml.text(toClient, {s =>  parseClient(s)}, ("class","form-control input-sm col-xs-12") , ("id",formId+"-fileInput") )
    val initScript = {
      Script(OnLoad( JsRaw(s"""
        fileManager.constant('contextPath', '${S.contextPath}');
        angular.bootstrap("#${formId}", ['fileManager']);
      """)))
    }

    val form = (".input-group -*" #> valueInput).apply(xml(formId)) ++ initScript
    Full(form)

  }
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
