package me.syari.ss.discord.internal;

import com.neovisionaries.ws.client.WebSocketFactory;
import gnu.trove.impl.sync.TSynchronizedLongObjectMap;
import gnu.trove.map.TLongObjectMap;
import gnu.trove.map.hash.TLongObjectHashMap;
import me.syari.ss.discord.api.JDA;
import me.syari.ss.discord.api.event.MessageReceivedEvent;
import me.syari.ss.discord.api.exceptions.RateLimitedException;
import me.syari.ss.discord.api.requests.Request;
import me.syari.ss.discord.api.requests.Response;
import me.syari.ss.discord.api.utils.ChunkingFilter;
import me.syari.ss.discord.api.utils.SessionController;
import me.syari.ss.discord.api.utils.cache.CacheView;
import me.syari.ss.discord.api.utils.cache.SnowflakeCacheView;
import me.syari.ss.discord.api.utils.data.DataObject;
import me.syari.ss.discord.internal.entities.*;
import me.syari.ss.discord.internal.handle.EventCache;
import me.syari.ss.discord.internal.handle.GuildSetupController;
import me.syari.ss.discord.internal.requests.Requester;
import me.syari.ss.discord.internal.requests.RestAction;
import me.syari.ss.discord.internal.requests.Route;
import me.syari.ss.discord.internal.requests.WebSocketClient;
import me.syari.ss.discord.internal.utils.Checks;
import me.syari.ss.discord.internal.utils.JDALogger;
import me.syari.ss.discord.internal.utils.cache.SnowflakeCacheViewImpl;
import me.syari.ss.discord.internal.utils.config.MetaConfig;
import me.syari.ss.discord.internal.utils.config.SessionConfig;
import me.syari.ss.discord.internal.utils.config.ThreadingConfig;
import okhttp3.OkHttpClient;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.MDC;

import javax.security.auth.login.LoginException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;

public class JDAImpl implements JDA {
    public static final Logger LOG = JDALogger.getLog(JDA.class);

    protected final SnowflakeCacheViewImpl<User> userCache = new SnowflakeCacheViewImpl<>(User.class);
    protected final SnowflakeCacheViewImpl<Guild> guildCache = new SnowflakeCacheViewImpl<>(Guild.class);
    protected final SnowflakeCacheViewImpl<TextChannel> textChannelCache = new SnowflakeCacheViewImpl<>(TextChannel.class);

    protected final TLongObjectMap<User> fakeUsers = new TSynchronizedLongObjectMap<>(new TLongObjectHashMap<>(), new Object());

    protected final Thread shutdownHook;
    protected final EntityBuilder entityBuilder = new EntityBuilder(this);
    protected final EventCache eventCache;

    protected final GuildSetupController guildSetupController;

    protected final String token;
    protected final ThreadingConfig threadConfig;
    protected final SessionConfig sessionConfig;
    protected final MetaConfig metaConfig;
    private final Consumer<MessageReceivedEvent> messageReceivedEvent;

    protected WebSocketClient client;
    protected final Requester requester;
    protected Status status = Status.INITIALIZING;
    protected long responseTotal;
    protected String gatewayUrl;
    protected final ChunkingFilter chunkingFilter;

    public JDAImpl(@NotNull String token,
                   @NotNull SessionConfig sessionConfig,
                   @NotNull ThreadingConfig threadConfig,
                   @NotNull MetaConfig metaConfig,
                   @NotNull ChunkingFilter chunkingFilter,
                   @NotNull Consumer<MessageReceivedEvent> messageReceivedEvent) {
        this.token = "Bot " + token;
        this.threadConfig = threadConfig;
        this.sessionConfig = sessionConfig;
        this.metaConfig = metaConfig;
        this.chunkingFilter = chunkingFilter;
        this.messageReceivedEvent = messageReceivedEvent;
        this.shutdownHook = new Thread(this::shutdown, "JDA Shutdown Hook");
        this.requester = new Requester(this);
        this.requester.setRetryOnTimeout(true);
        this.guildSetupController = new GuildSetupController(this);
        this.eventCache = new EventCache();
    }

    public int getLargeThreshold() {
        return sessionConfig.getLargeThreshold();
    }

    public int getMaxBufferSize() {
        return metaConfig.getMaxBufferSize();
    }

    public boolean chunkGuild(long id) {
        try {
            return chunkingFilter.filter(id);
        } catch (Exception e) {
            LOG.error("Uncaught exception from chunking filter", e);
            return true;
        }
    }

    public SessionController getSessionController() {
        return sessionConfig.getSessionController();
    }

    public GuildSetupController getGuildSetupController() {
        return guildSetupController;
    }

    public void login() throws LoginException {
        threadConfig.init(() -> "JDA");
        requester.getRateLimiter().init();
        this.gatewayUrl = getGateway();
        Checks.notNull(this.gatewayUrl, "Gateway URL");

        setStatus(Status.LOGGING_IN);

        ConcurrentMap<String, String> contextMap = metaConfig.getMdcContextMap();
        Map<String, String> previousContext = MDC.getCopyOfContextMap();
        contextMap.forEach(MDC::put);
        verifyToken();
        LOG.info("Login Successful!");

        client = new WebSocketClient(this);
        if (previousContext != null)
            previousContext.forEach(MDC::put);

        if (shutdownHook != null)
            Runtime.getRuntime().addShutdownHook(shutdownHook);

    }

    public String getGateway() {
        return getSessionController().getGateway(this);
    }


    public ConcurrentMap<String, String> getContextMap() {
        return new ConcurrentHashMap<>(metaConfig.getMdcContextMap());
    }

