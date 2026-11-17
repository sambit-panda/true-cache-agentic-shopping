package agentorders;

import java.time.Duration;
import java.util.Collection;

/**
 * Write-through tool-result cache used by ToolRuntime. Implementations: WriteThroughCache
 * (in-process) and RedisWriteThroughCache (shared across app nodes).
 *
 * Contract: values are JSON-shaped (Map/List/String/Number/Boolean) and treated as immutable;
 * put/invalidate are called AFTER the primary transaction commits; a fill that races a put or an
 * invalidation of the same key/tag must be dropped.
 */
public interface ToolCache {
    enum How { HIT, LOADED, COALESCED }

    record Result<V>(V value, boolean cacheable) {}

    record Loaded<V>(V value, How how) {}

    @FunctionalInterface
    interface Loader<V> { Result<V> load(); }

    final class Stats {
        public long hits, misses, fills, droppedFills, puts, invalidations, coalesced, evictions, errors;

        @Override public String toString() {
            String s = "hits=%d misses=%d fills=%d droppedFills=%d writeThroughPuts=%d invalidations=%d coalesced=%d evictions=%d"
                    .formatted(hits, misses, fills, droppedFills, puts, invalidations, coalesced, evictions);
            return errors > 0 ? s + " errors=" + errors : s;
        }
    }

    <V> Loaded<V> getOrLoad(String key, Collection<String> tags, Duration ttl, Loader<V> loader);

    /** Write-through: store the new state of key (bumps the key's version so racing fills are dropped). */
    void put(String key, Object value, Collection<String> tags, Duration ttl);

    /** Drops all entries carrying the tag and kills in-flight fills that read it. */
    void invalidateTag(String tag);

    void invalidateKey(String key);

    /**
     * Read-your-writes marker for a scope (e.g. one customer): after a commit, call this so that
     * hasRecentWrite(scope) is true for the next `window` (>= how long True Cache can lag). For a
     * shared cache the marker is shared by all app nodes, so a miss on ANY node reads the primary.
     */
    void markRecentWrite(String scope, Duration window);

    /** True while the scope may have writes True Cache has not applied yet. Fails safe (true) on errors. */
    boolean hasRecentWrite(String scope);

    Stats stats();
}
