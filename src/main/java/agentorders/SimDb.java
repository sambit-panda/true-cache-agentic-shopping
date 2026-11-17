package agentorders;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * In-memory stand-in for "Oracle primary + True Cache". Commits on the primary are shipped as
 * redo operations to a replica that applies them after a configurable delay, so the cache is
 * eventually consistent exactly like a lagging True Cache. Lets the app run without a database.
 */
public final class SimDb implements Db {
    private static final class Store {
        final Map<Long, Product> products = new HashMap<>();
        final Map<Long, Integer> stock = new HashMap<>();
        final Map<String, Order> orders = new HashMap<>();
        final Map<String, String> idem = new HashMap<>();
    }

    private record Redo(long seq, long dueNanos, List<Consumer<Store>> ops) {}

    private final Store primary = new Store();
    private final Store replica = new Store();
    private final ReentrantLock pLock = new ReentrantLock();
    private final ReentrantLock rLock = new ReentrantLock();
    private final LinkedBlockingQueue<Redo> log = new LinkedBlockingQueue<>();
    private final long lagNanos;
    private long seq;                 // guarded by pLock
    private volatile long applied;    // last redo sequence applied to the replica
    private final LongAdder primaryReads = new LongAdder();
    private final LongAdder cacheReads = new LongAdder();

    public SimDb(long replicationLagMillis) {
        this.lagNanos = replicationLagMillis * 1_000_000L;
        Thread t = new Thread(this::applyLoop, "sim-redo-apply");
        t.setDaemon(true);
        t.start();
    }

    /** Initial load, applied to both sides directly (not through the redo log). */
    public void seedProduct(Product p, int stock) {
        for (Store s : List.of(primary, replica)) {
            s.products.put(p.id(), p);
            s.stock.put(p.id(), stock);
        }
    }

    /** Number of read round trips served by the given target (for demos and tests). */
    public long reads(Target t) {
        return (t == Target.PRIMARY ? primaryReads : cacheReads).sum();
    }

    private void applyLoop() {
        try {
            while (true) {
                Redo r = log.take();
                long wait;
                while ((wait = r.dueNanos() - System.nanoTime()) > 0) {
                    LockSupport.parkNanos(wait);
                }
                rLock.lock();
                try {
                    for (Consumer<Store> op : r.ops()) op.accept(replica);
                } finally {
                    rLock.unlock();
                }
                applied = r.seq();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private <T> T read(Target t, java.util.function.Function<Store, T> fn) {
        (t == Target.PRIMARY ? primaryReads : cacheReads).increment();
        ReentrantLock lock = t == Target.PRIMARY ? pLock : rLock;
        lock.lock();
        try {
            return fn.apply(t == Target.PRIMARY ? primary : replica);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<Order> findOrder(Target t, long customerId, String orderId) {
        return read(t, s -> Optional.ofNullable(s.orders.get(orderId))
                .filter(o -> o.customerId() == customerId));
    }

    @Override
    public List<Order> recentOrders(Target t, long customerId, int limit) {
        return read(t, s -> s.orders.values().stream()
                .filter(o -> o.customerId() == customerId)
                .sorted(Comparator.comparingDouble(Order::createdAt).reversed()
                        .thenComparing(Order::orderId))
                .limit(limit).toList());
    }

    @Override
    public List<Product> productsByCategory(Target t, String category, int limit) {
        return read(t, s -> s.products.values().stream()
                .filter(p -> p.category().equals(category))
                .sorted(Comparator.comparing(Product::name))
                .limit(limit).toList());
    }

    @Override
    public Optional<Integer> stock(long productId) {
        return read(Target.PRIMARY, s -> Optional.ofNullable(s.stock.get(productId)));
    }

    // ------------------------------------------------------------------ transactions
    private final class SimTx implements Tx {
        final List<Runnable> undo = new ArrayList<>();
        final List<Consumer<Store>> redo = new ArrayList<>();

        @Override public Optional<String> idempotentResult(String key) {
            return Optional.ofNullable(primary.idem.get(key));
        }

        @Override public void recordIdempotent(String key, String json) {
            if (primary.idem.containsKey(key)) throw new DuplicateKeyException("idempotency key " + key);
            primary.idem.put(key, json);
            undo.add(() -> primary.idem.remove(key));
            redo.add(s -> s.idem.put(key, json));
        }

        @Override public boolean decrementStock(long pid, int qty) {
            Integer cur = primary.stock.get(pid);
            if (cur == null || cur < qty) return false;
            primary.stock.put(pid, cur - qty);
            undo.add(() -> primary.stock.put(pid, cur));
            redo.add(s -> s.stock.merge(pid, -qty, Integer::sum));
            return true;
        }

        @Override public void restock(long pid, int qty) {
            Integer cur = primary.stock.get(pid);
            if (cur == null) return;
            primary.stock.put(pid, cur + qty);
            undo.add(() -> primary.stock.put(pid, cur));
            redo.add(s -> s.stock.merge(pid, qty, Integer::sum));
        }

        @Override public void insertOrder(Order o) {
            primary.orders.put(o.orderId(), o);
            undo.add(() -> primary.orders.remove(o.orderId()));
            redo.add(s -> s.orders.put(o.orderId(), o));
        }

        @Override public Optional<Order> lockOrder(long customerId, String orderId) {
            return Optional.ofNullable(primary.orders.get(orderId)).filter(o -> o.customerId() == customerId);
        }

        @Override public void markCancelled(String orderId) {
            Order prev = primary.orders.get(orderId);
            Order next = prev.withStatus("CANCELLED");
            primary.orders.put(orderId, next);
            undo.add(() -> primary.orders.put(orderId, prev));
            redo.add(s -> s.orders.put(orderId, next));
        }

        void rollback() {
            for (int i = undo.size() - 1; i >= 0; i--) undo.get(i).run();
        }
    }

    @Override
    public <T> Committed<T> inTransaction(TxBody<T> body) {
        pLock.lock();   // one writer at a time keeps the simulator simple
        try {
            SimTx tx = new SimTx();
            T value;
            try {
                value = body.run(tx);
            } catch (RuntimeException e) {
                tx.rollback();
                throw e;
            }
            Long token = null;
            if (!tx.redo.isEmpty()) {
                token = ++seq;
                log.add(new Redo(token, System.nanoTime() + lagNanos, tx.redo));
            }
            return new Committed<>(value, token);
        } finally {
            pLock.unlock();
        }
    }

    @Override
    public java.time.Duration maxCacheLag() {
        return java.time.Duration.ofNanos(lagNanos).plusMillis(50);
    }

    @Override
    public boolean cacheCaughtUp(Long token) {
        return token == null || applied >= token;
    }
}
