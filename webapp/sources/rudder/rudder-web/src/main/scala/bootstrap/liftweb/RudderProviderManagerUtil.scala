package bootstrap.liftweb

import com.normation.errors.Inconsistency
import com.normation.errors.IOResult
import com.normation.rudder.domain.logger.ApplicationLoggerPure
import com.normation.rudder.users.RudderUserDetail
import com.normation.rudder.users.SessionId
import com.normation.rudder.users.UserRepository
import com.normation.zio.UnsafeRun
import org.joda.time.DateTime
import org.springframework.web.context.request.RequestAttributes
import org.springframework.web.context.request.ServletRequestAttributes
import zio.syntax.*

/**
 * Scala (ZIO, etc.) method calls used in RudderProviderManager.java, but needing additional transformations
 */
object RudderProviderManagerUtil {
  def logStartSession(
      userRepository:    UserRepository,
      details:           RudderUserDetail,
      sessionId:         SessionId,
      provider:          String,
      requestAttributes: RequestAttributes
  ): Unit = {
    (userRepository
      .logStartSession(
        details.getUsername,
        com.normation.rudder.Role.toDisplayNames(details.roles),
        com.normation.rudder.Rights
          .combineAll(details.roles.toList.map(_.rights))
          .authorizationTypes
          .toList
          .map(_.id),
        details.accessGrant.value,
        sessionId,
        provider,
        DateTime.now
      )
      .catchSome {
        case Inconsistency(msg) =>
          requestAttributes match {
            case requestAttrs: ServletRequestAttributes =>
              IOResult.attempt(requestAttrs.getRequest().getSession(false).invalidate())
            case _ =>
              // There is nothing we can do to change the user session programmatically, user will have to change session id manually
              ApplicationLoggerPure.warn(
                "Rudder does not know how to handle the current authentication request using " + requestAttributes.getClass.getName + ". Please retry to log in after clearing the browser cache."
              ) *>
              Inconsistency("Refused authentication: " + msg).fail
          }
      } *> // user session is started with known rights and password, we need to update users sessions cache to invalidate any change in user access
    LiftSpringApplicationContext.springContext
      .getBean(classOf[UserSessionInvalidationFilter])
      .updateUser(details)).runNow
  }
}
