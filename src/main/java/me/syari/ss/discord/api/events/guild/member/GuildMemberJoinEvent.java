package me.syari.ss.discord.api.events.guild.member;

import me.syari.ss.discord.api.JDA;
import me.syari.ss.discord.api.entities.Member;

import javax.annotation.Nonnull;


public class GuildMemberJoinEvent extends GenericGuildMemberEvent {
    public GuildMemberJoinEvent(@Nonnull JDA api, long responseNumber, @Nonnull Member member) {
        super(api, responseNumber, member);
    }
}
