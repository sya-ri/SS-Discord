

package me.syari.ss.discord.internal;

import com.neovisionaries.ws.client.WebSocketFactory;
import gnu.trove.map.TLongObjectMap;
import me.syari.ss.discord.api.AccountType;
import me.syari.ss.discord.api.JDA;
import me.syari.ss.discord.api.entities.*;
import me.syari.ss.discord.api.events.GatewayPingEvent;
import me.syari.ss.discord.api.events.GenericEvent;
import me.syari.ss.discord.api.events.StatusChangeEvent;
import me.syari.ss.discord.api.exceptions.AccountTypeException;
import me.syari.ss.discord.api.exceptions.RateLimitedException;
import me.syari.ss.discord.api.hooks.IEventManager;
import me.syari.ss.discord.api.hooks.InterfacedEventManager;
import me.syari.ss.discord.api.managers.Presence;
import me.syari.ss.discord.api.requests.Request;
import me.syari.ss.discord.api.requests.Response;
import me.syari.ss.discord.api.requests.RestAction;
import me.syari.ss.discord.api.utils.ChunkingFilter;
import me.syari.ss.discord.api.utils.Compression;
import me.syari.ss.discord.api.utils.MiscUtil;
import me.syari.ss.discord.api.utils.SessionController;
import me.syari.ss.discord.api.utils.cache.CacheFlag;
import me.syari.ss.discord.api.utils.cache.CacheView;
import me.syari.ss.discord.api.utils.cache.SnowflakeCacheView;
import me.syari.ss.discord.api.utils.data.DataObject;
import me.syari.ss.discord.internal.entities.EntityBuilder;
import me.syari.ss.discord.internal.handle.EventCache;
import me.syari.ss.discord.internal.handle.GuildSetupController;
import me.syari.ss.discord.internal.hooks.EventManagerProxy;
import me.syari.ss.discord.internal.managers.PresenceImpl;
import me.syari.ss.discord.internal.requests.Requester;
import me.syari.ss.discord.internal.requests.RestActionImpl;
import me.syari.ss.discord.internal.requests.Route;
import me.syari.ss.discord.internal.requests.WebSocketClient;
import me.syari.ss.discord.internal.utils.Checks;
import me.syari.ss.discord.internal.utils.JDALogger;
import me.syari.ss.discord.internal.utils.cache.SnowflakeCacheViewImpl;
import me.syari.ss.discord.internal.utils.config.AuthorizationConfig;
import me.syari.ss.discord.internal.utils.config.MetaConfig;
import me.syari.ss.discord.internal.utils.config.SessionConfig;
import me.syari.ss.discord.internal.utils.config.ThreadingConfig;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.MDC;

