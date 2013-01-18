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

package net.liftweb.http

import java.util.Locale
import java.io.InputStream
import scala.xml._
import net.liftweb.common._
import net.liftweb.util._
import net.liftweb.util.Helpers._
import org.springframework.core.io.ClassPathResource

/**
 * That class is mostly a copy&paste of net.liftweb.http.Templates,
 * but adapted to work for templates in classpath. 
 *
 */
object ClasspathTemplates extends Loggable {

  /**
   * Given a list of paths (e.g. List("foo", "index")),
   * find the template in the classpath.  
   * This method runs checkForContentId
   * on any found templates.  To get the raw template,
   * use findRawTemplate
   * @param places - the path to look in
   *
   * @return the template if it can be found
   */
  def apply(places: List[String]): Box[NodeSeq] = 
    apply(places, S.locale)


  /**
   * Given a list of paths (e.g. List("foo", "index")),
   * find the template in the classpath.  
   * This method runs checkForContentId
   * on any found templates.  To get the raw template,
   * use findRawTemplate
   * @param places - the path to look in
   * @param locale the locale of the template
   *
   * @return the template if it can be found
   */
  def apply(places: List[String], locale: Locale): Box[NodeSeq] = 
    findRawTemplate(places, locale).map(Templates.checkForContentId)
  
    /**
   * location is awaited in the format: "com" :: "normation" :: "foo" :: "template"
   * for a template "template.html" in package com/normation/foo 
   */
  def findRawTemplate(places: List[String], locale: Locale = S.locale) : Box[NodeSeq] = {
    val parserFunction: InputStream => Box[NodeSeq] = S.htmlProperties.htmlParser
    val name = places.mkString("/") + ".html"
    
    //copy&pasted from net.liftwebhttp.Templates.findRawTemplate
    val lrCache = LiftRules.templateCache
    val cache = lrCache.getOrElse(NoCache)
    val key = (locale, places)
    val tr = cache.get(key)
    
    if (tr.isDefined) tr
    else {
      import scala.xml.dtd.ValidationException
      val xmlb = try {
        val rule = new ClassPathResource(name)
        var is : InputStream = null
        try {
          is = rule.getInputStream
          if(null != is) parserFunction(is)
          else Failure("Input stream for resource '%s' is null".format(name))
        } finally {
          if(null != is) is.close
        }   
      } catch {
        case e: ValidationException if Props.devMode | Props.testMode =>
          val msg = Helpers.errorDiv(<div>Error locating template: <b>{name}</b><br/>
            Message: <b>{e.getMessage}</b><br/>
            {
              <pre>{e.toString}{e.getStackTrace.map(_.toString).mkString("\n")}</pre>
            }
            </div>)
            
          logger.error("Error was: " + e.getMessage)
            
          return msg
            
        case e: ValidationException => Empty
      }
      
      xmlb match {
        case Full(x) => 
          cache(key) = x
          Full(x)
        case f:Failure if(Props.devMode | Props.testMode) =>
          val msg = xmlb.asInstanceOf[Failure].msg
          val e = xmlb.asInstanceOf[Failure].exception
          val xml = Helpers.errorDiv(<div>Error locating template: <b>{name}</b><br/>Message: <b>{msg}</b><br/>{
            {
              e match {
              case Full(e) =>
              <pre>{e.toString}{e.getStackTrace.map(_.toString).mkString("\n")}</pre>
              case _ => NodeSeq.Empty
              }
            }}
          </div>)
  
          logger.error("Error was:" + msg)
          
          xml

        case _ => Failure("Not found")
      }
    }
  }
}