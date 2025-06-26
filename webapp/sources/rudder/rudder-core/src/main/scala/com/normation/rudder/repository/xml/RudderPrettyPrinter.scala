/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2003-2013, LAMP/EPFL             **
**  __\ \/ /__/ __ |/ /__/ __ |    http://scala-lang.org/               **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
\*                                                                      */

/*
 * License notice:
 * This class is a copy of scala.xml.PrettyPrinter class of Scala 2.10.3.
 *
 * The only changes in it are:
 * - the method doPreserve always returning true
 *   to workaround https://issues.scala-lang.org/browse/SI-4303
 *   and correct http://www.rudder-project.org/redmine/issues/4476
 * - the copy&paste of scala.xml.Utility.sbToString because of its
 *   visibility modifier.
 *
 *
 * The class name is altered so that the good dependency is used
 * every where in Rudder project.
 */

package com.normation.rudder.repository.xml

class RudderPrettyPrinter(width: Int, step: Int) {

  import scala.xml.*

  def sbToString(f: (StringBuilder) => Unit): String = {
    val sb = new StringBuilder
    f(sb)
    sb.toString
  }

  class BrokenException() extends java.lang.Exception

  class Item
  case object Break                   extends Item {
    override def toString() = "\\"
  }
  case class Box(col: Int, s: String) extends Item
  case class Para(s: String)          extends Item

  protected var items: List[Item] = Nil

  protected var cur = 0

  protected def reset(): Unit = {
    cur = 0
    items = Nil
  }

  /** Try to cut at whitespace.
   */
  protected def cut(s: String, ind: Int): List[Item] = {
    val tmp = width - cur
    if (s.length <= tmp)
      return List(Box(ind, s))
    var i   = s.indexOf(' ')
    if (i > tmp || i == -1) throw new BrokenException() // cannot break

    var last: List[Int] = Nil
    while (i != -1 && i < tmp) {
      last = i :: last
      i = s.indexOf(' ', i + 1)
    }
    while (Nil != last) try {
      cur = ind
      // backtrack
      last = last.tail
    } catch {
      case _: BrokenException => last = last.tail
    }
    throw new BrokenException()
  }

  /** Try to make indented box, if possible, else para.
   */
  protected def makeBox(ind: Int, s: String): Unit = {
    if (cur + s.length > width) { // fits in this line
      items ::= Box(ind, s)
      cur += s.length
    } else {
      try cut(s, ind) foreach (items ::= _) // break it up
      catch { case _: BrokenException => makePara(ind, s) } // give up, para
    }
  }

  // dont respect indent in para, but afterwards
  protected def makePara(ind: Int, s: String): Unit = {
    items = Break :: Para(s) :: Break :: items
    cur = ind
  }

  // respect indent
  protected def makeBreak(): Unit = { // using wrapping here...
    items = Break :: items
    cur = 0
  }

  protected def leafTag(n: Node): String = {
    def mkLeaf(sb: StringBuilder): Unit = {
      sb append '<'
      n nameToString sb
      n.attributes buildString sb
      sb append "/>"
    }
    sbToString(mkLeaf)
  }

  protected def startTag(n: Node, pscope: NamespaceBinding): (String, Int) = {
    var i = 0
    def mkStart(sb: StringBuilder): Unit = {
      sb append '<'
      n nameToString sb
      i = sb.length + 1
      n.attributes buildString sb
      n.scope.buildString(sb, pscope)
      sb append '>'
    }
    (sbToString(mkStart), i)
  }

  protected def endTag(n: Node): String = {
    def mkEnd(sb: StringBuilder): Unit = {
      sb append "</"
      n nameToString sb
      sb append '>'
    }
    sbToString(mkEnd)
  }

  protected def childrenAreLeaves(n: Node): Boolean = {
    def isLeaf(l: Node) = l match {
      case _: Atom[?] | _: Comment | _: EntityRef | _: ProcInstr => true
      case _                                                     => false
    }
    n.child forall isLeaf
  }

  protected def fits(test: String): Boolean =
    test.length < width - cur

  private def doPreserve(node: Node) = true

