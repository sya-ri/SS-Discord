package me.syari.ss.discord.internal.utils.config;

import com.neovisionaries.ws.client.WebSocketFactory;
import me.syari.ss.discord.api.utils.SessionController;
import me.syari.ss.discord.api.utils.SessionControllerAdapter;
import me.syari.ss.discord.internal.utils.config.flags.ConfigFlag;
import okhttp3.OkHttpClient;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.util.EnumSet;

public class SessionConfig {
    private final SessionController sessionController;
    private final OkHttpClient httpClient;
    private final WebSocketFactory webSocketFactory;
    private final int largeThreshold;
    private final EnumSet<ConfigFlag> flags;
    private final int maxReconnectDelay;

    public SessionConfig(
            @Nullable SessionController sessionController, @Nullable OkHttpClient httpClient,
            @Nullable WebSocketFactory webSocketFactory,
            EnumSet<ConfigFlag> flags, int maxReconnectDelay, int largeThreshold) {
        this.sessionController = sessionController == null ? new SessionControllerAdapter() : sessionController;
        this.httpClient = httpClient;
        this.webSocketFactory = webSocketFactory == null ? new WebSocketFactory() : webSocketFactory;
        this.flags = flags;
        this.maxReconnectDelay = maxReconnectDelay;
        this.largeThreshold = largeThreshold;
    }

    @NotNull
    public SessionController getSessionController() {
        return sessionController;
    }

    @Nullable
    public OkHttpClient getHttpClient() {
        return httpClient;
    }

    @NotNull
    public WebSocketFactory getWebSocketFactory() {
        return webSocketFactory;
    }

    public boolean isAutoReconnect() {
        return flags.contains(ConfigFlag.AUTO_RECONNECT);
    }

    public boolean isRetryOnTimeout() {
        return flags.contains(ConfigFlag.RETRY_TIMEOUT);
    }

    public boolean isRelativeRateLimit() {
        return flags.contains(ConfigFlag.USE_RELATIVE_RATELIMIT);
    }

    public int getMaxReconnectDelay() {
        return maxReconnectDelay;
    }

    public int getLargeThreshold() {
        return largeThreshold;
    }

    @NotNull
    public static SessionConfig getDefault() {
        return new SessionConfig(null, new OkHttpClient(), null, ConfigFlag.getDefault(), 900, 250);
    }
}