    public void setContext() {
        metaConfig.getMdcContextMap().forEach(MDC::put);
    }

    public void setStatus(Status status) {
        synchronized (this.status) {
            this.status = status;
        }
    }

    public void verifyToken() throws LoginException {
        RestAction<DataObject> login = new RestAction<DataObject>(this, Route.Self.GET_SELF.compile()) {
            @Override
            public void handleResponse(@NotNull Response response, Request<DataObject> request) {
                if (response.isOk()) {
                    request.onSuccess(response.getObject());
                } else if (response.isRateLimit()) {
                    request.onFailure(new RateLimitedException(request.getRoute(), response.retryAfter));
                } else if (response.code == 401) {
                    request.onSuccess(null);
                } else {
                    request.onFailure(new LoginException("When verifying the authenticity of the provided token, Discord returned an unknown response:\n" + response.toString()));
                }
            }
        };

        DataObject userResponse = checkToken(login);
        if (userResponse != null) {
            return;
        }

        userResponse = checkToken(login);
        shutdownNow();

        if (userResponse == null) {
            throw new LoginException("The provided token is invalid!");
        }
    }

    private DataObject checkToken(RestAction<DataObject> login) throws LoginException {
        DataObject userResponse;
        try {
            userResponse = login.complete();
        } catch (RuntimeException e) {
            Throwable ex = e.getCause() instanceof ExecutionException ? e.getCause().getCause() : null;
            if (ex instanceof LoginException)
                throw new LoginException(ex.getMessage());
            else
                throw e;
        }
        return userResponse;
    }

    @NotNull
    public String getToken() {
        return token;
    }

    @NotNull
    public Status getStatus() {
        return status;
    }

    @Override
    public void awaitStatus(@NotNull Status status, @NotNull Status... failOn) throws InterruptedException {
        Checks.notNull(status, "Status");
        Checks.check(status.isInit(), "Cannot await the status %s as it is not part of the login cycle!", status);
        if (getStatus() == Status.CONNECTED)
            return;
        List<Status> failStatus = Arrays.asList(failOn);
        while (!getStatus().isInit() || getStatus().ordinal() < status.ordinal()) {
            if (getStatus() == Status.SHUTDOWN)
                throw new IllegalStateException("Was shutdown trying to await status");
            else if (failStatus.contains(getStatus()))
                return;
            Thread.sleep(50);
        }
    }

    @NotNull
    public ScheduledExecutorService getRateLimitPool() {
        return threadConfig.getRateLimitPool();
    }

    @NotNull
    public ScheduledExecutorService getGatewayPool() {
        return threadConfig.getGatewayPool();
    }

    @NotNull
    public ExecutorService getCallbackPool() {
        return threadConfig.getCallbackPool();
    }

    @NotNull
    public OkHttpClient getHttpClient() {
        return sessionConfig.getHttpClient();
    }

    @NotNull
    @Override
    public SnowflakeCacheView<Guild> getGuildCache() {
        return guildCache;
    }

    public boolean isUnavailable(long guildId) {
        return guildSetupController.isUnavailable(guildId);
    }

    @NotNull
    @Override
    public SnowflakeCacheView<Emote> getEmoteCache() {
        return CacheView.allSnowflakes(() -> guildCache.stream().map(Guild::getEmoteCache));
    }

    @NotNull
    @Override
    public SnowflakeCacheView<TextChannel> getTextChannelCache() {
        return textChannelCache;
    }


    @NotNull
    @Override
    public SnowflakeCacheView<User> getUserCache() {
        return userCache;
    }

    private synchronized void shutdownNow() {
        shutdown();
        threadConfig.shutdownNow();
    }

    @Override
    public synchronized void shutdown() {
        if (status == Status.SHUTDOWN || status == Status.SHUTTING_DOWN) {
            return;
        }

        setStatus(Status.SHUTTING_DOWN);

        WebSocketClient client = getClient();
        if (client != null) {
            client.shutdown();
        }

        shutdownInternals();
    }

    public void callMessageReceiveEvent(Message message) {
        messageReceivedEvent.accept(new MessageReceivedEvent(message));
    }

    public synchronized void shutdownInternals() {
        if (status == Status.SHUTDOWN) {
            return;
        }

        getRequester().shutdown();
        threadConfig.shutdown();

        if (shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (Exception ignored) {
            }
        }

        setStatus(Status.SHUTDOWN);
    }

    public long getResponseTotal() {
        return responseTotal;
    }

    public int getMaxReconnectDelay() {
        return sessionConfig.getMaxReconnectDelay();
    }

    public EntityBuilder getEntityBuilder() {
        return entityBuilder;
    }

    public Requester getRequester() {
        return requester;
    }

    public WebSocketFactory getWebSocketFactory() {
        return sessionConfig.getWebSocketFactory();
    }

    public WebSocketClient getClient() {
        return client;
    }

    public SnowflakeCacheViewImpl<User> getUsersView() {
        return userCache;
    }

    public SnowflakeCacheViewImpl<Guild> getGuildsView() {
        return guildCache;
    }

    public SnowflakeCacheViewImpl<TextChannel> getTextChannelsView() {
        return textChannelCache;
    }

    public TLongObjectMap<User> getFakeUserMap() {
        return fakeUsers;
    }

    public void setResponseTotal(int responseTotal) {
        this.responseTotal = responseTotal;
    }

    public EventCache getEventCache() {
        return eventCache;
    }

    public String getGatewayUrl() {
        return gatewayUrl;
    }

    public void resetGatewayUrl() {
        this.gatewayUrl = getGateway();
    }
}
