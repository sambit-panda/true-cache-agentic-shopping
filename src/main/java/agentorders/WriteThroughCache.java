package agentorders;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * Write-through tool-result cache for agent tool calls. Sits in front of True Cache / primary.
 *
 * <ul>
 *   <li><b>Write-through</b>: after a write commits on the primary, the tool layer {@link #put}s the
 *       new entity state and {@link #invalidateTag invalidates} derived entries, so the next read is
 *       fresh without waiting for a TTL or for True Cache to catch up.</li>
 *   <li><b>Race-safe fills</b>: a miss snapshots the versions of its key and tags before loading; if a
 *       write lands before the fill, the stale fill is dropped.</li>
 *   <li><b>Single-flight</b>: concurrent misses on one key run the loader once (agent bursts).</li>
 *   <li><b>TTL + LRU</b> bound staleness from writers that bypass this layer, and memory.</li>
 * </ul>
 *
 * Cached values must be immutable (callers share the same instance). In-process; a multi-node
 * deployment would keep this interface on Redis (not implemented here). Uses ReentrantLock rather
 * than synchronized so it is friendly to virtual threads.
 */
public final class WriteThroughCache implements ToolCache {
    private record Entry(Object value, long expiresNanos, Set<String> tags) {}
    private record Snap(int stripe, List<Integer> tagVersions) {}
    private record Step(Entry hit, CountDownLatch waitFor, CountDownLatch lead, Snap snap) {}

    private static final int STRIPES = 1024;

    private final int maxEntries;
    private final long defaultTtlNanos;
    private final LongSupplier nanoClock;
    private final ReentrantLock lock = new ReentrantLock();
    private final LinkedHashMap<String, Entry> data = new LinkedHashMap<>(256, 0.75f, true);
    private final Map<String, Set<String>> tagKeys = new HashMap<>();
    private final Map<String, Integer> tagVer = new HashMap<>();
    private final int[] stripeVer = new int[STRIPES];
    private final Map<String, CountDownLatch> inflight = new HashMap<>();
    private final Map<String, Long> recentWrites = new HashMap<>();
    private final Stats stats = new Stats();

    public WriteThroughCache(int maxEntries, Duration defaultTtl) {
        this(maxEntries, defaultTtl, System::nanoTime);
    }

    public WriteThroughCache(int maxEntries, Duration defaultTtl, LongSupplier nanoClock) {
        this.maxEntries = maxEntries;
        this.defaultTtlNanos = defaultTtl.toNanos();
        this.nanoClock = nanoClock;
    }

    // ------------------------------------------------------------------ read path
    @SuppressWarnings("unchecked")
    public <V> Loaded<V> getOrLoad(String key, Collection<String> tags, Duration ttl, Loader<V> loader) {
        boolean coalesced = false;
        Step step;
        while (true) {
            step = step(key, tags);
            if (step.hit() != null) {
                return new Loaded<>((V) step.hit().value(), coalesced ? How.COALESCED : How.HIT);
            }
            if (step.lead() != null) break;
            coalesced = true;
            try {
                step.waitFor().await(10, TimeUnit.SECONDS);   // follower: wait for the leader, then re-check
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        try {
            Result<V> r = loader.load();
            if (r.cacheable()) {
                lock.lock();
                try {
                    if (step.snap().equals(snapshot(key, tags))) {
                        store(key, r.value(), tags, ttl);
                        stats.fills++;
                    } else {
                        stats.droppedFills++;    // a write raced this load: do not cache possibly-stale data
                    }
                } finally {
                    lock.unlock();
                }
            }
            return new Loaded<>(r.value(), How.LOADED);
        } finally {
            lock.lock();
            try {
                inflight.remove(key);
            } finally {
                lock.unlock();
            }
            step.lead().countDown();
        }
    }

    private Step step(String key, Collection<String> tags) {
        lock.lock();
        try {
            Entry e = lookup(key);
            if (e != null) {
                stats.hits++;
                return new Step(e, null, null, null);
            }
            CountDownLatch existing = inflight.get(key);
            if (existing != null) {
                stats.coalesced++;
                return new Step(null, existing, null, null);
            }
            CountDownLatch mine = new CountDownLatch(1);
            inflight.put(key, mine);
            stats.misses++;
            return new Step(null, null, mine, snapshot(key, tags));
        } finally {
            lock.unlock();
        }
    }

    // ------------------------------------------------------------------ write-through path
    /** Call AFTER the primary transaction commits. */
    public void put(String key, Object value, Collection<String> tags, Duration ttl) {
        lock.lock();
        try {
            stripeVer[stripe(key)]++;      // kills in-flight fills of this key
            store(key, value, tags, ttl);
            stats.puts++;
        } finally {
            lock.unlock();
        }
    }

    /** Drops every entry carrying the tag and kills in-flight fills that read the tag. */
    public void invalidateTag(String tag) {
        lock.lock();
        try {
            tagVer.merge(tag, 1, Integer::sum);
            Set<String> keys = tagKeys.remove(tag);
            if (keys != null) for (String k : new ArrayList<>(keys)) remove(k);
            stats.invalidations++;
        } finally {
            lock.unlock();
        }
    }

    public void invalidateKey(String key) {
        lock.lock();
        try {
            stripeVer[stripe(key)]++;
            remove(key);
            stats.invalidations++;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void markRecentWrite(String scope, Duration window) {
        lock.lock();
        try {
            recentWrites.put(scope, nanoClock.getAsLong() + window.toNanos());
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean hasRecentWrite(String scope) {
        lock.lock();
        try {
            Long deadline = recentWrites.get(scope);
            if (deadline == null) return false;
            if (deadline - nanoClock.getAsLong() <= 0) {
                recentWrites.remove(scope);
                return false;
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    public Stats stats() {
        lock.lock();
        try {
            Stats s = new Stats();
            s.hits = stats.hits; s.misses = stats.misses; s.fills = stats.fills;
            s.droppedFills = stats.droppedFills; s.puts = stats.puts;
            s.invalidations = stats.invalidations; s.coalesced = stats.coalesced; s.evictions = stats.evictions;
            return s;
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        lock.lock();
        try { return data.size(); } finally { lock.unlock(); }
    }

    // ------------------------------------------------------------------ internals (hold lock)
    private static int stripe(String key) { return Math.floorMod(key.hashCode(), STRIPES); }

    private Snap snapshot(String key, Collection<String> tags) {
        List<Integer> tv = new ArrayList<>(tags.size());
        for (String t : tags) tv.add(tagVer.getOrDefault(t, 0));
        return new Snap(stripeVer[stripe(key)], tv);
    }

    private Entry lookup(String key) {
        Entry e = data.get(key);
        if (e == null) return null;
        if (e.expiresNanos() - nanoClock.getAsLong() <= 0) {
            remove(key);
            return null;
        }
        return e;
    }

    private void remove(String key) {
        Entry e = data.remove(key);
        if (e == null) return;
        for (String t : e.tags()) {
            Set<String> ks = tagKeys.get(t);
            if (ks != null) {
                ks.remove(key);
                if (ks.isEmpty()) tagKeys.remove(t);
            }
        }
    }

    private void store(String key, Object value, Collection<String> tags, Duration ttl) {
        remove(key);
        Set<String> tagSet = Set.copyOf(tags);
        long ttlNanos = ttl == null ? defaultTtlNanos : ttl.toNanos();
        data.put(key, new Entry(value, nanoClock.getAsLong() + ttlNanos, tagSet));
        for (String t : tagSet) tagKeys.computeIfAbsent(t, x -> new HashSet<>()).add(key);
        Iterator<Map.Entry<String, Entry>> it = data.entrySet().iterator();
        while (data.size() > maxEntries && it.hasNext()) {
            String eldest = it.next().getKey();
            remove(eldest);          // safe: we only call next() again after re-creating the iterator
            it = data.entrySet().iterator();
            stats.evictions++;
        }
    }
}
