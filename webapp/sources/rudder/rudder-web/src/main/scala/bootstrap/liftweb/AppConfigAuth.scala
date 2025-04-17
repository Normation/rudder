/*
 *************************************************************************************
 * Copyright 2011 Normation SAS
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

package bootstrap.liftweb

import bootstrap.liftweb.checks.earlyconfig.db.CheckUsersFile
import com.normation.errors.*
import com.normation.rudder.Role
import com.normation.rudder.api.*
import com.normation.rudder.domain.appconfig.FeatureSwitch
import com.normation.rudder.domain.logger.ApplicationLoggerPure
import com.normation.rudder.facts.nodes.NodeSecurityContext
import com.normation.rudder.rest.ProviderRoleExtension
import com.normation.rudder.users.*
import com.normation.rudder.web.services.UserSessionLogEvent
import com.normation.zio.*
import com.softwaremill.quicklens.*
import com.typesafe.config.Config
import com.typesafe.config.ConfigException
import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.FilterConfig
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.util.Collection
import net.liftweb.common.*
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.springframework.beans.factory.config.PropertyPlaceholderConfigurer
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationContextAware
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.ImportResource
import org.springframework.context.support.AbstractApplicationContext
import org.springframework.context.support.ClassPathXmlApplicationContext
import org.springframework.ldap.core.DirContextAdapter
import org.springframework.ldap.core.DirContextOperations
import org.springframework.security.authentication.AuthenticationProvider
import org.springframework.security.authentication.DisabledException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.authentication.dao.DaoAuthenticationProvider
import org.springframework.security.core.Authentication
import org.springframework.security.core.AuthenticationException
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.ldap.userdetails.UserDetailsContextMapper
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.authentication.AuthenticationFailureHandler
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler
import scala.annotation.nowarn
import scala.util.Try
import zio.syntax.*

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
 * Note that the main Spring application entry point is bootstrap.liftweb.AppConfigAuth
 * which itself import generic security context and dedicated authentication methods.
 */
@Configuration
@ImportResource(Array("classpath:applicationContext-security.xml"))
@ComponentScan(Array("bootstrap.rudder.plugin"))
class AppConfigAuth extends ApplicationContextAware {
  import bootstrap.liftweb.RudderProperties.config

  // we define the System ApiAcl as one that is all mighty and can manage everything
  val SYSTEM_API_ACL = ApiAuthorization.RW

  // Configuration values within an authentication provider config from which we get provider properties
  val A_ROLES_ENABLED  = "roles.enabled"
  val A_ROLES_OVERRIDE = "roles.override"

