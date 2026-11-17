package agentorders;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Dependency-free test runner: java -cp out agentorders.Tests  (non-zero exit on failure).
 * Redis tests run against REDIS_ADDR (default 127.0.0.1:6390) and are skipped, loudly, if nothing answers.
 */
public final class Tests {
    static int failures;
    static int total;
    static RespClient redis;

    static void check(String name, boolean ok) {
        total++;
        if (!ok) failures++;
        System.out.println((ok ? "  PASS " : "  FAIL ") + name);
    }

    static final Duration TTL = Duration.ofSeconds(30);

    static Supplier<ToolCache> inProcess() { return () -> new WriteThroughCache(1000, TTL); }

    static Supplier<ToolCache> redisCache() {
        return () -> new RedisWriteThroughCache(redis, "t" + System.nanoTime() + ":", TTL);
    }

    static ToolRuntime fresh(long lagMs, SimDb[] out, Supplier<ToolCache> cache) {
        SimDb db = new SimDb(lagMs);
        Demo.seed(db);
        out[0] = db;
        return new ToolRuntime(db, cache.get());
    }

    public static void main(String[] args) throws Exception {
        String addr = System.getenv().getOrDefault("REDIS_ADDR", "127.0.0.1:6390");
        String[] hp = addr.split(":");
        redis = RespClient.tryConnect(hp[0], Integer.parseInt(hp[1]));

        System.out.println("== in-process cache");
        cacheContract(inProcess());
        inProcessOnly();
        toolSuite(inProcess());

        if (redis != null) {
            System.out.println("== Redis cache (" + addr + ")");
            respClientTests();
            cacheContract(redisCache());
            redisOnly();
            toolSuite(redisCache());
            multiNode();
        } else {
            System.out.println("== SKIPPED: no Redis at " + addr + "; Redis tests were NOT run");
        }

        System.out.println("== Responses agent loop (scripted model)");
        agentTests();
        miscTests();

        System.out.printf("%n%d checks, %d failed%s%n", total, failures, redis == null ? " (Redis tests skipped)" : "");
        System.exit(failures == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ cache contract (both caches)
    static void cacheContract(Supplier<ToolCache> f) throws Exception {
        List<String> none = List.of();

        ToolCache c = f.get();
        AtomicInteger loads = new AtomicInteger();
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<String>> fs = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                fs.add(ex.submit(() -> c.<String>getOrLoad("k", none, null, () -> {
                    loads.incrementAndGet();
                    sleep(80);
                    return new ToolCache.Result<>("v", true);
                }).value()));
            }
            boolean allSame = true;
            for (Future<String> x : fs) allSame &= "v".equals(x.get());
            check("single-flight: 32 concurrent misses -> 1 load, same value", loads.get() == 1 && allSame);
        }

        ToolCache c2 = f.get();
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        Thread t = Thread.ofVirtual().start(() -> c2.<String>getOrLoad("k", none, null, () -> {
            started.countDown();
            await(release);
            return new ToolCache.Result<>("old", true);
        }));
        started.await();
        c2.put("k", "new", none, null);
        release.countDown();
        t.join();
        var after = c2.<String>getOrLoad("k", none, null, () -> new ToolCache.Result<>("loaded", true));
        check("race: a fill that started before a write-through put is dropped",
                c2.stats().droppedFills == 1 && "new".equals(after.value()) && after.how() == ToolCache.How.HIT);

        ToolCache c3 = f.get();
        CountDownLatch s3 = new CountDownLatch(1), r3 = new CountDownLatch(1);
        Thread t3 = Thread.ofVirtual().start(() -> c3.<String>getOrLoad("list", List.of("tag"), null, () -> {
            s3.countDown();
            await(r3);
            return new ToolCache.Result<>("stale-list", true);
        }));
        s3.await();
        c3.invalidateTag("tag");
        r3.countDown();
        t3.join();
        var after3 = c3.<String>getOrLoad("list", List.of("tag"), null, () -> new ToolCache.Result<>("fresh", true));
        check("race: invalidateTag drops in-flight fills carrying the tag",
                c3.stats().droppedFills == 1 && after3.how() == ToolCache.How.LOADED && "fresh".equals(after3.value()));

        ToolCache c3b = f.get();
        CountDownLatch s4 = new CountDownLatch(1), r4 = new CountDownLatch(1);
        Thread t4 = Thread.ofVirtual().start(() -> c3b.<String>getOrLoad("k", none, null, () -> {
            s4.countDown();
            await(r4);
            return new ToolCache.Result<>("stale", true);
        }));
        s4.await();
        c3b.invalidateKey("k");
        r4.countDown();
        t4.join();
        check("race: invalidateKey drops an in-flight fill of that key", c3b.stats().droppedFills == 1);

        ToolCache c4 = f.get();
        c4.put("a", "1", List.of("t"), null);
        c4.put("b", "2", List.of("t"), null);
        c4.put("c", "3", none, null);
        c4.invalidateTag("t");
        boolean aGone = c4.<String>getOrLoad("a", none, null, () -> new ToolCache.Result<>("x", false)).how() == ToolCache.How.LOADED;
        boolean bGone = c4.<String>getOrLoad("b", none, null, () -> new ToolCache.Result<>("x", false)).how() == ToolCache.How.LOADED;
        boolean cKept = c4.<String>getOrLoad("c", none, null, () -> new ToolCache.Result<>("x", false)).how() == ToolCache.How.HIT;
        check("invalidateTag removes tagged entries only", aGone && bGone && cKept);

        ToolCache c5 = f.get();
        c5.put("k", "v", none, Duration.ofMillis(150));
        boolean hit = c5.<String>getOrLoad("k", none, null, () -> new ToolCache.Result<>("z", true)).how() == ToolCache.How.HIT;
        sleep(350);
        boolean reloaded = c5.<String>getOrLoad("k", none, null, () -> new ToolCache.Result<>("z", true)).how() == ToolCache.How.LOADED;
        check("TTL: hit before expiry, reload after", hit && reloaded);

        ToolCache c6 = f.get();
        AtomicInteger n6 = new AtomicInteger();
        for (int i = 0; i < 2; i++) {
            c6.<String>getOrLoad("e", none, null, () -> { n6.incrementAndGet(); return new ToolCache.Result<>("err", false); });
        }
        check("cacheable=false results are not stored", n6.get() == 2);

        ToolCache c7 = f.get();
        c7.markRecentWrite("c1", Duration.ofMillis(200));
        boolean marked = c7.hasRecentWrite("c1") && !c7.hasRecentWrite("c2");
        sleep(350);
        check("recent-write marker: set for its scope, expires after the window", marked && !c7.hasRecentWrite("c1"));
    }

