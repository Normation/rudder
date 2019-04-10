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

import java.security.Permission
import java.util.concurrent._
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

import scala.language.implicitConversions
import com.normation.cfclerk.domain.HashAlgoConstraint._
import com.normation.cfclerk.domain.Variable
import com.normation.rudder.domain.appconfig.FeatureSwitch
import com.normation.rudder.services.policies.HashOsType._
import com.normation.rudder.services.policies.JsEngine._
import com.normation.utils.Control._
import ca.mrvisser.sealerate
import javax.script.Bindings
import javax.script.ScriptEngine
import javax.script.ScriptEngineManager
import javax.script.ScriptException
import net.liftweb.common.Box
import net.liftweb.common.Empty
import net.liftweb.common.Failure
import net.liftweb.common.Full
import java.io.FilePermission

import org.apache.commons.codec.digest.Md5Crypt
import org.apache.commons.codec.digest.Sha2Crypt
import com.normation.cfclerk.domain.AixPasswordHashAlgo
import com.normation.cfclerk.domain.AbstactPassword
import java.security.NoSuchAlgorithmException
import java.net.URL
import java.net.URLPermission
import java.nio.file.Paths
import java.security.CodeSource
import java.security.ProtectionDomain
import java.util.logging.LoggingPermission
import java.security.cert.Certificate

import sun.security.provider.PolicyFile
import com.github.ghik.silencer.silent
import com.normation.rudder.domain.logger.JsDirectiveParamLogger

import com.normation.zio._

sealed trait HashOsType

final object HashOsType {
  final case object AixHash   extends HashOsType
  final case object CryptHash extends HashOsType //linux, bsd,...

  def all = sealerate.values[HashOsType]
}

/*
 * Define implicit bytes from string methods
 */
abstract class ImplicitGetBytes {
  protected implicit def getBytes(s: String): Array[Byte] = {
    s.getBytes("UTF-8")
  }
}

/*
 * we need to define each class of the lib as class, because
 * nashorn can't access static field.
 */
class JsLibHash() extends ImplicitGetBytes {
  def md5    (s: String): String = MD5.hash(s)
  def sha1   (s: String): String = SHA1.hash(s)
  def sha256 (s: String): String = SHA256.hash(s)
  def sha512 (s: String): String = SHA512.hash(s)
}

trait JsLibPassword extends ImplicitGetBytes {

  /// Standard Unix (crypt) specific

  def cryptMd5    (s: String): String = Md5Crypt.md5Crypt(s)
  def cryptSha256 (s: String): String = Sha2Crypt.sha256Crypt(s)
  def cryptSha512 (s: String): String = Sha2Crypt.sha512Crypt(s)

  def cryptMd5    (s: String, salt: String): String = Md5Crypt.md5Crypt(s, salt)
  def cryptSha256 (s: String, salt: String): String = Sha2Crypt.sha256Crypt(s, "$5$" + salt)
  def cryptSha512 (s: String, salt: String): String = Sha2Crypt.sha512Crypt(s, "$6$" + salt)

  /// Aix specific

  def aixMd5    (s: String): String = AixPasswordHashAlgo.smd5(s)
  def aixSha256 (s: String): String = AixPasswordHashAlgo.ssha256(s)
  def aixSha512 (s: String): String = AixPasswordHashAlgo.ssha512(s)

  def aixMd5    (s: String, salt: String): String = AixPasswordHashAlgo.smd5(s, Some(salt))
  def aixSha256 (s: String, salt: String): String = AixPasswordHashAlgo.ssha256(s, Some(salt))
  def aixSha512 (s: String, salt: String): String = AixPasswordHashAlgo.ssha512(s, Some(salt))

  /// one automatically choosing correct hash also based of the kind of HashOsType
  /// method accessible from JS

  def md5    (s: String): String
  def sha256 (s: String): String
  def sha512 (s: String): String

  def md5    (s: String, salt: String): String
  def sha256 (s: String, salt: String): String
  def sha512 (s: String, salt: String): String

  /// Advertised methods
  def unix(algo: String, s: String): String = {
    algo.toLowerCase match {
      case "md5" => cryptMd5(s)
      case "sha-256" | "sha256" => cryptSha256(s)
      case "sha-512" | "sha512" => cryptSha512(s)
      case _ => // unknown value, fail
        throw new NoSuchAlgorithmException(s"Evaluating script 'unix(${algo}, ${s})' failed, as algorithm ${algo} is not recognized")
    }
  }

