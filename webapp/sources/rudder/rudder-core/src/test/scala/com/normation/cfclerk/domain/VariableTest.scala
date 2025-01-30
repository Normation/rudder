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

package com.normation.cfclerk.domain

import com.normation.cfclerk.domain.HashAlgoConstraint.*
import com.normation.cfclerk.xmlparsers.*
import com.normation.rudder.services.policies.write.CFEngineAgentSpecificGeneration
import java.io.FileNotFoundException
import org.joda.time.format.*
import org.junit.runner.RunWith
import org.specs2.matcher.MatchResult
import org.specs2.mutable.*
import org.specs2.runner.*
import org.xml.sax.SAXParseException
import scala.collection.mutable
import scala.xml.*

@RunWith(classOf[JUnitRunner])
class VariableTest extends Specification {
  def variableSpecParser = new VariableSpecParser()

  implicit class EitherToThrow[T](res: Either[LoadTechniqueError, T]) {
    def orThrow: T = res match {
      case Right(value) => value
      case Left(error)  => throw new IllegalArgumentException(s"Variable error in test: ${error.fullMsg}")
    }
  }

  val nbVariables = 27

  val refName         = "name"
  val refDescription  = "description"
  val refValue        = "value"
  val refVariableName = Some("variable_name")
  val dateValue       = "2010-01-16T12:00:00.000+01:00"
  val listValue       = "value1;value2"
  val defaultValue    = "default_value"

  val rawValue  = """This is a test \ " \\ """
  val escapedTo = """This is a test \\ \" \\\\ """

  val refItem: Seq[ValueLabel] = Seq(ValueLabel("value", "label"), ValueLabel("value2", "label2"))

  val unvalidValue = "bobby"

  val simpleName         = "$SIMPLEVARIABLE";
  val withDefault        = "WITH_DEFAULT"
  val select1            = "SELECT1_TEST"
  val itemName           = "$VARIABLEITEM";
  val regexCardNumberVar = "$CARDNUMBER"
  val sizeVar            = "$SIZE"
  val mailVar            = "$MAIL"
  val ipVar              = "$IP"
  val varName            = "$VARMACHINE"
  val varDate            = "$VARDATE"
  val varList            = "varlist"
  val rawVar             = "raw_type"
  val gui_only           = "gui_only"

  val variables: mutable.Map[String, Variable] = {
    val doc = {
      try {
        XML.load(ClassLoader.getSystemResourceAsStream("testVariable.xml"))
      } catch {
        case e: SAXParseException              => throw new Exception("Unexpected issue (unvalid xml?) with testVariable.xml ")
        case e: java.net.MalformedURLException => throw new FileNotFoundException("testVariable.xml file not found ")
      }
    }
    if (doc.isEmpty) {
      throw new Exception("Unexpected issue (unvalido xml?) with the testvariable file ")
    }

    val variables = scala.collection.mutable.Map[String, Variable]()
    for {
      elt      <- (doc \\ "VARIABLES")
      specNode <- elt.nonEmptyChildren
      if (!specNode.isInstanceOf[Text])
    } {
      val (spec, other) = variableSpecParser
        .parseSectionVariableSpec("default section", specNode)
        .getOrElse(throw new IllegalArgumentException("I'm a failing test!"))
      variables += {
        // special case for reportkeys because name depends of section name, and here, we
        // don't have several sections
        spec match {
          case _: PredefinedValuesVariableSpec =>
            val v = spec.toVariable()
            if (v.values.size == 1) "predef_1" -> v
            else "predef_2"                    -> v
          case _ => spec.name -> spec.toVariable()
        }
      }
      variables ++= other.map(x => (x.name, x.toVariable()))
    }
    variables
  }

  "SYSTEM_VARIABLE tag" should {
    "lead to an exception" in {
      val sysvar = (for {
        elt      <- (XML.load(ClassLoader.getSystemResourceAsStream("testSystemVariable.xml")) \\ "VARIABLES")
        specNode <- elt.nonEmptyChildren
        if (!specNode.isInstanceOf[Text])
      } yield {
        variableSpecParser.parseSectionVariableSpec("default section", specNode)
      })
      sysvar.size === 1
      sysvar.head must beLeft
    }
  }

  "variables map" should {
    "be so that" in {
      variables.size mustEqual nbVariables
      variables must haveKeys(
        simpleName,
        select1,
        itemName,
        regexCardNumberVar,
        sizeVar,
        mailVar,
        ipVar,
        varName,
        varDate,
        varList,
        gui_only,
        rawVar
      )
      variables must haveKeys((1 to 6).map("password" + _)*)

    }
  }

