package zio.internal

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

import zio.blocking.Blocking

/*
 * Code copied from zio.blocking.internal
 *
 * On writting test, just the two modif below earns 50% of thread churn (thread kill before being reused)
 * and 10% performance (likely because creating a thread is costly).
 * It may not transpose to real use case gain.
 * Interestingly, using scala work stealing thread pool doesn't yield anything (but can lead to deadlock,
 * so we should not used it).
 */
object RudderBlockingService {
  trait Service extends Blocking.Service {
    def blockingExecutor: Executor
  }

  object RudderBlocking extends RudderBlockingService.Service {

    val zio = Executor.fromThreadPoolExecutor(_ => Int.MaxValue) {
      val corePoolSize  = Math.max(1, Runtime.getRuntime.availableProcessors()/2) // default is 0
      val maxPoolSize   = Int.MaxValue
      val keepAliveTime = 5*1000L // default is 1000
      val timeUnit      = TimeUnit.MILLISECONDS
      val workQueue     = new SynchronousQueue[Runnable]()
      val threadFactory = new NamedThreadFactory("zio-default-blocking", true)

      val threadPool = new ThreadPoolExecutor(
        corePoolSize,
        maxPoolSize,
        keepAliveTime,
        timeUnit,
        workQueue,
        threadFactory
      )

      threadPool
    }

    // this doesn't yield better results
    // val scalaGobal: UIO[Executor] = ZIO.succeed(Executor.fromExecutionContext(Int.MaxValue)(scala.concurrent.ExecutionContext.global))

    override val blockingExecutor: Executor = zio
  }
}


object RudderAsyncExecutor {

final def makeDefault(yieldOpCount: Int): Executor =
    Executor.fromThreadPoolExecutor(_ => yieldOpCount) {
      val corePoolSize  = Runtime.getRuntime.availableProcessors() * 2
      val maxPoolSize   = corePoolSize
      val keepAliveTime = 30*1000L
      val timeUnit      = TimeUnit.MILLISECONDS
      val workQueue     = new LinkedBlockingQueue[Runnable]()
      val threadFactory = new NamedThreadFactory("zio-default-async", true)

      val threadPool = new ThreadPoolExecutor(
        corePoolSize,
        maxPoolSize,
        keepAliveTime,
        timeUnit,
        workQueue,
        threadFactory
      )
      threadPool.allowCoreThreadTimeOut(true)

      threadPool
    }
}