  def unix(algo: String, s: String, salt: String): String = {
    algo.toLowerCase match {
      case "md5" => cryptMd5(s, salt)
      case "sha-256" | "sha256" => cryptSha256(s, salt)
      case "sha-512" | "sha512" => cryptSha512(s, salt)
      case _ => // unknown value, fail
        throw new NoSuchAlgorithmException(s"Evaluating script 'unix(${algo}, ${s}, ${salt})' failed, as algorithm ${algo} is not recognized")
    }
  }

  def aix(algo: String, s: String): String = {
    algo.toLowerCase match {
      case "md5" => aixMd5(s)
      case "sha-256" | "sha256" => aixSha256(s)
      case "sha-512" | "sha512" => aixSha512(s)
      case _ => // unknown value, fail
        throw new NoSuchAlgorithmException(s"Evaluating script 'aix(${algo}, ${s})' failed, as algorithm ${algo} is not recognized")
    }
  }

  def aix(algo: String, s: String, salt: String): String = {
    algo.toLowerCase match {
      case "md5" => aixMd5(s, salt)
      case "sha-256" | "sha256" => aixSha256(s, salt)
      case "sha-512" | "sha512" => aixSha512(s, salt)
      case _ => // unknown value, fail
        throw new NoSuchAlgorithmException(s"Evaluating script 'unix(${algo}, ${s}, ${salt})' failed, as algorithm ${algo} is not recognized")
    }
  }

  def auto(algo: String, s: String): String = {
    algo.toLowerCase match {
      case "md5" => md5(s)
      case "sha-256" | "sha256" => sha256(s)
      case "sha-512" | "sha512" => sha512(s)
      case _ => // unknown value, fail
        throw new NoSuchAlgorithmException(s"Evaluating script 'auto(${algo}, ${s})' failed, as algorithm ${algo} is not recognized")
    }
  }