import javax.annotation.Nonnull;
import javax.security.auth.login.LoginException;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class JDAImpl implements JDA
{
    public static final Logger LOG = JDALogger.getLog(JDA.class);

    protected ScheduledThreadPoolExecutor audioLifeCyclePool;

    protected final SnowflakeCacheViewImpl<User> userCache = new SnowflakeCacheViewImpl<>(User.class, User::getName);
    protected final SnowflakeCacheViewImpl<Guild> guildCache = new SnowflakeCacheViewImpl<>(Guild.class, Guild::getName);
    protected final SnowflakeCacheViewImpl<Category> categories = new SnowflakeCacheViewImpl<>(Category.class, GuildChannel::getName);
    protected final SnowflakeCacheViewImpl<StoreChannel> storeChannelCache = new SnowflakeCacheViewImpl<>(StoreChannel.class, GuildChannel::getName);
    protected final SnowflakeCacheViewImpl<TextChannel> textChannelCache = new SnowflakeCacheViewImpl<>(TextChannel.class, GuildChannel::getName);
    protected final SnowflakeCacheViewImpl<VoiceChannel> voiceChannelCache = new SnowflakeCacheViewImpl<>(VoiceChannel.class, GuildChannel::getName);
    protected final SnowflakeCacheViewImpl<PrivateChannel> privateChannelCache = new SnowflakeCacheViewImpl<>(PrivateChannel.class, MessageChannel::getName);

    protected final TLongObjectMap<User> fakeUsers = MiscUtil.newLongMap();
    protected final TLongObjectMap<PrivateChannel> fakePrivateChannels = MiscUtil.newLongMap();

    protected final PresenceImpl presence;
    protected final Thread shutdownHook;
    protected final EntityBuilder entityBuilder = new EntityBuilder(this);
    protected final EventCache eventCache;
    protected final EventManagerProxy eventManager = new EventManagerProxy(new InterfacedEventManager());

    protected final GuildSetupController guildSetupController;

    protected final AuthorizationConfig authConfig;
    protected final ThreadingConfig threadConfig;
    protected final SessionConfig sessionConfig;
    protected final MetaConfig metaConfig;

    protected WebSocketClient client;
    protected Requester requester;
    protected Status status = Status.INITIALIZING;
    protected SelfUser selfUser;
    protected ShardInfo shardInfo;
    protected long responseTotal;
    protected long gatewayPing = -1;
    protected String gatewayUrl;
    protected ChunkingFilter chunkingFilter;

    protected String clientId = null;

    public JDAImpl(AuthorizationConfig authConfig)
    {
        this(authConfig, null, null, null);
    }

    public JDAImpl(
            AuthorizationConfig authConfig, SessionConfig sessionConfig,
            ThreadingConfig threadConfig, MetaConfig metaConfig)
    {
        this.authConfig = authConfig;
        this.threadConfig = threadConfig == null ? ThreadingConfig.getDefault() : threadConfig;
        this.sessionConfig = sessionConfig == null ? SessionConfig.getDefault() : sessionConfig;
        this.metaConfig = metaConfig == null ? MetaConfig.getDefault() : metaConfig;
        this.shutdownHook = this.metaConfig.isUseShutdownHook() ? new Thread(this::shutdown, "JDA Shutdown Hook") : null;
        this.presence = new PresenceImpl(this);
        this.requester = new Requester(this);
        this.requester.setRetryOnTimeout(this.sessionConfig.isRetryOnTimeout());
        this.guildSetupController = new GuildSetupController(this);
        this.eventCache = new EventCache(isGuildSubscriptions());
    }

    public void handleEvent(@Nonnull GenericEvent event)
    {
        eventManager.handle(event);
    }

    public boolean isRawEvents()
    {
        return sessionConfig.isRawEvents();
    }

    public boolean isRelativeRateLimit()
    {
        return sessionConfig.isRelativeRateLimit();
    }

    public boolean isCacheFlagSet(CacheFlag flag)
    {
        return metaConfig.getCacheFlags().contains(flag);
    }

    public boolean isGuildSubscriptions()
    {
        return metaConfig.isGuildSubscriptions();
    }

    public int getLargeThreshold()
    {
        return sessionConfig.getLargeThreshold();
    }

    public int getMaxBufferSize()
    {
        return metaConfig.getMaxBufferSize();
    }

    public boolean chunkGuild(long id)
    {
        try
        {
            return isGuildSubscriptions() && chunkingFilter.filter(id);
        }
        catch (Exception e)
        {
            LOG.error("Uncaught exception from chunking filter", e);
            return true;
        }
    }

    public void setChunkingFilter(ChunkingFilter filter)
    {
        this.chunkingFilter = filter;
    }

    public SessionController getSessionController()
    {
        return sessionConfig.getSessionController();
    }

    public GuildSetupController getGuildSetupController()
    {
        return guildSetupController;
    }

    public int login(ShardInfo shardInfo, Compression compression, boolean validateToken) throws LoginException
    {
        return login(null, shardInfo, compression, validateToken);
    }

    public int login(String gatewayUrl, ShardInfo shardInfo, Compression compression, boolean validateToken) throws LoginException
    {
        this.shardInfo = shardInfo;
        threadConfig.init(this::getIdentifierString);
        requester.getRateLimiter().init();
        this.gatewayUrl = gatewayUrl == null ? getGateway() : gatewayUrl;
        Checks.notNull(this.gatewayUrl, "Gateway URL");

        String token = authConfig.getToken();
        setStatus(Status.LOGGING_IN);
        if (token.isEmpty())
            throw new LoginException("Provided token was null or empty!");

        Map<String, String> previousContext = null;
        ConcurrentMap<String, String> contextMap = metaConfig.getMdcContextMap();
        if (contextMap != null)
        {
            if (shardInfo != null)
            {
                contextMap.put("jda.shard", shardInfo.getShardString());
                contextMap.put("jda.shard.id", String.valueOf(shardInfo.getShardId()));
                contextMap.put("jda.shard.total", String.valueOf(shardInfo.getShardTotal()));
            }
            // set MDC metadata for build thread
            previousContext = MDC.getCopyOfContextMap();
            contextMap.forEach(MDC::put);
            requester.setContextReady(true);
        }
        if (validateToken)
        {
            verifyToken();
            LOG.info("Login Successful!");
        }

        client = new WebSocketClient(this, compression);
        // remove our MDC metadata when we exit our code
        if (previousContext != null)
            previousContext.forEach(MDC::put);

        if (shutdownHook != null)
            Runtime.getRuntime().addShutdownHook(shutdownHook);

        return shardInfo == null ? -1 : shardInfo.getShardTotal();
    }

    public String getGateway()
    {
        return getSessionController().getGateway(this);
    }


    // This method also checks for a valid bot token as it is required to get the recommended shard count.
    public SessionController.ShardedGateway getShardedGateway()
    {
        return getSessionController().getShardedGateway(this);
    }

    public ConcurrentMap<String, String> getContextMap()
    {
        return metaConfig.getMdcContextMap() == null ? null : new ConcurrentHashMap<>(metaConfig.getMdcContextMap());
    }

    public void setContext()
    {
        if (metaConfig.getMdcContextMap() != null)
            metaConfig.getMdcContextMap().forEach(MDC::put);
    }

    public void setToken(String token)
    {
        this.authConfig.setToken(token);
    }

    public void setStatus(Status status)
    {
        //noinspection SynchronizeOnNonFinalField
        synchronized (this.status)
        {
            Status oldStatus = this.status;
            this.status = status;

            handleEvent(new StatusChangeEvent(this, status, oldStatus));
        }
    }

    public void verifyToken() throws LoginException
    {
        this.verifyToken(false);
    }

    // @param alreadyFailed If has already been a failed attempt with the current configuration
    public void verifyToken(boolean alreadyFailed) throws LoginException
    {

        RestActionImpl<DataObject> login = new RestActionImpl<DataObject>(this, Route.Self.GET_SELF.compile())
        {
            @Override
            public void handleResponse(Response response, Request<DataObject> request)
            {
                if (response.isOk())
                    request.onSuccess(response.getObject());
                else if (response.isRateLimit())
                    request.onFailure(new RateLimitedException(request.getRoute(), response.retryAfter));
                else if (response.code == 401)
                    request.onSuccess(null);
                else
                    request.onFailure(new LoginException("When verifying the authenticity of the provided token, Discord returned an unknown response:\n" +
                        response.toString()));
            }
        };

        DataObject userResponse;

        if (!alreadyFailed)
        {
            userResponse = checkToken(login);
            if (userResponse != null)
            {
                verifyAccountType(userResponse);
                getEntityBuilder().createSelfUser(userResponse);
                return;
            }
        }

        //If we received a null return for userResponse, then that means we hit a 401.
        // 401 occurs when we attempt to access the users/@me endpoint with the wrong token prefix.
        // e.g: If we use a Client token and prefix it with "Bot ", or use a bot token and don't prefix it.
        // It also occurs when we attempt to access the endpoint with an invalid token.
        //The code below already knows that something is wrong with the token. We want to determine if it is invalid
        // or if the developer attempted to login with a token using the wrong AccountType.

        //If we attempted to login as a Bot, remove the "Bot " prefix and set the Requester to be a client.
        String token;
        if (getAccountType() == AccountType.BOT)
        {
            token = getToken().substring("Bot ".length());
            requester = new Requester(this, new AuthorizationConfig(AccountType.CLIENT, token));
        }
        else    //If we attempted to login as a Client, prepend the "Bot " prefix and set the Requester to be a Bot
        {
            requester = new Requester(this, new AuthorizationConfig(AccountType.BOT, getToken()));
        }

        userResponse = checkToken(login);
        shutdownNow();

        //If the response isn't null (thus it didn't 401) send it to the secondary verify method to determine
        // which account type the developer wrongly attempted to login as
        if (userResponse != null)
            verifyAccountType(userResponse);
        else    //We 401'd again. This is an invalid token
            throw new LoginException("The provided token is invalid!");
    }

    private void verifyAccountType(DataObject userResponse)
    {
        if (getAccountType() == AccountType.BOT)
        {
            if (!userResponse.hasKey("bot") || !userResponse.getBoolean("bot"))
                throw new AccountTypeException(AccountType.BOT, "Attempted to login as a BOT with a CLIENT token!");
        }
        else
        {
            if (userResponse.hasKey("bot") && userResponse.getBoolean("bot"))
                throw new AccountTypeException(AccountType.CLIENT, "Attempted to login as a CLIENT with a BOT token!");
        }
    }

    private DataObject checkToken(RestActionImpl<DataObject> login) throws LoginException
    {
        DataObject userResponse;
        try
        {
            userResponse = login.complete();
        }
        catch (RuntimeException e)
        {
            //We check if the LoginException is masked inside of a ExecutionException which is masked inside of the RuntimeException
            Throwable ex = e.getCause() instanceof ExecutionException ? e.getCause().getCause() : null;
            if (ex instanceof LoginException)
                throw new LoginException(ex.getMessage());
            else
                throw e;
        }
        return userResponse;
    }

    public AuthorizationConfig getAuthorizationConfig()
    {
        return authConfig;
    }

    @Nonnull
    @Override
    public String getToken()
    {
        return authConfig.getToken();
    }


    @Override
    public boolean isBulkDeleteSplittingEnabled()
    {
        return sessionConfig.isBulkDeleteSplittingEnabled();
    }

    @Override
    public boolean isAutoReconnect()
    {
        return sessionConfig.isAutoReconnect();
    }

    @Nonnull
    @Override
    public Status getStatus()
    {
        return status;
    }

    @Override
    public long getGatewayPing()
    {
        return gatewayPing;
    }

    @Nonnull
    @Override
    public JDA awaitStatus(@Nonnull Status status, @Nonnull Status... failOn) throws InterruptedException
    {
        Checks.notNull(status, "Status");
        Checks.check(status.isInit(), "Cannot await the status %s as it is not part of the login cycle!", status);
        if (getStatus() == Status.CONNECTED)
            return this;
        List<Status> failStatus = Arrays.asList(failOn);
        while (!getStatus().isInit()                         // JDA might disconnect while starting
                || getStatus().ordinal() < status.ordinal()) // Wait until status is bypassed
        {
            if (getStatus() == Status.SHUTDOWN)
                throw new IllegalStateException("Was shutdown trying to await status");
            else if (failStatus.contains(getStatus()))
                return this;
            Thread.sleep(50);
        }
        return this;
    }

    @Nonnull
    @Override
    public ScheduledExecutorService getRateLimitPool()
    {
        return threadConfig.getRateLimitPool();
    }

    @Nonnull
    @Override
    public ScheduledExecutorService getGatewayPool()
    {
        return threadConfig.getGatewayPool();
    }

    @Nonnull
    @Override
    public ExecutorService getCallbackPool()
    {
        return threadConfig.getCallbackPool();
    }

    @Nonnull
    @Override
    @SuppressWarnings("ConstantConditions") // this can't really happen unless you pass bad configs
    public OkHttpClient getHttpClient()
    {
        return sessionConfig.getHttpClient();
    }

    @Nonnull
    @Override
    public List<Guild> getMutualGuilds(@Nonnull User... users)
    {
        Checks.notNull(users, "users");
        return getMutualGuilds(Arrays.asList(users));
    }

    @Nonnull
    @Override
    public List<Guild> getMutualGuilds(@Nonnull Collection<User> users)
    {
        Checks.notNull(users, "users");
        for(User u : users)
            Checks.notNull(u, "All users");
        return Collections.unmodifiableList(getGuilds().stream()
                .filter(guild -> users.stream().allMatch(guild::isMember))
                .collect(Collectors.toList()));
    }

    @Nonnull
    @Override
    public SnowflakeCacheView<Guild> getGuildCache()
    {
        return guildCache;
    }

    @Override
    public boolean isUnavailable(long guildId)
    {
        return guildSetupController.isUnavailable(guildId);
    }

    @Nonnull
    @Override
    public SnowflakeCacheView<Role> getRoleCache()
    {
        return CacheView.allSnowflakes(() -> guildCache.stream().map(Guild::getRoleCache));
    }

    @Nonnull
    @Override
    public SnowflakeCacheView<Emote> getEmoteCache()
    {
        return CacheView.allSnowflakes(() -> guildCache.stream().map(Guild::getEmoteCache));
    }

    @Nonnull
    @Override
    public SnowflakeCacheView<Category> getCategoryCache()
    {
        return categories;
    }

    @Nonnull
    @Override
    public SnowflakeCacheView<StoreChannel> getStoreChannelCache()
    {
        return storeChannelCache;
    }

    @Nonnull
    @Override
    public SnowflakeCacheView<TextChannel> getTextChannelCache()
    {
        return textChannelCache;
    }

    @Nonnull
    @Override
    public SnowflakeCacheView<VoiceChannel> getVoiceChannelCache()
    {
        return voiceChannelCache;
    }

    @Nonnull
    @Override
    public SnowflakeCacheView<PrivateChannel> getPrivateChannelCache()
    {
        return privateChannelCache;
    }

    @Nonnull
    @Override
    public SnowflakeCacheView<User> getUserCache()
    {
        return userCache;
    }

    public boolean hasSelfUser()
    {
        return selfUser != null;
    }

    @Nonnull
    @Override
    public SelfUser getSelfUser()
    {
        if (selfUser == null)
            throw new IllegalStateException("Session is not yet ready!");
        return selfUser;
    }

    @Override
    public synchronized void shutdownNow()
    {
        shutdown();
        threadConfig.shutdownNow();
    }

    @Override
    public synchronized void shutdown()
    {
        if (status == Status.SHUTDOWN || status == Status.SHUTTING_DOWN)
            return;

        setStatus(Status.SHUTTING_DOWN);

        WebSocketClient client = getClient();
        if (client != null)
            client.shutdown();

        shutdownInternals();
    }

    public synchronized void shutdownInternals()
    {
        if (status == Status.SHUTDOWN)
            return;
        //so we can shutdown from WebSocketClient properly
        guildSetupController.close();

        getRequester().shutdown();
        if (audioLifeCyclePool != null)
            audioLifeCyclePool.shutdownNow();
        threadConfig.shutdown();

        if (shutdownHook != null)
        {
            try
            {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            }
            catch (Exception ignored) {}
        }

        setStatus(Status.SHUTDOWN);
    }

    @Override
    public long getResponseTotal()
    {
        return responseTotal;
    }

    @Override
    public int getMaxReconnectDelay()
    {
        return sessionConfig.getMaxReconnectDelay();
    }

    @Nonnull
    @Override
    public ShardInfo getShardInfo()
    {
        return shardInfo == null ? ShardInfo.SINGLE : shardInfo;
    }

    @Nonnull
    @Override
    public Presence getPresence()
    {
        return presence;
    }

    @Nonnull
    @Override
    public IEventManager getEventManager()
    {
        return eventManager.getSubject();
    }

    @Nonnull
    @Override
    public AccountType getAccountType()
    {
        return authConfig.getAccountType();
    }

    @Override
    public void setEventManager(IEventManager eventManager)
    {
        this.eventManager.setSubject(eventManager);
    }

    @Override
    public void addEventListener(@Nonnull Object... listeners)
    {
        Checks.noneNull(listeners, "listeners");

        for (Object listener: listeners)
            eventManager.register(listener);
    }

    @Nonnull
    @Override
    public RestAction<ApplicationInfo> retrieveApplicationInfo()
    {
        AccountTypeException.check(getAccountType(), AccountType.BOT);
        Route.CompiledRoute route = Route.Applications.GET_BOT_APPLICATION.compile();
        return new RestActionImpl<>(this, route, (response, request) ->
        {
            ApplicationInfo info = getEntityBuilder().createApplicationInfo(response.getObject());
            this.clientId = info.getId();
            return info;
        });
    }

    private StringBuilder buildBaseInviteUrl()
    {
        if (clientId == null)
            retrieveApplicationInfo().complete();
        StringBuilder builder = new StringBuilder("https://discordapp.com/oauth2/authorize?scope=bot&client_id=");
        builder.append(clientId);
        return builder;
    }

    public EntityBuilder getEntityBuilder()
    {
        return entityBuilder;
    }

    public void setGatewayPing(long ping)
    {
        long oldPing = this.gatewayPing;
        this.gatewayPing = ping;
        handleEvent(new GatewayPingEvent(this, oldPing));
    }

    public Requester getRequester()
    {
        return requester;
    }

    public WebSocketFactory getWebSocketFactory()
    {
        return sessionConfig.getWebSocketFactory();
    }

    public WebSocketClient getClient()
    {
        return client;
    }

    public SnowflakeCacheViewImpl<User> getUsersView()
    {
        return userCache;
    }

    public SnowflakeCacheViewImpl<Guild> getGuildsView()
    {
        return guildCache;
    }

    public SnowflakeCacheViewImpl<Category> getCategoriesView()
    {
        return categories;
    }

    public SnowflakeCacheViewImpl<StoreChannel> getStoreChannelsView()
    {
        return storeChannelCache;
    }

    public SnowflakeCacheViewImpl<TextChannel> getTextChannelsView()
    {
        return textChannelCache;
    }

    public SnowflakeCacheViewImpl<VoiceChannel> getVoiceChannelsView()
    {
        return voiceChannelCache;
    }

    public SnowflakeCacheViewImpl<PrivateChannel> getPrivateChannelsView()
    {
        return privateChannelCache;
    }

    public TLongObjectMap<User> getFakeUserMap()
    {
        return fakeUsers;
    }

    public TLongObjectMap<PrivateChannel> getFakePrivateChannelMap()
    {
        return fakePrivateChannels;
    }

    public void setSelfUser(SelfUser selfUser)
    {
        this.selfUser = selfUser;
    }

    public void setResponseTotal(int responseTotal)
    {
        this.responseTotal = responseTotal;
    }

    public String getIdentifierString()
    {
        if (shardInfo != null)
            return "JDA " + shardInfo.getShardString();
        else
            return "JDA";
    }

    public EventCache getEventCache()
    {
        return eventCache;
    }

    public String getGatewayUrl()
    {
        return gatewayUrl;
    }

    public void resetGatewayUrl()
    {
        this.gatewayUrl = getGateway();
    }

}
