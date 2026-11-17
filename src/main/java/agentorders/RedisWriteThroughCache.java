package agentorders;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Redis-backed write-through tool-result cache, shared by every app node.
 *
 * Same contract as the in-process cache, implemented with Lua scripts so each step is atomic:
 *   v:{key}          value (JSON) with a PX ttl
 *   ver:{key}        version, bumped on put/invalidateKey          (race-safe fills)
 *   tagver:{tag}     version, bumped on invalidateTag              (race-safe fills)
 *   tagkeys:{tag}    set of keys carrying the tag                  (tag invalidation)
 *
 * A fill runs: snapshot versions -> load -> FILL script (stores only if versions are unchanged).
 * Concurrent misses are coalesced per JVM only; across nodes a cold key can be loaded once per node.
 *
 * Failure mode: fail-open. If Redis errors, reads just load from the database uncached. A failed
 * put/invalidate after a commit is retried once, then counted in stats().errors; stale entries then
 * live until their TTL, so keep TTLs short for data where staleness matters.
 *
 * The INVALIDATE_TAG script deletes keys it did not receive in KEYS (it builds them from a prefix),
 * which is fine on a single Redis but not valid on Redis Cluster without hash tags.
 */
public final class RedisWriteThroughCache implements ToolCache {
    private static final long VERSION_TTL_MS = 86_400_000L;

    private static final String TRACK = """
            local function track(setkey, member, ttl)
              redis.call('SADD', setkey, member)
              local want = ttl * 2
              if redis.call('PTTL', setkey) < want then redis.call('PEXPIRE', setkey, want) end
            end
            """;

    private static final String SNAPSHOT = """
            local out = {}
            for i = 1, #KEYS do out[i] = tonumber(redis.call('GET', KEYS[i]) or '0') end
            return out
            """;

    // KEYS: val, ver, tagver_1..n, tagkeys_1..n   ARGV: json, ttlMs, expectedVer, n, expectedTagVer_1..n, member
    private static final String FILL = TRACK + """
            local n = tonumber(ARGV[4])
            if tonumber(redis.call('GET', KEYS[2]) or '0') ~= tonumber(ARGV[3]) then return 0 end
            for i = 1, n do
              if tonumber(redis.call('GET', KEYS[2 + i]) or '0') ~= tonumber(ARGV[4 + i]) then return 0 end
            end
            local ttl = tonumber(ARGV[2])
            redis.call('SET', KEYS[1], ARGV[1], 'PX', ttl)
            for i = 1, n do track(KEYS[2 + n + i], ARGV[5 + n], ttl) end
            return 1
            """;

    // KEYS: val, ver, tagkeys_1..n   ARGV: json, ttlMs, n, member
    private static final String PUT = TRACK + """
            local n = tonumber(ARGV[3])
            redis.call('INCR', KEYS[2])
            redis.call('PEXPIRE', KEYS[2], %d)
            local ttl = tonumber(ARGV[2])
            redis.call('SET', KEYS[1], ARGV[1], 'PX', ttl)
            for i = 1, n do track(KEYS[2 + i], ARGV[4], ttl) end
            return 1
            """.formatted(VERSION_TTL_MS);

    // KEYS: tagver, tagkeys   ARGV: value-key prefix
    private static final String INVALIDATE_TAG = """
            redis.call('INCR', KEYS[1])
            redis.call('PEXPIRE', KEYS[1], %d)
            local members = redis.call('SMEMBERS', KEYS[2])
            for _, m in ipairs(members) do redis.call('DEL', ARGV[1] .. m) end
            redis.call('DEL', KEYS[2])
            return #members
            """.formatted(VERSION_TTL_MS);

    // KEYS: val, ver
    private static final String INVALIDATE_KEY = """
            redis.call('INCR', KEYS[2])
            redis.call('PEXPIRE', KEYS[2], %d)
            redis.call('DEL', KEYS[1])
            return 1
            """.formatted(VERSION_TTL_MS);

    private final RedisOps redis;
    private final String prefix;
    private final long defaultTtlMs;
    private final ConcurrentHashMap<String, CountDownLatch> inflight = new ConcurrentHashMap<>();
    private final AtomicLong hits = new AtomicLong(), misses = new AtomicLong(), fills = new AtomicLong(),
            droppedFills = new AtomicLong(), puts = new AtomicLong(), invalidations = new AtomicLong(),
            coalesced = new AtomicLong(), errors = new AtomicLong();

    /** prefix namespaces this app's keys, e.g. "agentorders:". */
    public RedisWriteThroughCache(RedisOps redis, String prefix, Duration defaultTtl) {
        this.redis = redis;
        this.prefix = prefix;
        this.defaultTtlMs = defaultTtl.toMillis();
    }

    private String valKey(String k) { return prefix + "v:" + k; }
    private String verKey(String k) { return prefix + "ver:" + k; }
    private String tagVerKey(String t) { return prefix + "tagver:" + t; }
    private String tagKeysKey(String t) { return prefix + "tagkeys:" + t; }