  ///////////////// generic tests about variable construction, do not use files /////////////////

  "Unsetted Variable" should {
    implicit val variable = InputVariable(InputVariableSpec(refName, refDescription, refVariableName, id = None), Seq())
    haveName()
    haveDescription()
  }

  "Variable" should {
    val v = InputVariable(InputVariableSpec(refName, refDescription, refVariableName, id = None), Seq())
    implicit val variable: InputVariable = v.copyWithSavedValue(refValue).orThrow

    haveName()
    haveDescription()
    haveValue()
  }

  "Multivalued variable" should {
    val variable =
      InputVariable(InputVariableSpec(refName, refDescription, refVariableName, multivalued = true, id = None), Seq())
    implicit val v: InputVariable = variable.copyWithSavedValues(listValue.split(";").toSeq).orThrow

    haveName()
    haveDescription()
    haveNbValues(2)
  }

  "Select variable" should {
    val variable =
      SelectVariable(SelectVariableSpec(refName, refDescription, refVariableName, valueslabels = refItem, id = None), Seq())
    implicit val v: SelectVariable = variable.copyWithSavedValue(refValue).orThrow

    haveName()
    haveDescription()
    haveValue()
  }

  "Input variable" should {
    val variable = InputVariable(InputVariableSpec(refName, refDescription, refVariableName, id = None), Seq())
    implicit val v: InputVariable = variable.copyWithSavedValue(refValue).orThrow

    haveName()
    haveDescription()
    haveValue()
  }

  "Nulled variable" should {
    val variable = InputVariable(InputVariableSpec(refName, refDescription, refVariableName, id = None), Seq())
    implicit val v: InputVariable = variable.copyWithSavedValue(null).orThrow

    haveName()
    haveDescription()
    haveNbValues(0)
  }

  "Valid variable" should {
    val variable = InputVariable(InputVariableSpec(refName, refDescription, refVariableName, id = None), Seq())
    implicit val v: InputVariable = variable.copyWithSavedValue(refValue).orThrow

    haveName()
    haveDescription()
    haveValue()
  }

  "Boolean variable" should {
    val variable = {
      InputVariable(
        InputVariableSpec(refName, refDescription, refVariableName, constraint = Constraint(BooleanVType), id = None),
        Seq()
      )
    }

    implicit val v: InputVariable = variable.copyWithSavedValue("true").orThrow
    haveName()
    haveDescription()
    haveValue(true)
  }

  "Boolean variable" should {
    val variable = {
      InputVariable(
        InputVariableSpec(refName, refDescription, refVariableName, constraint = Constraint(BooleanVType), id = None),
        Seq()
      )
    }

    implicit val v: InputVariable = variable.copyWithSavedValue("false").orThrow
    haveValue(false)
  }

  "Invalid variable" should {
    implicit val variable =
      new SelectVariable(SelectVariableSpec(refName, refDescription, refVariableName, valueslabels = refItem, id = None), Seq())

    "throw a VariableException" in {
      variable.copyWithSavedValue(unvalidValue) must beLeft[LoadTechniqueError]
    }

    haveName()
    haveDescription()
  }

  ///////////////// test parsed variables /////////////////

  "Parsed variable" should {
    implicit val simpleVariable = variables(simpleName)
    beAnInput
    saveHaveValue()

    val constrainedVariable = variables(itemName)
    saveHaveValue()(constrainedVariable)
    beASelect(constrainedVariable)
  }

  "Variable with default" should {
    implicit val v = variables(withDefault)

    haveName(withDefault)
    haveDescription("Simple variable with default")
    haveValue(defaultValue)
  }

  "Date variable" should {
    implicit val dateVariable = variables(varDate)
    beAnInput
    haveType("datetime")

    haveValue(ISODateTimeFormat.dateTimeParser.parseDateTime(dateValue).toString)(
      dateVariable.copyWithSavedValue(dateValue).orThrow
    )
  }

  "Parsed variable having unvalid value" should {
    implicit val constrainedVariable = variables(itemName)

    "throw a VariableException" in {
      constrainedVariable.copyWithSavedValue(unvalidValue) must beLeft[LoadTechniqueError]
    }

    // I *DO* think that that value should not have any values.
    // In the past, it used to have, because the variable was stateful and was
    // set in "Parsed variable", but there is no reason to be the case.

    // haveValue()
  }

  "varlist" should {
    implicit val listVariable = variables(varList)
    beAnInput
    haveType("string")
    notBeMultivalued
  }

