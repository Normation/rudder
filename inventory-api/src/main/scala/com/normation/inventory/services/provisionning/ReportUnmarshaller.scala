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

package com.normation.inventory.services.provisioning

import com.normation.inventory.domain.InventoryReport
import com.normation.utils.Control.pipeline
import java.io.InputStream
import net.liftweb.common._
import scala.xml._


/**
 * General interface to process File report
 * and transform them into system object
 * 
 * In the future, it would be great if it was a separate jar
 * with a pluggable architecture.
 * 
 * Perhaps we would like to look at things with "transformer/connector"
 * before inventing again the wheel. Perhaps should we take a look at
 * http://camel.apache.org/component.html
 */
trait ReportUnmarshaller {

  /**
   * Return the computer from the given input stream
   * 
   * If the parsing does not succeed, we return Failure.
   * Else, we return a {@see Report} (more information about the
   * report structure on the object definition)
   * 
   * This method is rather lax, in the meaning that almost any value can be empty (even Ids), 
   * or not full, or partially false. We let all the sanitization / reconciliation / merging
   * problems to farther, clever part of the pipeline.
   * 
   * In particular, we DO NOT bind os, vms and container here (what means that os#containers will
   * be empty even if the List<Container<StringId>> is not.
   */
  def fromXml(reportName:String,s:InputStream) : Box[InventoryReport] 
  
}

/**
 * A version of the ReportUnmarshaller that take care of the parsing
 * of the input stream into an XML doc. 
 */
trait ParsedReportUnmarshaller extends ReportUnmarshaller {

  override def fromXml(reportName:String,is:InputStream) : Box[InventoryReport] = {
    (try {
      Full(XML.load(is))
    } catch {
      case e:SAXParseException => Failure("Cannot parse uploaded file as an XML Fusion Inventory report",Full(e),Empty)
    }) match {
      case f@Failure(m,e,c) => f
      case Full(doc) if(doc.isEmpty) => Failure("Fusion Inventory report seem's to be empty")
      case Empty => Failure("Fusion Inventory report seem's to be empty")
      case Full(x) => this.fromXmlDoc(reportName, x)
    }    
  }
  
  def fromXmlDoc(reportName:String, xml:NodeSeq) : Box[InventoryReport] 
}
  
/**
 * A pipelined default implementation of the ReportUnmarshaller that 
 * allows to add PostMarshall processor to manage non default datas. 
 */
trait PipelinedReportUnmarshaller extends ParsedReportUnmarshaller {
  
  def preUnmarshallPipeline : Seq[PreUnmarshall]
  def reportUnmarshaller : ParsedReportUnmarshaller
  
  override def fromXmlDoc(reportName:String, xml:NodeSeq) : Box[InventoryReport] = {
    //init pipeline with the data structure initialized by the configured reportUnmarshaller
    for {
       //run each post processor of the pipeline sequentially, stop on the first which
       //return an empty result
      preProcessingOk <- pipeline(preUnmarshallPipeline,xml) { case (preUnmarshall,report) =>
        preUnmarshall(report) ?~! "Error when post processing report with '%s', abort".format(preUnmarshall.name)
      }
      reportParsed <- reportUnmarshaller.fromXmlDoc(reportName, preProcessingOk) ?~! "Can not parse the given report, abort"
    } yield {
      reportParsed
    }
  }
}

