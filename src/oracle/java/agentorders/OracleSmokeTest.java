package agentorders;

import java.util.List;
import java.util.Map;

/**
 * Database-only integration smoke test for the Oracle primary and True Cache routes.
 * Run with ./run-oracle-agent.sh --smoke-test after building the oracle Maven profile.
 */
public final class OracleSmokeTest {
    private OracleSmokeTest() {}

    public static void main(String[] args) throws Exception {
        required("ORA_USER");
        required("ORA_PASSWORD");
        String primaryUrl = required("ORA_PRIMARY_URL");
        String cacheUrl = required("ORA_CACHE_URL");

        Db db = new OracleJdbcDb(OracleMain.pool(primaryUrl), OracleMain.pool(cacheUrl), 1000);
        ToolRuntime runtime = new ToolRuntime(db, new EvalNoAppCache());
        Session session = Session.forCustomer(1);

        ToolRuntime.CallResult catalog = runtime.callDetailed(session, "search_products",
                Map.of("category", "audio", "limit", 10), "oracle-smoke-catalog");
        requireRoute("catalog read", catalog, "true cache");

        Object rawProducts = catalog.result().get("products");
        if (!(rawProducts instanceof List<?> products) || products.isEmpty()) {
            throw new IllegalStateException("True Cache returned no audio products: " + catalog.result());
        }
        if (!(products.get(0) instanceof Map<?, ?> firstProduct)
                || !(firstProduct.get("product_id") instanceof Number idValue)) {
            throw new IllegalStateException("Catalog result did not contain a product_id: " + catalog.result());
        }
        long productId = idValue.longValue();

        ToolRuntime.CallResult stock = runtime.callDetailed(session, "check_stock",
                Map.of("product_id", productId), "oracle-smoke-stock");
        requireRoute("stock read", stock, "primary (strong read)");
        Object stockValue = stock.result().get("stock");
        if (stock.result().containsKey("error") || !(stockValue instanceof Number stockCount)
                || stockCount.longValue() < 0) {
            throw new IllegalStateException("Primary stock check failed for product " + productId + ": " + stock.result());
        }

        System.out.printf("PASS catalog: %d audio products via %s%n", products.size(), catalog.route());
        System.out.printf("PASS stock: product %d has %d units via %s%n",
                productId, stockCount.longValue(), stock.route());
        System.out.println("PASS database smoke test; no model or LangCache calls were made");
    }

    private static void requireRoute(String operation, ToolRuntime.CallResult result, String expected) {
        if (result.result().containsKey("error")) {
            throw new IllegalStateException(operation + " returned an error: " + result.result());
        }
        if (!expected.equals(result.route())) {
            throw new IllegalStateException(operation + " used route '" + result.route()
                    + "'; expected '" + expected + "'");
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Set " + name + " in the environment");
        return value;
    }
}