  /*
   * This method is expected to try to initialize the Spring bean for
   * all authentication provider configured by the user for \rudder.auth.provider`.
   * (we don't consider other possible existing backend to avoid errors on
   * not configured backends).
   * If any of the provider from the user list is unknown, then an error log is logged.
   * It will give back an ordered map of name -> AuthenticationMethods to the
   * AuthBackendProvidersManager.
   * It is the DefaultAuthBackendProviders which is responsible to dynamically
   * choose from the map and its own rules (plugin license, etc) what
   * provider are allowed in authentication, dynamically. This is done
   * when the RudderProviderManager ask for DynamicRudderProviderManager#getEnabledProviders.
   *
   * Summary:
   * - spring init is in charge of initializing all configured provider bean, whatever
   *   the plugins status (if any).
   * - spring init gives these provider to DefaultAuthBackendProviders
   * - AuthBackendProvidersManager is in charge to be the link with plugins to know
   *   what is enabled and when.
   */
  def setApplicationContext(applicationContext: ApplicationContext): Unit = {
    // the list of authentication backend configured by the user
    val configuredAuthProviders = AuthenticationMethods.getForConfig(config)

    // by default enable user REST token authentication for compatibility
    val defaultEnableRestToken = FeatureSwitch.Enabled
    val restTokenGlobalFeatureSwitch: FeatureSwitch = {
      val value = {
        try {
          FeatureSwitch.parse(config.getString(s"rudder.auth.userRestToken")).getOrElse(defaultEnableRestToken)
        } catch { case _: ConfigException.Missing => defaultEnableRestToken }
      }
      ApplicationLoggerPure.logEffect.info(
        s"User access to REST API via token is globally configured by default to be ${value.name} when the api-authorizations plugin is active"
      )
      value
    }

    // provider properties need to be set to default config, using global value, to be used when overriding config by provider
    val defaultProviderProperties: Map[String, AuthBackendProviderProperties] = configuredAuthProviders
      .map(_.name)
      .map {
        // providerRoleExtension has no global config value
        case p @ "ldap"              => // roles for LDAP-provided users come directly from the file, they override the file roles
          p -> AuthBackendProviderProperties(ProviderRoleExtension.WithOverride, restTokenGlobalFeatureSwitch)
        case p @ ("oidc" | "oauth2") => // default restTokenFeatureSwitch configuration is "disabled"
          p -> AuthBackendProviderProperties.default
            .copy(restTokenFeatureSwitch = FeatureSwitch.Disabled)
        case p                       =>
          p -> AuthBackendProviderProperties.default
            .copy(restTokenFeatureSwitch = restTokenGlobalFeatureSwitch)
      }
      .toMap

    // prepare specific properties for each configuredAuthProviders - we need system properties for spring
    val providerProperties: Map[String, AuthBackendProviderProperties] = {
      import scala.jdk.CollectionConverters.*
      configuredAuthProviders.map { x =>
        try {
          // try to load all the specific properties of that auth type
          // so that they are available from Spring
          // the config can have 0 specif entry => try/catch
          config.getConfig(s"rudder.auth.${x.name}").entrySet.asScala.foreach {
            case e =>
              val fullKey = s"rudder.auth.${x.name}.${e.getKey}"
              System.setProperty(fullKey, config.getString(fullKey))
          }
        } catch {
          case _: ConfigException.Missing =>
            ApplicationLoggerPure.logEffect.debug(s"Provider configuration missing : rudder.auth.${x.name}")
        }
        // config value is parsed as an Option : None means the config is missing
        val restTokenFeatureSwitch = {
          // feature switch is always disabled if globally disabled
          restTokenGlobalFeatureSwitch match {
            case FeatureSwitch.Disabled => FeatureSwitch.Disabled
            case FeatureSwitch.Enabled  =>
              // the fallback value if config is not correct, default map always has default values
              val defaultValue = defaultProviderProperties(x.name).restTokenFeatureSwitch
              try {
                val configKey   = s"rudder.auth.${x.name}.userRestToken"
                val configValue = config.getString(configKey)
                FeatureSwitch.parse(configValue) match {
                  case Left(value)  => // set to "disabled" for security reasons if the config value is not known
                    ApplicationLoggerPure.logEffect.warn(
                      s"User access to REST API via token is configured to the be ${defaultValue.name} by default, the ${configKey} property is ignored, cause was : ${value.msg}"
                    )
                    defaultValue
                  case Right(value) =>
                    if (value != defaultValue) {
                      ApplicationLoggerPure.logEffect.info(
                        s"User access to REST API via token for provider ${x.name} is configured to be ${value.name}, overriding the global configuration/default value"
                      )
                    } else {
                      ApplicationLoggerPure.logEffect.debug(
                        s"User access to REST API via token for provider ${x.name} is ${restTokenGlobalFeatureSwitch.name} as the global one"
                      )
                    }
                    value
                }
              } catch {
                case _: ConfigException.Missing =>
                  if (defaultValue != restTokenGlobalFeatureSwitch) {
                    ApplicationLoggerPure.logEffect.info(
                      s"User access to REST API via token for provider ${x.name} is by default configured to be ${defaultValue.name}, overriding the global configuration"
                    )
                  }
                  defaultValue
              }
          }
        }
        // properties config has values specific to oidc/oauth2
        // the rest token feature configuration is common to every provider
        val properties: (String, AuthBackendProviderProperties) = x.name match {
          case "oidc" | "oauth2" => {
            val baseProperty  = "rudder.auth.oauth2.provider"
            // we need to read under the registration key, under the base property
            val registrations =
              Try(config.getString(baseProperty + ".registrations").split(",").map(_.trim).toList).getOrElse(List.empty)
            // when there are multiple registrations we should take the most prioritized configuration under the same provider
            x.name -> registrations.foldLeft(AuthBackendProviderProperties.default) {
              case (acc, reg) =>
                val rolesEnabled = Try(config.getBoolean(s"${baseProperty}.${reg}.${A_ROLES_ENABLED}"))
                  .getOrElse(false) // default value, same as in the auth backend plugin
                val rolesOverride = Try(config.getBoolean(s"${baseProperty}.${reg}.${A_ROLES_OVERRIDE}"))
                  .getOrElse(false) // default value, same as in the auth backend plugin
                acc.maxByPriority(
                  AuthBackendProviderProperties.fromConfig(x.name, rolesEnabled, rolesOverride, restTokenFeatureSwitch)
                )
            }
          }
          case p                 => // use default config, override user-defined ones
            p -> defaultProviderProperties(p).copy(restTokenFeatureSwitch = restTokenFeatureSwitch)
        }
        properties
      }.toMap
    }

    // load additional beans from authentication dedicated resource files

    @nowarn("msg=deprecated") // switching to new solution seems involving since we use systemPropertiesMode
    val propertyConfigurer = new PropertyPlaceholderConfigurer()
    propertyConfigurer.setIgnoreResourceNotFound(true)
    propertyConfigurer.setIgnoreUnresolvablePlaceholders(true)
    propertyConfigurer.setSearchSystemEnvironment(true)
    propertyConfigurer.setSystemPropertiesMode(PropertyPlaceholderConfigurer.SYSTEM_PROPERTIES_MODE_OVERRIDE)
    propertyConfigurer.setOrder(10000)

    applicationContext match {
      case x: AbstractApplicationContext =>
        x.addBeanFactoryPostProcessor(propertyConfigurer)

      case _ => // nothing
    }

    ApplicationLoggerPure.logEffect.info(
      s"Configured authentication provider(s): [${configuredAuthProviders.map(_.name).mkString(", ")}]"
    )

    val ctx = new ClassPathXmlApplicationContext(applicationContext)
    ctx.addBeanFactoryPostProcessor(propertyConfigurer)
    // here, we load all spring bean conigured in the classpath files: applicationContext-security-auth-BACKEND.xml
    ctx.setConfigLocations(configuredAuthProviders.map(_.configFile).toSeq*)
    ctx.refresh

    // now, handle back the configured bean / user configured list to AuthBackendProvidersManager
    configuredAuthProviders.foreach { provider =>
      try {
        val bean = ctx.getBean(provider.springBean, classOf[AuthenticationProvider])
        RudderConfig.authenticationProviders.addSpringAuthenticationProvider(provider.name, bean)
      } catch {
        case ex: Exception =>
          ApplicationLoggerPure.logEffect.error(
            s"Error when trying to configure the authentication backend '${provider.name}': ${ex.getMessage}",
            ex
          )
      }
    }
    RudderConfig.authenticationProviders.setConfiguredProviders(configuredAuthProviders.toArray)
    RudderConfig.authenticationProviders.addProviderProperties(providerProperties)
  }

