package me.syari.ss.discord.api.events.channel.text;

import me.syari.ss.discord.api.JDA;
import me.syari.ss.discord.api.entities.TextChannel;

import javax.annotation.Nonnull;


public class TextChannelDeleteEvent extends GenericTextChannelEvent {
    public TextChannelDeleteEvent(@Nonnull JDA api, long responseNumber, @Nonnull TextChannel channel) {
        super(api, responseNumber, channel);
    }
}
