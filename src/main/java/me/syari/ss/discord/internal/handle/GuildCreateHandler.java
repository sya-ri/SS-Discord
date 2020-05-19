package me.syari.ss.discord.internal.handle;

import me.syari.ss.discord.api.utils.data.DataObject;
import me.syari.ss.discord.internal.JDA;
import me.syari.ss.discord.internal.entities.Guild;
import org.jetbrains.annotations.NotNull;

public class GuildCreateHandler extends SocketHandler {
    public GuildCreateHandler(JDA api) {
        super(api);
    }

    @Override
    protected Long handleInternally(@NotNull DataObject content) {
        System.out.println(">> GuildCreateHandler");
        final long id = content.getLong("id");
        JDA api = getJDA();
        if (!Guild.Companion.contains(id)) api.getGuildSetupController().onCreate(id, content);
        return null;
    }
}
