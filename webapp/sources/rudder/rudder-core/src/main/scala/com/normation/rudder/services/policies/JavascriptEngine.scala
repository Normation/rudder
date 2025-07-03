/*
 *************************************************************************************
 * Copyright 2016 Normation SAS
 *************************************************************************************
 *
 * This file is part of Rudder.
 *
 * Rudder is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * In accordance with the terms of section 7 (7. Additional Terms.) of
 * the GNU General Public License version 3, the copyright holders add
 * the following Additional permissions:
 * Notwithstanding to the terms of section 5 (5. Conveying Modified Source
 * Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU General
 * Public License version 3, when you create a Related Module, this
 * Related Module is not considered as a part of the work and may be
 * distributed under the license agreement of your choice.
 * A "Related Module" means a set of sources files including their
 * documentation that, without modification of the Source Code, enables
 * supplementary functions or services in addition to those offered by
 * the Software.
 *
 * Rudder is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Rudder.  If not, see <http://www.gnu.org/licenses/>.

 *
 *************************************************************************************
 */

package com.normation.rudder.services.policies

import com.normation.cfclerk.domain.AbstactPassword
import com.normation.cfclerk.domain.HashAlgoConstraint.*
import com.normation.cfclerk.domain.Variable
import com.normation.errors.*
import com.normation.rudder.domain.appconfig.FeatureSwitch
import com.normation.rudder.domain.logger.JsDirectiveParamLogger
import com.normation.rudder.domain.logger.JsDirectiveParamLoggerPure
import com.normation.rudder.services.policies.JsEngine.*
import enumeratum.*
import java.security.NoSuchAlgorithmException
import java.util.concurrent.*
import javax.script.Bindings
import javax.script.ScriptException
import org.apache.commons.codec.digest.Md5Crypt
import org.apache.commons.codec.digest.Sha2Crypt
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.io.IOAccess
import org.graalvm.polyglot.proxy.ProxyObject
import scala.annotation.nowarn
import scala.concurrent.duration.FiniteDuration
import zio.*
import zio.syntax.*

sealed trait HashOsType extends EnumEntry

object HashOsType extends Enum[HashOsType] {
  case object CryptHash extends HashOsType // linux, bsd,...

  val values: IndexedSeq[HashOsType] = findValues
}

/*
 * Define implicit bytes from string methods
 */
abstract class ImplicitGetBytes {
  implicit protected def getBytes(s: String): Array[Byte] = {
    s.getBytes("UTF-8")
  }
}

/*
 * we need to define each class of the lib as class, because
 * nashorn can't access static field.
 */
@HostAccess.Export
class JsLibHash() extends ImplicitGetBytes {
  @HostAccess.Export
  def md5(s:    String): String = MD5.hash(s)
  @HostAccess.Export
  def sha1(s:   String): String = SHA1.hash(s)
  @HostAccess.Export
  def sha256(s: String): String = SHA256.hash(s)
  @HostAccess.Export
  def sha512(s: String): String = SHA512.hash(s)
}

@HostAccess.Export
abstract class JsLibPassword extends ImplicitGetBytes {

  /// Standard Unix (crypt) specific

  @HostAccess.Export
  def cryptMd5(s:    String): String = Md5Crypt.md5Crypt(s)
  @HostAccess.Export
  def cryptSha256(s: String): String = Sha2Crypt.sha256Crypt(s)
  @HostAccess.Export
  def cryptSha512(s: String): String = Sha2Crypt.sha512Crypt(s)

  @HostAccess.Export
  def cryptMd5(s:    String, salt: String): String = Md5Crypt.md5Crypt(s, salt)
  @HostAccess.Export
  def cryptSha256(s: String, salt: String): String = Sha2Crypt.sha256Crypt(s, "$5$" + salt)
  @HostAccess.Export
  def cryptSha512(s: String, salt: String): String = Sha2Crypt.sha512Crypt(s, "$6$" + salt)

  /// one automatically choosing correct hash also based of the kind of HashOsType
  /// method accessible from JS

