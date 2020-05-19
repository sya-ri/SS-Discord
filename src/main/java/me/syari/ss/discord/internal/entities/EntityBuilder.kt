package me.syari.ss.discord.internal.entities

import gnu.trove.set.TLongSet
import gnu.trove.set.hash.TLongHashSet
import me.syari.ss.discord.api.utils.data.DataArray
import me.syari.ss.discord.api.utils.data.DataObject
import me.syari.ss.discord.internal.JDA
import me.syari.ss.discord.internal.utils.Check
import java.util.ArrayList
import java.util.function.Function

class EntityBuilder(private val api: JDA) {
    private val guildCache = mutableMapOf<Long, Guild>()
    private val userCache = mutableMapOf<Long, User>()

    fun createGuild(id: Long, guildData: DataObject): Guild {
        val name = guildData.getString("name", "")
        val allRole = guildData.getArray("roles")
        val guild = Guild(api, id, name)
        Guild.add(guild)
        guildCache[id] = guild
        val roles = mutableMapOf<Long, Role>()
        for(i in 0 until allRole.length()){
            val role = createRole(guild, allRole.getObject(i))
            roles[role.idLong] = role
        }
        val allChannel = guildData.getArray("channels")
        for (i in 0 until allChannel.length()) {
            val channelData = allChannel.getObject(i)
            createTextChannel(guild, channelData)
        }
        return guild
    }

    private fun createUser(userData: DataObject): User {
        val id = userData.getLong("id")
        return userCache.getOrPut(id){
            val name = userData.getString("username")
            val isBot = userData.getBoolean("bot")
            User(id, api, name, isBot)
        }
    }

    private fun createMember(guild: Guild, memberData: DataObject): Member {
        val user = createUser(memberData.getObject("user"))
        val member = guild.getMemberOrPut(user){ Member(guild, user) }
        if (memberData.hasKey("nick")) {
            val lastNickName = member.nickname
            val nickName = memberData.getString("nick", null)
            if (nickName != lastNickName) {
                member.nickname = nickName
            }
        }
        return member
    }

    private fun createTextChannel(guild: Guild, channelData: DataObject) {
        val channelId = channelData.getLong("id")
        val name = channelData.getString("name")
        val textChannel = TextChannel(channelId, guild, name)
        guild.addTextChannel(channelId, textChannel)
    }

    private fun createRole(
        guild: Guild, roleData: DataObject
    ): Role {
        val id = roleData.getLong("id")
        return guild.getRoleOrPut(id){
            val name = roleData.getString("name")
            Role(id, name)
        }
    }

    fun createMessage(jsonObject: DataObject): Message {
        val channelId = jsonObject.getLong("channel_id")
        val channel = api.getTextChannelById(channelId) ?: throw IllegalArgumentException(MISSING_CHANNEL)
        return createMessage(jsonObject, channel)
    }

    fun createMessage(
        messageData: DataObject, channel: TextChannel
    ): Message {
        val id = messageData.getLong("id")
        val authorData = messageData.getObject("author")
        val guild = channel.guild
        val member = guild.getMemberOrPut(id){
            val memberData = messageData.getObject("member")
            memberData.put("user", authorData)
            createMember(guild, memberData)
        }
        val fromWebhook = messageData.hasKey("webhook_id")
        val user = member.user
        if (!fromWebhook) {
            val lastName = user.name
            val name = authorData.getString("username")
            if (name != lastName) {
                user.name = name
            }
        }
        val mentionedRoles: TLongSet = TLongHashSet()
        val mentionedUsers: TLongSet = TLongHashSet(map(messageData, "mentions", Function { o: DataObject -> o.getLong("id") }))
        val roleMentionArray = messageData.optArray("mention_roles")
        roleMentionArray.ifPresent { array: DataArray ->
            for (i in 0 until array.length()) {
                mentionedRoles.add(array.getLong(i))
            }
        }
        val content = messageData.getString("content", "")
        val message = if (Check.isDefaultMessage(messageData.getInt("type"))) {
            Message(
                id, channel, mentionedUsers, mentionedRoles, content, user, member
            )
        } else {
            throw IllegalArgumentException(UNKNOWN_MESSAGE_TYPE)
        }
        val mentionedUsersList: MutableList<User> = ArrayList()
        val mentionedMembersList: MutableList<Member> = ArrayList()
        val userMentions = messageData.getArray("mentions")
        for (i in 0 until userMentions.length()) {
            val mentionJson = userMentions.getObject(i)
            if (mentionJson.isNull("member")) {
                val mentionedUser = createUser(mentionJson)
                mentionedUsersList.add(mentionedUser)
                val mentionedMember = guild.getMember(mentionedUser)
                if (mentionedMember != null) mentionedMembersList.add(mentionedMember)
            } else {
                val memberJson = mentionJson.getObject("member")
                mentionJson.remove("member")
                memberJson.put("user", mentionJson)
                val mentionedMember = createMember(guild, memberJson)
                mentionedMembersList.add(mentionedMember)
                mentionedUsersList.add(mentionedMember.user)
            }
        }
        if (mentionedUsersList.isNotEmpty()) message.setMentions(mentionedUsersList, mentionedMembersList)
        return message
    }

    private fun <T> map(
        jsonObject: DataObject, key: String, convert: Function<DataObject, T>
    ): List<T> {
        if (jsonObject.isNull(key)) return emptyList()
        val array = jsonObject.getArray(key)
        val mappedObjects: MutableList<T> = ArrayList(array.length())
        for (i in 0 until array.length()) {
            val obj = array.getObject(i)
            val result: T? = convert.apply(obj)
            if (result != null) mappedObjects.add(result)
        }
        return mappedObjects
    }

    companion object {
        const val MISSING_CHANNEL = "MISSING_CHANNEL"
        const val MISSING_USER = "MISSING_USER"
        const val UNKNOWN_MESSAGE_TYPE = "UNKNOWN_MESSAGE_TYPE"
    }

}