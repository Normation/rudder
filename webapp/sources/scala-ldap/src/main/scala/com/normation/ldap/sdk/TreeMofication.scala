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

package com.normation.ldap.sdk

import com.unboundid.ldap.sdk.{DN,Modification}
import com.normation.utils.HashcodeCaching


/**
 * DataType that represent modification made to
 * a tree.
 */
sealed abstract class TreeModification
case object NoMod extends TreeModification
case class Add(tree:LDAPTree) extends TreeModification with HashcodeCaching
case class Delete(tree:Tree[DN]) extends TreeModification with HashcodeCaching
case class Replace(mods:(DN,Seq[Modification])) extends TreeModification with HashcodeCaching