  @HostAccess.Export
  def md5(s:    String): String
  @HostAccess.Export
  def sha256(s: String): String
  @HostAccess.Export
  def sha512(s: String): String

  @HostAccess.Export
  def md5(s:    String, salt: String): String
  @HostAccess.Export
  def sha256(s: String, salt: String): String
  @HostAccess.Export
  def sha512(s: String, salt: String): String

  /// Advertised methods
  @HostAccess.Export
  def unix(algo: String, s: String): String = {
    algo.toLowerCase match {
      case "md5"                => cryptMd5(s)
      case "sha-256" | "sha256" => cryptSha256(s)
      case "sha-512" | "sha512" => cryptSha512(s)
      case _                    => // unknown value, fail
        throw new NoSuchAlgorithmException(
          s"Evaluating script 'unix(${algo}, ${s})' failed, as algorithm ${algo} is not recognized"
        )
    }
  }

  @HostAccess.Export
  def unix(algo: String, s: String, salt: String): String = {
    algo.toLowerCase match {
      case "md5"                => cryptMd5(s, salt)
      case "sha-256" | "sha256" => cryptSha256(s, salt)
      case "sha-512" | "sha512" => cryptSha512(s, salt)
      case _                    => // unknown value, fail
        throw new NoSuchAlgorithmException(
          s"Evaluating script 'unix(${algo}, ${s}, ${salt})' failed, as algorithm ${algo} is not recognized"
        )
    }
  }

  @HostAccess.Export
  def auto(algo: String, s: String): String = {
    algo.toLowerCase match {
      case "md5"                => md5(s)
      case "sha-256" | "sha256" => sha256(s)
      case "sha-512" | "sha512" => sha512(s)
      case _                    => // unknown value, fail
        throw new NoSuchAlgorithmException(
          s"Evaluating script 'auto(${algo}, ${s})' failed, as algorithm ${algo} is not recognized"
        )
    }
  }

  @HostAccess.Export
  def auto(algo: String, s: String, salt: String): String = {
    algo.toLowerCase match {
      case "md5"                => md5(s, salt)
      case "sha-256" | "sha256" => sha256(s, salt)
      case "sha-512" | "sha512" => sha512(s, salt)
      case _                    => // unknown value, fail
        throw new NoSuchAlgorithmException(
          s"Evaluating script 'auto(${algo}, ${s}, ${salt})' failed, as algorithm ${algo} is not recognized"
        )
    }
  }
}

/*
 * This class provides the Rudder JS lib.
 * All method signatures are available in JS.
 *
 * This lib is intended to be bound to the "rudder" namespace,
 * so that one can access:
 * ## Under the "rudder.hash" namespace, simple hashing methods:
 * - rudder.hash.md5(value)
 * - rudder.hash.sha256(value)
 * - rudder.hash.sha512(value)
 *
 * ## Under the "rudder.password" namespace, salted hashing functions
 *    compatible with Unix crypt (Linux, BSD...)
 * ### Automatically chosen based on the node type:
 * - rudder.password.md5(value [, salt])
 * - rudder.password.sha256(value [, salt])
 * - rudder.password.sha512(value [, salt])
 *
 * ### Generates Unix crypt password compatible hashes:
 * - rudder.password.cryptMd5(value [, salt])
 * - rudder.password.cryptSha256(value [, salt])
 * - rudder.password.cryptSha512(value [, salt])
 *
 * ### Public methods (advertised to the users, fallback to the previous methods)
 * - rudder.password.auto(algo, password [, salt])
 * - rudder.password.unix(algo, password [, salt])
 *
 *   where algo can be MD5, SHA-512, SHA-256 (case-insensitive, with or without -)
 *   * auto automatically choose the encryption based on the node type, currently only Unix
 *   * unix generated Unix crypt password compatible hashes (Linux, BSD, ...)
 */

import com.normation.rudder.services.policies.HashOsType.*
import org.graalvm.polyglot.*

