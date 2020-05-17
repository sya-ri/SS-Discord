package me.syari.ss.discord.internal.requests;

import me.syari.ss.discord.api.JDA;
import me.syari.ss.discord.api.requests.Request;
import me.syari.ss.discord.api.requests.Response;
import me.syari.ss.discord.internal.JDAImpl;
import me.syari.ss.discord.internal.requests.ratelimit.BotRateLimiter;
import me.syari.ss.discord.internal.utils.JDALogger;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.internal.http.HttpMethod;
import org.slf4j.Logger;

import javax.net.ssl.SSLPeerUnverifiedException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.LinkedHashSet;
import java.util.Set;

public class Requester {
    public static final Logger LOG = JDALogger.getLog(Requester.class);
    public static final String DISCORD_API_PREFIX = "https://discordapp.com/api/v6/";
    public static final String USER_AGENT = "SS-Discord";
    public static final RequestBody EMPTY_BODY = RequestBody.create(null, new byte[0]);
    public static final MediaType MEDIA_TYPE_JSON = MediaType.parse("application/json; charset=utf-8");
    public static final MediaType MEDIA_TYPE_OCTET = MediaType.parse("application/octet-stream; charset=utf-8");

    protected final JDAImpl api;
    private final RateLimiter rateLimiter;

    private final OkHttpClient httpClient;

    private volatile boolean retryOnTimeout = false;

    public Requester(JDA api) {
        this.api = (JDAImpl) api;
        rateLimiter = new BotRateLimiter(this);

        this.httpClient = this.api.getHttpClient();
    }

    public JDAImpl getJDA() {
        return api;
    }

    public <T> void request(Request<T> apiRequest) {
        if (rateLimiter.isShutdown)
            throw new IllegalStateException("The Requester has been shutdown! No new requests can be requested!");

        if (apiRequest.shouldQueue())
            rateLimiter.queueRequest(apiRequest);
        else
            execute(apiRequest, true);
    }

    private static boolean isRetry(Throwable e) {
        return e instanceof SocketException || e instanceof SocketTimeoutException || e instanceof SSLPeerUnverifiedException;
    }

    public Long execute(Request<?> apiRequest, boolean handleOnRateLimit) {
        return execute(apiRequest, false, handleOnRateLimit);
    }

    public Long execute(Request<?> apiRequest, boolean retried, boolean handleOnRatelimit) {
        Route.CompiledRoute route = apiRequest.getRoute();
        Long retryAfter = rateLimiter.getRateLimit(route);
        if (retryAfter != null && retryAfter > 0) {
            if (handleOnRatelimit)
                apiRequest.handleResponse(new Response(retryAfter));
            return retryAfter;
        }

        okhttp3.Request.Builder builder = new okhttp3.Request.Builder();

        String url = DISCORD_API_PREFIX + route.getCompiledRoute();
        builder.url(url);

        String method = apiRequest.getRoute().getMethod().toString();
        RequestBody body = apiRequest.getBody();

        if (body == null && HttpMethod.requiresRequestBody(method))
            body = EMPTY_BODY;

        builder.method(method, body)
                .header("X-RateLimit-Precision", "millisecond")
                .header("user-agent", USER_AGENT)
                .header("accept-encoding", "gzip");

        if (url.startsWith(DISCORD_API_PREFIX))
            builder.header("authorization", api.getToken());

        okhttp3.Request request = builder.build();

        Set<String> rays = new LinkedHashSet<>();
        okhttp3.Response[] responses = new okhttp3.Response[4];
        okhttp3.Response lastResponse = null;
        try {
            LOG.trace("Executing request {} {}", apiRequest.getRoute().getMethod(), url);
            int attempt = 0;
            do {
                Call call = httpClient.newCall(request);
                lastResponse = call.execute();
                responses[attempt] = lastResponse;
                String cfRay = lastResponse.header("CF-RAY");
                if (cfRay != null)
                    rays.add(cfRay);

                if (lastResponse.code() < 500)
                    break;

                attempt++;
                LOG.debug("Requesting {} -> {} returned status {}... retrying (attempt {})",
                        apiRequest.getRoute().getMethod(),
                        url, lastResponse.code(), attempt);
                try {
                    Thread.sleep(50 * attempt);
                } catch (InterruptedException ignored) {
                }
            }
            while (attempt < 3 && lastResponse.code() >= 500);

            LOG.trace("Finished Request {} {} with code {}", route.getMethod(), lastResponse.request().url(), lastResponse.code());

            if (lastResponse.code() >= 500) {
                Response response = new Response(lastResponse, -1);
                apiRequest.handleResponse(response);
                return null;
            }

            retryAfter = rateLimiter.handleResponse(route, lastResponse);
            if (!rays.isEmpty())
                LOG.debug("Received response with following cf-rays: {}", rays);

            if (retryAfter == null)
                apiRequest.handleResponse(new Response(lastResponse, -1));
            else if (handleOnRatelimit)
                apiRequest.handleResponse(new Response(lastResponse, retryAfter));

            return retryAfter;
        } catch (SocketTimeoutException e) {
            if (retryOnTimeout && !retried)
                return execute(apiRequest, true, handleOnRatelimit);
            LOG.error("Requester timed out while executing a request", e);
            apiRequest.handleResponse(new Response(lastResponse, e));
            return null;
        } catch (Exception e) {
            if (retryOnTimeout && !retried && isRetry(e))
                return execute(apiRequest, true, handleOnRatelimit);
            LOG.error("There was an exception while executing a REST request", e);
            apiRequest.handleResponse(new Response(lastResponse, e));
            return null;
        } finally {
            for (okhttp3.Response r : responses) {
                if (r == null)
                    break;
                r.close();
            }
        }
    }

    public RateLimiter getRateLimiter() {
        return rateLimiter;
    }

    public void setRetryOnTimeout(boolean retryOnTimeout) {
        this.retryOnTimeout = retryOnTimeout;
    }

    public void shutdown() {
        rateLimiter.shutdown();
    }

}
