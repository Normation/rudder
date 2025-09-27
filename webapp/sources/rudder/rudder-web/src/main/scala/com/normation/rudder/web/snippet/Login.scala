package com.normation.rudder.web.snippet

import bootstrap.liftweb.RudderConfig
import com.normation.plugins.DefaultExtendableSnippet
import net.liftweb.http.DispatchSnippet
import scala.xml.NodeSeq

class Login extends DispatchSnippet with DefaultExtendableSnippet[Login] {

  private val userListProvider = RudderConfig.rudderUserListProvider
  private val script           = {
    """window.setTimeout(function(){location.reload()}, 10000);
      |$('.btn-clipboard').click(function(){
      |  navigator.clipboard.writeText("rudder server create-user -u " ).then(function(){
      |    $('.btn-clipboard').attr("title","Copied to clipboard!");
      |    $('.btn-cmd-user .fa-clipboard').attr('class', 'fas fa-check') ;
      |  }, function() {})
      |} );""".stripMargin
  }

  def mainDispatch: Map[String, NodeSeq => NodeSeq] = Map(
    "display" -> { (authForm: NodeSeq) =>
      if (userListProvider.authConfig.users.isEmpty) {
        <div>
          <div class="logo-container">
            <img src="/images/logo/rudder-logo-rect-black.svg" data-lift="with-cached-resource" alt="Rudder"/>
          </div>
          <div class="plugin-info"></div>
          <form id="login-form">
            <div class="motd"></div>
            <div>
              <div class="success-info">
                <i class="fa fa-check"></i>
                Rudder installation complete!
              </div>
              <p>
                To get started, create a first user with:
              </p>
              <div class="group-cmd">
                <span id="cmd-user" class="cmd-text">
                  rudder server create-user -u &lt;username&gt;
                </span>
                <button class="btn btn-cmd-user btn-clipboard" type="button" data-bs-toggle="tooltip" data-bs-placement="bottom" title="Copy to clipboard">
                  <i class="far fa-clipboard"></i>
                </button>
              </div>
              <script type="text/javascript" data-lift="with-nonce">
                {script}
              </script>
            </div>
          </form>
        </div>
      } else authForm
    }
  )
}
