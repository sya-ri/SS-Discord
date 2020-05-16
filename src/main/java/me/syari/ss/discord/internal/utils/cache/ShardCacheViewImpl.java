package me.syari.ss.discord.internal.utils.cache;

import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.hash.TIntObjectHashMap;
import gnu.trove.set.TIntSet;
import gnu.trove.set.hash.TIntHashSet;
import me.syari.ss.discord.api.JDA;
import me.syari.ss.discord.api.utils.ClosableIterator;
import me.syari.ss.discord.api.utils.LockIterator;
import me.syari.ss.discord.api.utils.cache.CacheView;
import me.syari.ss.discord.api.utils.cache.ShardCacheView;
import me.syari.ss.discord.internal.utils.ChainedClosableIterator;
import me.syari.ss.discord.internal.utils.Checks;
import me.syari.ss.discord.internal.utils.UnlockHook;
import org.apache.commons.collections4.iterators.ObjectArrayIterator;

import javax.annotation.Nonnull;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class ShardCacheViewImpl extends ReadWriteLockCache<JDA> implements ShardCacheView {
    protected static final JDA[] EMPTY_ARRAY = new JDA[0];
    protected final TIntObjectMap<JDA> elements;

    public ShardCacheViewImpl(int initialCapacity) {
        this.elements = new TIntObjectHashMap<>(initialCapacity);
    }

    public void clear() {
        try (UnlockHook hook = writeLock()) {
            elements.clear();
        }
    }

    public TIntObjectMap<JDA> getMap() {
        if (!lock.writeLock().isHeldByCurrentThread())
            throw new IllegalStateException("Cannot access map without holding write lock!");
        return elements;
    }

    public TIntSet keySet() {
        try (UnlockHook hook = readLock()) {
            return new TIntHashSet(elements.keySet());
        }
    }

    @Override
    public void forEach(Consumer<? super JDA> action) {
        Objects.requireNonNull(action);
        try (UnlockHook hook = readLock()) {
            for (JDA shard : elements.valueCollection()) {
                action.accept(shard);
            }
        }
    }

    @Nonnull
    @Override
    public List<JDA> asList() {
        if (isEmpty())
            return Collections.emptyList();
        try (UnlockHook hook = readLock()) {
            List<JDA> list = getCachedList();
            if (list != null)
                return list;
            return cache(new ArrayList<>(elements.valueCollection()));
        }
    }

    @Nonnull
    @Override
    public Set<JDA> asSet() {
        if (isEmpty())
            return Collections.emptySet();
        try (UnlockHook hook = readLock()) {
            Set<JDA> set = getCachedSet();
            if (set != null)
                return set;
            return cache(new HashSet<>(elements.valueCollection()));
        }
    }

    @Nonnull
    @Override
    public LockIterator<JDA> lockedIterator() {
        ReentrantReadWriteLock.ReadLock readLock = lock.readLock();
        readLock.lock();
        try {
            Iterator<JDA> directIterator = elements.valueCollection().iterator();
            return new LockIterator<>(directIterator, readLock);
        } catch (Throwable t) {
            readLock.unlock();
            throw t;
        }
    }

    @Override
    public long size() {
        return elements.size();
    }

    @Override
    public boolean isEmpty() {
        return elements.isEmpty();
    }

    @Nonnull
    @Override
    public List<JDA> getElementsByName(@Nonnull String name, boolean ignoreCase) {
        Checks.notEmpty(name, "Name");
        if (elements.isEmpty())
            return Collections.emptyList();

        try (UnlockHook hook = readLock()) {
            List<JDA> list = new LinkedList<>();
            for (JDA elem : elements.valueCollection()) {
                String elementName = elem.getShardInfo().getShardString();
                if (elementName != null) {
                    if (ignoreCase) {
                        if (elementName.equalsIgnoreCase(name))
                            list.add(elem);
                    } else {
                        if (elementName.equals(name))
                            list.add(elem);
                    }
                }
            }

            return list;
        }
    }

    @Override
    public Spliterator<JDA> spliterator() {
        try (UnlockHook hook = readLock()) {
            return Spliterators.spliterator(iterator(), size(), Spliterator.IMMUTABLE | Spliterator.NONNULL);
        }
    }

    @Nonnull
    @Override
    public Stream<JDA> stream() {
        return StreamSupport.stream(spliterator(), false);
    }

    @Nonnull
    @Override
    public Stream<JDA> parallelStream() {
        return StreamSupport.stream(spliterator(), true);
    }

    @Nonnull
    @Override
    public Iterator<JDA> iterator() {
        try (UnlockHook hook = readLock()) {
            JDA[] arr = elements.values(EMPTY_ARRAY);
            return new ObjectArrayIterator<>(arr);
        }
    }

    @Override
    public JDA getElementById(int id) {
        try (UnlockHook hook = readLock()) {
            return this.elements.get(id);
        }
    }

    @Override
    public int hashCode() {
        try (UnlockHook hook = readLock()) {
            return elements.hashCode();
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this)
            return true;
        if (!(obj instanceof ShardCacheViewImpl))
            return false;
        ShardCacheViewImpl view = (ShardCacheViewImpl) obj;
        try (UnlockHook hook = readLock(); UnlockHook otherHook = view.readLock()) {
            return this.elements.equals(view.elements);
        }
    }

    @Override
    public String toString() {
        return asList().toString();
    }

    public static class UnifiedShardCacheViewImpl implements ShardCacheView {
        protected final Supplier<? extends Stream<? extends ShardCacheView>> generator;

        public UnifiedShardCacheViewImpl(Supplier<? extends Stream<? extends ShardCacheView>> generator) {
            this.generator = generator;
        }

        @Override
        public long size() {
            return distinctStream().mapToLong(CacheView::size).sum();
        }

        @Override
        public boolean isEmpty() {
            return generator.get().allMatch(CacheView::isEmpty);
        }

        @Nonnull
        @Override
        public List<JDA> asList() {
            List<JDA> list = new ArrayList<>();
            stream().forEach(list::add);
            return Collections.unmodifiableList(list);
        }

        @Nonnull
        @Override
        public Set<JDA> asSet() {
            Set<JDA> set = new HashSet<>();
            generator.get().flatMap(CacheView::stream).forEach(set::add);
            return Collections.unmodifiableSet(set);
        }

        @Nonnull
        @Override
        public ClosableIterator<JDA> lockedIterator() {
            Iterator<? extends ShardCacheView> gen = this.generator.get().iterator();
            return new ChainedClosableIterator<>(gen);
        }

        @Nonnull
        @Override
        public List<JDA> getElementsByName(@Nonnull String name, boolean ignoreCase) {
            return Collections.unmodifiableList(distinctStream()
                    .flatMap(view -> view.getElementsByName(name, ignoreCase).stream())
                    .collect(Collectors.toList()));
        }

        @Override
        public JDA getElementById(int id) {
            return generator.get()
                    .map(view -> view.getElementById(id))
                    .filter(Objects::nonNull)
                    .findFirst().orElse(null);
        }

        @Nonnull
        @Override
        public Stream<JDA> stream() {
            return generator.get().flatMap(CacheView::stream).distinct();
        }

        @Nonnull
        @Override
        public Stream<JDA> parallelStream() {
            return generator.get().flatMap(CacheView::parallelStream).distinct();
        }

        @Nonnull
        @Override
        public Iterator<JDA> iterator() {
            return stream().iterator();
        }

        protected Stream<? extends ShardCacheView> distinctStream() {
            return generator.get().distinct();
        }
    }
}