    static void inProcessOnly() {
        AtomicLong now = new AtomicLong();
        WriteThroughCache c = new WriteThroughCache(100, Duration.ofSeconds(10), now::get);
        c.put("k", "v", List.of(), null);
        now.addAndGet(Duration.ofSeconds(11).toNanos());
        check("TTL with injected clock", c.<String>getOrLoad("k", List.of(), null, () -> new ToolCache.Result<>("z", true)).how() == ToolCache.How.LOADED);

        WriteThroughCache lru = new WriteThroughCache(2, Duration.ofSeconds(10));
        lru.put("a", "1", List.of(), null);
        lru.put("b", "2", List.of(), null);
        lru.<String>getOrLoad("a", List.of(), null, () -> new ToolCache.Result<>("0", true));   // touch a
        lru.put("c", "3", List.of(), null);                                                      // evicts b
        boolean aKept = lru.<String>getOrLoad("a", List.of(), null, () -> new ToolCache.Result<>("-", true)).how() == ToolCache.How.HIT;
        boolean bGone = lru.<String>getOrLoad("b", List.of(), null, () -> new ToolCache.Result<>("-", true)).how() == ToolCache.How.LOADED;
        check("LRU: least recently used entry evicted", aKept && bGone && lru.stats().evictions >= 1);
    }

