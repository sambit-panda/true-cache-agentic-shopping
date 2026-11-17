package agentorders;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Cache-aside wrapper for prompts served by a catalog-only agent. Callers must use an agent that
 * exposes only stable, read-only catalog tools. Dynamic inventory, order, and checkout prompts
 * should call ResponsesAgent directly and bypass this wrapper.
 */
public final class LangCacheAgent {
    public static final long CATALOG_RESPONSE_TTL_MILLIS = 30_000;
    private static final String CACHE_PROMPT_PREFIX =
            "ai-shopping-platform catalog-only product name and listed price v1: ";

    public enum Status { HIT, MISS_STORED, MISS_NOT_STORED, LOOKUP_ERROR, STORE_ERROR }
    public record Result(ResponsesAgent.Result agentResult, Status status, String detail) {}

    private final LangCacheClient cache;
    private final double similarityThreshold;

    public LangCacheAgent(LangCacheClient cache, double similarityThreshold) {
        if (!Double.isFinite(similarityThreshold) || similarityThreshold < 0 || similarityThreshold > 1) {
            throw new IllegalArgumentException("similarity threshold must be between 0 and 1");
        }
        this.cache = cache;
        this.similarityThreshold = similarityThreshold;
    }

    public Result run(ResponsesAgent agent, Session session, String prompt, String scope, int maxModelCalls) throws IOException {
        if (!agent.allowedToolNames().equals(Set.of("search_products"))) {
            throw new IllegalArgumentException("LangCacheAgent requires an agent that exposes only search_products");
        }
        if (scope == null || scope.isBlank()) throw new IllegalArgumentException("A cache scope is required");
        String cachePrompt = CACHE_PROMPT_PREFIX + prompt.strip();
        Map<String, Object> attributes = Map.of("scope", scope);
        Optional<String> cached;
        try {
            cached = cache.search(cachePrompt, attributes, similarityThreshold);
        } catch (IOException | IllegalArgumentException e) {
            ResponsesAgent.Result generated = agent.run(session, prompt, maxModelCalls);
            return new Result(generated, Status.LOOKUP_ERROR, e.getMessage());
        }
        if (cached.isPresent()) {
            return new Result(new ResponsesAgent.Result(cached.get(), ResponsesAgent.Usage.ZERO, 0, 0, false), Status.HIT, "");
        }

        ResponsesAgent.Result generated = agent.run(session, prompt, maxModelCalls);
        // Only cache a completed answer after the catalog-only agent has called its database tool.
        if (generated.text().isBlank() || generated.toolCalls() == 0 || generated.hadToolError()) {
            return new Result(generated, Status.MISS_NOT_STORED, "agent did not produce a tool-backed answer");
        }
        try {
            cache.store(cachePrompt, generated.text(), attributes, CATALOG_RESPONSE_TTL_MILLIS);
            return new Result(generated, Status.MISS_STORED, "");
        } catch (IOException | IllegalArgumentException e) {
            return new Result(generated, Status.STORE_ERROR, e.getMessage());
        }
    }
}
