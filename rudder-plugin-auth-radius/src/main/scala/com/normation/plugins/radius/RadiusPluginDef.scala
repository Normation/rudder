package com.normation.plugins.radius

import scala.xml.NodeSeq
import com.normation.plugins.PluginName
import com.normation.plugins.PluginVersion
import com.normation.plugins.RudderPluginDef
import bootstrap.liftweb.ClassPathResource
import net.liftweb.common.Loggable
import net.liftweb.http.LiftRules

class RadiusPluginDef() extends RudderPluginDef with Loggable {

  val name = PluginName("iTop Compliance API")
  val basePackage = "com.normation.plugins.itop"
  val version = PluginVersion(1,0,0)
  val description : NodeSeq  =
<div>
  This plugin allows to use a radius authentication.

  The configuration is done in the main rudder.properties
  configuration file.

  <ul>
		<li><b>rudder.auth.type=radius</b>
				Use "radius" auth type to enable radius authentication
		</li>

		<li><b>rudder.auth.radius.host.name=192.168.42.80</b>
			IP or hostname of the Radius server. Both work, but it is prefered to use an IP.
    </li>

		<li><b>rudder.auth.radius.host.authPort=1812</b>
			Authentication port for the Radius server
    </li>

		<li><b>rudder.auth.radius.host.sharedSecret=secret</b>
			The  shared secret as configured in your Radius server for Rudder application / host.
    </li>

		<li><b>rudder.auth.radius.auth.protocol</b>
				Authentication protocol to use to connect to the Radius server. The default one is 'pap' (PAP).
			<br/> Available protocols:
			<ul>
				<li>pap</li>
        <li>chap</li>
        <li>eap-md5</li>
        <li>eap-ttls</li>
      </ul>
      You can append key=value parameters, separated by ":" to the protocol name to specify protocol option, for example:
			<br/>"eap-tls:keyFile=keystore:keyPassword=mypass"
    </li>

		<li><b>rudder.auth.radius.auth.timeout</b>
			Time to wait in seconds when trying to connect to the server before giving up.
    </li>

		<li><b>rudder.auth.radius.auth.retries</b>
			Number of retries to attempt in case of timeout before giving up.
    </li>
	</ul>

  Here comes a example of config you can copy and past directly:
<pre>
rudder.auth.type=radius
rudder.auth.radius.host.name=192.168.42.80
rudder.auth.radius.host.authPort=1812
rudder.auth.radius.host.sharedSecret=secret
rudder.auth.radius.auth.protocol=pap
rudder.auth.radius.auth.timeout=10
rudder.auth.radius.auth.retries=0
</pre>


</div>


  val configFiles = Seq(ClassPathResource("auth-radius.properties"))


  def init = {
    logger.info("loading Radius plugin")
  }

  def oneTimeInit : Unit = {}

}
