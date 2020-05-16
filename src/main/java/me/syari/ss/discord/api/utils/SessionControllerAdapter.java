package me.syari.ss.discord.api.utils;

import com.neovisionaries.ws.client.OpeningHandshakeException;
import me.syari.ss.discord.api.JDA;
import me.syari.ss.discord.internal.requests.RestActionImpl;
import me.syari.ss.discord.internal.requests.Route;
import me.syari.ss.discord.internal.utils.JDALogger;
import org.slf4j.Logger;

import javax.annotation.Nonnull;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class SessionControllerAdapter implements SessionController {
    protected static final Logger log = JDALogger.getLog(SessionControllerAdapter.class);
    protected final Object lock = new Object();
    protected final Queue<SessionConnectNode> connectQueue;
    protected final AtomicLong globalRatelimit;
    protected Thread workerHandle;
    protected long lastConnect = 0;

    public SessionControllerAdapter() {
        connectQueue = new ConcurrentLinkedQueue<>();
        globalRatelimit = new AtomicLong(Long.MIN_VALUE);
    }

    @Override
    public void appendSession(@Nonnull SessionConnectNode node) {
        removeSession(node);
        connectQueue.add(node);
        runWorker();
    }

    @Override
    public void removeSession(@Nonnull SessionConnectNode node) {
        connectQueue.remove(node);
    }

    @Override
    public long getGlobalRatelimit() {
        return globalRatelimit.get();
    }

    @Override
    public void setGlobalRatelimit(long ratelimit) {
        globalRatelimit.set(ratelimit);
    }

    @Nonnull
    @Override
    public String getGateway(@Nonnull JDA api) {
        Route.CompiledRoute route = Route.Misc.GATEWAY.compile();
        return new RestActionImpl<String>(api, route,
                (response, request) -> response.getObject().getString("url")).complete();
    }

    protected void runWorker() {
        synchronized (lock) {
            if (workerHandle == null) {
                workerHandle = new QueueWorker();
                workerHandle.start();
            }
        }
    }

    protected class QueueWorker extends Thread {

        protected final long delay;

        public QueueWorker() {
            this(IDENTIFY_DELAY);
        }


        public QueueWorker(int delay) {
            this(TimeUnit.SECONDS.toMillis(delay));
        }


        public QueueWorker(long delay) {
            super("SessionControllerAdapter-Worker");
            this.delay = delay;
            super.setUncaughtExceptionHandler(this::handleFailure);
        }

        protected void handleFailure(Thread thread, Throwable exception) {
            log.error("Worker has failed with throwable!", exception);
        }

        @Override
        public void run() {
            try {
                if (this.delay > 0) {
                    final long interval = System.currentTimeMillis() - lastConnect;
                    if (interval < this.delay)
                        Thread.sleep(this.delay - interval);
                }
            } catch (InterruptedException ex) {
                log.error("Unable to backoff", ex);
            }
            processQueue();
            synchronized (lock) {
                workerHandle = null;
                if (!connectQueue.isEmpty())
                    runWorker();
            }
        }

        protected void processQueue() {
            boolean isMultiple = connectQueue.size() > 1;
            while (!connectQueue.isEmpty()) {
                SessionConnectNode node = connectQueue.poll();
                try {
                    node.run(isMultiple && connectQueue.isEmpty());
                    isMultiple = true;
                    lastConnect = System.currentTimeMillis();
                    if (connectQueue.isEmpty())
                        break;
                    if (this.delay > 0)
                        Thread.sleep(this.delay);
                } catch (IllegalStateException e) {
                    Throwable t = e.getCause();
                    if (t instanceof OpeningHandshakeException)
                        log.error("Failed opening handshake, appending to queue. Message: {}", e.getMessage());
                    else if (!JDA.Status.RECONNECT_QUEUED.name().equals(t.getMessage()))
                        log.error("Failed to establish connection for a node, appending to queue", e);
                    appendSession(node);
                } catch (InterruptedException e) {
                    log.error("Failed to run node", e);
                    appendSession(node);
                    return; // caller should start a new thread
                }
            }
        }
    }
}
