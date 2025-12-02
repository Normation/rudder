package com.normation

import org.specs2.matcher.*
import org.specs2.mutable.Specification
import scala.xml.NodeSeq

trait XmlSpecMatcher { self: MustMatchers & Specification =>

  def equalsIgnoringSpace(res: NodeSeq): Matcher[NodeSeq] = {
    new EqualityMatcher(res.map(scala.xml.Utility.trim).toString) ^^ (_.map(scala.xml.Utility.trim).toString)
  }
}
