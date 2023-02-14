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

import com.normation.eventlog.EventLog
import com.normation.eventlog.EventLogDetails
import com.normation.eventlog.ModificationId
import com.normation.inventory.domain.InventoryProcessingLogger
import com.normation.plugins.AlwaysEnabledPluginStatus
import com.normation.plugins.PluginName
import com.normation.plugins.PluginStatus
import com.normation.plugins.PluginVersion
import com.normation.plugins.RudderPluginDef
import com.normation.plugins.RudderPluginModule
import com.normation.rudder.AuthorizationType
import com.normation.rudder.domain.eventlog.ApplicationStarted
import com.normation.rudder.domain.logger.ApplicationLogger
import com.normation.rudder.domain.logger.ApplicationLoggerPure
import com.normation.rudder.domain.logger.PluginLogger
import com.normation.rudder.rest.{InfoApi => InfoApiDef}
import com.normation.rudder.rest.ApiModuleProvider
import com.normation.rudder.rest.EndpointSchema
import com.normation.rudder.rest.lift.InfoApi
import com.normation.rudder.rest.lift.LiftApiModuleProvider
import com.normation.rudder.rest.v1.RestStatus
import com.normation.rudder.web.services.CurrentUser
import com.normation.rudder.web.snippet.WithCachedResource
import com.normation.zio._
import java.net.URI
import java.net.URLConnection
import java.util.Locale
import net.liftweb.common._
import net.liftweb.http._
import net.liftweb.http.js.JE.JsRaw
import net.liftweb.http.rest.RestHelper
import net.liftweb.sitemap._
import net.liftweb.sitemap.Loc._
import net.liftweb.sitemap.Loc.LocGroup
import net.liftweb.sitemap.Loc.TestAccess
import net.liftweb.sitemap.Menu
import net.liftweb.util.TimeHelpers._
import org.joda.time.DateTime
import org.reflections.Reflections
import org.springframework.security.core.context.SecurityContextHolder
import scala.concurrent.duration.DAYS
import scala.concurrent.duration.Duration
import scala.xml.NodeSeq

/*
 * Utilities about rights
 */
object Boot {
  val redirection =
    RedirectState(() => (), "You are not authorized to access that page, please contact your administrator." -> NoticeType.Error)

  def userIsAllowed(redirectTo: String, requiredAuthz: AuthorizationType*): Box[LiftResponse] = {
    if (requiredAuthz.exists((CurrentUser.checkRights(_)))) {
      Empty
    } else {
      Full(RedirectWithState(redirectTo, redirection))
    }
  }
}

/*
 * Configuration about plugins. Information will only be available
 * after full boot, and it's why that object is not in RudderConfig.
 * You can't use information from here in RudderConfig services without
 * a system of callback or something like that.
 *
 * Plugins are stored by their plugin-fullname.
 */
object PluginsInfo {

  private[this] var _plugins = Map[PluginName, RudderPluginDef]()

  def registerPlugin(plugin: RudderPluginDef) = {
    _plugins = _plugins + (plugin.name -> plugin)
  }

  def plugins = _plugins

  def pluginApisDef: List[EndpointSchema] = {

    @scala.annotation.tailrec
    def recApi(apis: List[EndpointSchema], plugins: List[RudderPluginDef]): List[EndpointSchema] = {
      plugins match {
        case Nil            => apis
        case plugin :: tail =>
          plugin.apis match {
            case None    => recApi(apis, tail)
            case Some(x) =>
              x.schemas match {
                case p: ApiModuleProvider[_] => recApi(p.endpoints ::: apis, tail)
                case _ => recApi(apis, tail)
              }
          }
      }
    }
    recApi(Nil, _plugins.values.toList)
  }
}

////////// rewrites rules to remove the version from resources urls //////////
//////////
object StaticResourceRewrite extends RestHelper {
  // prefix added to signal that the resource is cached
  val prefix = s"cache-${RudderConfig.rudderFullVersion}"
  def headers(others: List[(String, String)]): List[(String, String)] = {
    ("Cache-Control", "max-age=31556926, public") ::
    ("Pragma", "") ::
    ("Expires", DateTime.now.plusMonths(6).toString("EEE, d MMM yyyy HH':'mm':'ss 'GMT'")) ::
    others
  }

