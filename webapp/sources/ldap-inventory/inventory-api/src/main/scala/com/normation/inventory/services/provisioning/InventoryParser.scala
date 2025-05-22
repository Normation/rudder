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

import com.normation.errors.*
import com.normation.inventory.domain.Inventory
import com.normation.inventory.domain.InventoryError
import com.normation.utils.XmlSafe
import java.io.InputStream
import scala.xml.*
import zio.*
import zio.syntax.*

/**
 * General interface to process inventory files
 * and transform them into system object
 *
 * In the future, it would be great if it was a separate jar
 * with a pluggable architecture.
 *
 * Perhaps we would like to look at things with "transformer/connector"
 * before inventing again the wheel. Perhaps should we take a look at
 * http://camel.apache.org/component.html
 */
trait InventoryParser {

  /**
   * Return the computer from the given input stream
   *
   * If the parsing does not succeed, we return Failure.
   * Else, we return an {@see Inventory} (more information about the
   * inventory structure on the object definition)
   *
   * This method is rather lax, in the meaning that almost any value can be empty (even Ids),
   * or not full, or partially false. We let all the sanitization / reconciliation / merging
   * problems to farther, clever part of the pipeline.
   *
   * In particular, we DO NOT bind os, vms and container here (what means that os#containers will
   * be empty even if the List<Container<StringId>> is not.
   */
  def fromXml(inventoryName: String, s: InputStream): IOResult[Inventory]

}

/**
 * A version of the InventoryParser that take care of the parsing
 * of the input stream into an XML doc, and so implementation can
 * directly deal with the XML document.
 */
trait XmlInventoryParser extends InventoryParser {

  override def fromXml(inventoryName: String, is: InputStream): IOResult[Inventory] = {
    (ZIO.attempt {
      XmlSafe.load(is)
    } mapError { ex => InventoryError.Deserialisation("Cannot parse uploaded file as an XML Fusion Inventory file", ex) })
      .flatMap(doc => {
        if (doc.isEmpty) {
          InventoryError.Inconsistency("Fusion Inventory file seems to be empty").fail
        } else {
          this.fromXmlDoc(inventoryName, doc)
        }
      })
  }

  def fromXmlDoc(inventoryName: String, xml: NodeSeq): IOResult[Inventory]
}

/**
 * A pipelined default implementation of the InventoryParser that
 * allows to add PostMarshall processor to manage non default datas.
 */
trait PipelinedInventoryParser extends XmlInventoryParser {

  def preParsingPipeline: Seq[PreInventoryParser]
  def inventoryParser:    XmlInventoryParser

  override def fromXmlDoc(inventoryName: String, xml: NodeSeq): IOResult[Inventory] = {
    // init pipeline with the data structure initialized by the configured inventoryParser
    for {
      // run each post processor of the pipeline sequentially, stop on the first which
      // return an empty result
      preProcessingOk <- ZIO.foldLeft(preParsingPipeline)(xml) { (inventory, preParsing) =>
                           preParsing(inventory).chainError(
                             s"Error when post processing inventory with '${preParsing.name}', abort"
                           )
                         }
      inventoryParsed <-
        inventoryParser.fromXmlDoc(inventoryName, preProcessingOk).chainError("Can not parse the given inventory file, abort")
    } yield {
      inventoryParsed
    }
  }
}
