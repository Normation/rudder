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

package com.normation.inventory.services.provisioning

import com.normation.inventory.domain.InventoryReport
import java.io.InputStream

import com.normation.inventory.domain.InventoryError
import com.normation.inventory.domain.InventoryResult._
import scalaz.zio._
import scalaz.zio.syntax._

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
  def fromXml(reportName:String, s:InputStream) : InventoryResult[InventoryReport]

}

/**
 * A version of the ReportUnmarshaller that take care of the parsing
 * of the input stream into an XML doc.
 */
trait ParsedReportUnmarshaller extends ReportUnmarshaller {

  override def fromXml(reportName:String,is:InputStream) : InventoryResult[InventoryReport] = {
    (Task.effect {
      XML.load(is)
    } mapError {
      case e:SAXParseException => InventoryError.Deserialisation("Cannot parse uploaded file as an XML Fusion Inventory report", e)
    }).flatMap( doc =>
      if(doc.isEmpty) {
        InventoryError.Inconsistency("Fusion Inventory report seem's to be empty").fail
      } else {
        this.fromXmlDoc(reportName, doc)
      }
    )
  }

  def fromXmlDoc(reportName:String, xml:NodeSeq) : InventoryResult[InventoryReport]
}

/**
 * A pipelined default implementation of the ReportUnmarshaller that
 * allows to add PostMarshall processor to manage non default datas.
 */
trait PipelinedReportUnmarshaller extends ParsedReportUnmarshaller {

  def preUnmarshallPipeline : Seq[PreUnmarshall]
  def reportUnmarshaller : ParsedReportUnmarshaller

  override def fromXmlDoc(reportName:String, xml:NodeSeq) : InventoryResult[InventoryReport] = {
    //init pipeline with the data structure initialized by the configured reportUnmarshaller
    for {
       //run each post processor of the pipeline sequentially, stop on the first which
       //return an empty result
      preProcessingOk <- ZIO.foldLeft(preUnmarshallPipeline)(xml) { (report, preUnmarshall) =>
        preUnmarshall(report).chainError(s"Error when post processing report with '${preUnmarshall.name}', abort")
      }
      reportParsed <- reportUnmarshaller.fromXmlDoc(reportName, preProcessingOk).chainError("Can not parse the given report, abort")
    } yield {
      reportParsed
    }
  }
}

