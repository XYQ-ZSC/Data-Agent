package edu.zsc.ai.domain.service.db.impl;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import edu.zsc.ai.domain.model.context.DbContext;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** Typed keys prevent delimiter/name collisions; generations fence in-flight loads after DDL. */
@Component
public class SqlCompletionMetadataCache {
    private record Key(String plugin, Long connection, long generation, String catalog,
                       String schema, String kind, String object, SessionIdentity session) {}
    private record SessionIdentity(Object handle) {
        @Override public int hashCode() { return System.identityHashCode(handle); }
        @Override public boolean equals(Object other) {
            return other instanceof SessionIdentity identity && handle == identity.handle;
        }
    }
    private final Cache<Key, List<?>> entries = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(60)).maximumSize(10_000).build();
    private final ConcurrentHashMap<Long, AtomicLong> generations = new ConcurrentHashMap<>();

    public <T> List<T> get(String plugin, DbContext db, String kind, String object, Supplier<List<T>> loader) {
        return get(plugin, db, kind, object, null, loader);
    }

    @SuppressWarnings("unchecked")
    public <T> List<T> get(String plugin, DbContext db, String kind, String object, Object session, Supplier<List<T>> loader) {
        long generation = generations.computeIfAbsent(db.connectionId(), id -> new AtomicLong()).get();
        Key key = new Key(plugin, db.connectionId(), generation, db.catalog(), db.schema(), kind, object, new SessionIdentity(session));
        return (List<T>) entries.get(key, ignored -> List.copyOf(loader.get()));
    }

    public void invalidate(Long connectionId) {
        generations.computeIfAbsent(connectionId, id -> new AtomicLong()).incrementAndGet();
        // Old entries expire with the TTL. Avoid waiting on an in-flight JDBC query;
        // its old-generation result cannot be read by subsequent requests.
    }
}