  ///////////// FOR WEB INTERFACE /////////////

  /**
   * Configure the authentication provider, with always trying
   * the root admin account first so that we always have a way to
   * log-in into Rudder.
   */
  @Bean(name = Array("org.springframework.security.authenticationManager"))
  def authenticationManager = new RudderProviderManager(RudderConfig.authenticationProviders, userRepository)

  @Bean def rudderWebAuthenticationFailureHandler: AuthenticationFailureHandler = new RudderUrlAuthenticationFailureHandler(
    "/index.html?login_error=true"
  )

  @Bean def userRepository: UserRepository = RudderConfig.userRepository

  @Bean def rudderUserListProvider: FileUserDetailListProvider = RudderConfig.rudderUserListProvider

  @Bean def rudderUserDetailsService: RudderInMemoryUserDetailsService = {
    new RudderInMemoryUserDetailsService(rudderUserListProvider, userRepository)
  }

  @Bean def passwordEncoderDispatcher: PasswordEncoderDispatcher = {
    new PasswordEncoderDispatcher(
      RudderConfig.RUDDER_BCRYPT_COST,
      RudderConfig.RUDDER_ARGON2_PARAMS
    )
  }

  @Bean def checkUsersFile: CheckUsersFile = new CheckUsersFile(rudderUserListProvider)

  @Bean def fileAuthenticationProvider: AuthenticationProvider = {
    val provider = new DaoAuthenticationProvider()
    provider.setUserDetailsService(rudderUserDetailsService)
    provider.setPasswordEncoder(rudderUserDetailsService.authConfigProvider.authConfig.encoder)

    // we need to register a callback to check and update users file and a callback to update password encoder when needed
    val checkUsersFileCallback = RudderAuthorizationFileReloadCallback(
      "check-users-file-callback",
      (c: ValidatedUserList) => checkUsersFile.prog
    )
    rudderUserListProvider.registerCallback(checkUsersFileCallback)

    val updatePasswordEncoder = RudderAuthorizationFileReloadCallback(
      "update-password-encoder",
      (c: ValidatedUserList) => effectUioUnit(provider.setPasswordEncoder(c.encoder))
    )
    rudderUserListProvider.registerCallback(updatePasswordEncoder)

    provider
  }

