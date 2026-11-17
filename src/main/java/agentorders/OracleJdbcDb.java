package agentorders;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * Oracle implementation over two pools: one to the primary, one to a True Cache service.
 * Pool-agnostic (any javax.sql.DataSource, e.g. UCP's PoolDataSource); see OracleMain.
 * Not exercised against a live database in this repo's tests.
 *
 * Read-your-writes bound: a commit is treated as visible on True Cache after maxCacheLag.
 * If you can read the cache's applied SCN, compare SCNs instead for an exact check.
 */
public final class OracleJdbcDb implements Db {
    private final DataSource primary;
    private final DataSource cache;
    private final long lagNanos;

    public OracleJdbcDb(DataSource primary, DataSource cache, long maxCacheLagMillis) {
        this.primary = primary;
        this.cache = cache;
        this.lagNanos = maxCacheLagMillis * 1_000_000L;
    }

    private DataSource ds(Target t) { return t == Target.PRIMARY ? primary : cache; }

    private static final String ORDER_COLS = "order_id, customer_id, product_id, qty, status, created_at";

    private static Order order(ResultSet rs) throws SQLException {
        return new Order(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getInt(4),
                rs.getString(5), rs.getDouble(6));
    }

    @Override
    public Optional<Order> findOrder(Target t, long customerId, String orderId) {
        String sql = "SELECT " + ORDER_COLS + " FROM orders WHERE order_id = ? AND customer_id = ?";
        try (Connection c = ds(t).getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, orderId);
            ps.setLong(2, customerId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(order(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new DbException(e);
        }
    }

    @Override
    public List<Order> recentOrders(Target t, long customerId, int limit) {
        String sql = "SELECT " + ORDER_COLS + " FROM orders WHERE customer_id = ? "
                + "ORDER BY created_at DESC FETCH FIRST ? ROWS ONLY";
        try (Connection c = ds(t).getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setFetchSize(limit);
            ps.setLong(1, customerId);
            ps.setInt(2, limit);
            List<Order> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(order(rs));
            }
            return out;
        } catch (SQLException e) {
            throw new DbException(e);
        }
    }

    @Override
    public List<Product> productsByCategory(Target t, String category, int limit) {
        String sql = "SELECT product_id, name, category, price_cents FROM products "
                + "WHERE category = ? ORDER BY name FETCH FIRST ? ROWS ONLY";
        try (Connection c = ds(t).getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setFetchSize(limit);
            ps.setString(1, category);
            ps.setInt(2, limit);
            List<Product> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new Product(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4)));
            }
            return out;
        } catch (SQLException e) {
            throw new DbException(e);
        }
    }

    @Override
    public Optional<Integer> stock(long productId) {
        try (Connection c = primary.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT stock FROM products WHERE product_id = ?")) {
            ps.setLong(1, productId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getInt(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new DbException(e);
        }
    }

    // ------------------------------------------------------------------ transactions
    private static final class OraTx implements Tx {
        private final Connection c;
        boolean wrote;

        OraTx(Connection c) { this.c = c; }

        private int update(String sql, Binder b) {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                b.bind(ps);
                int n = ps.executeUpdate();
                if (n > 0) wrote = true;
                return n;
            } catch (SQLIntegrityConstraintViolationException e) {
                throw new DuplicateKeyException(e);
            } catch (SQLException e) {
                if (e.getErrorCode() == 1) throw new DuplicateKeyException(e);   // ORA-00001
                throw new DbException(e);
            }
        }

        @Override public Optional<String> idempotentResult(String key) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT result_json FROM idempotency_keys WHERE idem_key = ?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
                }
            } catch (SQLException e) {
                throw new DbException(e);
            }
        }

        @Override public void recordIdempotent(String key, String json) {
            update("INSERT INTO idempotency_keys (idem_key, result_json, created_at) VALUES (?, ?, ?)",
                    ps -> { ps.setString(1, key); ps.setString(2, json); ps.setDouble(3, System.currentTimeMillis() / 1000.0); });
        }

        @Override public boolean decrementStock(long pid, int qty) {
            return update("UPDATE products SET stock = stock - ? WHERE product_id = ? AND stock >= ?",
                    ps -> { ps.setInt(1, qty); ps.setLong(2, pid); ps.setInt(3, qty); }) == 1;
        }

        @Override public void restock(long pid, int qty) {
            update("UPDATE products SET stock = stock + ? WHERE product_id = ?",
                    ps -> { ps.setInt(1, qty); ps.setLong(2, pid); });
        }

        @Override public void insertOrder(Order o) {
            update("INSERT INTO orders (" + ORDER_COLS + ") VALUES (?, ?, ?, ?, ?, ?)", ps -> {
                ps.setString(1, o.orderId()); ps.setLong(2, o.customerId()); ps.setLong(3, o.productId());
                ps.setInt(4, o.qty()); ps.setString(5, o.status()); ps.setDouble(6, o.createdAt());
            });
        }

        @Override public Optional<Order> lockOrder(long customerId, String orderId) {
            try (PreparedStatement ps = c.prepareStatement("SELECT " + ORDER_COLS
                    + " FROM orders WHERE order_id = ? AND customer_id = ? FOR UPDATE")) {
                ps.setString(1, orderId);
                ps.setLong(2, customerId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(order(rs)) : Optional.empty();
                }
            } catch (SQLException e) {
                throw new DbException(e);
            }
        }

        @Override public void markCancelled(String orderId) {
            update("UPDATE orders SET status = 'CANCELLED' WHERE order_id = ?", ps -> ps.setString(1, orderId));
        }
    }

    @FunctionalInterface
    private interface Binder { void bind(PreparedStatement ps) throws SQLException; }

    @Override
    public <T> Committed<T> inTransaction(TxBody<T> body) {
        try (Connection c = primary.getConnection()) {
            c.setAutoCommit(false);
            OraTx tx = new OraTx(c);
            try {
                T value = body.run(tx);
                c.commit();
                return new Committed<>(value, tx.wrote ? System.nanoTime() + lagNanos : null);
            } catch (RuntimeException e) {
                try { c.rollback(); } catch (SQLException ignore) { /* original failure wins */ }
                throw e;
            }
        } catch (SQLException e) {
            throw new DbException(e);
        }
    }

    @Override
    public java.time.Duration maxCacheLag() {
        return java.time.Duration.ofNanos(lagNanos);
    }

    @Override
    public boolean cacheCaughtUp(Long token) {
        return token == null || System.nanoTime() - token >= 0;
    }
}
