package me.syari.ss.discord.internal.handle;

import gnu.trove.map.TLongObjectMap;
import gnu.trove.map.hash.TLongObjectHashMap;
import me.syari.ss.discord.api.entities.ChannelType;
import me.syari.ss.discord.api.utils.data.DataArray;
import me.syari.ss.discord.api.utils.data.DataObject;
import me.syari.ss.discord.internal.JDAImpl;
import me.syari.ss.discord.internal.entities.EntityBuilder;
import me.syari.ss.discord.internal.requests.WebSocketClient;

public class ReadyHandler extends SocketHandler {

    public ReadyHandler(JDAImpl api) {
        super(api);
    }

    @Override
    protected Long handleInternally(DataObject content) {
        System.out.println(">> ReadyHandler");
        EntityBuilder builder = getJDA().getEntityBuilder();

        DataArray guilds = content.getArray("guilds");
        //Make sure we don't have any duplicates here!
        TLongObjectMap<DataObject> distinctGuilds = new TLongObjectHashMap<>();
        for (int i = 0; i < guilds.length(); i++) {
            DataObject guild = guilds.getObject(i);
            long id = guild.getUnsignedLong("id");
            DataObject previous = distinctGuilds.put(id, guild);
            if (previous != null)
                WebSocketClient.LOG.warn("Found duplicate guild for id {} in ready payload", id);
        }

        DataObject selfJson = content.getObject("user");

        builder.createSelfUser(selfJson);
        if (getJDA().getGuildSetupController().setIncompleteCount(distinctGuilds.size())) {
            distinctGuilds.forEachEntry((id, guild) ->
            {
                getJDA().getGuildSetupController().onReady(id, guild);
                return true;
            });
        }

        handleReady(content);
        return null;
    }

    public void handleReady(DataObject content) {
        DataArray privateChannels = content.getArray("private_channels");

        for (int i = 0; i < privateChannels.length(); i++) {
            DataObject chan = privateChannels.getObject(i);
            ChannelType type = ChannelType.fromId(chan.getInt("type"));

            WebSocketClient.LOG.warn("Received a Channel in the private_channels array in READY of an unknown type! Type: {}", type);
        }
    }
}
