package me.syari.ss.discord.requests

import java.util.concurrent.ExecutorService
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal object ThreadConfig {
    lateinit var rateLimitPool: ScheduledExecutorService
        private set
    lateinit var gatewayPool: ScheduledExecutorService
        private set
    val callbackPool: ExecutorService = ForkJoinPool.commonPool()

    fun init() {
        rateLimitPool = newScheduler(
            5, "RateLimit"
        )
        gatewayPool = newScheduler(
            1, "Gateway"
        )
    }

    fun shutdown() {
        callbackPool.shutdown()
        gatewayPool.shutdown()
        if (rateLimitPool is ScheduledThreadPoolExecutor) {
            val executor = rateLimitPool as ScheduledThreadPoolExecutor
            executor.setKeepAliveTime(5L, TimeUnit.SECONDS)
            executor.allowCoreThreadTimeOut(true)
        } else {
            rateLimitPool.shutdown()
        }
    }

    private fun newScheduler(
        coreSize: Int, baseName: String
    ) = ScheduledThreadPoolExecutor(coreSize,
        CountingThreadFactory(baseName)
    )
}