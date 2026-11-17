package agentorders;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Scripted walk-through of the architecture on the in-memory simulator (True Cache lag = 300 ms).
 * There is no LLM here: each line is a tool call an agent would make.
 */
public final class Demo {
    static ToolRuntime rt;
    static SimDb db;

    static void seed(SimDb d) {
        d.seedProduct(new Db.Product(101, "Studio Headphones", "audio", 12900), 25);
        d.seedProduct(new Db.Product(102, "Bluetooth Speaker", "audio", 7900), 40);
        d.seedProduct(new Db.Product(103, "USB-C Dock", "accessories", 9900), 15);
        d.seedProduct(new Db.Product(104, "Mechanical Keyboard", "accessories", 14900), 3);
        d.seedProduct(new Db.Product(105, "4K Monitor", "displays", 34900), 8);
        d.seedProduct(new Db.Product(106, "Webcam HD", "accessories", 5900), 30);
    }

    static ToolRuntime.CallResult call(Session s, String tool, Map<String, Object> args, String callId) {
        ToolRuntime.CallResult r = rt.callDetailed(s, tool, args, callId);
        System.out.printf("  %s %s%n      route : %s%n      result: %s%n", tool, args, r.route(), MiniJson.write(r.result()));
        return r;
    }

    static void step(String title) { System.out.println("\n== " + title); }

    public static void main(String[] argv) throws Exception {
        db = new SimDb(300);
        seed(db);
        rt = new ToolRuntime(db, new WriteThroughCache(10_000, Duration.ofSeconds(30)));

        Session a = Session.forCustomer(1);
        Session a2 = Session.forCustomer(1);   // another agent conversation for the same customer
        Session b = Session.forCustomer(2);

        step("1. Catalog + order-list reads: True Cache on first touch, app cache after");
        call(a, "search_products", Map.of("category", "audio"), "c1");
        call(a, "search_products", Map.of("category", "audio"), "c2");
        call(a, "list_recent_orders", Map.of(), "c3");

        step("2. Strong read before buying: always the primary, never cached");
        call(a, "check_stock", Map.of("product_id", 104), "c4");

        step("3. Write: primary transaction, then write-through into the app cache");
        ToolRuntime.CallResult placed = call(a, "place_order", Map.of("product_id", 104, "qty", 2), "t1");
        String orderId = (String) placed.result().get("order_id");
        System.out.println("  (what a naive cache-only read sees right now: "
                + db.findOrder(Db.Target.CACHE, 1, orderId).map(Db.Order::status).orElse("order not found!") + ")");

        step("4. Read-your-writes without touching the DB: served fresh from the write-through entry");
        call(a, "get_order_status", Map.of("order_id", orderId), "c5");

        step("5. Derived list was invalidated; True Cache may lag, so this miss goes to the primary, then refills");
        call(a, "list_recent_orders", Map.of(), "c6");
        call(a, "list_recent_orders", Map.of(), "c7");
        System.out.println("  -- a different agent session for the same customer shares the cache:");
        call(a2, "list_recent_orders", Map.of(), "c8");

        step("6. Agent retries the same tool call (same call id): replayed, stock decremented once");
        call(a, "place_order", Map.of("product_id", 104, "qty", 2), "t1");
        call(a, "check_stock", Map.of("product_id", 104), "c9");

        step("7. Rejected write (only 1 left): nothing cached, nothing written through");
        call(a, "place_order", Map.of("product_id", 104, "qty", 2), "t2");

        step("8. Cancel: write-through updates the cached status; stock is restored");
        call(a, "cancel_order", Map.of("order_id", orderId), "t3");
        call(a, "get_order_status", Map.of("order_id", orderId), "c10");
        call(a, "check_stock", Map.of("product_id", 104), "c11");

        step("9. Guard rails: identity comes from the session, arguments are validated");
        call(b, "get_order_status", Map.of("order_id", orderId), "c12");
        call(b, "cancel_order", Map.of("order_id", orderId), "c13");
        call(a, "place_order", Map.of("product_id", 104, "qty", 99), "c14");
        call(a, "place_order", Map.of("product_id", 104, "qty", 1, "customer_id", 2), "c15");

        step("10. After True Cache catches up, misses go back to True Cache");
        Thread.sleep(400);
        call(a, "list_recent_orders", Map.of(), "c16");

        step("11. Burst: 64 agents ask the same uncached question at once");
        long before = db.reads(Db.Target.CACHE);
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < 64; i++) {
                final Session s = Session.forCustomer(100 + i);
                final int n = i;
                fs.add(ex.submit(() -> rt.call(s, "search_products", Map.of("category", "displays", "limit", 7), "burst" + n)));
            }
            for (Future<?> f : fs) f.get();
        }
        System.out.printf("  64 calls -> %d True Cache query(ies)%n", db.reads(Db.Target.CACHE) - before);

        step("Summary");
        System.out.println("  route counts:");
        rt.routeCounts().forEach((k, v) -> System.out.printf("    %-60s %d%n", k, v));
        System.out.println("  app cache : " + rt.cache().stats());
        System.out.printf("  DB reads  : primary=%d trueCache=%d%n", db.reads(Db.Target.PRIMARY), db.reads(Db.Target.CACHE));
        System.out.printf("  tool schema JSON for the model: %d bytes%n", ToolSchemas.responsesToolsJson(rt.tools()).length());
    }
}