    // ------------------------------------------------------------------ Redis-specific
    static void respClientTests() {
        Object r = redis.eval("return {1, 'a', 'é'}", List.of(), List.of());
        check("RESP: EVAL returns arrays, integers and UTF-8 strings", r instanceof List<?> l && l.equals(List.of(1L, "a", "é")));
        check("RESP: GET of a missing key is null", redis.get("definitely-missing-" + System.nanoTime()) == null);
        boolean threw = false;
        try { redis.eval("this is not lua", List.of(), List.of()); } catch (RespClient.RedisException e) { threw = true; }
        check("RESP: server error becomes RedisException and the connection stays usable", threw && "PONG".equals(redis.command("PING")));
    }

    static void redisOnly() throws Exception {
        String prefix = "n" + System.nanoTime() + ":";
        RedisWriteThroughCache a = new RedisWriteThroughCache(redis, prefix, TTL);
        RedisWriteThroughCache b = new RedisWriteThroughCache(redis, prefix, TTL);
        List<String> none = List.of();

        a.put("k", "v1", List.of("t"), null);
        boolean shared = "v1".equals(b.<String>getOrLoad("k", none, null, () -> new ToolCache.Result<>("x", true)).value());
        a.invalidateTag("t");
        boolean gone = b.<String>getOrLoad("k", none, null, () -> new ToolCache.Result<>("reload", false)).how() == ToolCache.How.LOADED;
        check("two nodes: a put on node A is a hit on node B; invalidateTag on A drops it for B", shared && gone);

        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        Thread t = Thread.ofVirtual().start(() -> b.<String>getOrLoad("race", none, null, () -> {
            started.countDown();
            await(release);
            return new ToolCache.Result<>("stale-from-lagging-source", true);
        }));
        started.await();
        a.put("race", "fresh-write-through", none, null);
        release.countDown();
        t.join();
        var got = a.<String>getOrLoad("race", none, null, () -> new ToolCache.Result<>("loaded", true));
        check("two nodes: node B's slow fill cannot overwrite node A's write-through value",
                b.stats().droppedFills == 1 && "fresh-write-through".equals(got.value()));

        RedisOps broken = new RedisOps() {
            @Override public Object eval(String s, List<String> k, List<String> a) { throw new RuntimeException("redis down"); }
            @Override public String get(String k) { throw new RuntimeException("redis down"); }
        };
        RedisWriteThroughCache down = new RedisWriteThroughCache(broken, "x:", TTL);
        var l = down.<String>getOrLoad("k", none, null, () -> new ToolCache.Result<>("from-db", true));
        down.put("k", "v", none, null);
        check("fail-open: Redis down -> reads still load from the database, writes do not throw, errors are counted",
                "from-db".equals(l.value()) && l.how() == ToolCache.How.LOADED && down.stats().errors > 0);
        check("fail-safe: Redis down -> hasRecentWrite is true (route to the primary)", down.hasRecentWrite("c1"));
    }

    static void multiNode() {
        String prefix = "mn" + System.nanoTime() + ":";
        SimDb[] h = new SimDb[1];
        SimDb db = new SimDb(300);
        Demo.seed(db);
        h[0] = db;
        ToolRuntime n1 = new ToolRuntime(db, new RedisWriteThroughCache(redis, prefix, TTL));
        ToolRuntime n2 = new ToolRuntime(db, new RedisWriteThroughCache(redis, prefix, TTL));
        Session s1 = Session.forCustomer(5);
        Session s2 = Session.forCustomer(5);
        var placed = n1.callDetailed(s1, "place_order", Map.of("product_id", 104, "qty", 1), "t1");
        String oid = (String) placed.result().get("order_id");
        var st = n2.callDetailed(s2, "get_order_status", Map.of("order_id", oid), "c1");
        var list = n2.callDetailed(s2, "list_recent_orders", Map.of(), "c2");
        check("multi-node: node 2 serves node 1's write-through status from the shared cache",
                st.route().equals("app cache (hit)") && "PLACED".equals(st.result().get("status")));
        check("multi-node: node 2 miss after node 1's write reads the primary (shared read-your-writes marker), not stale True Cache",
                list.route().equals("primary (read-your-writes)") && ((List<?>) list.result().get("orders")).size() == 1);
    }

