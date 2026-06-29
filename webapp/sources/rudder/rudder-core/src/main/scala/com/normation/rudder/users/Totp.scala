package com.normation.rudder.users

import java.time.Instant

// Need to be a case class because opaque type doesn't allow toString override
case class TotpSecret private (secret: String) extends AnyVal {
  // Avoid printing the value
  override def toString: String = "[REDACTED TotpSecret]"

  def exposeSecret(): String = {
    secret
  }
}
object TotpSecret {
  def apply(secret: String) = new TotpSecret(secret)
}

/**
 * Represents a TOTP (Time-based One-Time Password) configuration for a user.
 * Stores the secret and creation timestamp.
 */
case class Totp(
    secret:  TotpSecret,
    created: Instant
)
