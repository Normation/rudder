/*
*************************************************************************************
* Copyright 2011 Normation SAS
*************************************************************************************
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*
*************************************************************************************
*/

package com.normation.utils

import scala.xml.{Node,NodeSeq}
import net.liftweb.common._
import scala.xml.XML
import scala.xml.Elem
import org.xml.sax.SAXParseException
import java.io.InputStream
import com.normation.exceptions.TechnicalException

object XmlUtils {
  
  
  /**
   * Retrieve exactly one element of the given name in
   * the given node children.
   * If subtree is set to true (default to false), look
   * for all tree elements, not only direct children.
   */
  def getUniqueNode(root:Node, nodeName:String, subtree:Boolean = false) : Box[Node] = {
    def checkCardinality(nodes:NodeSeq) : Box[Node] = {
      if(nodes.size < 1) Failure("No node found for name %s in %s children with scope %s".format(nodeName, root, if(subtree) "subtree" else "one level"))
      else if(nodes.size > 1 ) Failure("More than one node found for name %s in %s children with scope %s".format(nodeName, root, if(subtree) "subtree" else "one level"))
      else Full(nodes.head)
    }
    
    if(subtree) {
      checkCardinality((root.child:NodeSeq) \\ nodeName)
    } else {
      checkCardinality(root \ nodeName)
    }
  }

  //text version of XmlUtils.getUniqueNode with a default value
  //The default value is also applied if node exists but its text is empty
  def getUniqueNodeText(root: Node, nodeName: String, default: String) =
    getUniqueNode(root, nodeName).map(_.text.trim) match {
      case x: EmptyBox => default
      case Full(x) => x match {
        case "" | null => default
        case text => text
      }
    }

  def getAttributeText(node: Node, name: String, default: String) = {
    val seq = node \ ("@" + name)
    if (seq.isEmpty) default else seq.head.text
  }
  
  
  /**
   * Parse the file denoted by input stream (filePath is only
   * for explicit error messages)
   */
  def parseXml(is: InputStream, filePath : Option[String] = None) : Box[Elem] = {
    val name = filePath.getOrElse("[unknown]")
    for {
      doc <- try {
               Full(XML.load(is))
             } catch {
               case e: SAXParseException =>0
                 Failure("Unexpected issue with the XML file %s: %s".format(name, e.getMessage), Full(e), Empty)
               case e: java.net.MalformedURLException =>
                 Failure("XML file not found: " + name, Full(e), Empty)
             }
      nonEmpty <- if (doc.isEmpty) { 
                    Failure("Error when parsing XML file: '%s': the parsed document is empty".format(name))
                  } else {
                    Full("ok")
                  }
    } yield {
      doc
    }
  }
  
  /**
   * Trim spaces from an XML elem
   */def trim(elt:Elem) = scala.xml.Utility.trim(elt) match {
      case e:Elem => e
      case x => throw new TechnicalException("Bad returned type for xml.trim. Awaiting an Elem, got: " + x)
    } 
}
