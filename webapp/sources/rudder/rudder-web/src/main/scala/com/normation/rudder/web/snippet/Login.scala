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
          <div class="row" style="margin-top:30px">
            <h3 class="text-center">
              <i class="fa fa-check-circle" style="color: #9bc832;" aria-hidden="true"></i>
              Rudder installation complete!
            </h3>
            <div class="text-center" style="margin-top:20px">
              <i class="fa fa-info-circle" aria-hidden="true" style="color: #3694d1;"></i>
              To get started, create a first user with:
            </div>
          </div>
          <div class="row" style="margin-top:10px">
            <span class="cmd-server text-center col-xs-8 col-xs-offset-2">
              <span class="cmd-text">
                rudder server create-user -u &lt;username&gt;
              </span>
            </span>
          </div>
        </div>
      } else authForm
    }
  )

}
