package agentorders;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** An agent-callable tool. Reads may be cacheable; writes are primary-only and write through. */
public sealed interface Tool permits Tool.Read, Tool.Write {
    String name();
    String description();
    List<Param> params();

    @FunctionalInterface interface ReadFn { Map<String, Object> run(Db db, Db.Target target, Session s, Param.Args a); }
    @FunctionalInterface interface WriteFn { WriteOutcome run(Db db, Session s, Param.Args a, String idemKey); }
    @FunctionalInterface interface KeyFn { String key(Session s, Param.Args a); }
    @FunctionalInterface interface TagFn { List<String> tags(Session s, Param.Args a); }

    /**
     * cacheable=false marks a strong read: always the primary, never cached (e.g. stock check
     * right before a purchase). cacheable=true reads go app cache -> True Cache (or the primary
     * while True Cache may still be behind this customer's last write).
     */
    record Read(String name, String description, List<Param> params, boolean cacheable,
                KeyFn key, TagFn tags, Duration ttl, ReadFn fn) implements Tool {}

    record Write(String name, String description, List<Param> params, WriteFn fn) implements Tool {}

    /**
     * result: what the model sees (flat map, also stored for idempotent replay).
     * order: the new entity state to write through to the cache (null if nothing changed).
     * token: commit token (null if nothing was written). replayed: served from the idempotency table.
     */
    record WriteOutcome(Map<String, Object> result, Db.Order order, Long token, boolean replayed) {}
}