  @Bean def rootAdminAuthenticationProvider: AuthenticationProvider = {
    // We want to be able to disable that account.
    // For that, we let the user either let undefined rudder.auth.admin.login,
    // or let empty udder.auth.admin.login or rudder.auth.admin.password

    val encoder = RudderPasswordEncoder(passwordEncoderDispatcher)
    val admins  = if (config.hasPath("rudder.auth.admin.login") && config.hasPath("rudder.auth.admin.password")) {
      val login    = config.getString("rudder.auth.admin.login")
      val password = config.getString("rudder.auth.admin.password")

      if (login.isEmpty || password.isEmpty) {
        Map.empty[String, RudderUserDetail]
      } else {
        Map(
          login -> RudderUserDetail(
            RudderAccount.User(
              login,
              HashedUserPassword(password) // FIXME: this password seems to be clear-text
            ),
            UserStatus.Active,
            Set(Role.Administrator),
            SYSTEM_API_ACL,
            NodeSecurityContext.All
          )
        )
      }
    } else {
      Map.empty[String, RudderUserDetail]
    }

    if (admins.isEmpty) {
      ApplicationLoggerPure.logEffect.info(
        "No master admin account is defined. You can define one with 'rudder.auth.admin.login' and 'rudder.auth.admin.password' properties in the configuration file"
      )
    }

    val authConfigProvider = new UserDetailListProvider {
      // in the case of the root admin defined in config file, given is very specific use case, we enforce case sensitivity
      override def authConfig: ValidatedUserList =
        ValidatedUserList(encoder, isCaseSensitive = true, customRoles = Nil, users = admins)
    }
    (for {
      rootAccountUserRepo <- InMemoryUserRepository.make()
      _                   <- rootAccountUserRepo.setExistingUsers(
                               "root-account",
                               admins.keys.toList,
                               EventTrace(com.normation.rudder.domain.eventlog.RudderEventActor, DateTime.now(DateTimeZone.UTC))
                             )
    } yield {
      val provider = new DaoAuthenticationProvider()
      provider.setUserDetailsService(new RudderInMemoryUserDetailsService(authConfigProvider, rootAccountUserRepo))
      provider.setPasswordEncoder(encoder) // force password encoder to the one we want
      provider
    }).runNow
  }

  ///////////// FOR REST API /////////////

  @Bean def restAuthenticationFilter = {
    new RestAuthenticationFilter(
      RudderConfig.roApiAccountRepository,
      rudderUserDetailsService,
      SYSTEM_API_ACL,
      RestAuthenticationFilter.API_TOKEN_HEADER
    )
  }

  @Bean def restSecureAuthenticationFilter =
    new RestSecureAuthenticationFilter()

  @Bean def restAuthenticationEntryPoint: AuthenticationEntryPoint = new AuthenticationEntryPoint() {
    override def commence(request: HttpServletRequest, response: HttpServletResponse, ex: AuthenticationException): Unit = {
      response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized")
    }
  }

  @Bean def userSessionInvalidationFilter: UserSessionInvalidationFilter =
    new UserSessionInvalidationFilter(userRepository, rudderUserListProvider)

  /**
   * Map an user from XML user config file
   */
  @Bean def rudderXMLUserDetails: UserDetailsContextMapper = {
    new RudderXmlUserDetailsContextMapper(rudderUserDetailsService)
  }

  // userSessionLogEvent must not be lazy, because not used by anybody directly
  @Bean def userSessionLogEvent = new UserSessionLogEvent(RudderConfig.eventLogRepository, RudderConfig.stringUuidGenerator)
}

/*
 * A trivial extension to the SimpleUrlAuthenticationFailureHandler that correctly log failure
 */
class RudderUrlAuthenticationFailureHandler(failureUrl: String) extends SimpleUrlAuthenticationFailureHandler(failureUrl) {
  override def onAuthenticationFailure(
      request:   HttpServletRequest,
      response:  HttpServletResponse,
      exception: AuthenticationException
  ): Unit = {
    LogFailedLogin.warn(exception, request)
    super.onAuthenticationFailure(request, response, exception)
  }
}

object LogFailedLogin {

  def warn(ex: AuthenticationException, request: HttpServletRequest): Unit = {
    ApplicationLoggerPure.Auth.logEffect.warn(
      s"Login authentication failed for user '${getUser(request)}' from IP '${getRemoteAddr(request)}': ${ex.getMessage}"
    )
  }

  // user login is passed in parameters named "j_username"
  def getUser(req: HttpServletRequest): String = {
    req.getParameter("username") match {
      case null  => "unknown"
      case login => login
    }
  }

  def getRemoteAddr(request: HttpServletRequest): String = {
    /*
     * Of course there is not reliable way to get remote address, because of proxy, forging, etc.
     */

    // Some interesting header, get from https://gist.github.com/nioe/11477264
    // the list seems to be roughtly correctly ordered - perhaps we don't want one, but all?
    val headers = List("X-Forwarded-For", "Proxy-Client-IP", "WL-Proxy-Client-IP", "HTTP_CLIENT_IP", "HTTP_X_FORWARDED_FOR")

    // utility to display correctly the pair header:value
    def serialize(t2: Tuple2[String, String]) = t2._1 + ":" + t2._2

    // always returns the ip addr as seen from the server, and the list of other perhaps interesting info
    (
      request.getRemoteAddr ::
      headers.flatMap { header =>
        request.getHeader(header) match {
          case null | "" => None
          case x         => if (x.toLowerCase != "unknown") Some((header, x)) else None
        }
      }.groupBy(_._2).map(x => serialize(x._2.head)).toList
    ).mkString("|")
  }
}

