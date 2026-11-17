package agentorders;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/** The order-management tools exposed to the agent, plus the cache-key scheme they share. */
public final class Tools {
    private Tools() {}

    public static final List<String> CATEGORIES = List.of("audio", "accessories", "displays");
    public static final Duration ORDER_TTL = Duration.ofSeconds(60);
    private static final Duration CATALOG_TTL = Duration.ofSeconds(60);
    private static final Duration LIST_TTL = Duration.ofSeconds(30);

    /** Cache keys and tags. Write-through puts must use the same keys the read tools use. */
    public static final class Keys {
        private Keys() {}
        public static String order(long customerId, String orderId) { return "c" + customerId + ":order:" + orderId; }
        public static String orders(long customerId, int limit) { return "c" + customerId + ":orders:" + limit; }
        public static String ordersTag(long customerId) { return "orders:c" + customerId; }
        public static String catalog(String category, int limit) { return "catalog:" + category + ":" + limit; }
    }

    // ------------------------------------------------------------------ result helpers (immutable)
    static Map<String, Object> immutable(Map<String, Object> m) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(m));
    }

    static Map<String, Object> error(String code) {
        return Map.of("error", code);
    }

    public static Map<String, Object> orderMap(Db.Order o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("order_id", o.orderId());
        m.put("product_id", o.productId());
        m.put("qty", o.qty());
        m.put("status", o.status());
        m.put("created_at", o.createdAt());
        return immutable(m);
    }

    // ------------------------------------------------------------------ registry
    public static List<Tool> all() {
        return List.of(
                new Tool.Read("search_products",
                        "List products in a category (name and price). Catalog data, may be a few seconds stale.",
                        List.of(Param.requiredEnum("category", "Product category", CATEGORIES),
                                Param.optionalInt("limit", "Max results (1-20)", 1, 20, 10)),
                        true,
                        (s, a) -> Keys.catalog(a.str("category"), a.integer("limit")),
                        (s, a) -> List.of(),
                        CATALOG_TTL,
                        (db, t, s, a) -> {
                            List<Map<String, Object>> rows = db.productsByCategory(t, a.str("category"), a.integer("limit"))
                                    .stream().<Map<String, Object>>map(p -> {
                                        Map<String, Object> m = new LinkedHashMap<>();
                                        m.put("product_id", p.id());
                                        m.put("name", p.name());
                                        m.put("price_cents", p.priceCents());
                                        return immutable(m);
                                    }).toList();
                            return Map.of("products", rows);
                        }),

                new Tool.Read("get_order_status",
                        "Get the status of one of the current customer's orders.",
                        List.of(Param.requiredString("order_id", "Order id, e.g. ord_1a2b3c4d5e6f")),
                        true,
                        (s, a) -> Keys.order(s.customerId(), a.str("order_id")),
                        (s, a) -> List.of(),
                        ORDER_TTL,
                        (db, t, s, a) -> db.findOrder(t, s.customerId(), a.str("order_id"))
                                .map(Tools::orderMap).orElse(error("order_not_found"))),

                new Tool.Read("list_recent_orders",
                        "List the current customer's most recent orders, newest first.",
                        List.of(Param.optionalInt("limit", "Max results (1-20)", 1, 20, 5)),
                        true,
                        (s, a) -> Keys.orders(s.customerId(), a.integer("limit")),
                        (s, a) -> List.of(Keys.ordersTag(s.customerId())),
                        LIST_TTL,
                        (db, t, s, a) -> Map.of("orders",
                                db.recentOrders(t, s.customerId(), a.integer("limit")).stream().map(Tools::orderMap).toList())),

                new Tool.Read("check_stock",
                        "Exact, current stock for a product. Use right before placing an order.",
                        List.of(Param.requiredInt("product_id", "Product id", 1, Long.MAX_VALUE)),
                        false, null, null, null,
                        (db, t, s, a) -> db.stock(a.lng("product_id"))
                                .<Map<String, Object>>map(n -> Map.of("product_id", a.lng("product_id"), "stock", n))
                                .orElse(error("unknown_product"))),

                new Tool.Write("place_order",
                        "Place an order for the current customer. Safe to retry: duplicate calls do not double-order.",
                        List.of(Param.requiredInt("product_id", "Product id", 1, Long.MAX_VALUE),
                                Param.requiredInt("qty", "Quantity (1-10)", 1, 10)),
                        Tools::placeOrder),

                new Tool.Write("cancel_order",
                        "Cancel one of the current customer's orders that is still PLACED.",
                        List.of(Param.requiredString("order_id", "Order id")),
                        Tools::cancelOrder));
    }

    // ------------------------------------------------------------------ writes
    private record Body(Map<String, Object> result, Db.Order order) {}
    private record Txr(Map<String, Object> result, Db.Order order, boolean replayed) {}

    /**
     * Runs body once per idempotency key. A retry (same key) returns the stored result and changes
     * nothing; a concurrent duplicate loses on the unique key and is retried once as a replay.
     */
    private static Tool.WriteOutcome idempotent(Db db, String key, Function<Db.Tx, Body> body) {
        for (int attempt = 1; ; attempt++) {
            try {
                Db.Committed<Txr> c = db.inTransaction(tx -> {
                    Optional<String> prior = tx.idempotentResult(key);
                    if (prior.isPresent()) {
                        Map<String, Object> r = new LinkedHashMap<>(MiniJson.readObject(prior.get()));
                        r.put("replayed", true);
                        return new Txr(immutable(r), null, true);
                    }
                    Body b = body.apply(tx);
                    tx.recordIdempotent(key, MiniJson.write(b.result()));
                    return new Txr(b.result(), b.order(), false);
                });
                Txr v = c.value();
                return new Tool.WriteOutcome(v.result(), v.order(), c.token(), v.replayed());
            } catch (Db.DuplicateKeyException e) {
                if (attempt >= 2) throw e;
            }
        }
    }

    private static Tool.WriteOutcome placeOrder(Db db, Session s, Param.Args a, String idemKey) {
        long pid = a.lng("product_id");
        int qty = a.integer("qty");
        return idempotent(db, idemKey, tx -> {
            if (!tx.decrementStock(pid, qty)) {
                return new Body(Map.of("ok", false, "error", "insufficient_stock_or_unknown_product"), null);
            }
            Db.Order o = new Db.Order("ord_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                    s.customerId(), pid, qty, "PLACED", System.currentTimeMillis() / 1000.0);
            tx.insertOrder(o);
            return new Body(Map.of("ok", true, "order_id", o.orderId(), "status", o.status()), o);
        });
    }

    private static Tool.WriteOutcome cancelOrder(Db db, Session s, Param.Args a, String idemKey) {
        String orderId = a.str("order_id");
        return idempotent(db, idemKey, tx -> {
            Optional<Db.Order> cur = tx.lockOrder(s.customerId(), orderId);
            if (cur.isEmpty() || !cur.get().status().equals("PLACED")) {
                return new Body(Map.of("ok", false, "error", "not_cancellable"), null);
            }
            Db.Order o = cur.get();
            tx.markCancelled(orderId);
            tx.restock(o.productId(), o.qty());
            return new Body(Map.of("ok", true, "order_id", orderId, "status", "CANCELLED"), o.withStatus("CANCELLED"));
        });
    }
}