  "Raw variable" should {
    implicit val rawVariable = variables(rawVar)
    haveType("raw")

    "have correct content" in {
      (rawVariable
        .copyWithSavedValue(rawValue)
        .orThrow
        .getValidatedValue(identity)
        .getOrElse(throw new Exception("Invalid content for the raw variable")) must containTheSameElementsAs(Seq(rawValue)))
    }
  }

  "Simple variable " should {
    implicit val simpleVariable = variables(simpleName)
    haveType("string")
    "correctly escape variable" in {
      simpleVariable
        .copyWithSavedValue(rawValue)
        .orThrow
        .getValidatedValue(CFEngineAgentSpecificGeneration.escape)
        .getOrElse(throw new Exception("Invalid content for the escaped variable")) must containTheSameElementsAs(Seq(escapedTo))
    }
  }

  "select 1" should {
    implicit val v = variables(select1)
    beASelect1
    haveName("SELECT1_TEST")
  }

  checkType("size-kb", sizeVar)
  checkType("mail", mailVar)
  checkType("ip", ipVar)

  "An input with a 'regex'" should {
    val input = variables(regexCardNumberVar)

    "have its regex field correctly set" in {
      input.spec.constraint.typeName match {
        case s: StringVType =>
          s.regex mustEqual Some(RegexConstraint("""\d\d\d\d-\d\d\d\d-\d\d\d\d-\d\d\d\d""", "must resemble 1234-1234-1234-1234"))
        case t => ko(s"Variable type '${t.name}' can not have a regex")
      }

    }
  }

  def testSpecVarFields(
      spec:            VariableSpec,
      longDescription: String = "",
      defaultValue:    Option[String] = None
  ): MatchResult[Serializable] = {
    spec.longDescription === longDescription and
    spec.constraint.default === defaultValue
  }

  "Variables which may be empty" should {
    // varlist can be empty, the others can't
    val listVariable = variables(varList)
    "have mayBeEmpty set to true" in {
      listVariable.spec.constraint.mayBeEmpty
    }
  }

  "Variables which may not be empty" should {
    "have mayBeEmpty set to false" in {
      val vars = variables(itemName) :: variables(simpleName) :: variables(varName) :: variables(varDate) :: Nil
      vars forall (!_.spec.constraint.mayBeEmpty) must beTrue
    }
  }

  "password1" should {
    implicit val v = variables("password1")
    beAPassword
    haveNoAlgo
  }

  "password2" should {
    implicit val v = variables("password2")
    beAPassword
    haveAlgo(PLAIN :: Nil)
  }

  "password3" should {
    implicit val v = variables("password3")
    beAPassword
    haveAlgo(MD5 :: Nil)
  }

  "password4" should {
    implicit val v = variables("password4")
    beAPassword
    haveAlgo(MD5 :: Nil)
  }

  "password5" should {
    implicit val v = variables("password5")
    beAPassword
    haveAlgo(SHA1 :: Nil)
  }

  "password6" should {
    implicit val v = variables("password6")
    beAPassword
    haveAlgo(SHA256 :: Nil)
  }

  "password7" should {
    implicit val v = variables("password7")
    beAPassword
    haveAlgo(MD5 :: SHA256 :: PLAIN :: Nil)
  }

  "password8" should {
    implicit val v = variables("password8")
    beAPassword
    haveAlgo(SHA512 :: Nil)
  }

  "password9" should {
    implicit val v = variables("password9")
    beAPassword
    haveAlgo(LinuxShadowMD5 :: Nil)
  }

  "password10" should {
    implicit val v = variables("password10")
    beAPassword
    haveAlgo(LinuxShadowSHA256 :: Nil)
  }

  "password11" should {
    implicit val v = variables("password11")
    beAPassword
    haveAlgo(LinuxShadowSHA512 :: Nil)
  }

  "unvalide password algo" should {
    val p = {
      <INPUT>
      <NAME>password6</NAME>
      <DESCRIPTION>Some password</DESCRIPTION>
      <CONSTRAINT>
        <TYPE>password</TYPE>
        <PASSWORDHASH>Not a real algo</PASSWORDHASH>
      </CONSTRAINT>
    </INPUT>
    }

    "throw a parsing error" in {
      variableSpecParser.parseSectionVariableSpec("some section", p) must throwA[ConstraintException]
    }
  }

