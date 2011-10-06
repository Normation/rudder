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
}