  // the resource directory we want to server that way
  val resources = Set("javascript", "style", "images", "toserve")
  serve {
    case Get(prefix :: resource :: tail, req) if (resources.contains(resource)) =>
      val resourcePath = req.uri.replaceFirst(prefix + "/", "")
      () => {
        for {
          url <- LiftRules.getResource(resourcePath)
        } yield {
          val contentType = URLConnection.guessContentTypeFromName(url.getFile) match {
            // if we don't know the content type, skip the header: most of the time,
            // browsers can live without it, but can't with a bad value in it
            case null                         => Nil
            case x if (x.contains("unknown")) => Nil
            case x                            => ("Content-Type", x) :: Nil
          }
          val conn        = url.openConnection // will be local, so ~ efficient
          val size        = conn.getContentLength.toLong
          val in          = conn.getInputStream
          StreamingResponse(in, () => in.close, size = size, headers(contentType), cookies = Nil, code = 200)
        }
      }
  }
}

/*
 * Define the list of fatal exception that should stop rudder.
 */
object FatalException {

  private[this] var fatalException  = Set[String]()
  // need to be pre-allocated
  private[this] val format          = org.joda.time.format.ISODateTimeFormat.dateTime()
  /*
   * Call that method with the list of fatal exception to set-up the
   * UncaughtExceptionHandler.
   * Termination should be () => System.exit(1) safe in tests.
   */
  def init(exceptions: Set[String]) = {
    this.fatalException = exceptions + "java.lang.Error"

    Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
      override def uncaughtException(t: Thread, e: Throwable): Unit = {
        val desc =
          s"exception in thread '${t.getName}' (in threadgroup '${t.getThreadGroup.getName}'): '${e.getClass.getName}': '${e.getMessage}'"

        // use println to minimize the number of component that can fail
        if (e.isInstanceOf[java.lang.Error] || fatalException.contains(e.getClass.getName)) {
          System.err.println(
            s"[${format.print(System.currentTimeMillis())}] ERROR FATAL Rudder JVM caught an unhandled fatal exception. Rudder will now stop to " +
            "prevent further inconsistant behavior. This is likely a bug, please " +
            "contact Rudder developers. You can configure the list of fatal exception " +
            "in /opt/rudder/etc/rudder-web.properties -> rudder.jvm.fatal.exceptions"
          )
          System.err.println(s"[${format.print(System.currentTimeMillis())}] ERROR FATAL ${desc}")
          e.printStackTrace()
          System.exit(5)
        } else {
          ApplicationLogger.warn(
            s"Uncaught ${desc} (add it in /opt/rudder/etc/rudder-web.properties -> 'rudder.jvm.fatal.exceptions' to make it fatal)"
          )
          e.printStackTrace()
        }
      }
    })
    ApplicationLogger.info(
      s"Global exception handler configured to stop Rudder on: ${fatalException.toList.sorted.mkString(", ")}"
    )
  }
}

/**
 * A class that's instantiated early and run.  It allows the application
 * to modify lift's environment
 */
class Boot extends Loggable {

  import Boot._

