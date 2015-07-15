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

package com.normation.cfclerk.stringtemplate

import org.junit.Test;
import org.junit._
import org.junit.Assert._

import org.antlr.stringtemplate._;
import org.antlr.stringtemplate.language._;
import com.normation.stringtemplate.language._;
import junit.framework.TestSuite
import antlr.TokenStreamRecognitionException
import org.junit.runner.RunWith
import org.junit.runners.BlockJUnit4ClassRunner
import org.joda.time.DateTime
import org.joda.time.format._
import com.normation.stringtemplate.language.formatter._
import com.normation.cfclerk.domain._

@RunWith(classOf[BlockJUnit4ClassRunner])
class TemplateTest {

  @Test
  def helloWorldTest() {
    val hello = new StringTemplate("Hello, &name&", classOf[NormationAmpersandTemplateLexer]);
    hello.setAttribute("name", "World");
    assert("Hello, World" == hello.toString)
  }

  @Test
  def arrayTest() {
    val hello = new StringTemplate("&list1,list2:{ n,p |&n&:&p&}&", classOf[NormationAmpersandTemplateLexer]);
    hello.setAttribute("list1", "chi");
    hello.setAttribute("list1", "fou");
    hello.setAttribute("list1", "mi");
    hello.setAttribute("list2", "bar");
    hello.setAttribute("list2", "bazz");

    assert("chi:barfou:bazzmi:" == hello.toString)

    val nonTemplated = new StringTemplate("$(sys.workdir)/bin/cf-agent -f failsafe.cf \\&\\& $(sys.workdir)/bin/cf-agent", classOf[NormationAmpersandTemplateLexer]);
    assert("$(sys.workdir)/bin/cf-agent -f failsafe.cf && $(sys.workdir)/bin/cf-agent" == nonTemplated.toString)
  }

  @Test
  def templateLoadingTest() {
    val group =  new StringTemplateGroup("myGroup", classOf[NormationAmpersandTemplateLexer]);
    val templatetest = group.getInstanceOf("template");

    templatetest.setAttribute("title", "test of a template")
    assert("<title>test of a template</title>" == templatetest.toString)
  }

  @Test
  def multiTemplatesLoadingTest() {
    val group =  new StringTemplateGroup("myGroup", classOf[NormationAmpersandTemplateLexer]);
    val templatetest = group.getInstanceOf("templates1/templatetest");

    templatetest.setAttribute("title", "test of a template")
    assert("<title>test of a template</title>" == templatetest.toString)


    val template2test = group.getInstanceOf("templates2/templatetest");

    template2test.setAttribute("title", "test of a template")
    assert("<boo>test of a template</boo>" == template2test.toString)
  }

  @Test
  def templatesWithVarsTest() {
    val group =  new StringTemplateGroup("myGroup", classOf[NormationAmpersandTemplateLexer]);
    val templatetest = group.getInstanceOf("templates1/vartest");

    assert("\"cfserved\" string => \"$POLICY_SERVER\";\n\"$(file[$(fileParameters)][1])\"" == templatetest.toString)

    val amptest = group.getInstanceOf("templates1/amptest");
  }


  @Test ( expected = classOf[ IllegalArgumentException ] )
  def notExistingTemplateTest() {
    val group =  new StringTemplateGroup("myGroup", classOf[NormationAmpersandTemplateLexer]);
    val templatetest = group.getInstanceOf("templates1/azertyui");

  }

  @Test
  def dateRenderingTest() {
    val vared = new StringTemplate("&date;format=\"cfengine_datetime\"&", classOf[NormationAmpersandTemplateLexer]);
    val date =ISODateTimeFormat.dateTimeParser.parseDateTime("2010-01-24T21:28:32.309+01:00")

    vared.setAttribute("date", date);
    val dateRenderer = new DateRenderer()
    vared.registerRenderer(classOf[DateTime], dateRenderer);
    assert("2010:01:24:09:28:32" == vared.toString)
  }

  @Test
  def nativeDateRenderingTest() {
    val vared = new StringTemplate("&date;format=\"cfengine_datetime\"&", classOf[NormationAmpersandTemplateLexer]);
    val date =ISODateTimeFormat.dateTimeParser.parseDateTime("2010-01-24T21:28:32.309+01:00")

    val variable = InputVariable(InputVariableSpec("date", "this is a native date object", constraint = Constraint(DateTimeVType())), Seq(date.toString))

    vared.setAttribute("date", variable.getTypedValues.get.head)
    val dateRenderer = new DateRenderer()
    vared.registerRenderer(classOf[DateTime], dateRenderer)

    assert("2010:01:24:09:28:32" == vared.toString)
  }

  @Test
  def conditionTest() {
    val hello = new StringTemplate("&if(CLIENTSLIST)&hello&endif&", classOf[NormationAmpersandTemplateLexer]);
    hello.setAttribute("CLIENTSLIST", true);

    assertEquals("hello",hello.toString)

    val foo = new StringTemplate("&if(CLIENTSLIST)&hello&endif&foo", classOf[NormationAmpersandTemplateLexer]);
    foo.setAttribute("CLIENTSLIST", false);

    assertEquals("foo",foo.toString)

  }

/*
  @Test
  def invalidTemplateTest {
    val group =  new StringTemplateGroup("myGroup", classOf[NormationAmpersandTemplateLexer]);

    val errorListener = group.getErrorListener()

    val templatetest = group.getInstanceOf("templates1/invalid");

    templatetest.setAttribute("title", "a non existing variable")
  }
*/
}