final class JsRudderLibImpl(
    hashKind: HashOsType
) extends ProxyObject {

  ///// simple hash algorithms /////
  private val hash = new JsLibHash()

  ///// unix password hash /////
  private val password = hashKind match {
    case CryptHash =>
      new JsLibPassword() {
        /// method accessible from JS
        @HostAccess.Export
        def md5(s:    String): String = super.cryptMd5(s)
        @HostAccess.Export
        def sha256(s: String): String = super.cryptSha256(s)
        @HostAccess.Export
        def sha512(s: String): String = super.cryptSha512(s)

        @HostAccess.Export
        def md5(s:    String, salt: String): String = super.cryptMd5(s, salt)
        @HostAccess.Export
        def sha256(s: String, salt: String): String = super.cryptSha256(s, salt)
        @HostAccess.Export
        def sha512(s: String, salt: String): String = super.cryptSha512(s, salt)

      }
  }

  // with the Proxy interface, it will be accessed with rudder.password... or rudder.hash...
  val members: Map[String, ImplicitGetBytes] = Map(
    ("password", password),
    ("hash", hash)
  )

  override def getMember(key: String): AnyRef =
    members.get(key).getOrElse(s"Requested access to unknown member '${key}' in JS proxy object")
  override def getMemberKeys:          AnyRef = members.keys.toArray
  override def hasMember(key: String): Boolean = members.isDefinedAt(key)
  // do not allow modification of lib content at runtime
  override def putMember(key: String, value: Value): Unit = ()
}

sealed trait JsRudderLibBinding {
  def bindings:    Bindings
  def jsRudderLib: JsRudderLibImpl
}

object JsRudderLibBinding {

  import java.util.HashMap as JHMap
  import javax.script.SimpleBindings

  private def toBindings(k: String, v: JsRudderLibImpl): Bindings = {
    val m = new JHMap[String, Object]()
    m.put(k, v)
    new SimpleBindings(m)
  }

  /*
   * Be careful, as bindings are mutable, we can't have
   * a val for bindings, else the same context is shared...
   *
   * We have one for Crypt to specialize the
   * "auto" methods
   */
  object Crypt extends JsRudderLibBinding {
    val jsRudderLib = new JsRudderLibImpl(CryptHash)
    def bindings: Bindings = toBindings("rudder", jsRudderLib)
  }
}

/**
 * This class provides the graalvm (Java 12 & up) java script engine.
 *
 * It allows to eval parameters in directives which are starting
 * with $eval.
 *
 */
object JsEngineProvider {

  /**
   * Initialize a new JsEngine with the correct bindings.
   *
   * Not all Libs (bindings in JSR-223 name) can't be provided here,
   * because we want to make them specific to each eval
   * (i.e: different eval may have to different bindings).
   * So we just provide eval-indep lib here, but we don't
   * have any for now.
   *
   */
  def withNewEngine[T](feature: FeatureSwitch, maxThread: Int = 1, timeout: FiniteDuration)(
      script: JsEngine => IOResult[T]
  ): IOResult[T] = {
    feature match {
      case FeatureSwitch.Enabled  =>
        val res = SandboxedJsEngine.sandboxed(maxThread, timeout)(engine => script(engine))

        // we may want to debug hard to debug case here, especially when we had a stackoverflow below
        res.foldZIO(
          err =>
            (if (JsDirectiveParamLogger.isDebugEnabled) {
               import scala.util.Properties as P
               JsDirectiveParamLoggerPure.debug(
                 s"Error when trying to use the JS script engine in a directive. Java version: '${P.javaVersion}'; JVM info: '${P.javaVmInfo}'; name: '${P.javaVmName}'; version: : '${P.javaVmVersion}'; vendor: '${P.javaVmVendor}';"
               ) *>
               // in the case of an exception and debug enable, print stack
               JsDirectiveParamLoggerPure.debug(err.fullMsg)
             } else ZIO.unit) *> err.fail,
          res => res.succeed
        )
      case FeatureSwitch.Disabled =>
        script(DisabledEngine)
    }
  }

}