  protected def traverse(node: Node, pscope: NamespaceBinding, ind: Int): Unit = node match {

    case Text(s) if s.trim() == ""                             =>
      ;
    case _: Atom[?] | _: Comment | _: EntityRef | _: ProcInstr =>
      makeBox(ind, node.toString.trim())
    case g @ Group(xs)                                         =>
      traverse(xs.iterator, pscope, ind)
    case _                                                     =>
      val test = {
        val sb = new StringBuilder()
        Utility.serialize(node, pscope, sb, stripComments = false)
        if (doPreserve(node)) sb.toString
        else TextBuffer.fromString(sb.toString).toText(0).data
      }
      if (childrenAreLeaves(node) && fits(test)) {
        makeBox(ind, test)
      } else {
        val (stg, len2) = startTag(node, pscope)
        val etg         = endTag(node)
        if (stg.length < width - cur) { // start tag fits
          makeBox(ind, stg)
          makeBreak()
          traverse(node.child.iterator, node.scope, ind + step)
          makeBox(ind, etg)
        } else if (len2 < width - cur) {
          // <start label + attrs + tag + content + end tag
          makeBox(ind, stg.substring(0, len2))
          makeBreak() // todo: break the rest in pieces
          /*{ //@todo
             val sq:Seq[String] = stg.split(" ");
             val it = sq.iterator;
             it.next;
             for (c <- it) {
               makeBox(ind+len2-2, c)
               makeBreak()
             }
             }*/
          makeBox(ind, stg.substring(len2, stg.length))
          makeBreak()
          traverse(node.child.iterator, node.scope, ind + step)
          makeBox(cur, etg)
          makeBreak()
        } else { // give up
          makeBox(ind, test)
          makeBreak()
        }
      }
  }

  protected def traverse(it: Iterator[Node], scope: NamespaceBinding, ind: Int): Unit = {
    for (c <- it) {
      traverse(c, scope, ind)
      makeBreak()
    }
  }

  /** Appends a formatted string containing well-formed XML with
   *  given namespace to prefix mapping to the given string buffer.
   *
   * @param n    the node to be serialized
   * @param sb   the stringbuffer to append to
   */
  def format(n: Node, sb: StringBuilder): Unit = { // entry point
    format(n, null, sb)
  }

  def format(n: Node, pscope: NamespaceBinding, sb: StringBuilder): Unit = { // entry point
    var lastwasbreak = false
    reset()
    traverse(n, pscope, 0)
    var cur          = 0
    for (b <- items.reverse) (b: @unchecked) match {
      case Break =>
        if (!lastwasbreak) sb.append('\n') // on windows: \r\n ?
        lastwasbreak = true
        cur = 0
//        while (cur < last) {
//          sb append ' '
//          cur += 1
//        }

      case Box(i, s) =>
        lastwasbreak = false
        while (cur < i) {
          sb append ' '
          cur += 1
        }
        sb.append(s)
      case Para(s)   =>
        lastwasbreak = false
        sb append s
    }
  }

  // public convenience methods

  /** Returns a formatted string containing well-formed XML with
   *  given namespace to prefix mapping.
   *
   *  @param n      the node to be serialized
   *  @param pscope the namespace to prefix mapping
   *  @return      the formatted string
   */
  def format(n: Node, pscope: NamespaceBinding = null): String =
    sbToString(format(n, pscope, _))

  /** Returns a formatted string containing well-formed XML.
   *
   *  @param nodes  the sequence of nodes to be serialized
   *  @param pscope the namespace to prefix mapping
   */
  def formatNodes(nodes: Seq[Node], pscope: NamespaceBinding = null): String =
    sbToString(formatNodes(nodes, pscope, _))

  /** Appends a formatted string containing well-formed XML with
   *  the given namespace to prefix mapping to the given stringbuffer.
   *
   *  @param nodes  the nodes to be serialized
   *  @param pscope the namespace to prefix mapping
   *  @param sb     the string buffer to which to append to
   */
  def formatNodes(nodes: Seq[Node], pscope: NamespaceBinding, sb: StringBuilder): Unit =
    nodes foreach (n => sb append format(n, pscope))
}
