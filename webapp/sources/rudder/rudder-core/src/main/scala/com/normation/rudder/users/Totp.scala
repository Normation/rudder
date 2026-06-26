package com.normation.rudder.users

import java.time.Instant

opaque type TotpSecret = String
object TotpSecret {
  def apply(s: String): TotpSecret = s

  extension (self: TotpSecret) {
    def value: String = self
  }
}

/**
 * Represents a TOTP (Time-based One-Time Password) configuration for a user.
 * Stores the secret and creation timestamp.
 */
case class Totp(
    secret:  TotpSecret,
    created: Instant
)