    // ------------------------------------------------------------------ read path
    @Override
    @SuppressWarnings("unchecked")
    public <V> Loaded<V> getOrLoad(String key, Collection<String> tags, Duration ttl, Loader<V> loader) {
        List<String> tagList = List.copyOf(tags);
        boolean wasCoalesced = false;
        while (true) {
            String json = safeGet(key);
            if (json != null) {
                hits.incrementAndGet();
                return new Loaded<>((V) MiniJson.read(json), wasCoalesced ? How.COALESCED : How.HIT);
            }
            CountDownLatch mine = new CountDownLatch(1);
            CountDownLatch other = inflight.putIfAbsent(key, mine);
            if (other == null) return lead(key, tagList, ttl, loader, mine);
            wasCoalesced = true;
            coalesced.incrementAndGet();
            try {
                other.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private <V> Loaded<V> lead(String key, List<String> tags, Duration ttl, Loader<V> loader, CountDownLatch mine) {
        try {
            long[] snap = snapshot(key, tags);            // BEFORE loading
            if (snap != null) {
                String again = safeGet(key);              // a write-through put may have landed since the miss
                if (again != null) {
                    hits.incrementAndGet();
                    return new Loaded<>((V) MiniJson.read(again), How.HIT);
                }
            }
            misses.incrementAndGet();
            Result<V> r = loader.load();
            if (r.cacheable() && snap != null) fill(key, tags, ttl, snap, r.value());
            return new Loaded<>(r.value(), How.LOADED);
        } finally {
            inflight.remove(key);
            mine.countDown();
        }
    }

    private String safeGet(String key) {
        try {
            return redis.get(valKey(key));
        } catch (RuntimeException e) {
            errors.incrementAndGet();
            return null;       // fail open: treat as a miss
        }
    }

    private long[] snapshot(String key, List<String> tags) {
        try {
            List<String> keys = new ArrayList<>();
            keys.add(verKey(key));
            for (String t : tags) keys.add(tagVerKey(t));
            List<?> res = (List<?>) redis.eval(SNAPSHOT, keys, List.of());
            long[] out = new long[res.size()];
            for (int i = 0; i < out.length; i++) out[i] = (Long) res.get(i);
            return out;
        } catch (RuntimeException e) {
            errors.incrementAndGet();
            return null;
        }
    }

    private void fill(String key, List<String> tags, Duration ttl, long[] snap, Object value) {
        try {
            int n = tags.size();
            List<String> keys = new ArrayList<>();
            keys.add(valKey(key));
            keys.add(verKey(key));
            for (String t : tags) keys.add(tagVerKey(t));
            for (String t : tags) keys.add(tagKeysKey(t));
            List<String> args = new ArrayList<>();
            args.add(MiniJson.write(value));
            args.add(Long.toString(ttl == null ? defaultTtlMs : ttl.toMillis()));
            args.add(Long.toString(snap[0]));
            args.add(Integer.toString(n));
            for (int i = 1; i <= n; i++) args.add(Long.toString(snap[i]));
            args.add(key);
            Object res = redis.eval(FILL, keys, args);
            if (Long.valueOf(1).equals(res)) fills.incrementAndGet();
            else droppedFills.incrementAndGet();     // a write raced this load: do not cache possibly-stale data
        } catch (RuntimeException e) {
            errors.incrementAndGet();
        }
    }

    // ------------------------------------------------------------------ write-through path
    @Override
    public void put(String key, Object value, Collection<String> tags, Duration ttl) {
        List<String> tagList = List.copyOf(tags);
        List<String> keys = new ArrayList<>();
        keys.add(valKey(key));
        keys.add(verKey(key));
        for (String t : tagList) keys.add(tagKeysKey(t));
        List<String> args = List.of(MiniJson.write(value),
                Long.toString(ttl == null ? defaultTtlMs : ttl.toMillis()),
                Integer.toString(tagList.size()), key);
        if (retry(() -> redis.eval(PUT, keys, args))) puts.incrementAndGet();
    }

    @Override
    public void invalidateTag(String tag) {
        if (retry(() -> redis.eval(INVALIDATE_TAG, List.of(tagVerKey(tag), tagKeysKey(tag)), List.of(prefix + "v:")))) {
            invalidations.incrementAndGet();
        }
    }

    @Override
    public void invalidateKey(String key) {
        if (retry(() -> redis.eval(INVALIDATE_KEY, List.of(valKey(key), verKey(key)), List.of()))) {
            invalidations.incrementAndGet();
        }
    }

    private boolean retry(java.util.function.Supplier<Object> op) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                op.get();
                return true;
            } catch (RuntimeException e) {
                errors.incrementAndGet();
            }
        }
        return false;
    }

    @Override
    public void markRecentWrite(String scope, Duration window) {
        retry(() -> redis.eval("redis.call('SET', KEYS[1], '1', 'PX', ARGV[1]) return 1",
                List.of(prefix + "rw:" + scope), List.of(Long.toString(Math.max(1, window.toMillis())))));
    }

    @Override
    public boolean hasRecentWrite(String scope) {
        try {
            return redis.get(prefix + "rw:" + scope) != null;
        } catch (RuntimeException e) {
            errors.incrementAndGet();
            return true;     // fail safe: read the primary
        }
    }

    @Override
    public Stats stats() {
        Stats s = new Stats();
        s.hits = hits.get(); s.misses = misses.get(); s.fills = fills.get(); s.droppedFills = droppedFills.get();
        s.puts = puts.get(); s.invalidations = invalidations.get(); s.coalesced = coalesced.get(); s.errors = errors.get();
        return s;
    }
}
