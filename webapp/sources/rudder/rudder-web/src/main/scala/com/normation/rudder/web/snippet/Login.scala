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
        <div>
          <div class="logo-container">
            <img src="/images/logo-rudder.svg" data-lift="with-cached-resource" alt="Rudder"/>
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
                <button class="btn btn-cmd-user btn-clipboard" type="button" data-clipboard-text="rudder server create-user -u " data-toggle='tooltip' data-placement='bottom' data-container="html" title="Copy to clipboard">
                  <i class="far fa-clipboard"></i>
                </button>
              </div>
              <script type="text/javascript">
                // <![CDATA[
                window.setTimeout('location.reload()', 10000);
                new ClipboardJS('.btn-clipboard');
                var checked;
                $('.btn-cmd-user').on('click', function(){
                  clearInterval(checked);
                  $('.btn-cmd-user .fa-clipboard').attr('class', 'fas fa-check') ;
                    checked = setInterval(function(){
                    $('.btn-cmd-user .fa-check').attr('class', 'far fa-clipboard') ;
                  },700);
                });
                // ]]>
              </script>
            </div>
          </form>
        </div>
      } else authForm
    }
  )
}
