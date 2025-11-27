/*
 *************************************************************************************
 * Copyright 2019 Normation SAS
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

package com.normation.rudder.web.snippet

import bootstrap.liftweb.RudderConfig
import com.normation.rudder.AuthorizationType
import com.normation.rudder.services.healthcheck.HealthcheckResult.Critical
import com.normation.rudder.services.healthcheck.HealthcheckResult.Ok
import com.normation.rudder.services.healthcheck.HealthcheckResult.Warning
import com.normation.rudder.services.healthcheck.HealthcheckUtils.compareCheck
import com.normation.rudder.users.CurrentUser
import com.normation.zio.*
import net.liftweb.http.DispatchSnippet
import net.liftweb.http.RenderDispatch
import scala.xml.NodeSeq

sealed trait NotificationLevel
object NotificationLevel {
  case object Warning  extends NotificationLevel
  case object Critical extends NotificationLevel
}

class HealthcheckInfo extends DispatchSnippet with RenderDispatch {

  override def render(html: NodeSeq): NodeSeq = {
    def notifHtml(notifClass: String, notifTitle: String, checksHtml: List[String]): NodeSeq = {
      val checksMenu = "<ul class='menu'>" + checksHtml.mkString("\n") + "</ul>"

      val tooltipContent = "<h4><i class='fa fa-warning text-warning'></i> " + notifTitle + "</h4><div>" + checksMenu + "</div>"
      <li
      class={"plugin-warning " + notifClass}
      data-bs-toggle="tooltip"
      data-bs-placement="bottom"
      title={tooltipContent}
      >
        <a href="/secure/administration/maintenance"><span class="fa fa-heart"></span></a>
      </li>
    }

    if (CurrentUser.checkRights(AuthorizationType.Administration.Read)) {
      (for {
        checks <- RudderConfig.healthcheckNotificationService.healthcheckCache.get
      } yield {

        val sorted = checks
          .filter(_ match {
            case _: Ok => false
            case _ => true
          })
          .sortWith((c1, c2) => compareCheck(c1, c2))

        val notifInfo = sorted match {
          case Nil    => None
          case h :: _ =>
            // sorted only contains Warning and Critical
            val transformed = sorted.map { c =>
              val circle = "<div class='circle-notif " + c.getClass.getSimpleName.toLowerCase + "-light-notif'></span>"
              "<li class='notif-msg-hc'><span>" + c.msg.replaceAll("(\r\n|\n)", "<br />") + circle + "</span></li>"
            }
            h match {
              case Critical(_, _, _) => Some(("critical", "There is an anomaly that requires your attention", transformed))
              case Warning(_, _, _)  => Some(("warning", "Something may cause an anomaly", transformed))
              case _                 => None
            }
          case _      => None
        }
        notifInfo match {
          case Some((notifiClass, msg, liHtml)) => notifHtml(notifiClass, msg, liHtml)
          case None                             => NodeSeq.Empty
        }
      }).runNow
    } else NodeSeq.Empty
  }

}
