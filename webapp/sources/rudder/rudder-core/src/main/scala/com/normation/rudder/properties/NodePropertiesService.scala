/*
 *************************************************************************************
 * Copyright 2024 Normation SAS
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

package com.normation.rudder.properties

import com.normation.errors.*
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.logger.NodePropertiesLoggerPure
import com.normation.rudder.domain.properties.FailedNodePropertyHierarchy
import com.normation.rudder.domain.properties.GlobalParameter
import com.normation.rudder.domain.properties.ParentProperty
import com.normation.rudder.domain.properties.SuccessNodePropertyHierarchy
import com.normation.rudder.facts.nodes.NodeFactRepository
import com.normation.rudder.repository.FullNodeGroupCategory
import com.normation.rudder.repository.RoNodeGroupRepository
import com.normation.rudder.repository.RoParameterRepository
import com.normation.rudder.tenants.QueryContext
import com.normation.rudder.tenants.TenantReachIndex
import com.typesafe.config.ConfigRenderOptions
import zio.*

/*
 * This file contains a cache/in-memory repository for inherited node properties, ie the result
 * of the computation of a property from the node, group and global context.
 */

trait NodePropertiesService {

  /*
   * Update all property hierarchy
   */
  def updateAll(): IOResult[Unit]

}

class NodePropertiesServiceImpl(
    globalPropsRepo:       RoParameterRepository,
    roNodeGroupRepository: RoNodeGroupRepository,
    nodeFactRepository:    NodeFactRepository,
    propertiesRepository:  PropertiesRepository
) extends NodePropertiesService {
  override def updateAll(): IOResult[Unit] = QueryContext.asSystem("node properties are computed for the whole fleet") {
    for {
      allProperties     <- globalPropsRepo.getAllGlobalParameters()
      groups            <- roNodeGroupRepository.getFullGroupLibrary()
      nodes             <- nodeFactRepository.getAll().map(_.values)
      // scopes are resolved to node ids once for the whole fleet, not once per node
      split             <- partitionScopedProperties(allProperties, groups)
      (unscoped, scoped) = split
      // which global properties are roots for a given tenant (ADR 28945-global-properties-and-tenant-scoping)
      globalByTenant     = TenantReachIndex(unscoped.map(p => (p.security, p)))
      scopedByTenant     = TenantReachIndex(scoped.map { case (ids, t) => (t.value.security, (ids, t)) })
      mergedGroups       = {
        groups.allGroups.map {
          case (gid, group) =>
            val security = group.nodeGroup.security
            val resolved = MergeNodeProperties.forGroup(
              group,
              groups.allGroups,
              byName(globalByTenant.reaching(security)),
              scopedByTenant.reaching(security).map(_._2)
            )
            resolved match {
              case f: FailedNodePropertyHierarchy  =>
                NodePropertiesLoggerPure.logEffect.debug(
                  s"Node property for group ${gid.serialize} has a failure : ${f.getMessage}. Success values : ${f.resolved
                      .map(p => s"[${p.prop.name}=${p.prop.value.render(ConfigRenderOptions.concise().setComments(true))}]")
                      .mkString}"
                )
              case s: SuccessNodePropertyHierarchy =>
                NodePropertiesLoggerPure.logEffect
                  .trace(
                    s"Node properties for group ${gid.serialize} has been updated with the following : ${s.resolved
                        .map(p => s"[${p.prop.name}=${p.prop.value.render(ConfigRenderOptions.concise().setComments(true))}]")
                        .mkString}"
                  )
            }
            gid -> TenantScopedGroupProps(group.nodeGroup.security, resolved)
        }
      }
      mergedNodes        = {
        nodes
          .map(n => {
            val security   = n.rudderSettings.security
            // a name scoped away from that node must not reach it through a group or node
            // override either, so we carry both halves of the split (ADR 29409). A parameter the
            // node's tenants can not see is out of the split entirely: it is absent from the roots
            // and leaves group and node properties of the same name alone.
            val (in, out)  = scopedByTenant.reaching(security).partition { case (nodeIds, _) => nodeIds.contains(n.id) }
            val nodeScoped = NodeScopedParameters(in.map(_._2), out.map(_._2.value.name).toSet)
            val resolved   = MergeNodeProperties.forNode(
              n,
              groups.getGroupTarget(n).values,
              byName(globalByTenant.reaching(security)),
              nodeScoped
            )
            resolved match {
              case f: FailedNodePropertyHierarchy  =>
                NodePropertiesLoggerPure.logEffect.debug(
                  s"Node property for node ${n.id.value} has a failure : ${f.getMessage}. Success values : ${f.resolved
                      .map(p => s"[${p.prop.name}=${p.prop.value.render(ConfigRenderOptions.concise().setComments(true))}]")
                      .mkString}"
                )
              case s: SuccessNodePropertyHierarchy =>
                NodePropertiesLoggerPure.logEffect
                  .trace(
                    s"Node properties for node ${n.id.value} has been updated with the following : ${s.resolved
                        .map(p => s"[${p.prop.name}=${p.prop.value.render(ConfigRenderOptions.concise().setComments(true))}]")
                        .mkString}"
                  )
            }
            n.id -> resolved
          })
      }
      _                 <- propertiesRepository.saveNodeProps(mergedNodes.toMap)
      _                 <- propertiesRepository.saveGroupProps(mergedGroups.toMap)
    } yield ()
  }

  private def byName(properties: List[GlobalParameter]): Map[String, GlobalParameter] = {
    properties.map(p => (p.name, p)).toMap
  }

  /*
   * Split global properties between the unscoped ones - candidate for every node - and the scoped
   * ones, resolving each scope to its node ids once for the whole fleet (ADR 29409). Tenants are
   * applied afterwards, per node and per group.
   */
  private def partitionScopedProperties(
      properties: Seq[GlobalParameter],
      groups:     FullNodeGroupCategory
  )(implicit qc: QueryContext): IOResult[(List[GlobalParameter], List[(Set[NodeId], ParentProperty.Target)])] = {
    // the qc does the tenant filtering, and policy servers are cached in the repository
    nodeFactRepository.getNodeAndServerIds().map { ids =>
      val split = properties.toList.map { p =>
        p.scope match {
          case None    => Left(p)
          case Some(t) => Right((groups.getNodeIds(Set(t), ids), ParentProperty.Target(t, p, None)))
        }
      }
      (split.collect { case Left(x) => x }, split.collect { case Right(x) => x })
    }
  }
}
