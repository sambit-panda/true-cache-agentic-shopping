package agentorders;

import java.util.List;
import java.util.Optional;

/**
 * Data access used by the agent tool layer.
 * Reads can target the primary or True Cache; writes are primary-only transactions.
 */
public interface Db {
    enum Target { PRIMARY, CACHE }

    record Product(long id, String name, String category, long priceCents) {}

    record Order(String orderId, long customerId, long productId, int qty, String status, double createdAt) {
        public Order withStatus(String s) {
            return new Order(orderId, customerId, productId, qty, s, createdAt);
        }
    }

    /** Result of a committed transaction. token identifies the commit (null if nothing was written). */
    record Committed<T>(T value, Long token) {}

    class DbException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public DbException(Throwable cause) { super(cause); }
        public DbException(String msg) { super(msg); }
    }

    class DuplicateKeyException extends DbException {
        private static final long serialVersionUID = 1L;
        public DuplicateKeyException(String msg) { super(msg); }
        public DuplicateKeyException(Throwable cause) { super(cause); }
    }

    // ---- reads (customerId is always part of the predicate: tools never trust the model for identity)
    Optional<Order> findOrder(Target t, long customerId, String orderId);
    List<Order> recentOrders(Target t, long customerId, int limit);
    List<Product> productsByCategory(Target t, String category, int limit);

    /** Strong read: always served by the primary. */
    Optional<Integer> stock(long productId);

    // ---- writes
    interface Tx {
        Optional<String> idempotentResult(String key);
        /** @throws DuplicateKeyException if the key already exists (concurrent retry) */
        void recordIdempotent(String key, String resultJson);
        boolean decrementStock(long productId, int qty);
        void restock(long productId, int qty);
        void insertOrder(Order o);
        /** Reads and row-locks the order (FOR UPDATE on Oracle). */
        Optional<Order> lockOrder(long customerId, String orderId);
        void markCancelled(String orderId);
    }

    interface TxBody<T> { T run(Tx tx); }

    <T> Committed<T> inTransaction(TxBody<T> body);

    /** Has True Cache applied the commit identified by token? */
    boolean cacheCaughtUp(Long token);

    /** Upper bound on how long True Cache can lag the primary; sizes the read-your-writes window. */
    java.time.Duration maxCacheLag();
}