/**
 * In SpringSecurity-land, "UserDetails" is all the authentication and authorization related
 * information about an user.
 * In rudder, by default these information are gathered:
 * - from the database to know if an user exists, is enabled, etc
 * - and from file to get roles, authentication properties, etc.
 * Other backend can override / super charge some of these properties.
 */
class RudderInMemoryUserDetailsService(val authConfigProvider: UserDetailListProvider, userRepository: UserRepository)
    extends UserDetailsService {

  @throws(classOf[UsernameNotFoundException])
  @throws(classOf[DisabledException])
  override def loadUserByUsername(username: String): RudderUserDetail = {
    loadUserDetailInfoByUsername(username)._1
  }

  def loadUserDetailInfoByUsername(username: String): (RudderUserDetail, UserInfo) = {
    userRepository
      .get(username, isCaseSensitive = authConfigProvider.authConfig.isCaseSensitive)
      .catchSome {
        case err: Inconsistency =>
          ApplicationLoggerPure.Auth
            .warn(
              s"User '${username}' was found in Rudder base, but with an error: ${err.fullMsg}. Please check/remove duplicate users, and consider reloading users."
            )
            .as(None)
      }
      .flatMap {
        case Some(user) if (user.status != UserStatus.Deleted) =>
          val rudderUserDetails = authConfigProvider.getUserByName(username) match {
            case Left(err) =>
              // when the user is not found, we return a default "no roles" user.
              // It will be the responsibility of other backend to provided the correct set of rights.
              RudderUserDetail(
                RudderAccount.User(user.id, HashedUserPassword("")), // FIXME: empty ?
                user.status,
                Set(),
                ApiAuthorization.None,
                NodeSecurityContext.None
              )
            case Right(d)  =>
              // update status, user can be disabled for ex
              d.modify(_.status).setTo(user.status)
          }
          Some(rudderUserDetails -> user).succeed
        case _                                                 => None.succeed
      }
      .runNow match {
      case None                                            => throw new UsernameNotFoundException(s"User '${username}' was not found in Rudder base")
      case Some((u, _)) if u.status == UserStatus.Disabled => throw new DisabledException("User is disabled")
      case Some(u)                                         => u
    }
  }
}

/**
 * Spring context mapper
 */
class RudderXmlUserDetailsContextMapper(userDetailsService: UserDetailsService) extends UserDetailsContextMapper {
  // we are not able to try to save user in the XML file
  def mapUserToContext(user: UserDetails, ctx: DirContextAdapter): Unit = ()

  def mapUserFromContext(
      ctx:         DirContextOperations,
      username:    String,
      authorities: Collection[? <: GrantedAuthority]
  ): UserDetails = {
    userDetailsService.loadUserByUsername(username)
  }
}

/**
  * A description of some properties of an authentication backend
  */
final case class AuthBackendProviderProperties(
    providerRoleExtension:  ProviderRoleExtension,
    restTokenFeatureSwitch: FeatureSwitch
) {
  def maxByPriority(
      that: AuthBackendProviderProperties
  ): AuthBackendProviderProperties = {
    if (this.providerRoleExtension.priority > that.providerRoleExtension.priority) {
      this
    } else {
      that
    }
  }
}

object AuthBackendProviderProperties {
  def fromConfig(
      name:                   String,
      hasAdditionalRoles:     Boolean,
      overridesRoles:         Boolean,
      restTokenFeatureSwitch: FeatureSwitch
  ): AuthBackendProviderProperties = {
    (hasAdditionalRoles, overridesRoles) match {
      case (false, false) =>
        new AuthBackendProviderProperties(ProviderRoleExtension.None, restTokenFeatureSwitch)
      case (false, true)  =>
        // This is not a consistent configuration, we log a warning and persue with the most restrictive configuration
        ApplicationLoggerPure.logEffect.warn(
          s"Backend provider properties for '${name}' are not consistent: backend does not enables providing roles but roles overrides is set to true. " +
          s"Overriding roles will not be enabled."
        )
        new AuthBackendProviderProperties(ProviderRoleExtension.None, restTokenFeatureSwitch)
      case (true, false)  =>
        new AuthBackendProviderProperties(ProviderRoleExtension.NoOverride, restTokenFeatureSwitch)
      case (true, true)   =>
        new AuthBackendProviderProperties(ProviderRoleExtension.WithOverride, restTokenFeatureSwitch)
    }
  }

