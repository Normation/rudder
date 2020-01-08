package zio.internal

import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

import zio.UIO
import zio.ZIO
import zio.blocking.Blocking
import zio.blocking.Blocking.Service

/*
 * Code copied from zio.blocking.internal
 *
 * On writting test, just the two modif below earns 50% of thread churn (thread kill before being reused)
 * and 10% performance (likely because creating a thread is costly).
 * It may not transpose to real use case gain.
 * Interestingly, using scala work stealing thread pool doesn't yield anything (but can lead to deadlock,
 * so we should not used it).
 */
trait RudderBlockingService extends Blocking {
  val blocking: Service[Any] = new Service[Any] {

    val zio = ZIO.succeed(Executor.fromThreadPoolExecutor(_ => Int.MaxValue) {
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
    })

    // this doesn't yield better results
    // val scalaGobal: UIO[Executor] = ZIO.succeed(Executor.fromExecutionContext(Int.MaxValue)(scala.concurrent.ExecutionContext.global))

    val blockingExecutor: UIO[Executor] = zio
  }
}