  def auto(algo: String, s: String, salt: String): String = {
    algo.toLowerCase match {
      case "md5" => md5(s, salt)
      case "sha-256" | "sha256" => sha256(s, salt)
      case "sha-512" | "sha512" => sha512(s, salt)
      case _ => // unknown value, fail
        throw new NoSuchAlgorithmException(s"Evaluating script 'auto(${algo}, ${s}, ${salt})' failed, as algorithm ${algo} is not recognized")
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
 *    compatible with Unix crypt (Linux, BSD...) or AIX
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
 * ### Generates AIX password compatible hashes:
 * - rudder.password.aixMd5(value [, salt])
 * - rudder.password.aixSha256(value [, salt])
 * - rudder.password.aixSha512(value [, salt])
 *
 * ### Public methods (advertised to the users, fallback to the previous methods)
 * - rudder.password.auto(algo, password [, salt])
 * - rudder.password.unix(algo, password [, salt])
 * - rudder.password.aix(algo, password [, salt])
 *
 *   where algo can be MD5, SHA-512, SHA-256 (case insensitive, with or without -)
 *   * auto automatically choose the encryption based on the node type
 *   * unix generated Unix crypt password compatible hashes (Linux, BSD, ...)
 *   * aix generates AIX password compatible hashes
 */
final class JsRudderLibImpl(
  hashKind: HashOsType
) {

  ///// simple hash algorithms /////
  private val hash = new JsLibHash()
  // with the getter, it will be accessed with rudder.hash...
  def getHash() = hash

  ///// unix password hash /////
  private val password = hashKind match {
    case CryptHash =>
      new JsLibPassword() {
        /// method accessible from JS
        def md5    (s: String): String = super.cryptMd5(s)
        def sha256 (s: String): String = super.cryptSha256(s)
        def sha512 (s: String): String = super.cryptSha512(s)

        def md5    (s: String, salt: String): String = super.cryptMd5(s, salt)
        def sha256 (s: String, salt: String): String = super.cryptSha256(s, salt)
        def sha512 (s: String, salt: String): String = super.cryptSha512(s, salt)

      }
    case AixHash =>
      new JsLibPassword() {
        /// method accessible from JS
        def md5    (s: String): String = super.aixMd5(s)
        def sha256 (s: String): String = super.aixSha256(s)
        def sha512 (s: String): String = super.aixSha512(s)

        def md5    (s: String, salt: String): String = super.aixMd5(s, salt)
        def sha256 (s: String, salt: String): String = super.aixSha256(s, salt)
        def sha512 (s: String, salt: String): String = super.aixSha512(s, salt)

      }
  }

  // with the getter, it will be accessed with rudder.password...
  def getPassword = password
}

sealed trait JsRudderLibBinding { def bindings: Bindings }

object JsRudderLibBinding {

  import java.util.{ HashMap => JHMap }
  import javax.script.SimpleBindings

  private[this] def toBindings(k: String, v: JsRudderLibImpl): Bindings = {
    val m = new JHMap[String, Object]()
    m.put(k, v)
    new SimpleBindings(m)
  }

  /*
   * Be carefull, as bindings are mutable, we can't have
   * a val for bindings, else the same context is shared...
   */
  final object Aix extends JsRudderLibBinding {
    def bindings = toBindings("rudder", new JsRudderLibImpl(AixHash))
  }

  final object Crypt extends JsRudderLibBinding {
    def bindings = toBindings("rudder", new JsRudderLibImpl(CryptHash))
  }
}

/**
 * This class provides the Rhino (java 7) or Longhorn (Java 8 & up)
 * java script engine.
 *
 * It allows to eval parameters in directives which are starting
 * with $eval.
 *
 */
final object JsEngineProvider {

  /**
   * Initialize a new JsEngine with the correct bindings.
   *
   * Not all Libs (bindings in JSR-223 name) can't be provided here,
   * because we want to make them specific to each eval
   * (i.e: different eval may have to different bindings).
   * So we just provide eval-indep lib here, but we don't
   * have any for now.
   *
   * Security policy are taken from `rudder-js.policy` file in the classpath
   * unlessthe file `/opt/rudder/etc/rudder-js.policy` is defined.
   */
  def withNewEngine[T](feature: FeatureSwitch)(script: JsEngine => Box[T]): Box[T] = {
    feature match {
      case FeatureSwitch.Enabled  =>
        val defaultPath = this.getClass.getClassLoader.getResource("rudder-js.policy")
        val userPolicies = Paths.get("/opt/rudder/etc/rudder-js.policy")
        val url = if(userPolicies.toFile.canRead) {
          userPolicies.toUri.toURL
        } else {
          defaultPath
        }

        val res = SandboxedJsEngine.sandboxed(url) { engine => script(engine) }

        //we may want to debug hard to debug case here, especially when we had a stackoverflow below
        (JsDirectiveParamLogger.isDebugEnabled, res) match {
          case (true, Failure(msg, Full(ex), x)) =>
            import scala.util.{Properties => P}
            JsDirectiveParamLogger.debug(s"Error when trying to use the JS script engine in a directive. Java version: '${P.javaVersion}'; JVM info: '${P.javaVmInfo}'; name: '${P.javaVmName}'; version: : '${P.javaVmVersion}'; vendor: '${P.javaVmVendor}';")

            // in the case of an exception and debug enable, print stack
            ex .printStackTrace()

          case _ => // nothing more
        }

        res
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
  def eval(variable: Variable, lib: JsRudderLibBinding): Box[Variable]
}

/*
 * Our JsEngine.
 */
final object JsEngine {
  // Several evals: one default and one JS (in the future, we may have several language)
  final val DEFAULT_EVAL = "eval:"
  final val EVALJS       = "evaljs:"

  final val PASSWORD_PREFIX       = "plain:"
  final val DEFAULT_EVAL_PASSWORD = PASSWORD_PREFIX+DEFAULT_EVAL
  final val EVALJS_PASSWORD       = PASSWORD_PREFIX+EVALJS

  // From a variable, returns the two string we should check at the beginning of the variable value
  // For a password, check if it's a plain text or not, otherwise check simply the eval keywords
  def getEvaluatorTuple(variable: Variable) : (String, String, Boolean) = {
    variable.spec.constraint.typeName match {
        case t: AbstactPassword => (DEFAULT_EVAL_PASSWORD, EVALJS_PASSWORD, true)
        case _                  => (DEFAULT_EVAL, EVALJS, false)
    }
  }

  final object DisabledEngine extends JsEngine {
    /*
     * Eval does nothing on variable without the EVAL keyword, and
     * fails on variable with the keyword.
     */
    def eval(variable: Variable, lib: JsRudderLibBinding): Box[Variable] = {
      val (default, js, _) = getEvaluatorTuple(variable)
      variable.values.find( x => x.startsWith(default)) match {
        /*
         * Here, we need to chose between:
         * - fails when the feature is disabled, but the string starts with $eval,
         *   meaning that maybe the user wanted to use it anyway.
         *   But that means that we are changing GENERATION behavior on existing prod,
         *   for a feature the user don't know anything.
         * - not fails, because perhaps the user had that in its parameter. But in
         *   that case, it will fails when feature is enabled by default.
         *   And we risk to let the user spread sensitive information  into nodes
         *   (because he thought the will be hashed, but in fact no).
         *
         * For now, failing because it seems to be the safe bet.
         */
        case None =>
          variable.values.find( x => x.startsWith(js)) match {
            case None =>
              Full(variable)
            case Some(v) =>
              Failure(s"Value '${v}' starts with the ${js} keyword, but the 'parameter evaluation feature' "
                  +"is disabled. Please, either don't use the keyword or enable the feature")
           }
        case Some(v) =>
           Failure(s"Value '${v}' starts with the ${default} keyword, but the 'parameter evaluation feature' "
                  +"is disabled. Please, either don't use the keyword or enable the feature")
      }
    }
  }

  final object SandboxedJsEngine {
    /*
     * The value is successlly arbitrary. We expects that a normal use case ends in tens of ms.
     * But we don't want the user to have a whole generation fails because it scripts took 2 seconds
     * for reasons. As it is something rather exceptionnal, and which will ends the
     * Policy Generation, the value can be rather hight.
     *
     * Note: maybe make that a parameter so that we can put an even higher value here,
     * but only put 1s in tests so that they end quickly
     */
    val MAX_EVAL_DURATION = (5, TimeUnit.SECONDS)

    /**
     * Get a new JS Engine.
     * This is expensive, several seconds on a 8-core i7 @ 3.5Ghz.
     * So you should minimize the number of time it is done.
     */
    def sandboxed[T](policyFileUrl: URL)(script: SandboxedJsEngine => Box[T]): Box[T] = {
      var sandbox = new SandboxSecurityManager(policyFileUrl)
      var threadFactory = new RudderJsEngineThreadFactory(sandbox)
      var pool = Executors.newSingleThreadExecutor(threadFactory)
      System.setSecurityManager(sandbox)

      getJsEngine().flatMap { jsEngine =>
        val engine = new SandboxedJsEngine(jsEngine, sandbox, pool, MAX_EVAL_DURATION)

        try {
          script(engine)
        } catch {
          case RudderFatalScriptException(message, cause) =>
            Failure(message, Full(cause), Empty)
        } finally {
          //clear everything
          pool = null
          threadFactory = null
          sandbox = null
          //check & clear interruption of the calling thread
          Thread.currentThread().isInterrupted()
          //restore the "none" security manager
          System.setSecurityManager(null)
        }
      }
    }

    protected[policies] def getJsEngine(): Box[ScriptEngine] = {
      val message = s"Error when trying to get the java script engine. Check with your system administrator that you JVM support JSR-223 with javascript"
      try {
        // create a script engine manager
        val factory = new ScriptEngineManager()
        // create a JavaScript engine
        factory.getEngineByName("JavaScript") match {
          case null   => Failure(message)
          case engine => Full(engine)
        }
      } catch {
        case ex: Exception =>
         Failure(s"${message}. Exception message was: ${ex.getMessage}")
      }
    }
  }

  /*
   * The whole idea is to give a throwable Sandox class whose only
   * goal is to run a thread in a contained environment.
   * Notice that using the sandox HAS a global effect, because it
   * changes the security manager.
   * So generally, you want to manage that in a contained scope.
   */

  final class SandboxedJsEngine private (jsEngine: ScriptEngine, sm: SecurityManager, pool: ExecutorService, maxTime: (Int, TimeUnit)) extends JsEngine {

    // we need to preload Permission to avoid loops - see #13014
    val preload1 = new FilePermission("not a real path", "read")
    val preload2 = new URLPermission("file://not/a/real/url", "read")
    val preload3 = new RuntimePermission("not a real action", "read")
    val preload4 = new LoggingPermission("control", null)

    private[this] trait KillingThread {
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
      @silent def abortWithConsequences(): Unit = {
        Thread.currentThread().stop()
      }
    }

    // If it's a password, we need to reconstruct the correct password structure
    def reconstructPassword(value: String, isPassword: Boolean) : String = {
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
    def eval(variable: Variable, lib: JsRudderLibBinding): Box[Variable] = {

      val (default, js, isPassword) = getEvaluatorTuple(variable)

      for {
        values <- sequence(variable.values) { value =>
          (if (value.startsWith(default)) {
            val script = value.substring(default.length())
            //do something with script
            singleEval(script, lib.bindings).map(reconstructPassword(_, isPassword))
          } else {
            if (value.startsWith(js)) {
              val script = value.substring(js.length())
              //do something with script
              singleEval(script, lib.bindings).map(reconstructPassword(_, isPassword))
            } else {
              Full(value)
            }
          }) ?~! s"Invalid script '${value}' for Variable ${variable.spec.name} - please check method call and/or syntax"
        }
        copied <- variable.copyWithSavedValues(values).toBox
      } yield {
        copied
      }
    }

    /**
     * The single eval method encapsulate the evaluation in a dedicated thread.
     * We check that the evaluation doesn't take to much time, and if so, we
     * kill the thread (gently, and then with a force stop).
     * The thread is also restricted, and can't do dangerous things (access
     * to FS, Network, System (jvm), class loader, etc).
     */
    def singleEval(value: String, bindings: Bindings): Box[String] = {
      val res = safeExec(value) {
        try {
          jsEngine.eval(value, bindings) match {
            case null => Failure(s"The script '${value}' was evaluated to disallowed value 'null'")
            case x    => Full(x.toString)
          }
        } catch {
          case ex: ScriptException => Failure(ex.getMessage)
        }
      }
      res
    }

    /**
     * The safe eval method encapsulate the evaluation of a block of code
     * in a dedicated thread.
     * We check that the evaluation doesn't take to much time, and if so, we
     * kill the thread (gently, and then with a force stop).
     * The thread is also restricted, and can't do dangerous things (access
     * to FS, Network, System (jvm), class loader, etc).
     */
    def safeExec[T](name: String)(block: => Box[T]): Box[T] = {
      //create the callable object
      val scriptCallable = new Callable[Box[T]] with KillingThread() {
        def call() = {
          try {
            block
          } catch {
            case ex: Exception => Failure(s"Error when evaluating value '${name}': ${ex.getMessage}", Full(ex), Empty)
          }
        }
      }

      try {
        // submit to the pool and synchroniously retrieve the value with a timeout
        pool.submit(scriptCallable).get(maxTime._1.toLong, maxTime._2)
      } catch {
        case ex: ExecutionException => //this can happen when rhino get security exception... Yeah...
          throw RudderFatalScriptException(s"Evaluating script '${name}' was forced interrupted due to ${ex.getMessage}, aborting.", ex)

        case ex: TimeoutException =>
          //try to interrupt the thread
          try {
            // try to gently terminate the thread
            pool.shutdownNow()
            Thread.sleep(200)
            if(pool.isTerminated()) {
              Failure(s"Evaluating script '${name}' took more than ${maxTime._1}s, aborting")
            } else {
              //not interrupted - force kill
              scriptCallable.abortWithConsequences() //that throws TreadDead, the following is never reached
              Failure(s"Evaluating script '${name}' took more than ${maxTime._1}s, and " +
                    "we were force to kill the thread. Check for infinite loop or uninterruptible system calls")
            }
          } catch {
            case ex: ThreadDeath =>
              throw RudderFatalScriptException(s"Evaluating script '${name}' took more than ${maxTime._1}s, and " +
                    "we were force to kill the thread. Check for infinite loop or uninterruptible system calls", ex)

            case ex: InterruptedException =>
              throw RudderFatalScriptException(s"Evaluating script '${name}' was forced interrupted, aborting.", ex)
          }
      }
    }
  }
  /*
   * An exception marker class to handle thread related error cases
   */
  protected[policies] case class RudderFatalScriptException(message: String, cause: Throwable) extends Exception(message, cause)

  /*
   * A sandboxed security manager, allowing only restricted
   * set of operation for restricted thread.
   * The RudderJsEngineThreadFactory take care of correctly initializing
   * threads.
   *
   * BE CAREFUL - this sandbox won't prevent an attacker to get/do things.
   * It's goal is to prevent a user to do obviously bad things on the servers,
   * but more by mistake (because he didn't understood when the script is evalued,
   * for example).
   *
   * The security rules are taken from a rudder-js.policy file.
   *
   */
  protected[policies] class SandboxSecurityManager(policyFileUrl: URL) extends SecurityManager {
    //by default, we don't sand box. Thread will do it in their factory
    val isSandboxed = {
      val itl = new InheritableThreadLocal[Boolean]()
      itl.set(false)
      itl
    }

    val protectionDomain = new ProtectionDomain(new CodeSource(null, Array[Certificate]()), null)
    // policy file must be instanciate here else it need to follows its own rules...
    val policyFile = new PolicyFile(policyFileUrl)

    override def checkPermission(permission: Permission): Unit = {

      /*
       * TIP for the future myself: when bug happens here, don't try to put a println here,
       * it will bring desolation in your debug session.
       * You can try to put one in new catch all test after policyFile (as showned).
       *
       * There should be a way to tell "do not check privilege for the current method,
       * else obviously it will loop", but I don't know how.
       */


      if(isSandboxed.get) {
        // there some perms which are hard to specify in that file
        permission match {
          // this seems to be now mandatory to avoid loops, see #13014
          case x: FilePermission if(x.getActions == "read" && (x.getName.startsWith("/opt/rudder/share/webapps")|| x.getName.startsWith("/opt/rudder/etc/rudder-jetty-base/"))) => // ok
          // jar and classes
          case x: FilePermission if( x.getActions == "read" && (x.getName.contains(".class") || x.getName.endsWith(".jar")  || x.getName.endsWith(".so") ) ) => // ok
          // configuration files
          case x: FilePermission if( x.getActions == "read" && (x.getName.endsWith(".cfg") || x.getName.endsWith(".properties") ) )  => // ok
          // cert files and config
          case x: FilePermission if( x.getActions == "read" && (x.getName.endsWith(".certs") || x.getName.endsWith("jre/lib/security/cacerts") || x.getName.contains("/security/policy/unlimited")) )  => // ok
          case x: LoggingPermission  => // ok
          // else look in the dynamic rules from rudder-js.policy files
          case x if(policyFile.implies(protectionDomain, permission)) => // ok
//          kept for a future myself
//          case x: URLPermission => // ok
//            throw new Throwable("url perm: " + permission)

          case x =>
            //JsDirectiveParamLogger.warn(s"Script tries to access protected resources: ${permission.toString}")
            throw new SecurityException("access denied to: " + permission)    // error
        }
      } else {
        // don't check anything - it's the nearest to having a
        // null SecurityManager, what is the default in Rudder
      }
    }

    override def checkPermission(permission: Permission , context: Any): Unit = {
      if(isSandboxed.get) this.checkPermission(permission)
      else super.checkPermission(permission, context)
    }
  }

  /**
   * A thread factory that works with a SandboxSecurityManager and correctly
   * set the sandboxed value of the thread.
   */
  protected[policies] class RudderJsEngineThreadFactory(sm: SandboxSecurityManager) extends ThreadFactory {
    val RUDDER_JSENGINE_THREAD = "rudder-jsengine"
    class SandboxedThread(group: ThreadGroup, target: Runnable, name: String, stackSize: Long) extends Thread(group, target, name, stackSize) {
      override def run(): Unit = {
        sm.isSandboxed.set(true)
        super.run()
      }
    }

    val threadNumber = new AtomicInteger(1)
    val group = sm.getThreadGroup()

    override def newThread(r: Runnable): Thread = {
      val t = new SandboxedThread(group, r, RUDDER_JSENGINE_THREAD + "-" + threadNumber.getAndIncrement(), 0)

      if(t.isDaemon) {
        t.setDaemon(false)
      }
      if(t.getPriority != Thread.NORM_PRIORITY) {
        t.setPriority(Thread.NORM_PRIORITY)
      }

      t
    }

  }

}
