package agentorders;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Executes agent tool calls and decides where each one is served from.
 *
 * <pre>
 *   cacheable read : app cache (write-through) -> True Cache -> primary (only while True Cache may lag)
 *   strong read    : primary, never cached
 *   write          : primary transaction (idempotent) -> then update the app cache (write-through)
 * </pre>
 *
 * A per-customer "recent write" marker (shared through the cache) implements read-your-writes across ALL
 * sessions and app nodes of that customer: for max-cache-lag after a write, cache misses for that customer
 * go to the primary instead of True Cache.
 */
public final class ToolRuntime {
    public record CallResult(Map<String, Object> result, String route) {}

    private final Db db;
    private final ToolCache cache;
    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final ConcurrentHashMap<String, LongAdder> routeCounts = new ConcurrentHashMap<>();

    public ToolRuntime(Db db, ToolCache cache) {
        this.db = db;
        this.cache = cache;
        for (Tool t : Tools.all()) tools.put(t.name(), t);
    }

    public Collection<Tool> tools() { return tools.values(); }

    public Map<String, Object> call(Session s, String name, Map<String, Object> args, String callId) {
        return callDetailed(s, name, args, callId).result();
    }

    /** callId must be stable across retries of the same model tool call (e.g. the tool_use id). */
    public CallResult callDetailed(Session s, String name, Map<String, Object> args, String callId) {
        Tool tool = tools.get(name);
        if (tool == null) return new CallResult(Tools.error("unknown_tool"), "none");
        Param.Args a;
        try {
            a = Param.validate(tool.params(), args);
        } catch (IllegalArgumentException e) {
            return new CallResult(Tools.error("invalid_arguments: " + e.getMessage()), "none");
        }
        CallResult r = switch (tool) {
            case Tool.Read rd -> read(rd, s, a);
            case Tool.Write w -> write(w, s, a, callId);
        };
        routeCounts.computeIfAbsent(name + " -> " + r.route(), k -> new LongAdder()).increment();
        return r;
    }

    // ------------------------------------------------------------------ reads
    private CallResult read(Tool.Read t, Session s, Param.Args a) {
        if (!t.cacheable()) {
            return new CallResult(t.fn().run(db, Db.Target.PRIMARY, s, a), "primary (strong read)");
        }
        String[] loadedFrom = {null};
        ToolCache.Loaded<Map<String, Object>> loaded = cache.getOrLoad(
                t.key().key(s, a), t.tags().tags(s, a), t.ttl(), () -> {
                    Db.Target target = routeFor(s);
                    loadedFrom[0] = target == Db.Target.PRIMARY ? "primary (read-your-writes)" : "true cache";
                    Map<String, Object> res = t.fn().run(db, target, s, a);
                    return new ToolCache.Result<>(res, !res.containsKey("error"));   // never cache errors
                });
        String route = switch (loaded.how()) {
            case HIT -> "app cache (hit)";
            case COALESCED -> "app cache (coalesced)";
            case LOADED -> loadedFrom[0];
        };
        return new CallResult(loaded.value(), route);
    }

    private Db.Target routeFor(Session s) {
        return cache.hasRecentWrite(scope(s.customerId())) ? Db.Target.PRIMARY : Db.Target.CACHE;
    }

    // ------------------------------------------------------------------ writes
    private CallResult write(Tool.Write t, Session s, Param.Args a, String callId) {
        String idemKey = s.sessionId() + ":" + callId;
        Tool.WriteOutcome o = t.fn().run(db, s, a, idemKey);
        long cid = s.customerId();
        // 1) mark the customer as having a recent write so cache misses (on any node, when the cache is
        //    shared) read the primary until True Cache has caught up. Only when entity data changed:
        //    a rejected call just stores an idempotency row.
        if (o.order() != null) cache.markRecentWrite(scope(cid), db.maxCacheLag());
        // 2) write through. Order matters: invalidate derived entries, then put the entity itself.
        String route = "primary (write)";
        if (o.replayed()) {
            route += " [replayed, cache untouched]";
        } else if (o.order() != null) {
            cache.invalidateTag(Tools.Keys.ordersTag(cid));
            cache.put(Tools.Keys.order(cid, o.order().orderId()), Tools.orderMap(o.order()), List.of(), Tools.ORDER_TTL);
            route += " + write-through";
        }
        return new CallResult(o.result(), route);
    }

    private static String scope(long customerId) { return "c" + customerId; }

    // ------------------------------------------------------------------ introspection
    public ToolCache cache() { return cache; }

    public Map<String, Long> routeCounts() {
        Map<String, Long> m = new TreeMap<>();
        routeCounts.forEach((k, v) -> m.put(k, v.sum()));
        return m;
    }
}
