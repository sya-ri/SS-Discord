package me.syari.ss.discord.internal.entities

import me.syari.ss.discord.api.ISnowflake

class Emote(override val idLong: Long, val name: String, private val isAnimated: Boolean): ISnowflake {
    companion object {
        private val emoteList = mutableMapOf<Long, Emote>()

        fun get(id: Long): Emote? {
            return emoteList[id]
        }

        fun get(id: Long, run: () -> Emote): Emote {
            return get(id) ?: run.invoke()
        }
    }

    init {
        emoteList[idLong] = this
    }

    val asMention: String
        get() = (if (isAnimated) "<a:" else "<:") + name + ":" + id + ">"

    override fun equals(other: Any?): Boolean {
        if (other === this) return true
        if (other !is Emote) return false
        return idLong == other.idLong && name == other.name
    }

    override fun hashCode(): Int {
        return java.lang.Long.hashCode(idLong)
    }

    override fun toString(): String {
        return "Emote:$name($idLong)"
    }
}