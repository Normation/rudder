/*
 *************************************************************************************
 * Copyright 2023 Normation SAS
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

import bootstrap.liftweb.StaticResourceRewrite
import net.liftweb.http._
import net.liftweb.http.js.JE._
import net.liftweb.http.js.JsCmd
import net.liftweb.http.js.JsCmds._
import scala.xml.NodeSeq
import scala.xml.Utility

sealed trait ToastNotification {
  def in: String
  def message: String = Utility.escape(in).replaceAll("""\n""", " ")
}

object ToastNotification {

  final case class Success(in: String) extends ToastNotification
  final case class Error(in: String)   extends ToastNotification
}

object UnpublishedToasts extends SessionVar[List[ToastNotification]](Nil)

/**
 * A simple class to display toasts (notifications for success/etc) after a
 * redirect
 */
object RegisterToasts {

  def register(toast: ToastNotification) = {
    UnpublishedToasts(toast :: UnpublishedToasts.get)
  }
}

class RegisterToasts {

  def display: NodeSeq = {
    val toasts = UnpublishedToasts.get
    UnpublishedToasts.set(Nil)
    toasts match {
      case Nil  => NodeSeq.Empty
      case list =>
        Script(
          OnLoad(
            list
              .map(t => {
                t match {
                  case x: ToastNotification.Error   => JsRaw(s"""createErrorNotification("${x.message}")""").cmd
                  case x: ToastNotification.Success => JsRaw(s"""createSuccessNotification("${x.message}")""").cmd
                }
              })
              .reduce[JsCmd](_ & _)
          )
        )
    }
  }
}
