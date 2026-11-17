package agentorders;

import java.time.Duration;
import java.util.Collection;

/** Bypass the application cache so repeated reads exercise Oracle True Cache directly. */
final class EvalNoAppCache implements ToolCache {
    private long loads;

    @Override
    public synchronized <V> Loaded<V> getOrLoad(String key, Collection<String> tags, Duration ttl, Loader<V> loader) {
        loads++;
        Result<V> result = loader.load();
        return new Loaded<>(result.value(), How.LOADED);
    }

    @Override public void put(String key, Object value, Collection<String> tags, Duration ttl) {}
    @Override public void invalidateTag(String tag) {}
    @Override public void invalidateKey(String key) {}
    @Override public void markRecentWrite(String scope, Duration window) {}
    @Override public boolean hasRecentWrite(String scope) { return false; }

    @Override
    public synchronized Stats stats() {
        Stats s = new Stats();
        s.misses = loads;
        return s;
    }
}