  /**
    * The default properties the ones handled in the config parsing to be the default values:
    * - a provider can provide roles and roles are not overridden accross different providers
    * - the rest API token feature for users is allowed
    */
  def default: AuthBackendProviderProperties =
    new AuthBackendProviderProperties(ProviderRoleExtension.NoOverride, restTokenFeatureSwitch = FeatureSwitch.Enabled)
}

/**
 * This is the class that defines the authentication methods.
 * Without the plugin, by default only "file" is known.
 */
trait AuthBackendsProvider {
  def authenticationBackends: Set[String]
  def name:                   String
  // dynamically check if a given plugin is enable
  def allowedToUseBackend(name: String): Boolean
}

final case class AuthenticationMethods(name: String) {
  val path:       String = s"applicationContext-security-auth-${name}.xml"
  val configFile: String = s"classpath:${path}"
  val springBean: String = s"${name}AuthenticationProvider"
}

object AuthenticationMethods {
  // some logic for the authentication providers
  def getForConfig(config: Config): Seq[AuthenticationMethods] = {
    val names = {
      try {
        // config.getString can't be null by contract
        config.getString("rudder.auth.provider").split(",").toSeq.map(_.trim).collect { case s if (s.size > 0) => s }
      } catch {
        // if the property is missing, use the default "file" value
        // it can be a migration.
        case ex: ConfigException.Missing => Seq("file")
      }
    }

    // always add "rootAdmin" has the first method
    // and de-duplicate methods
    val auths = ("rootAdmin" +: names).distinct.map(AuthenticationMethods(_))

    // for each methods, check that the provider file is present, or log an error and
    // disable that provider
    auths.flatMap { a =>
      if (a.name == "rootAdmin") {
        Some(a)
      } else {
        // try to instantiate
        val cpr = new org.springframework.core.io.ClassPathResource(a.path)
        if (cpr.exists) {
          Some(a)
        } else {
          ApplicationLoggerPure.logEffect.error(
            s"The authentication provider '${a.name}' will not be loaded because the spring " +
            s"resource file '${a.configFile}' was not found. Perhaps are you missing a plugin?"
          )
          None
        }
      }
    }
  }
}

object DefaultAuthBackendProvider extends AuthBackendsProvider {

  val FILE       = "file"
  val ROOT_ADMIN = "rootAdmin"

  override def authenticationBackends: Set[String] = Set(FILE, ROOT_ADMIN)
  override def name:                   String      = s"Default authentication backends provider: '${authenticationBackends.mkString("','")}"
  override def allowedToUseBackend(name: String): Boolean = true // always enable - ie we never want to skip them
}

// and default implementation: provides 'file', 'rootAdmin'
class AuthBackendProvidersManager() extends DynamicRudderProviderManager {

  val defaultAuthBackendsProvider: AuthBackendsProvider = DefaultAuthBackendProvider

  // the list of AuthenticationMethods configured by the user
  private var authenticationMethods = Array[AuthenticationMethods]() // must be a var/array, because init by spring-side

  private var backends            = Seq[AuthBackendsProvider]()
  // a map of status for each backend (status is dynamic)
  private var allowedToUseBackend = Map[String, () => Boolean]()

  // this is the map of configured spring bean "AuthenticationProvider"
  private var springProviders = Map[String, AuthenticationProvider]()

  // a map of properties registered for each backend
  private[this] var backendProperties = Map[String, AuthBackendProviderProperties]()

  // add default providers into mutable variables
  initializeDefaultProviders()

  private def initializeDefaultProviders(): Unit = {
    this.addProvider(defaultAuthBackendsProvider)
    this.addProviderProperties(
      defaultAuthBackendsProvider.authenticationBackends
        .map(
          _ -> AuthBackendProviderProperties.default
        )
        .toMap
    )
  }

  def addProvider(p: AuthBackendsProvider): Unit = {
    ApplicationLoggerPure.logEffect.info(s"Add backend providers '${p.name}'")
    backends = backends :+ p
    allowedToUseBackend = allowedToUseBackend ++ (p.authenticationBackends.map(name => (name, () => p.allowedToUseBackend(name))))
  }

  /*
   * Add a spring configured name -> provider
   */
  def addSpringAuthenticationProvider(name: String, provider: AuthenticationProvider): Unit = {
    this.springProviders = this.springProviders + (name -> provider)
  }

  /*
   * set the list of providers currently configured by user. Array because used from spring
   */
  def setConfiguredProviders(providers: Array[AuthenticationMethods]): Unit = {
    this.authenticationMethods = providers
  }

