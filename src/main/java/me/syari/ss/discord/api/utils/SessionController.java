package me.syari.ss.discord.api.utils;

import me.syari.ss.discord.internal.JDA;
import me.syari.ss.discord.internal.requests.RestAction;
import me.syari.ss.discord.internal.requests.Route;
import org.jetbrains.annotations.NotNull;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class SessionController {
    public final static int IDENTIFY_DELAY = 5;
    protected final Object lock = new Object();
    protected final Queue<SessionConnectNode> connectQueue = new ConcurrentLinkedQueue<>();
    protected final AtomicLong globalRatelimit = new AtomicLong(Long.MIN_VALUE);
    protected Thread workerHandle;
    protected long lastConnect = 0;

    public void appendSession(@NotNull SessionConnectNode node) {
        removeSession(node);
        connectQueue.add(node);
        runWorker();
    }

    public void removeSession(@NotNull SessionConnectNode node) {
        connectQueue.remove(node);
    }

    public long getGlobalRatelimit() {
        return globalRatelimit.get();
    }

    public void setGlobalRatelimit(long ratelimit) {
        globalRatelimit.set(ratelimit);
    }

    @NotNull
    public String getGateway(@NotNull JDA api) {
        Route route = Route.getGatewayRoute();
        return new RestAction<String>(api, route, (response, request) -> response.getDataObject().getString("url")).complete();
    }

    protected void runWorker() {
        synchronized (lock) {
            if (workerHandle == null) {
                workerHandle = new QueueWorker();
                workerHandle.start();
            }
        }
    }

    private class QueueWorker extends Thread {
        private final long delay = TimeUnit.SECONDS.toMillis(IDENTIFY_DELAY);

        public QueueWorker() {
            super("SessionControllerAdapter-Worker");
        }

        @Override
        public void run() {
            try {
                if (0 < this.delay) {
                    final long interval = System.currentTimeMillis() - lastConnect;
                    if (interval < this.delay) {
                        Thread.sleep(this.delay - interval);
                    }
                }
            } catch (InterruptedException ex) {
                ex.printStackTrace();
            }
            processQueue();
            synchronized (lock) {
                workerHandle = null;
                if (!connectQueue.isEmpty()) {
                    runWorker();
                }
            }
        }

        private void processQueue() {
            boolean isMultiple = connectQueue.size() > 1;
            while (!connectQueue.isEmpty()) {
                SessionConnectNode node = connectQueue.poll();
                try {
                    node.run(isMultiple && connectQueue.isEmpty());
                    isMultiple = true;
                    lastConnect = System.currentTimeMillis();
                    if (connectQueue.isEmpty())
                        break;
                    if (this.delay > 0) {
                        Thread.sleep(this.delay);
                    }
                } catch (IllegalStateException | InterruptedException e) {
                    appendSession(node);
                }
            }
        }
    }

    public interface SessionConnectNode {
        void run(boolean isLast) throws InterruptedException;
    }
}