  "password_master" should {
    val v     = variables("password_master")
    val algos = LinuxShadowSHA512 :: Nil

    "Be an input master password input" in {
      v.spec.isInstanceOf[InputVariableSpec] and
      v.spec.constraint.typeName.name == "masterPassword"
    }

    s"Have hash algorithm of type ${algos.map(_.prefix).mkString(",")}" in {
      v.spec.constraint.typeName match {
        case MasterPasswordVType(a) => a ==== (algos)
        case _                      => ko("Variable is not a password input")
      }
    }
  }

  // predef variables

  "unvalid predef value (empty VALUES tag)" should {
    val p = {
      <REPORTKEYS>
      <VALUES/>
    </REPORTKEYS>
    }

    "throw a parsing error" in {
      variableSpecParser.parseSectionVariableSpec("some section", p) must throwA[EmptyReportKeysValue]
    }
  }

  "predef_1" should {
    implicit val v = variables("predef_1")
    beAPredefVal
    provideAndHaveValues("val1")
  }

  "predef_2" should {
    implicit val v = variables("predef_2")
    beAPredefVal
    provideAndHaveValues("val1", "val2")
  }

  ///
  /// Utility methods
  ///

  private def checkType(typeName: String, varName: String) = {
    "An input with a type '%s'".format(typeName) should {
      val input = variables(varName)
      "have a constraint with a type '%s'".format(typeName) in {
        input.spec.constraint.typeName.name mustEqual typeName
      }
    }
  }

  private def beAnInput(implicit variable: Variable) = {
    "Be an input variable" in {
      variable.spec.isInstanceOf[InputVariableSpec]
    }
  }

  private def beASelect1(implicit variable: Variable) = {
    "Be an input select 1" in {
      variable.spec.isInstanceOf[SelectOneVariableSpec]
    }
  }

  private def beASelect(implicit variable: Variable) = {
    "Be an input select" in {
      variable.spec.isInstanceOf[SelectVariableSpec]
    }
  }

  private def beAPassword(implicit variable: Variable) = {
    "Be an input password input" in {
      variable.spec.isInstanceOf[InputVariableSpec] and
      variable.spec.constraint.typeName.name == "password"
    }
  }

  private def beAPredefVal(implicit variable: Variable) = {
    "Be an PREDEF val" in {
      variable.spec.isInstanceOf[PredefinedValuesVariableSpec]
    }
  }

  private def haveDescription(description: String = refDescription)(implicit variable: Variable) = {
    "have description '%s'".format(description) in {
      variable.spec.description mustEqual description
    }
  }

  private def haveName(name: String = refName)(implicit variable: Variable) = {
    "have name '%s'".format(name) in {
      variable.spec.name mustEqual name
    }
  }

  private def haveValue[T](value: T = refValue)(implicit variable: Variable) = {
    "have value '%s'".format(value) in {
      variable.getValidatedValue(identity).getOrElse(throw new Exception("for test")).head mustEqual value
    }
  }

  private def saveHaveValue(value: String = refValue)(implicit variable: Variable) = {
    haveValue(value)(variable.copyWithSavedValue(value).orThrow)
  }

  private def haveNbValues(nbValues: Int)(implicit variable: Variable) = {
    "have %d values".format(nbValues) in {
      variable.values.length mustEqual nbValues
    }
  }

  private def haveType(typeName: String)(implicit variable: Variable) = {
    "have type " + typeName in {
      variable.spec.constraint.typeName.name mustEqual typeName
    }
  }

  private def provideAndHaveValues(values: String*)(implicit variable: Variable) = {
    s"have both provided values and set values '${values.mkString(",")}'" in {
      variable match {
        case v: PredefinedValuesVariable =>
          v.spec.nelOfProvidedValues === values.toList and
          v.values.toList === values.toList
        case _ => ko(s"Variable ${variable} is not a predefined variable type and so can not provides values")
      }
    }
  }

  private def notBeMultivalued(implicit variable: Variable) = {
    "not be multivalued (because it is not a valid tag of variable spec)" in {
      !variable.spec.multivalued
    }
  }

  private def haveNoAlgo(implicit variable: Variable) = {
    s"Have an user defined hash algorithm (and so none constrained)" in {
      variable.spec.constraint.typeName match {
        case PasswordVType(algos) => algos must containTheSameElementsAs(HashAlgoConstraint.values)
        case _                    => ko("Variable is not a password input")
      }
    }
  }

  private def haveAlgo(algos: List[HashAlgoConstraint])(implicit variable: Variable) = {
    s"Have hash algorithm of type ${algos.map(_.prefix).mkString(",")}" in {
      variable.spec.constraint.typeName match {
        case PasswordVType(a) => a ==== (algos)
        case _                => ko("Variable is not a password input")
      }
    }
  }
}