  // get the list of provider in an imutable seq
  def getConfiguredProviders(): Seq[AuthenticationMethods] = {
    this.authenticationMethods.toSeq
  }

  def addProviderProperties(properties: Map[String, AuthBackendProviderProperties]): Unit = {
    this.backendProperties = this.backendProperties ++ properties
  }

  def getProviderProperties(): Map[String, AuthBackendProviderProperties] = {
    this.backendProperties
  }

  /*
   * what we
   */
  override def getEnabledProviders(): Array[RudderAuthenticationProvider] = {
    authenticationMethods.flatMap { m =>
      if (allowedToUseBackend.isDefinedAt(m.name)) {
        if (allowedToUseBackend(m.name)() == true) {
          springProviders.get(m.name).map(p => RudderAuthenticationProvider(m.name, p))
        } else {
          ApplicationLoggerPure.logEffect.debug(
            s"Authentication backend '${m.name}' is not currently enable. Perhaps a plugin is not enabled?"
          )
          None
        }
      } else {
        ApplicationLoggerPure.logEffect.debug(
          s"Authentication backend '${m.name}' was not found in available backends. Perhaps a plugin is missing?"
        )
        None
      }
    }
  }
}

/**
 * Our rest filter, we just look for X-API-Token and
 * check if a token exists with that value.
 * For Account with type "user", we need to also check
 * for the user and update REST token ACL with that knowledge
 */
