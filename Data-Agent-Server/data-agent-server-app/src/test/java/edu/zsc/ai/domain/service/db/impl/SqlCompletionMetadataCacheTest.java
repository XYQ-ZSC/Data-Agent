package edu.zsc.ai.domain.service.db.impl;

import edu.zsc.ai.domain.model.context.DbContext;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class SqlCompletionMetadataCacheTest {
    @Test void keysDistinguishDelimiterNamesNullStringsAndPlugins() {
        var cache = new SqlCompletionMetadataCache();
        var one = new DbContext(1L, "A::B", "C");
        var two = new DbContext(1L, "A", "B::C");
        assertEquals(List.of("one"), cache.get("dm", one, "columns", "X", () -> List.of("one")));
        assertEquals(List.of("two"), cache.get("dm", two, "columns", "X", () -> List.of("two")));
        assertEquals(List.of("mysql"), cache.get("mysql", one, "columns", "X", () -> List.of("mysql")));
        assertEquals(List.of("null"), cache.get("dm", new DbContext(1L, null, "C"), "tables", null, () -> List.of("null")));
        assertEquals(List.of("literal"), cache.get("dm", new DbContext(1L, "null", "C"), "tables", null, () -> List.of("literal")));
    }
    @Test void invalidationDoesNotEvictOtherConnections() {
        var cache = new SqlCompletionMetadataCache(); var one = new DbContext(1L, null, "APP"); var two = new DbContext(2L, null, "APP");
        cache.get("dm", one, "tables", null, () -> List.of("old")); cache.get("dm", two, "tables", null, () -> List.of("other"));
        cache.invalidate(1L);
        assertEquals(List.of("new"), cache.get("dm", one, "tables", null, () -> List.of("new")));
        assertEquals(List.of("other"), cache.get("dm", two, "tables", null, () -> List.of("should not load")));
    }
    @Test void failedLoadIsRetriedInsteadOfCachingAnEmptyResult() {
        var cache = new SqlCompletionMetadataCache(); var db = new DbContext(1L, null, "APP");
        assertThrows(IllegalStateException.class, () -> cache.get("dm", db, "tables", null, () -> { throw new IllegalStateException("offline"); }));
        assertEquals(List.of("back online"), cache.get("dm", db, "tables", null, () -> List.of("back online")));
    }
    @Test void reopenedConnectionDoesNotReusePreviousPoolMetadata() {
        var cache = new SqlCompletionMetadataCache(); var db = new DbContext(1L, null, "APP");
        Object first = new Object(); Object second = new Object();
        assertEquals(List.of("first account"), cache.get("dm", db, "tables", null, first, () -> List.of("first account")));
        assertEquals(List.of("second account"), cache.get("dm", db, "tables", null, second, () -> List.of("second account")));
    }
    @Test void invalidationFencesInFlightLoadsWithoutBlockingOnJdbc() throws Exception {
        var cache = new SqlCompletionMetadataCache(); var db = new DbContext(1L, null, "APP");
        var started = new CountDownLatch(1); var release = new CountDownLatch(1); var executor = Executors.newSingleThreadExecutor();
        try {
            var old = executor.submit(() -> cache.get("dm", db, "tables", null, () -> {
                started.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); } catch (InterruptedException e) { throw new RuntimeException(e); }
                return List.of("old");
            }));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            cache.invalidate(1L);
            assertEquals(List.of("new"), cache.get("dm", db, "tables", null, () -> List.of("new")));
            release.countDown(); assertEquals(List.of("old"), old.get(5, TimeUnit.SECONDS));
            assertEquals(List.of("new"), cache.get("dm", db, "tables", null, () -> List.of("should not load")));
        } finally { release.countDown(); executor.shutdownNow(); }
    }
}
