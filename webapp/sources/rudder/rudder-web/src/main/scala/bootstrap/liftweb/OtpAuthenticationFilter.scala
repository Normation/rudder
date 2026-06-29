package bootstrap.liftweb

import com.normation.rudder.domain.logger.ApplicationLoggerPure
import com.normation.rudder.users.*
import com.normation.zio.UnsafeRun
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.*
import org.springframework.security.authentication.*
import org.springframework.security.core.*
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.authentication.*
import scala.jdk.CollectionConverters.*
import zio.*

/**
 * Filter for OTP check which is run when the enrollment/verify page submits the verification code
 */
class OtpAuthenticationFilter extends AbstractAuthenticationProcessingFilter("/secure/otp/verify") {

  private val otpService = RudderConfig.otpService

  @throws[AuthenticationException]
  override def attemptAuthentication(req: HttpServletRequest, res: HttpServletResponse): Authentication = {
    val current = SecurityContextHolder.getContext().getAuthentication

    val validAuthorities = RudderAuthType.PreAuthUser.grantedAuthorities.asScala.toSet
    if (
      current == null || !current
        .getAuthorities()
        .stream()
        .anyMatch(a => validAuthorities.contains(a))
    ) {
      throw new InsufficientAuthenticationException("OTP step requires prior password auth");
    }

    (Option(req.getParameter("code")), current.getPrincipal) match {
      case (Some(code), u: RudderUserDetail) =>
        val userId = u.getUsername
        // need to verify a newly generated OTP too
        otpService
          .verifyGenerated(userId, code)
          .tap(totp => ApplicationLoggerPure.Auth.info(s"User '${userId}' registered OTP at ${totp.created}"))
          .orElse(
            otpService
              .verify(userId, code)
              .onError(err => ApplicationLoggerPure.Auth.debug(s"User '${userId}' did not submit a valid OTP code")) *>
            ApplicationLoggerPure.Auth.debug(s"User '${userId}' submitted a valid OTP code")
          )
          .chainError(
            "Could not verify user OTP, pleasy retry with a valid code, generate a new OTP, or ask admin to reset your OTP if you lost it"
          )
          .fold(
            err => errorResponse(res, HttpStatus.FORBIDDEN, err.fullMsg),
            _ => new UsernamePasswordAuthenticationToken(u, null, RudderAuthType.User.grantedAuthorities)
          )
          .runNow
      case _                                 =>
        errorResponse(
          res,
          HttpStatus.UNAUTHORIZED,
          "Unknown user or code, not able to authenticate OTP"
        )
    }
  }

  private def errorResponse(
      response:   HttpServletResponse,
      httpStatus: HttpStatus,
      message:    String
  ): Authentication = {
    response.setStatus(httpStatus.value())
    val writer = response.getWriter()
    writer.write(message)
    writer.flush()
    // need to return an invalid authentication to avoid stack trace (Spring is sadly happy about this)
    null
  }
}
