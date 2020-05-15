

package me.syari.ss.discord.api.audio.hooks;

import me.syari.ss.discord.api.Permission;
import me.syari.ss.discord.api.Region;
import me.syari.ss.discord.api.entities.Guild;
import me.syari.ss.discord.api.managers.AudioManager;

/**
 * Represents the connection status of an audio connection.
 */
public enum ConnectionStatus
{

    NOT_CONNECTED(false),

    SHUTTING_DOWN(false),

    CONNECTING_AWAITING_ENDPOINT,

    CONNECTING_AWAITING_WEBSOCKET_CONNECT,

    CONNECTING_AWAITING_AUTHENTICATION,
    /**
     * JDA successfully authenticated the audio websocket and it now attempting UDP discovery. UDP discovery involves
     * opening a UDP socket and sending a packet to a provided Discord remote resource which responds with the
     * external ip and port which the packet was sent from.
     */
    CONNECTING_ATTEMPTING_UDP_DISCOVERY,
    /**
     * After determining our external ip and port, JDA forwards this information to Discord so that it can send
     * audio packets for us to properly receive. At this point, JDA is waiting for final websocket READY.
     */
    CONNECTING_AWAITING_READY,

    CONNECTED,
    /**
     * Indicates that the logged in account lost the {@link Permission#VOICE_CONNECT Permission.VOICE_CONNECT}
     * and cannot connect to the channel.
     */
    DISCONNECTED_LOST_PERMISSION(false),

    DISCONNECTED_CHANNEL_DELETED(false),
    /**
     * Indicates that the logged in account was removed from the {@link Guild Guild}
     * that this audio connection was connected to, thus the connection was severed.
     */
    DISCONNECTED_REMOVED_FROM_GUILD(false),

    DISCONNECTED_KICKED_FROM_CHANNEL(false),
    /**
     * Indicates that the logged in account was removed from the {@link Guild Guild}
     * while reconnecting to the gateway
     */
    DISCONNECTED_REMOVED_DURING_RECONNECT(false),
    /**
     * Indicates that our token was not valid.
     */
    DISCONNECTED_AUTHENTICATION_FAILURE,
    /**
     * Indicates that the audio connection was closed due to the {@link Region Region} of the
     * audio connection being changed. JDA will automatically attempt to reconnect the audio connection regardless
     * of the value of the {@link AudioManager#isAutoReconnect() AudioManager.isAutoReconnect()}.
     */
    AUDIO_REGION_CHANGE,

    //All will attempt to reconnect unless autoReconnect is disabled
    /**
     * Indicates that the connection was lost, either via UDP socket problems or the audio Websocket disconnecting.
     * <br>This is typically caused by a brief loss of internet which results in connection loss.
     * <br>JDA automatically attempts to resume the session when this error occurs.
     */
    ERROR_LOST_CONNECTION,
    /**
     * Indicates that the audio WebSocket was unable to resume an active session.
     * <br>JDA automatically attempts to reconnect when this error occurs.
     */
    ERROR_CANNOT_RESUME,
    /**
     * Indicates that the audio Websocket was unable to connect to discord. This could be due to an internet
     * problem causing a connection problem or an error on Discord's side (possibly due to load)
     * <br>JDA automatically attempts to reconnect when this error occurs.
     */
    ERROR_WEBSOCKET_UNABLE_TO_CONNECT,
    /**
     * Indicates that the audio WebSocket was unable to complete a handshake with discord, because
     * discord did not provide any supported encryption modes.
     * <br>JDA automatically attempts to reconnect when this error occurs.
     */
    ERROR_UNSUPPORTED_ENCRYPTION_MODES,
    /**
     * Indicates that the UDP setup failed. This is caused when JDA cannot properly communicate with Discord to
     * discover the system's external IP and port which audio data will be sent from. Typically caused by an internet
     * problem or an overly aggressive NAT port table.
     * <br>JDA automatically attempts to reconnect when this error occurs.
     */
    ERROR_UDP_UNABLE_TO_CONNECT,
    /**
     * Occurs when it takes longer than
     * {@link AudioManager#getConnectTimeout() AudioManager.getConnectTimeout()} to establish
     * the Websocket connection and setup the UDP connection.
     * <br>JDA automatically attempts to reconnect when this error occurs.
     */
    ERROR_CONNECTION_TIMEOUT;

    private final boolean shouldReconnect;

    ConnectionStatus()
    {
        this(true);
    }

    ConnectionStatus(boolean shouldReconnect)
    {
        this.shouldReconnect = shouldReconnect;
    }

    public boolean shouldReconnect()
    {
        return shouldReconnect;
    }
}