sealed trait JsEngine {

  /**
   *
   * Parse a value looking for EVAL keyword.
   * Return the correct NodeContextualizedValue type.
   *
   * Note that the eval result is ALWAY cast to
   * string. So computation, object, etc must be
   * correctly defined by the user to get and
   * interesting value.
   */
  def eval(variable: Variable, lib: JsRudderLibBinding): IOResult[Variable]
}

/*
 * Our JsEngine.
 */
object JsEngine {
  // Several evals: one default and one JS (in the future, we may have several language)
  final val DEFAULT_EVAL = "eval:"
  final val EVALJS       = "evaljs:"

  final val PASSWORD_PREFIX       = "plain:"
  final val DEFAULT_EVAL_PASSWORD = PASSWORD_PREFIX + DEFAULT_EVAL
  final val EVALJS_PASSWORD       = PASSWORD_PREFIX + EVALJS

  // From a variable, returns the two string we should check at the beginning of the variable value
  // For a password, check if it's a plain text or not, otherwise check simply the eval keywords
  def getEvaluatorTuple(variable: Variable): (String, String, Boolean) = {
    variable.spec.constraint.typeName match {
      case t: AbstactPassword => (DEFAULT_EVAL_PASSWORD, EVALJS_PASSWORD, true)
      case _ => (DEFAULT_EVAL, EVALJS, false)
    }
  }

  object DisabledEngine extends JsEngine {
    /*
     * Eval does nothing on variable without the EVAL keyword, and
     * fails on variable with the keyword.
     */
    def eval(variable: Variable, lib: JsRudderLibBinding): IOResult[Variable] = {
      val (default, js, _) = getEvaluatorTuple(variable)
      variable.values.find(x => x.startsWith(default)) match {
        /*
         * Here, we need to chose between:
         * - fail when the feature is disabled, but the string starts with $eval,
         *   meaning that maybe the user wanted to use it anyway.
         *   But that means that we are changing GENERATION behavior on existing prod,
         *   for a feature the user don't know anything.
         * - don't fail, because perhaps the user had that in its parameter. But in
         *   that case, it will fail when the feature is enabled by default.
         *   And we risk to let the user spread sensitive information into nodes
         *   (because they thought it will be hashed, but in fact no).
         *
         * For now, failing because it seems to be the safe bet.
         */
        case None    =>
          variable.values.find(x => x.startsWith(js)) match {
            case None    =>
              variable.succeed
            case Some(v) =>
              Unexpected(
                s"Value '${v}' starts with the ${js} keyword, but the 'parameter evaluation feature' "
                + "is disabled. Please, either don't use the keyword or enable the feature"
              ).fail
          }
        case Some(v) =>
          Unexpected(
            s"Value '${v}' starts with the ${default} keyword, but the 'parameter evaluation feature' "
            + "is disabled. Please, either don't use the keyword or enable the feature"
          ).fail
      }
    }
  }

  final case class GraalEngine(engine: Engine) {
    def buildContext: ZIO[Any with Any with Scope, SystemError, Context] = ZIO.acquireRelease(
      IOResult.attempt(
        Context
          .newBuilder("js")
          .engine(engine)
          .allowHostAccess(HostAccess.EXPLICIT)
          .allowIO(IOAccess.NONE)
          .allowCreateProcess(false)
          .allowCreateThread(false)
          .allowNativeAccess(false)
          .build()
      )
    )(x => effectUioUnit(x.close(true)))
  }
  object SandboxedJsEngine                     {
    // we need to set the warning for interpreted mode to off, because, yeah for now, we are doing that only
    java.lang.System.setProperty("polyglot.engine.WarnInterpreterOnly", "false")