  def boot(): Unit = {

    // Set locale to English to prevent having localized message in some exception message (like SAXParserException in AppConfigAuth).
    // For now we don't manage locale in Rudder so setting it to English is harmless.
    // If one day we handle it in Rudder we should start from here by modifying code here..
    Locale.setDefault(Locale.ENGLISH)

    LiftRules.early.append({ req: provider.HTTPRequest => req.setCharacterEncoding("UTF-8") })
    LiftRules.ajaxStart = Full(() => LiftRules.jsArtifacts.show("ajax-loader").cmd)
    LiftRules.ajaxEnd = Full(() => LiftRules.jsArtifacts.hide("ajax-loader").cmd)
    LiftRules.ajaxPostTimeout = 30000

    LiftRules.maxMimeFileSize = 10 * 1024 * 1024

    // We don't want to reload the page
    LiftRules.redirectAsyncOnSessionLoss = false;
    // we don't want to retry on ajax timeout, as it may have big consequence
    // when it's (for example) a deploy

    logger.info(LiftRules.resourceServerPath)
    LiftRules.ajaxRetryCount = Full(1)

    // where to search snippet
    LiftRules.addToPackages("com.normation.rudder.web")

    // exclude Rudder doc from context-path rewriting
    LiftRules.excludePathFromContextPathRewriting.default.set(() => { (path: String) =>
      {
        val noRedirectPaths = "/rudder-doc" :: Nil
        noRedirectPaths.exists(path.startsWith)
      }
    })

    //// init plugin code (ie: bootstrap their objects / connections / etc ////
    val plugins = initPlugins()

    ////////// CACHE INVALIDATION FOR RESOURCES //////////
    // fails on invalid JSON body because it's unsufferable
    LiftRules.statelessDispatch.append {
      case req: Req
          if (req.json_?
          && req.requestType != GetRequest
          && req.requestType != HeadRequest
          && req.requestType != OptionsRequest
          && req.json != null
          && req.json.isEmpty) =>
        () => {
          Full(
            JsonResponse(
              net.liftweb.json.parse(
                """{"result": "error"
                  |, "errorDetails": "The request has a JSON content type but the body is not valid JSON"}""".stripMargin
              ),
              412
            )
          )
        }
    }
    // Resolve resources prefixed with the cache resource prefix
    LiftRules.statelessDispatch.append(StaticResourceRewrite)
    // and tell lift to append rudder version when "with-resource-id" is used
    LiftRules.attachResourceId = (path: String) => { "/" + StaticResourceRewrite.prefix + path }
    LiftRules.snippetDispatch.append(Map("with-cached-resource" -> WithCachedResource))

    // REST API V1
    LiftRules.statelessDispatch.append(RestStatus)

    // REST API Internal
    LiftRules.statelessDispatch.append(RudderConfig.restApiAccounts)
    LiftRules.statelessDispatch.append(RudderConfig.restQuicksearch)
    LiftRules.statelessDispatch.append(RudderConfig.restCompletion)
    LiftRules.statelessDispatch.append(RudderConfig.sharedFileApi)
    LiftRules.statelessDispatch.append(RudderConfig.eventLogApi)
    // REST API (all public/internal API)
    // we need to add "info" API here to have all used API (even plugins)
    val infoApi = {
      // all used api - add info as it is not yet declared
      val schemas   = RudderConfig.rudderApi.apis().map(_.schema) ++ InfoApiDef.endpoints
      val endpoints = schemas.flatMap(RudderConfig.apiDispatcher.withVersion(_, RudderConfig.ApiVersions))
      new InfoApi(RudderConfig.restExtractorService, RudderConfig.ApiVersions, endpoints)
    }
    RudderConfig.rudderApi.addModules(infoApi.getLiftEndpoints())
    LiftRules.statelessDispatch.append(RudderConfig.rudderApi.getLiftRestApi())

    // URL rewrites
    LiftRules.statefulRewrite.append {
      case RewriteRequest(
            ParsePath("secure" :: "administration" :: "techniqueLibraryManagement" :: activeTechniqueId :: Nil, _, _, _),
            GetRequest,
            _
          ) =>
        RewriteResponse(
          "secure" :: "administration" :: "techniqueLibraryManagement" :: Nil,
          Map("techniqueId" -> activeTechniqueId)
        )
      case RewriteRequest(ParsePath("secure" :: "nodeManager" :: "searchNodes" :: nodeId :: Nil, _, _, _), GetRequest, _) =>
        RewriteResponse("secure" :: "nodeManager" :: "searchNodes" :: Nil, Map("nodeId" -> nodeId))
      case RewriteRequest(ParsePath("secure" :: "nodeManager" :: "node" :: nodeId :: Nil, _, _, _), GetRequest, _)        =>
        RewriteResponse("secure" :: "nodeManager" :: "node" :: Nil, Map("nodeId" -> nodeId))
    }

    // Fix relative path to css resources
    LiftRules.fixCSS("style" :: "style" :: Nil, Empty)

    // i18n
    LiftRules.resourceNames = "default" :: "ldapObjectAndAttributes" :: "eventLogTypeNames" :: Nil

    // Content type things : use text/html in place of application/xhtml+xml
    LiftRules.useXhtmlMimeType = false

    ////////// SECURITY SETTINGS //////////

    // Strict-Transport-Security (HSTS) header
    val hsts = if (RudderConfig.RUDDER_SERVER_HSTS) {
      // Include subdomains or not depending on setting
      // Set for 1 year (standard value for "forever")
      Some(HttpsRules(includeSubDomains = RudderConfig.RUDDER_SERVER_HSTS_SUBDOMAINS, requiredTime = Some(Duration(365, DAYS))))
    } else {
      None
    }

    // Content-Security-Policies header
    // Only prevent loading external resources, no other XSS protection for now
    // Can be made stricter for some pages when we get rid of inline scripts and style
    val csp = ContentSecurityPolicy(
      reportUri = Full(new URI("/rudder/lift/content-security-policy-report")),
      defaultSources = ContentSourceRestriction.Self :: Nil,
      imageSources = ContentSourceRestriction.Self :: ContentSourceRestriction.Scheme("data") :: Nil,
      styleSources = ContentSourceRestriction.Self :: ContentSourceRestriction.UnsafeInline :: Nil,
      scriptSources =
        ContentSourceRestriction.Self :: ContentSourceRestriction.UnsafeInline :: ContentSourceRestriction.UnsafeEval :: Nil
    )

    LiftRules.securityRules = () => {
      SecurityRules(
        https = hsts,
        content = Some(csp),
        // Allow frames from same domain, used by external-node-info and openscap plugins
        frameRestrictions = Some(FrameRestrictions.SameOrigin),
        // OtherModes = not(DevMode) = Prod, enforce and log
        enforceInOtherModes = true,
        logInOtherModes = true,
        // Dev mode, don't enforce but log
        enforceInDevMode = false,
        logInDevMode = true
      )
    }

    // Override to remove X-Lift-Version header
    LiftRules.supplementalHeaders.default.set(
      // Prevent search engine indexation
      ("X-Robots-Tag", "noindex, nofollow") ::
      LiftRules.securityRules().headers
    )

    // By default Lift redirects to login page when a comet request's session changes
    // which happens when there is a connection to the same server in another tab.
    // Do nothing instead, as it allows to keep open tabs context until we get the new cookie
    // This does not affect security as it is only a redirection anyway and did not change
    // the session itself.
    LiftRules.noCometSessionCmd.default.set(() =>
      JsRaw(s"createErrorNotification('You have been signed out. Please reload the page to sign in again.')").cmd
    )

    // Log CSP violations
    LiftRules.contentSecurityPolicyViolationReport = (r: ContentSecurityPolicyViolation) => {
      ApplicationLogger.warn(
        s"Content security policy violation: blocked ${r.blockedUri} in ${r.documentUri} because of ${r.violatedDirective} directive"
      )
      Full(OkResponse())
    }

    // Store access time for user idle tracking in each non-comet request
    // Required as standard idle timeout includes comet requests,
    // which practically means that an open page never expires.
    LiftRules.onBeginServicing.append((r: Req) => {
      // This filters out lift-related XHR only, which is exactly what we want
      if (r.standardRequest_?) {
        LiftRules
          .getLiftSession(r)
          .httpSession
          .foreach(s => s.setAttribute("lastNonCometAccessedTime", millis))
      }
    })

    // Custom session expiration that ignores comet requests
    val IdleSessionTimeout = (sessions: Map[String, SessionInfo], delete: SessionInfo => Unit) => {
      sessions.foreach {
        case (id, info) =>
          info.session.httpSession.foreach(s => {
            s.attribute("lastNonCometAccessedTime") match {
              case lastNonCometAccessedTime: Long =>
                val inactiveFor = millis - lastNonCometAccessedTime
                LiftRules.sessionInactivityTimeout.vend.foreach(timeout => {
                  ApplicationLogger.trace(s"Session $id inactive for ${inactiveFor}ms / ${timeout}ms (${info.userAgent})")
                  if (inactiveFor > timeout) {
                    ApplicationLogger.debug(
                      s"Session $id has been inactive for ${inactiveFor}ms which exceeds the ${timeout}ms limit, terminating"
                    )
                    // Let's terminate the session
                    SecurityContextHolder.clearContext()
                    // info.session.destroySession() //does not seems to actually terminate the session everytime
                    info.session.httpSession.foreach(s => s.terminate)
                    // This only cleans up the session at lift level and unlink underlying session
                    // but does nothing on it.
                    delete(info)
                  }
                })
              // null here means lastNonCometAccessedTime was not set, which happens if only
              // non-standard requests happened on a connection.
              case null => ()
              case _ => ApplicationLogger.error("lastNonCometAccessedTime has an unexpected value, please report a bug.")
            }
          })
      }
    }
    // Register session cleaner
    SessionMaster.sessionCheckFuncs = SessionMaster.sessionCheckFuncs ::: List(IdleSessionTimeout)

    // Set timeout value, which will be applied by both the standard and custom session cleaner
    LiftRules.sessionInactivityTimeout.default.set(RudderConfig.AUTH_IDLE_TIMEOUT.map(d => d.toMillis))

    ////////// END OF SECURITY SETTINGS //////////

    /*
     * For development, we override the default local calculator
     * to allow explicit locale switch with just the addition
     * of &locale=en at the end of urls
     */
    import net.liftweb.http.provider.HTTPRequest
    import java.util.Locale
    val DefaultLocale = new Locale("")
    LiftRules.localeCalculator = { (request: Box[HTTPRequest]) =>
      {
        request match {
          case Empty | Failure(_, _, _) => DefaultLocale
          case Full(r)                  =>
            r.param("locale") match {
              case Nil         => DefaultLocale
              case loc :: tail => {
                logger.debug("Switch to locale: " + loc)
                loc.split("_").toList match {
                  case Nil                     => DefaultLocale
                  case lang :: Nil             => new Locale(lang)
                  case lang :: country :: tail => new Locale(lang, country)
                }
              }
            }
        }
      }
    }

    // All the following is related to the sitemap
    val nodeManagerMenu = {
      (Menu(MenuUtils.nodeManagementMenu, <i class="fa fa-sitemap"></i> ++ <span>Node management</span>: NodeSeq) /
      "secure" / "nodeManager" / "index" >> TestAccess(() => userIsAllowed("/secure/index", AuthorizationType.Node.Read)))
        .submenus(
          Menu("110-nodes", <span>Nodes</span>) /
          "secure" / "nodeManager" / "nodes"
          >> LocGroup("nodeGroup"),
          Menu("120-search-nodes", <span>Node search</span>) /
          "secure" / "nodeManager" / "searchNodes"
          >> LocGroup("nodeGroup"),
          Menu("120-node-details", <span>Node details</span>) /
          "secure" / "nodeManager" / "node"
          >> LocGroup("nodeGroup")
          >> Hidden,
          Menu("130-pending-nodes", <span>Pending nodes</span>) /
          "secure" / "nodeManager" / "manageNewNode"
          >> LocGroup("nodeGroup"),
          Menu("140-groups", <span>Groups</span>) /
          "secure" / "nodeManager" / "groups"
          >> LocGroup("groupGroup")
          >> TestAccess(() => userIsAllowed("/secure/index", AuthorizationType.Group.Read))
        )
    }

    def policyMenu = {
      val name = "configuration"
      (Menu(MenuUtils.policyMenu, <i class="fa fa-pencil"></i> ++ <span>{name.capitalize} policy</span>: NodeSeq) /
      "secure" / (name + "Manager") / "index" >> TestAccess(() =>
        userIsAllowed("/secure/index", AuthorizationType.Configuration.Read)
      )).submenus(
        Menu("210-rules", <span>Rules</span>) /
        "secure" / (name + "Manager") / "ruleManagement"
        >> LocGroup(name + "Group")
        >> TestAccess(() => userIsAllowed("/secure/index", AuthorizationType.Rule.Read)),
        Menu("210-rule-details", <span>Rule</span>) /
        "secure" / (name + "Manager") / "ruleManagement" / "rule" / *
        >> TemplateBox { case _ => Templates("secure" :: (name + "Manager") :: "ruleManagement" :: Nil) }
        >> LocGroup(name + "Group")
        >> TestAccess(() => userIsAllowed("/secure/index", AuthorizationType.Rule.Read))
        >> Hidden,
        Menu("210-rule-category", <span>Rule Category</span>) /
        "secure" / (name + "Manager") / "ruleManagement" / "ruleCategory" / *
        >> TemplateBox { case _ => Templates("secure" :: (name + "Manager") :: "ruleManagement" :: Nil) }
        >> LocGroup(name + "Group")
        >> TestAccess(() => userIsAllowed("/secure/index", AuthorizationType.Rule.Read))
        >> Hidden,
        Menu("220-directives", <span>Directives</span>) /
        "secure" / (name + "Manager") / "directiveManagement"
        >> LocGroup(name + "Group")
        >> TestAccess(() => userIsAllowed("/secure/index", AuthorizationType.Directive.Read)),
        Menu("230-techniques", <span>Techniques</span>) /
        "secure" / (name + "Manager") / "techniqueEditor"
        >> LocGroup((name + "Manager"))
        >> TestAccess(() => userIsAllowed("/secure/index", AuthorizationType.Technique.Read)),
        Menu("230-technique-details", <span>Technique</span>) /
        "secure" / (name + "Manager") / "techniqueEditor" / "technique" / *
        >> TemplateBox { case _ => Templates("secure" :: (name + "Manager") :: "techniqueEditor" :: Nil) }
        >> LocGroup(name + "Group")
        >> TestAccess(() => userIsAllowed("/secure/index", AuthorizationType.Rule.Read))
        >> Hidden,
        Menu("240-global-parameters", <span>Parameters</span>) /
        "secure" / (name + "Manager") / "parameterManagement"
        >> LocGroup(name + "Group")
        >> TestAccess(() => userIsAllowed("/secure/index", AuthorizationType.Parameter.Read))
      )
    }

    def administrationMenu = {
      (Menu(MenuUtils.administrationMenu, <i class="fa fa-gear"></i> ++ <span>Administration</span>: NodeSeq) /
      "secure" / "administration" / "index" >> TestAccess(() =>
        userIsAllowed("/secure/index", AuthorizationType.Administration.Read, AuthorizationType.Technique.Read)
      )).submenus(
        Menu("710-setup", <span>Setup</span>) /
        "secure" / "administration" / "setup"
        >> LocGroup("administrationGroup")
        >> TestAccess(() => userIsAllowed("/secure/index", AuthorizationType.Administration.Read)),
        Menu("720-settings", <span>Settings</span>) /
        "secure" / "administration" / "policyServerManagement"
        >> LocGroup("administrationGroup")
        >> TestAccess(() => userIsAllowed("/secure/index", AuthorizationType.Administration.Read)),
        Menu("730-database", <span>Reports database</span>) /
        "secure" / "administration" / "databaseManagement"
        >> LocGroup("administrationGroup")
        >> TestAccess(() =>
          userIsAllowed("/secure/administration/policyServerManagement", AuthorizationType.Administration.Read)
        ),
        Menu("740-techniques-tree", <span>Techniques tree</span>) /
        "secure" / "administration" / "techniqueLibraryManagement"
        >> LocGroup("administrationGroup")
        >> TestAccess(() => userIsAllowed("/secure/index", AuthorizationType.Technique.Read)),
        Menu("750-api", <span>API accounts</span>) /
        "secure" / "administration" / "apiManagement"
        >> LocGroup("administrationGroup")
        >> TestAccess(() =>
          userIsAllowed("/secure/administration/policyServerManagement", AuthorizationType.Administration.Write)
        ),
        Menu("760-hooks", <span>Hooks</span>) /
        "secure" / "administration" / "hooksManagement"
        >> LocGroup("administrationGroup")
        >> TestAccess(() => userIsAllowed("/secure/index", AuthorizationType.Administration.Read))
      )
    }

    def pluginsMenu = {
      (Menu(
        MenuUtils.pluginsMenu,
        <i class="fa fa-puzzle-piece"></i> ++ <span>Plugins</span> ++ <span data-lift="PluginExpirationInfo.renderIcon"></span>: NodeSeq
      ) /
      "secure" / "plugins" / "index"
      >> LocGroup("pluginsGroup")
      >> TestAccess(() => userIsAllowed("/secure/index", AuthorizationType.Administration.Read))) submenus (
        Menu("910-plugins", <span>Plugin information</span>) /
        "secure" / "plugins" / "pluginInformation"
        >> LocGroup("pluginsGroup")
        >> TestAccess(() => userIsAllowed("/secure/index", AuthorizationType.Administration.Read))
      )
    }

    def utilitiesMenu = {
      // if we can't get the workflow property, default to false
      // (don't give rights if you don't know)
      def workflowEnabled = RudderConfig.configService.rudder_workflow_enabled().either.runNow.getOrElse(false)
      (Menu(MenuUtils.utilitiesMenu, <i class="fa fa-wrench"></i> ++ <span>Utilities</span>: NodeSeq) /
      "secure" / "utilities" / "index" >>
      TestAccess(() => {
        if (
          (workflowEnabled && (CurrentUser.checkRights(AuthorizationType.Validator.Read) || CurrentUser
            .checkRights(AuthorizationType.Deployer.Read))) || CurrentUser
            .checkRights(AuthorizationType.Administration.Read) || CurrentUser.checkRights(AuthorizationType.Technique.Read)
        ) {
          Empty
        } else {
          Full(RedirectWithState("/secure/index", redirection))
        }
      })).submenus(
        Menu("610-archives", <span>Archives</span>) /
        "secure" / "utilities" / "archiveManagement"
        >> LocGroup("utilitiesGroup")
        >> TestAccess(() => userIsAllowed("/secure/utilities/eventLogs", AuthorizationType.Administration.Write)),
        Menu("620-event-logs", <span>Event logs</span>) /
        "secure" / "utilities" / "eventLogs"
        >> LocGroup("utilitiesGroup")
        >> TestAccess(() => userIsAllowed("/secure/index", AuthorizationType.Administration.Read)),
        Menu("630-health-check", <span>Health check</span>) /
        "secure" / "utilities" / "healthcheck"
        >> LocGroup("utilitiesGroup")
        >> TestAccess(() => userIsAllowed("/secure/index", AuthorizationType.Administration.Read))
      )
    }

    val rootMenu = List(
      Menu("000-dashboard", <i class="fa fa-dashboard"></i> ++ <span>Dashboard</span>: NodeSeq) / "secure" / "index",
      Menu("010-login") / "index" >> Hidden,
      Menu("020-templates") / "templates" / ** >> Hidden, // allows access to html file use by js
      nodeManagerMenu,
      policyMenu,
      utilitiesMenu,
      administrationMenu,
      pluginsMenu
    ).map(_.toMenu)

    ////////// import and init modules //////////
    val newSiteMap = addPluginsMenuTo(plugins, rootMenu.map(_.toMenu))

    // not sur why we are using that ?
    // SiteMap.enforceUniqueLinks = false

    LiftRules.setSiteMapFunc(() => SiteMap(newSiteMap: _*))

    // load users from rudder-users.xml
    RudderConfig.rudderUserListProvider.reload()

    // start node count historization
    ZioRuntime.runNow(
      RudderConfig.historizeNodeCountBatch.catchAll(err =>
        ApplicationLoggerPure.error(s"Error when starting node historization batch: ${err.fullMsg}")
      )
    )
    // start inventory garbage collector
    RudderConfig.inventoryWatcher.startGarbageCollection
    // start inventory watchers if needed
    if (RudderConfig.WATCHER_ENABLE) {
      RudderConfig.inventoryWatcher.startWatcher()
    } else { // don't start
      InventoryProcessingLogger.debug(
        s"Not automatically starting incoming inventory watcher because 'inventories.watcher.enable'=${RudderConfig.WATCHER_ENABLE}"
      )
    }

    RudderConfig.eventLogRepository
      .saveEventLog(
        ModificationId(RudderConfig.stringUuidGenerator.newUuid),
        ApplicationStarted(
          EventLogDetails(
            modificationId = None,
            principal = com.normation.rudder.domain.eventlog.RudderEventActor,
            details = EventLog.emptyDetails,
            reason = None
          )
        )
      )
      .either
      .runNow match {
      case Left(err) =>
        ApplicationLogger.error(s"Error when trying to save the EventLog for application start: ${err.fullMsg}")
      case Right(_)  => ApplicationLogger.info("Application Rudder started")
    }

    // release guard for promise generation awaiting end of boot
    RudderConfig.policyGenerationBootGuard.succeed(()).runNow

  }

