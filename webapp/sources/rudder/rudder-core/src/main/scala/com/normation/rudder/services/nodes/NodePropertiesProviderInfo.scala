/*
*************************************************************************************
* Copyright 2017 Normation SAS
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

package com.normation.rudder.services.nodes

import com.normation.rudder.domain.nodes.PropertyProvider

import net.liftweb.common.Box
import net.liftweb.common.Full
import net.liftweb.json.JsonAST.JValue

/*
 * Information available for the provider, for the given provider / key couple
 */
final case class ProviderKeyInfo(
    /*
     * A human readable name or very short description
     */
    name: String

    /*
     * A semi-structure data bag to give more information
     * about the provider/key:
     */
  , description: JValue

    /*
     * A relative link to rudder base url, i.e relative
     * to https://..../rudder/
     *
     * The link is expected to point to the provider details
     * for that key.
     */
  , link: Option[String]
)

/*
 * A service that allows to get information about NodePropreties metadata provider.
 * The default case is when no specific metadata are defined for a node property provider.
 *
 * The goal of that service is to be extensible by third-party providers.
 *
 */

class NodePropertiesProviderInfo() {

  private[this] val providers = collection.mutable.Buffer[NodePropertiesProviderInfoExtension]()

  def getInfo(provider: PropertyProvider, key: String): Box[Option[ProviderKeyInfo]] = {
    providers.find(p => p.isDefined(provider)) match {
      // default Rudder information
      case None    => Full(None)
      case Some(p) => p.getInfo(key)
    }
  }

  /**
   * The extension point that each node properties new provider must
   * call to add a hook.
   */
  def append(extension: NodePropertiesProviderInfoExtension): Unit = {
    providers.append(extension)
  }
}

/**
 * And this is the extension point that should be implemented
 */
trait NodePropertiesProviderInfoExtension {

  def isDefined(provider: PropertyProvider): Boolean

  def getInfo(key: String): Box[Option[ProviderKeyInfo]]

}