    /*
     * The value is purely arbitrary. We expects that a normal use case ends in tens of ms.
     * But we don't want the user to have a whole generation fails because it scripts took 2 seconds
     * for reasons. As it is something rather exceptional, and which will ends the
     * Policy Generation, the value can be rather high.
     *
     * Note: maybe make that a parameter so that we can put an even higher value here,
     * but only put 1s in tests so that they end quickly
     */
    val DEFAULT_MAX_EVAL_DURATION: FiniteDuration = FiniteDuration(5, TimeUnit.SECONDS)

    /**
     * Get a new JS Engine.
     * This is expensive, several seconds on a 8-core i7 @ 3.5Ghz.
     * So you should minimize the number of time it is done.
     */
    def sandboxed[T](maxThread: Int = 1, timeout: FiniteDuration = DEFAULT_MAX_EVAL_DURATION)(
        script: SandboxedJsEngine => IOResult[T]
    ): IOResult[T] = {
      final case class ManagedJsEnv(pool: ExecutorService, engine: SandboxedJsEngine)

      val managedJsEngine = ZIO.acquireRelease(
        getJsEngine(maxThread).flatMap(jsEngine => {
          IOResult.attempt {
            val pool = Executors.newFixedThreadPool(maxThread)
            ManagedJsEnv(pool, new SandboxedJsEngine(jsEngine, pool, timeout))
          }
        })
      ) { managedJsEnv =>
        IOResult.attempt {
          // clear everything
          managedJsEnv.pool.shutdownNow()
          // check & clear interruption of the calling thread
          Thread.currentThread().isInterrupted()
          // restore the "none" security manager
        }.foldZIO(
          err => JsDirectiveParamLoggerPure.error(err.fullMsg),
          ok => ok.succeed
        )
      }

      ZIO.scoped(managedJsEngine.flatMap(managedJsEnv => script(managedJsEnv.engine)))
    }

    /*
     * Context are monothreaded, so we need one for each thread.
     * But actually, we can create one Engine and reuse it for each context, benefiting from
     * it's warmup for all context.
     * Return a Queue filled with the number of conte
     */
    protected[policies] def getJsEngine(number: Int): IOResult[GraalEngine] = {
      val message =
        s"Error when trying to get the java script engine. Check with your system administrator that you JVM support JSR-223 with javascript"
      IOResult.attempt(message)(GraalEngine(Engine.create()))
    }
  }

  /*
   * The whole idea is to give a throwable Sandox class whose only
   * goal is to run a thread in a contained environment.
   * Notice that using the sandox HAS a global effect, because it
   * changes the security manager.
   * So generally, you want to manage that in a contained scope.
   */

  final class SandboxedJsEngine private (jsEngine: GraalEngine, pool: ExecutorService, maxTime: FiniteDuration) extends JsEngine {

    private trait KillingThread {

      /**
       * Force stop the thread, throws a the ThreadDeath error.
       *
       * As explained in Thread#stop(), that method has consequences and
       * can cause random error in all the object interracting with it
       * (because monitor released without any protection).
       * So caller of that method should ensure to 1/ contains
       * the thread to abort and 2/ clean as best as possible object
       * which interrected with it.
       *
       */
      @nowarn("msg=deprecated") def abortWithConsequences(): Unit = {
        Thread.currentThread().stop()
      }
    }

    // If it's a password, we need to reconstruct the correct password structure
    def reconstructPassword(value: String, isPassword: Boolean): String = {
      if (isPassword) {
        (PASSWORD_PREFIX + value)
      } else {
        value
      }
    }

    /**
     * This is the user-accessible eval.
     * It is expected to throws, and should always be used in
     * a SandboxedJsEngine.sandboxed wrapping call.
     *
     * Nothing fancy here.
     */
    def eval(variable: Variable, lib: JsRudderLibBinding): IOResult[Variable] = {

      val (default, js, isPassword) = getEvaluatorTuple(variable)

      (for {
        values <- ZIO.foreach(variable.values) { value =>
                    (if (value.startsWith(default)) {
                       val script = value.substring(default.length())
                       // do something with script
                       singleEval(script, lib.jsRudderLib).map(reconstructPassword(_, isPassword))
                     } else if (value.startsWith(js)) {
                       val script = value.substring(js.length())
                       // do something with script
                       singleEval(script, lib.jsRudderLib).map(reconstructPassword(_, isPassword))
                     } else {
                       value.succeed
                     }).chainError(
                      s"Invalid script '${value}' for Variable ${variable.spec.name} - please check method call and/or syntax"
                    )
                  }
        copied <- variable.copyWithSavedValues(values).toIO
      } yield {
        copied
      })
    }

