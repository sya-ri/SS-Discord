package me.syari.ss.discord.api.hooks;

import me.syari.ss.discord.api.events.message.MessageReceivedEvent;

import javax.annotation.Nonnull;
import java.util.List;


public interface IEventManager {

    void register(@Nonnull Object listener);


    void unregister(@Nonnull Object listener);


    void handle(@Nonnull MessageReceivedEvent event);


    @Nonnull
    List<Object> getRegisteredListeners();
}
