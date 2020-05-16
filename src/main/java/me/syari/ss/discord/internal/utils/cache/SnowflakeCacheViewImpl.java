package me.syari.ss.discord.internal.utils.cache;

import me.syari.ss.discord.api.entities.ISnowflake;
import me.syari.ss.discord.api.utils.cache.SnowflakeCacheView;

import java.util.function.Function;

public class SnowflakeCacheViewImpl<T extends ISnowflake> extends AbstractCacheView<T> implements SnowflakeCacheView<T> {
    public SnowflakeCacheViewImpl(Class<T> type, Function<T, String> nameMapper) {
        super(type, nameMapper);
    }

    @Override
    public T getElementById(long id) {
        if (elements.isEmpty())
            return null;
        return get(id);
    }
}