    /**
     * The single eval method encapsulate the evaluation in a dedicated thread.
     * We check that the evaluation doesn't take to much time, and if so, we
     * kill the thread (gently, and then with a force stop).
     * The thread is also restricted, and can't do dangerous things (access
     * to FS, Network, System (jvm), class loader, etc).
     */
    def singleEval(value: String, jsRudderLib: JsRudderLibImpl): IOResult[String] = {
      ZIO.scoped(
        jsEngine.buildContext.flatMap(context => {
          (safeExec(value) {
            try {
              context.getBindings("js").putMember("rudder", jsRudderLib)
              val res = context.eval("js", value) match { // returned Value object is never null, it has a method to check that!
                case x if (x.isNull) => Unexpected(s"The script '${value}' was evaluated to disallowed value 'null'").fail
                case x               => x.toString().succeed
              }
              res
            } catch {
              case ex: ScriptException => SystemError("Error with script evaluation", ex).fail
            }
          })
        })
      )
    }

    /**
     * The safe eval method encapsulate the evaluation of a block of code
     * in a dedicated thread.
     * We check that the evaluation doesn't take to much time, and if so, we
     * kill the thread (gently, and then with a force stop).
     * The thread is also restricted, and can't do dangerous things (access
     * to FS, Network, System (jvm), class loader, etc).
     */
    def safeExec[T](name: String)(block: => IOResult[T]): IOResult[T] = {
      // create the callable object
      val scriptCallable = new Callable[IOResult[T]] with KillingThread() {
        def call() = {
          try {
            block
          } catch {
            case ex: Exception => SystemError(s"Error when evaluating value '${name}': ${ex.getMessage}", ex).fail
          }
        }
      }

      (try {
        val task = pool.submit(scriptCallable)
        try {
          // submit to the pool and synchroniously retrieve the value with a timeout
          task.get(maxTime.toMillis, TimeUnit.MILLISECONDS)
        } catch {
          case ex: ExecutionException => // this can happen when jsengine get security exception... Yeah...
            SystemError(s"Evaluating script '${name}' was forced interrupted due to ${ex.getMessage}, aborting.", ex).fail

          case ex: TimeoutException =>
            // try to interrupt the thread
            try {
              // try to gently terminate the task
              if (!task.isDone) {
                task.cancel(true)
              }
              if (task.isCancelled || task.isDone) {
                Unexpected(s"Evaluating script '${name}' took more than ${maxTime.toString()}, aborting").fail
              } else {
                // not interrupted - force kill
                scriptCallable.abortWithConsequences() // that throws TreadDead, the following is never reached
                Unexpected(
                  s"Evaluating script '${name}' took more than ${maxTime.toString()}, and " +
                  "we were force to kill the thread. Check for infinite loop or uninterruptible system calls"
                ).fail
              }
            } catch {
              case ex: ThreadDeath =>
                SystemError(
                  s"Evaluating script '${name}' took more than ${maxTime.toString()}, and " +
                  "we were force to kill the thread. Check for infinite loop or uninterruptible system calls",
                  ex
                ).fail

              case ex: InterruptedException =>
                SystemError(s"Evaluating script '${name}' was forced interrupted, aborting.", ex).fail
            }
        }
      } catch {
        case ex: RejectedExecutionException =>
          SystemError(
            s"Evaluating script '${name}' lead to a '${ex.getClass.getName}'. Perhaps the thread pool was stopped?",
            ex
          ).fail
      }).uninterruptible
    }
  }
}
