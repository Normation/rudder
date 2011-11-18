/*
*************************************************************************************
* Copyright 2011 Normation SAS
*************************************************************************************
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Affero General Public License as
* published by the Free Software Foundation, either version 3 of the
* License, or (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU Affero GPL v3, the copyright holders add the following
* Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU Affero GPL v3
* licence, when you create a Related Module, this Related Module is
* not considered as a part of the work and may be distributed under the
* license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Affero General Public License for more details.
*
* You should have received a copy of the GNU Affero General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/agpl.html>.
*
*************************************************************************************
*/

package bootstrap.liftweb


// spring boilerplate //
import org.springframework.context.annotation.{Bean,Configuration,Import,ImportResource}
import org.springframework.core.io.{Resource,ClassPathResource,FileSystemResource}
import org.springframework.context.annotation.Lazy
import org.springframework.security.authentication.AuthenticationProvider
import org.springframework.security.authentication.encoding._
import org.springframework.security.core.userdetails.memory.{InMemoryDaoImpl,UserMap}
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.authentication.dao.DaoAuthenticationProvider
import org.springframework.security.core.GrantedAuthority
import scala.collection.JavaConversions._
import net.liftweb.common._
import java.io.File
import com.normation.utils.HashcodeCaching

/**
 * Spring configuration for user authentication.
 * 
 * We are looking for an XML file with looking like:
 * 
 * <authentication hash="sha">
 *  <user name="name1" password="p1" />
 *  <user name="name2" password="p2" />
 * </authentication>
 * 
 */
@Configuration
@Import(Array(classOf[PropertyPlaceholderConfig]))
@ImportResource(Array("classpath:applicationContext-security.xml"))
class AppConfigAuth extends Loggable {
  import AppConfigAuth._
  
  val JVM_AUTH_FILE_KEY = "rudder.authFile"
  val DEFAULT_AUTH_FILE_NAME = "demo-rudder-users.xml"
  
  @Bean def demoAuthenticationProvider : AuthenticationProvider = {
    
    val resource = System.getProperty(JVM_AUTH_FILE_KEY) match {
      case null | "" => //use default location in classpath
        logger.info("JVM property -D%s is not defined, use configuration file '%s' in classpath".format(JVM_AUTH_FILE_KEY, DEFAULT_AUTH_FILE_NAME))
        new ClassPathResource(DEFAULT_AUTH_FILE_NAME)
      case x => //so, it should be a full path, check it
        val config = new FileSystemResource(new File(x))
        if(config.exists && config.isReadable) {
          logger.info("Use configuration file defined by JVM property -D%s : %s".format(JVM_AUTH_FILE_KEY, config.getPath))
          config
        } else {
          logger.error("Can not find configuration file specified by JVM property %s: %s ; abort".format(JVM_AUTH_FILE_KEY, config.getPath))
          throw new javax.servlet.UnavailableException("Configuration file not found: %s".format(config.getPath))
        }
    }
    
    //try to read and parse the file for users
    parseUsers(resource) match {
      case Some(config) =>
        val userDetails = new InMemoryDaoImpl()
        val userMap = new UserMap
        config.users.foreach { case (login,pass) =>
          userMap.addUser(DemoUserDetail(login,pass))
        }
        userDetails.setUserMap(userMap)
        val provider = new DaoAuthenticationProvider()
        provider.setUserDetailsService(userDetails)
        provider.setPasswordEncoder(config.encoder)
        provider
      case None => throw new javax.servlet.UnavailableException("Error when triyng to parse user file '%s', aborting.".format(resource.getURL.toString))
    }
  }
}

case object RoleUserAuthority extends GrantedAuthority {
  override val getAuthority = "ROLE_USER"
}

case class DemoUserDetail(login:String,password:String) extends UserDetails with HashcodeCaching {
  override val getAuthorities:java.util.Collection[GrantedAuthority] = Seq(RoleUserAuthority)
  override val getPassword = password
  override val getUsername = login
  override val isAccountNonExpired = true
  override val isAccountNonLocked = true
  override val isCredentialsNonExpired = true
  override val isEnabled = true
}

case class AuthConfig(
  encoder: PasswordEncoder,
  users:List[(String,String)]
) extends HashcodeCaching 

object AppConfigAuth extends Loggable {
  
  def parseUsers(resource:Resource) : Option[AuthConfig] = {
    if(resource.exists && resource.isReadable) {
      val xml = scala.xml.XML.load(resource.getInputStream)
      //what password hashing algo to use ?
      val root = (xml \\ "authentication")
      if(root.size != 1) {
        val msg = "Authentication file is malformed, the root tag '<authentication>' was not found"
        logger.error(msg)
        None
      } else {
        val hash = (root(0) \ "@hash").text.toLowerCase match {
          case "sha" | "sha1" => new ShaPasswordEncoder(1)
          case "sha256" | "sha-256" => new ShaPasswordEncoder(256)
          case "sha512" | "sha-512" => new ShaPasswordEncoder(512)
          case "md5" => new Md5PasswordEncoder
          case _ => new PlaintextPasswordEncoder
        }
        
        //now, get users
        val users = ( (xml \ "user").toList.flatMap { node =>
         ( node.attribute("name").map(_.toList.map(_.text)) , node.attribute("password").map(_.toList.map(_.text)) ) match {
           case (Some(name :: Nil) , Some(pwd :: Nil) ) if(name.size > 0 && pwd.size > 0) => (name, pwd) :: Nil
           case _ => 
             logger.error("Ignore user line in authentication file '%s', some required attribute is missing: %s".format(resource.getURL.toString, node.toString))
             Nil
         }
        })
        logger.debug("User with defined credentials: %s".format(users.map( _._1).mkString(", ")))
        Some(AuthConfig(hash,users))
      }
    } else {
      logger.error("The resource '%s' does not exist or is not readable".format(resource.getURL.toString))
      None
    }
  }
}

