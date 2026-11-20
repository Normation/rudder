package com.normation.rudder.domain.secret

final case class Secret(name: String, value: String, description: String) {
  // Avoid dumping the secret value in logs, error messages, etc.
  // The name is not sensitive and helps debugging.
  override def toString: String = s"[REDACTED Secret ${name}]"
}