class RestAuthenticationFilter(
    apiTokenRepository: RoApiAccountRepository,
    userDetailsService: RudderInMemoryUserDetailsService,
    systemApiAcl:       ApiAuthorization,
    apiTokenHeaderName: String
) extends Filter with Loggable {
  override def destroy(): Unit = {}
  override def init(config: FilterConfig): Unit = {}

  private val not_authenticated_api = List(
    "/api/status"
  )

  private def isValidNonAuthApiV1(httpRequest: HttpServletRequest): Boolean = {
    val requestPath = httpRequest.getRequestURI.substring(httpRequest.getContextPath.length)
    not_authenticated_api.exists(path => requestPath.startsWith(path))
  }

  private def failsAuthentication(
      httpRequest:  HttpServletRequest,
      httpResponse: HttpServletResponse,
      error:        RudderError
  ): Unit = {
    val msg = s"REST authentication failed from IP '${LogFailedLogin.getRemoteAddr(httpRequest)}'. Error was: ${error.fullMsg}"
    ApplicationLoggerPure.Auth.logEffect.warn(msg)
    httpResponse.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized")
  }

  private def authenticate(userDetails: RudderUserDetail): Unit = {
    val authenticationToken = new UsernamePasswordAuthenticationToken(
      userDetails,
      userDetails.getAuthorities,
      userDetails.getAuthorities
    )

    // save in spring security context
    SecurityContextHolder.getContext().setAuthentication(authenticationToken)
  }

  /**
   * Look for the X-API-TOKEN header, and use it
   * to try to find a correspond token,
   * and authenticate in SpringSecurity with that.
   */
  def doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain): Unit = {
    if (
      SecurityContextHolder.getContext != null &&
      SecurityContextHolder.getContext.getAuthentication != null &&
      SecurityContextHolder.getContext.getAuthentication.isAuthenticated
    ) {
      // already authenticated by another mean, just pass to next case
      chain.doFilter(request, response)
    } else {
      (request, response) match {
        case (httpRequest: HttpServletRequest, httpResponse: HttpServletResponse) =>
          val token = httpRequest.getHeader(apiTokenHeaderName);
          token match {
            case null | "" =>
              if (isValidNonAuthApiV1(httpRequest)) {
                val name         = "api-v1-unauthenticated-account"
                val apiV1Account = ApiAccount(
                  ApiAccountId(name),
                  ApiAccountKind.PublicApi(
                    ApiAuthorization.None,
                    None
                  ), // un-authenticated APIv1 token certainly doesn't get any authz on v2 API

                  ApiAccountName(name),
                  Some(ApiTokenHash.disabled()),
                  "API Account for un-authenticated API",
                  isEnabled = true,
                  creationDate = new DateTime(0, DateTimeZone.UTC),
                  tokenGenerationDate = DateTime.now(DateTimeZone.UTC),
                  tenants = NodeSecurityContext.None
                )

                authenticate(
                  RudderUserDetail(
                    RudderAccount.Api(apiV1Account),
                    UserStatus.Active,
                    RudderAuthType.Api.apiRudderRole,
                    ApiAuthorization.None,   // un-authenticated APIv1 token certainly doesn't get any authz on v2 API
                    NodeSecurityContext.None // ApiV1 should not have to deal with nodes
                  )
                )
                chain.doFilter(request, response)
              } else {
                failsAuthentication(
                  httpRequest,
                  httpResponse,
                  Inconsistency(s"Missing or empty HTTP header '${apiTokenHeaderName}'")
                )
              }

            case token =>
              // try to authenticate
              val apiToken      = ApiTokenSecret(token)
              val apiTokenHash  = apiToken.toHash()
              val systemAccount = apiTokenRepository.getSystemAccount
              if (systemAccount.token.exists(_.equalsToken(apiTokenHash))) { // system token with super authz
                authenticate(
                  RudderUserDetail(
                    RudderAccount.Api(systemAccount),
                    UserStatus.Active,
                    Set(Role.Administrator), // this token has "admin rights - use with care
                    systemApiAcl,
                    NodeSecurityContext.All
                  )
                )

                chain.doFilter(request, response)
              } else { // standard token, try to find it in DB
                apiTokenRepository.getByToken(apiTokenHash).either.runNow match {
                  case Left(err) =>
                    failsAuthentication(httpRequest, httpResponse, err)

                  case Right(None) =>
                    failsAuthentication(
                      httpRequest,
                      httpResponse,
                      Inconsistency(s"No registered token '${apiToken.exposeSecretBeginning}'")
                    )

                  case Right(Some(principal)) =>
                    if (principal.isEnabled) {
                      principal.kind match {
                        case ApiAccountKind.System                           => // we don't want to allow system account kind from DB
                          failsAuthentication(
                            httpRequest,
                            httpResponse,
                            Inconsistency(s"A saved API account can not have the kind 'System': '${principal.name.value}'")
                          )
                        case ApiAccountKind.PublicApi(authz, expirationDate) =>
                          expirationDate match {
                            case Some(date) if (DateTime.now(DateTimeZone.UTC).isAfter(date)) =>
                              failsAuthentication(
                                httpRequest,
                                httpResponse,
                                Inconsistency(s"Account with ID ${principal.id.value} is disabled")
                              )
                            case _                                                            => // no expiration date or expiration date not reached
                              val user = RudderUserDetail(
                                RudderAccount.Api(principal),
                                UserStatus.Active,
                                RudderAuthType.Api.apiRudderRole,
                                authz,
                                principal.tenants
                              )
                              // cool, build an authentication token from it
                              authenticate(user)
                              chain.doFilter(request, response)
                          }
                        case ApiAccountKind.User                             =>
                          // User account need an update for their ACL.
                          (try {
                            Right(userDetailsService.loadUserDetailInfoByUsername(principal.id.value))
                          } catch {
                            case ex: UsernameNotFoundException =>
                              Left(
                                s"User with id '${principal.id.value}' was not found on the system. The API token linked to that user can not be used anymore."
                              )
                            case ex: Exception                 =>
                              Left(s"Error when trying to get user information linked to user '${principal.id.value}' API token.")
                          }) match {
                            case Right((u, info))
                                if RudderConfig.authenticationProviders
                                  .getProviderProperties()
                                  .get(info.managedBy)
                                  .map(_.restTokenFeatureSwitch == FeatureSwitch.Enabled)
                                  .getOrElse(false) => // do not allow rest authentication if user is managed by unknown provider
                              // update acl
                              authenticate(
                                RudderUserDetail(
                                  RudderAccount.Api(principal),
                                  u.status,
                                  u.roles,
                                  u.apiAuthz,
                                  u.nodePerms
                                )
                              )
                              chain.doFilter(request, response)

                            case Right((u, info)) =>
                              failsAuthentication(
                                httpRequest,
                                httpResponse,
                                Inconsistency(
                                  s"User with id '${u.getUsername} and managed by provider ${info.managedBy} is not allowed to use the REST API. " +
                                  s"The configuration for this provider disables REST API token authentication, you should change the provider configuration to enable that."
                                )
                              )

                            case Left(er) =>
                              failsAuthentication(httpRequest, httpResponse, Inconsistency(er))
                          }
                      }
                    } else {
                      failsAuthentication(
                        httpRequest,
                        httpResponse,
                        Inconsistency(s"Account with ID ${principal.id.value} is disabled")
                      )
                    }
                }
              }
          }

        case _ =>
          // can not do anything with that, chain filter.
          chain.doFilter(request, response)
      }
    }

  }
}

object RestAuthenticationFilter {
  val API_TOKEN_HEADER: String = "X-API-Token"
}

/*
 * A class that simply add the rudder name to the authentication provider so that we can provide helpful messages
 */
case class RudderAuthenticationProvider(name: String, provider: AuthenticationProvider) extends AuthenticationProvider {
  override def authenticate(authentication: Authentication): Authentication = {
    provider.authenticate(authentication)
  }

  override def supports(authentication: Class[?]): Boolean = {
    provider.supports(authentication)
  }
}