  // Run a health check
  RudderConfig.healthcheckNotificationService.init

  private[this] def addPluginsMenuTo(plugins: List[RudderPluginDef], menus: List[Menu]): List[Menu] = {
    // return the updated siteMap
    plugins.foldLeft(menus) { case (prev, mutator) => mutator.updateSiteMap(prev) }
  }

  private[this] def initPlugins(): List[RudderPluginDef] = {
    import scala.jdk.CollectionConverters._

    val reflections = new Reflections("bootstrap.rudder.plugin", "com.normation.plugins")

    val modules = reflections
      .getSubTypesOf(classOf[RudderPluginModule])
      .asScala
      .map(c => c.getField("MODULE$").get(null).asInstanceOf[RudderPluginModule])

    val scalaPlugins = modules.toList.map(_.pluginDef).map(p => (p.name.value, p)).toMap

    val nonScala = RudderConfig.jsonPluginDefinition.getInfo().either.runNow match {
      case Left(err)      =>
        PluginLogger.error(
          s"Error when trying to read plugins index file '${RudderConfig.jsonPluginDefinition.index.pathAsString}': ${err.fullMsg}"
        )
        Nil
      case Right(plugins) =>
        val scalaKeys = scalaPlugins.keySet
        // log parsing errors
        plugins.collect { case Left(e) => e }.foreach { e =>
          PluginLogger.error(
            s"Error when parsing plugin information from index file '${RudderConfig.jsonPluginDefinition.index.pathAsString}': ${e.fullMsg}"
          )
        }
        plugins.collect { case Right(p) if (!scalaKeys.contains(p.name) && p.jars.isEmpty) => p }.map { p =>
          val sn = p.name.replace("rudder-plugin-", "")
          new RudderPluginDef {
            override def displayName = sn.capitalize
            override val name        = PluginName(p.name)
            override val shortName:   String         = sn
            override val description: NodeSeq        = <p>{p.name}</p>
            override val version:     PluginVersion  = p.version
            override val versionInfo: Option[String] = None
            override val status:      PluginStatus   = AlwaysEnabledPluginStatus
            override val init = ()
            override val basePackage: String              = p.name
            override val configFiles: Seq[ConfigResource] = Nil
          }
        }
    }

    val pluginDefs = scalaPlugins.values.toList ++ nonScala

    pluginDefs.foreach { plugin =>
      PluginLogger.info(s"Initializing plugin '${plugin.name.value}': ${plugin.version.toString}")

      // resources in src/main/resources/toserve/${plugin short-name} must be allowed for each plugin
      ResourceServer.allow {
        case base :: _ if (base == plugin.shortName) => true
      }
      plugin.init

      // add APIs
      plugin.apis.foreach { (api: LiftApiModuleProvider[_]) =>
        RudderConfig.rudderApi.addModules(api.getLiftEndpoints())
        RudderConfig.authorizationApiMapping.addMapper(api.schemas.authorizationApiMapping)
      }

      // add the plugin packages to Lift package to look for packages and add API information for other services
      // add the plugin packages to Lift package to look for packages
      LiftRules.addToPackages(plugin.basePackage)
      PluginsInfo.registerPlugin(plugin)
    }

    pluginDefs
  }
}

object MenuUtils {
  val nodeManagementMenu = "100-nodes"
  val policyMenu         = "200-policy"
  val administrationMenu = "700-administration"
  val utilitiesMenu      = "600-utilities"
  val pluginsMenu        = "900-plugins"
}
