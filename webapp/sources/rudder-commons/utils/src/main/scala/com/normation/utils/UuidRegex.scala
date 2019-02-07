package com.normation.utils
import java.util.regex.Pattern

object UuidRegex {

  val stringPattern = """[\w]{8}-[\w]{4}-[\w]{4}-[\w]{4}-[\w]{12}"""

  val pattern = Pattern.compile(stringPattern)

  def isValid(candidate:String) : Boolean = pattern.matcher(candidate).matches
}