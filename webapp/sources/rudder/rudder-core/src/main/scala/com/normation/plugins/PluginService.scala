/*
 *************************************************************************************
 * Copyright 2025 Normation SAS
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
package com.normation.plugins

import com.normation.errors.*
import zio.*

/**
  * A service to manage plugins, it is an abstraction over system administration of plugins
  */
trait PluginService {

  def updateIndex(): IOResult[Option[RudderError]]
  def list():        IOResult[Chunk[Plugin]]
  def install(plugins:     Chunk[PluginId]): IOResult[Unit]
  def remove(plugins:      Chunk[PluginId]): IOResult[Unit]
  def updateStatus(status: PluginInstallStatus, plugins: Chunk[PluginId]): IOResult[Unit]

}

/**
  * Implementation for tests, will do any operation without any error
  */
class InMemoryPluginService(ref: Ref[Map[PluginId, Plugin]]) extends PluginService {
  override def updateIndex(): IOResult[Option[RudderError]] = {
    ZIO.none
  }

  override def list(): UIO[Chunk[Plugin]] = {
    ref.get.map(m => Chunk.fromIterable(m.values))
  }

  override def install(plugins: Chunk[PluginId]): UIO[Unit] = {
    updatePluginStatus(PluginInstallStatus.Enabled, plugins)
  }

  override def remove(plugins: Chunk[PluginId]): UIO[Unit] = {
    updatePluginStatus(PluginInstallStatus.Uninstalled, plugins)
  }

  override def updateStatus(status: PluginInstallStatus, plugins: Chunk[PluginId]): UIO[Unit] = {
    updatePluginStatus(status, plugins)
  }

  private def updatePluginStatus(status: PluginInstallStatus, plugins: Chunk[PluginId]) = {
    ref.update(m => m ++ plugins.flatMap(id => m.get(id).map(p => id -> p.copy(status = status))))
  }
}

object InMemoryPluginService {
  def make(initialPlugins: List[Plugin]): UIO[InMemoryPluginService] = {
    for {
      ref <- Ref.make(initialPlugins.map(p => p.id -> p).toMap)
    } yield new InMemoryPluginService(ref)
  }
}
