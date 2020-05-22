package me.syari.ss.discord.api.data

import com.google.gson.Gson
import me.syari.ss.discord.api.exceptions.ParsingException
import java.io.Reader
import kotlin.reflect.KClass

class DataContainer(init: Map<String, Any?> = emptyMap()) {
    val data = init.toMutableMap()

    val keys
        get() = data.keys

    fun contains(key: String): Boolean {
        return data.contains(key)
    }

    fun remove(key: String) {
        data.remove(key)
    }

    fun get(key: String): Any? {
        return data[key]
    }

    fun getString(key: String): String? {
        return get(key, String::class)
    }

    fun getStringOrThrow(key: String): String {
        return getString(key) ?: orThrow(key, "String")
    }

    fun getBoolean(key: String): Boolean? {
        return get(key, Boolean::class, { it.toBoolean() }, null)
    }

    fun getLong(key: String): Long? {
        return get(key, Long::class, { it.toLong() }, { it.toLong() })
    }

    fun getLongOrThrow(key: String): Long {
        return getLong(key) ?: orThrow(key, "Long")
    }

    fun getInt(key: String): Int? {
        return get(key, Int::class, { it.toInt() }, { it.toInt() })
    }

    fun getIntOrThrow(key: String): Int {
        return getInt(key) ?: orThrow(key, "Int")
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T: Any> get(
        key: String, type: KClass<T>, stringParse: ((String) -> T)? = null, numberParse: ((Number) -> T)? = null
    ): T? {
        val value = get(key) ?: return null
        return when {
            type.isInstance(value) -> value as T
            numberParse != null && value is Number -> numberParse.invoke(value)
            stringParse != null && value is String -> stringParse.invoke(value)
            else -> throw ParsingException("Cannot parse value for $key into type ${type.qualifiedName}: $value instance of ${value::class.qualifiedName}")
        }
    }

    private fun orThrow(key: String, typeName: String): Nothing {
        throw IllegalStateException("Unable to resolve value with key $key to type $typeName: ${data[key]}")
    }

    fun debug() {
        println()
        println(data)
        println()
    }

    companion object {
        private val gson = Gson()

        @Suppress("UNCHECKED_CAST")
        fun fromJson(json: String): DataContainer {
            val data = gson.fromJson(json, Map::class.java) as Map<String, Any?>
            return DataContainer(data)
        }

        @Suppress("UNCHECKED_CAST")
        fun fromJson(reader: Reader): DataContainer {
            val data = gson.fromJson(reader, Map::class.java) as Map<String, Any?>
            return DataContainer(data)
        }
    }
}