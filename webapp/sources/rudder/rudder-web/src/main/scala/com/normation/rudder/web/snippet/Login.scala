package com.normation.rudder.web.snippet

import bootstrap.liftweb.RudderConfig
import net.liftweb.http.DispatchSnippet
import com.normation.plugins.DefaultExtendableSnippet

import scala.xml.NodeSeq

class Login extends DispatchSnippet with DefaultExtendableSnippet[Login] {

  val userListProvider = RudderConfig.rudderUserListProvider

  def mainDispatch = Map(
    "display" -> { authForm:NodeSeq =>
      if(userListProvider.authConfig.users.isEmpty) {
        <div class="col-xs-12" style="min-height:250px">
          <div class="row" style="margin-top:50px">
            <h3 class="text-danger text-center col-xs-10 col-xs-offset-1">No user is defined.</h3>
          </div>
          <div class="row" style="margin-top:50px">
            <a href="/rudder-doc/reference/current/administration/users.html" class="text-center col-xs-8 col-xs-offset-2">
              You need to define at least one user.
            </a>
          </div>
        </div>
      } else authForm
    }
  )

}
