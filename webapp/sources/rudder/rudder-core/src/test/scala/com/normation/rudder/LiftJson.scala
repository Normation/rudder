package com.normation.rudder

trait LiftJson[A] {
  def encode(a: A): String
}
