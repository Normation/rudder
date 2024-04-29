/*
 *************************************************************************************
 * Copyright 2011 Normation SAS
 *************************************************************************************
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 *************************************************************************************
 */

package com.normation.utils

import cats.Applicative
import net.liftweb.common.*

/**
 *
 * Interesting control structures
 *
 */
object Control {

  import cats.syntax.traverse.*

  implicit private val boxApplicative: Applicative[Box] = new Applicative[Box] {
    override def pure[A](x: A): Box[A] = Full(x)

    override def ap[A, B](ff: Box[A => B])(fa: Box[A]): Box[B] = ff.flatMap(f => fa.map(f))
  }

  /**
   * Instance of the application function to Iterable and Box.
   * Transform a TraversableOnce[Box] into a Box[Iterable]
   * note: I don't how to say T<:TravesableOnce , T[Box[U]] => Box[T[U]]
   */
  def sequence[U](seq: Seq[Box[U]]): Box[Seq[U]] = seq.sequence

  /**
   * Iter on all elements of seq, applying f to each one of them.
   * If the result of f is Full, continue, else abort the processing,
   * returning the Empty or Failure.
   */
  def traverse[U, T](seq: Seq[U])(f: U => Box[T]): Box[Seq[T]] = seq.traverse(f)

  /**
   * A version of sequence that will try to reach the end and accumulate
   * results
   * In case of error, it provides a failure with all accumulated
   * other failure that leads to it
   */
  def bestEffort[U, T](seq: Seq[U])(f: U => Box[T]): Box[Seq[T]] = {
    val buf    = scala.collection.mutable.Buffer[T]()
    var errors = Option.empty[Failure]
    seq.foreach { u =>
      f(u) match {
        case e: EmptyBox =>
          val fail = e match {
            case failure: Failure => failure
            // these case should never happen, because Empty is verbotten, so took a
            // not to bad solution - u.toString can be very ugly, so limit size.
            case Empty => Failure(s"An error occured while processing: ${u.toString().take(20)}")
          }
          errors match {
            case None    => errors = Some(fail)
            case Some(f) => errors = Some(Failure(fail.msg, Empty, Full(f)))
          }
        case Full(x) => buf += x
      }
    }
    errors.getOrElse(Full(buf.toSeq))
  }

  /**
   * A version of sequence that also provide the last output as input
   * of the next processing (it's a foldleft)
   *
   * Exemple of use:
   * init value : a report
   * processors: a sequence of objects with an id, and a processReport method
   *
   * for {
   *   processingOk <- pipeline(processors, initialReport) { case(processor, report) =>
   *     processor(report) ?~! s"Error when processing report with processor '${processor.id}'"
   *   }
   * } yield { processingOk } //match on Failure / Full(report)
   */
  def pipeline[T, U](seq: Seq[T], init: U)(call: (T, U) => Box[U]): Box[U] = {
    seq.foldLeft(Full(init): Box[U]) { (currentValue, nextProcessor) =>
      currentValue match {
        case x: EmptyBox => return x // interrupt pipeline early
        case Full(value) => call(nextProcessor, value)
      }
    }
  }
}
