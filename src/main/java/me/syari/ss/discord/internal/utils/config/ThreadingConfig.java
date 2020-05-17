package me.syari.ss.discord.internal.utils.config;

import me.syari.ss.discord.internal.utils.concurrent.CountingThreadFactory;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.util.concurrent.*;
import java.util.function.Supplier;

public class ThreadingConfig {
    private ScheduledExecutorService rateLimitPool;
    private ScheduledExecutorService gatewayPool;
    private final ExecutorService callbackPool;

    public ThreadingConfig() {
        this.callbackPool = ForkJoinPool.commonPool();
        this.rateLimitPool = null;
        this.gatewayPool = null;
    }

    public void init(@NotNull Supplier<String> identifier) {
        if (this.rateLimitPool == null)
            this.rateLimitPool = newScheduler(5, identifier, "RateLimit");
        if (this.gatewayPool == null)
            this.gatewayPool = newScheduler(1, identifier, "Gateway");
    }

    public void shutdown() {
        callbackPool.shutdown();
        gatewayPool.shutdown();
        if (rateLimitPool instanceof ScheduledThreadPoolExecutor) {
            ScheduledThreadPoolExecutor executor = (ScheduledThreadPoolExecutor) rateLimitPool;
            executor.setKeepAliveTime(5L, TimeUnit.SECONDS);
            executor.allowCoreThreadTimeOut(true);
        } else {
            rateLimitPool.shutdown();
        }
    }

    public void shutdownNow() {
        callbackPool.shutdownNow();
        gatewayPool.shutdownNow();
        rateLimitPool.shutdownNow();
    }

    @NotNull
    public ScheduledExecutorService getRateLimitPool() {
        return rateLimitPool;
    }

    @NotNull
    public ScheduledExecutorService getGatewayPool() {
        return gatewayPool;
    }

    @NotNull
    public ExecutorService getCallbackPool() {
        return callbackPool;
    }

    @NotNull
    public static ScheduledThreadPoolExecutor newScheduler(int coreSize, Supplier<String> identifier, String baseName) {
        return new ScheduledThreadPoolExecutor(coreSize, new CountingThreadFactory(identifier, baseName));
    }

}