    // ------------------------------------------------------------------ tools (both caches)
    static void toolSuite(Supplier<ToolCache> cf) throws Exception {
        SimDb[] h = new SimDb[1];

        ToolRuntime rt = fresh(200, h, cf);
        SimDb db = h[0];
        Session s1 = Session.forCustomer(1);
        var placed = rt.callDetailed(s1, "place_order", Map.of("product_id", 104, "qty", 2), "t1");
        String oid = (String) placed.result().get("order_id");
        boolean naiveStale = db.findOrder(Db.Target.CACHE, 1, oid).isEmpty();
        long cacheReadsBefore = db.reads(Db.Target.CACHE);
        var st = rt.callDetailed(s1, "get_order_status", Map.of("order_id", oid), "c1");
        check("write-through: a cache-only read right after commit is stale (the problem)", naiveStale);
        check("write-through: get_order_status is served fresh from the app cache with no DB read",
                st.route().equals("app cache (hit)") && "PLACED".equals(st.result().get("status"))
                        && db.reads(Db.Target.CACHE) == cacheReadsBefore);
        check("write route reports write-through", placed.route().contains("write-through"));

        Session s1b = Session.forCustomer(1);
        var l1 = rt.callDetailed(s1b, "list_recent_orders", Map.of(), "c2");
        check("read-your-writes: list miss right after a write goes to the primary and sees the order (other session, same customer)",
                l1.route().equals("primary (read-your-writes)") && ((List<?>) l1.result().get("orders")).size() == 1);
        check("list refilled from the primary is then served from the app cache",
                rt.callDetailed(s1, "list_recent_orders", Map.of(), "c3").route().equals("app cache (hit)"));
        check("read-your-writes is per customer: other customers still use True Cache",
                rt.callDetailed(Session.forCustomer(2), "list_recent_orders", Map.of(), "c4").route().equals("true cache"));

        var retry = rt.callDetailed(s1, "place_order", Map.of("product_id", 104, "qty", 2), "t1");
        var stock = rt.callDetailed(s1, "check_stock", Map.of("product_id", 104), "c5");
        check("idempotency: a retry replays the same order and decrements stock once",
                Boolean.TRUE.equals(retry.result().get("replayed")) && oid.equals(retry.result().get("order_id"))
                        && ((Number) stock.result().get("stock")).intValue() == 1 && stock.route().equals("primary (strong read)"));

        long putsBefore = rt.cache().stats().puts;
        var rej = rt.callDetailed(s1, "place_order", Map.of("product_id", 104, "qty", 2), "t2");
        check("rejected write (insufficient stock): ok=false, no write-through",
                Boolean.FALSE.equals(rej.result().get("ok")) && rt.cache().stats().puts == putsBefore);

        rt.callDetailed(s1, "cancel_order", Map.of("order_id", oid), "t3");
        var after = rt.callDetailed(s1, "get_order_status", Map.of("order_id", oid), "c6");
        var stock2 = rt.callDetailed(s1, "check_stock", Map.of("product_id", 104), "c7");
        check("cancel: cached status is CANCELLED from write-through and stock is restored",
                after.route().equals("app cache (hit)") && "CANCELLED".equals(after.result().get("status"))
                        && ((Number) stock2.result().get("stock")).intValue() == 3);

        Session s2 = Session.forCustomer(2);
        var peek = rt.callDetailed(s2, "get_order_status", Map.of("order_id", oid), "c8");
        var steal = rt.callDetailed(s2, "cancel_order", Map.of("order_id", oid), "c9");
        check("authz: another customer cannot read or cancel the order",
                "order_not_found".equals(peek.result().get("error")) && Boolean.FALSE.equals(steal.result().get("ok")));
        check("errors are not cached", !rt.callDetailed(s2, "get_order_status", Map.of("order_id", oid), "c10").route().equals("app cache (hit)"));

        check("validation: unknown argument rejected", rt.call(s1, "place_order", Map.of("product_id", 104, "qty", 1, "customer_id", 2), "v1").containsKey("error"));
        check("validation: out-of-range rejected", rt.call(s1, "place_order", Map.of("product_id", 104, "qty", 99), "v2").containsKey("error"));
        check("validation: wrong type rejected", rt.call(s1, "get_order_status", Map.of("order_id", 5), "v3").containsKey("error"));
        check("validation: unknown tool rejected", rt.call(s1, "drop_table", Map.of(), "v4").containsKey("error"));

        ToolRuntime rt2 = fresh(100, h, cf);
        Session s = Session.forCustomer(7);
        rt2.call(s, "place_order", Map.of("product_id", 101, "qty", 1), "t1");
        Thread.sleep(300);
        var lateList = rt2.callDetailed(s, "list_recent_orders", Map.of(), "c1");
        check("after the lag window, misses go back to True Cache and see the order",
                lateList.route().equals("true cache") && ((List<?>) lateList.result().get("orders")).size() == 1);

        ToolRuntime rt3 = fresh(50, h, cf);
        Session sc = Session.forCustomer(9);
        Set<String> ids = ConcurrentHashMap.newKeySet();
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                fs.add(ex.submit(() -> ids.add(String.valueOf(rt3.call(sc, "place_order", Map.of("product_id", 105, "qty", 1), "dup").get("order_id")))));
            }
            for (Future<?> f : fs) f.get();
        }
        long stock3 = ((Number) rt3.call(sc, "check_stock", Map.of("product_id", 105), "x").get("stock")).longValue();
        check("16 concurrent deliveries of one tool call -> one order, stock decremented once", ids.size() == 1 && stock3 == 7);

        ToolRuntime rt4 = fresh(50, h, cf);
        long b0 = h[0].reads(Db.Target.CACHE);
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < 64; i++) {
                final Session ss = Session.forCustomer(100 + i);
                fs.add(ex.submit(() -> rt4.call(ss, "search_products", Map.of("category", "audio"), "b")));
            }
            for (Future<?> f : fs) f.get();
        }
        check("burst: 64 concurrent identical reads -> 1 True Cache query", h[0].reads(Db.Target.CACHE) - b0 == 1);
    }

    // ------------------------------------------------------------------ Responses agent loop
    static void agentTests() throws Exception {
        SimDb[] h = new SimDb[1];
        ToolRuntime rt = fresh(50, h, inProcess());
        AgentDemo.ScriptedModel model = AgentDemo.defaultScript();
        ResponsesAgent agent = new ResponsesAgent(model, rt, "test-model", AgentPolicy.INSTRUCTIONS, 500, rt.tools());
        Session s = Session.forCustomer(11);
        ResponsesAgent.Result r = agent.run(s, "buy a speaker", 8);

        String first = model.requests.get(0), second = model.requests.get(1);
        check("agent: loop runs 4 tool calls then returns the final text", r.text().contains("PLACED") && r.modelCalls() == 5 && r.toolCalls() == 4);
        check("agent: requests carry instructions and tools without provider-side response storage or cache keys",
                first.contains("\"instructions\"") && first.contains("\"tools\"")
                        && !first.contains("previous_response_id") && !first.contains("prompt_cache_key") && !first.contains("\"store\""));
        check("agent: follow-up requests replay the function call and its result with the same call_id",
                second.contains("\"type\":\"function_call\"") && second.contains("\"type\":\"function_call_output\"")
                        && second.contains("\"call_id\":\"call_1\""));
        int cut0 = first.indexOf("\"input\":"), cut1 = second.indexOf("\"input\":");
        check("agent: instructions + tools prefix is byte-identical on every call (what prompt caching matches on)",
                cut0 > 0 && first.substring(0, cut0).equals(second.substring(0, cut1)));
        check("agent: no provider-specific cache_control markers are required",
                model.requests.stream().noneMatch(x -> x.contains("cache_control")));
        check("agent: get_order_status found the new order and was served from the write-through entry",
                rt.routeCounts().getOrDefault("get_order_status -> app cache (hit)", 0L) == 1);
        check("agent: the tools really ran (order placed, stock 40 -> 39)",
                ((Number) rt.call(s, "check_stock", Map.of("product_id", 102), "x").get("stock")).intValue() == 39);
        int est = (AgentPolicy.INSTRUCTIONS.length() + ToolSchemas.responsesToolsJson(rt.tools()).length()) / 4;
        check("agent: static policy and tool prefix is substantial and stable, was about " + est + " tokens", est >= 1100);

        AgentDemo.ScriptedModel dup = new AgentDemo.ScriptedModel(List.of(
                b -> AgentDemo.toolCall("r1", "same_call", "place_order", "{\"product_id\":105,\"qty\":1}", 0, 0),
                b -> AgentDemo.toolCall("r2", "same_call", "place_order", "{\"product_id\":105,\"qty\":1}", 0, 0),
                b -> AgentDemo.toolCall("r3", "bad_call", "search_products", "{\"category\":\"toys\"}", 0, 0),
                b -> AgentDemo.toolCall("r4", "junk_call", "search_products", "not json", 0, 0),
                b -> AgentDemo.finalText("r5", "done", 0, 0)));
        new ResponsesAgent(dup, rt, "m", "i", 100).run(Session.forCustomer(12), "x", 8);
        check("agent: a repeated function_call with the same call_id is replayed, stock decremented once",
                dup.requests.get(2).contains("replayed") && ((Number) rt.call(s, "check_stock", Map.of("product_id", 105), "y").get("stock")).intValue() == 7);
        check("agent: bad arguments and non-JSON arguments come back to the model as tool errors",
                dup.requests.get(3).contains("invalid_arguments") && dup.requests.get(4).contains("not valid JSON"));

        AgentDemo.ScriptedModel usage = new AgentDemo.ScriptedModel(List.of(
                b -> AgentDemo.toolCall("u1", "c1", "search_products", "{\"category\":\"audio\"}", 2000, 0),
                b -> AgentDemo.toolCall("u2", "c2", "search_products", "{\"category\":\"audio\"}", 2100, 1920),
                b -> AgentDemo.finalText("u3", "ok", 2200, 2048)));
        ResponsesAgent.Usage u = new ResponsesAgent(usage, rt, "m", "i", 100).run(Session.forCustomer(13), "x", 8).usage();
        check("agent: usage totals input, cached and output tokens",
                u.inputTokens() == 6300 && u.cachedTokens() == 3968 && u.outputTokens() == 60);

        boolean threw = false;
        try {
            new ResponsesAgent(new AgentDemo.ScriptedModel(List.of(b -> "{\"error\":{\"message\":\"boom\"}}")), rt, "m", "i", 100)
                    .run(Session.forCustomer(14), "x", 3);
        } catch (IOException e) { threw = e.getMessage().contains("boom"); }
        check("agent: a model server error response surfaces as IOException", threw);

        boolean capped = false;
        try {
            new ResponsesAgent(new AgentDemo.ScriptedModel(List.of(
                    b -> AgentDemo.toolCall("a", "x1", "search_products", "{\"category\":\"audio\"}", 0, 0),
                    b -> AgentDemo.toolCall("b", "x2", "search_products", "{\"category\":\"audio\"}", 0, 0))), rt, "m", "i", 100)
                    .run(Session.forCustomer(15), "x", 2);
        } catch (IllegalStateException e) { capped = true; }
        check("agent: a model that never stops calling tools is cut off at maxModelCalls", capped);
    }

    // ------------------------------------------------------------------ misc
    @SuppressWarnings("unchecked")
    static void miscTests() {
        System.out.println("== MiniJson / ToolSchemas");
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("s", "a\"b\\c\nd\u0001é");
        m.put("n", 42L);
        m.put("d", 1.5);
        m.put("t", true);
        m.put("z", null);
        m.put("list", List.of(1L, "two", Map.of("k", List.of())));
        check("MiniJson round trip: nested maps/lists, escapes, unicode, null", MiniJson.readObject(MiniJson.write(m)).equals(m));

        String json = ToolSchemas.responsesToolsJson(new ToolRuntime(new SimDb(1), new WriteThroughCache(1, TTL)).tools());
        Map<String, Object> first = (Map<String, Object>) ((List<?>) MiniJson.read(json)).get(0);
        check("tool schemas: Responses-API function tools (flat name/parameters, strict=false)",
                "function".equals(first.get("type")) && first.containsKey("parameters") && Boolean.FALSE.equals(first.get("strict")));
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    static void await(CountDownLatch l) {
        try { l.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
