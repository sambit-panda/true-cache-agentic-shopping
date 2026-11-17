package agentorders;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Read-only Oracle exercise against a self-hosted open-weight model. */
public final class LocalModelOracleDemo {
    private static final String DEFAULT_PROMPT =
            "Find the cheapest speaker in audio, check its exact current stock, and report its name, price, and stock.";
    private static final String READ_ONLY_POLICY = AgentPolicy.INSTRUCTIONS + """


            # This evaluation is read-only
            Only read tools are available in this run. Never claim to place, change or cancel an order.
            Answer the shopper's question from the tool results. Do not offer a purchase or ask whether the shopper wants to buy.
            Report only the requested product name, price and current stock.
            """;
    private static final String CATALOG_ONLY_POLICY = AgentPolicy.INSTRUCTIONS + """


            # This run is limited to stable catalog data
            Use only search_products. It returns product names and listed prices.
            Never claim or imply current stock, availability, order status, or checkout state.
            Report the requested product name and listed price using the tool result.
            """;

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Set " + name + " in the environment");
        return value;
    }

    public static void main(String[] args) throws Exception {
        String endpoint = System.getenv().getOrDefault("LLM_RESPONSES_URL", "http://127.0.0.1:8080/v1/responses");
        String model = System.getenv().getOrDefault("LLM_MODEL", "qwen3-4b");
        boolean langCacheDemo = args.length > 0 && "--langcache-demo".equals(args[0]);
        required("ORA_USER");
        required("ORA_PASSWORD");
        String primaryUrl = required("ORA_PRIMARY_URL");
        String cacheUrl = required("ORA_CACHE_URL");
        String prompt = langCacheDemo || args.length == 0 ? DEFAULT_PROMPT : String.join(" ", args);

        Optional<LangCacheClient> langCache = langCacheDemo ? LangCacheClient.fromEnvironment() : Optional.empty();
        if (langCacheDemo && langCache.isEmpty()) {
            throw new IllegalStateException("Set LANGCACHE_BASE_URL, LANGCACHE_CACHE_ID, and LANGCACHE_API_KEY to run --langcache-demo");
        }

        Db db = new OracleJdbcDb(OracleMain.pool(primaryUrl), OracleMain.pool(cacheUrl), 1000);
        ToolRuntime runtime = new ToolRuntime(db, new EvalNoAppCache());
        Collection<Tool> catalogTools = runtime.tools().stream()
                .filter(t -> t.name().equals("search_products")).toList();
        Collection<Tool> liveReadTools = runtime.tools().stream()
                .filter(t -> t.name().equals("search_products") || t.name().equals("check_stock")).toList();
        ResponsesAgent.Transport transport = new ResponsesAgent.HttpTransport(endpoint);
        ResponsesAgent catalogAgent = new ResponsesAgent(transport, runtime, model, CATALOG_ONLY_POLICY, 768, catalogTools);
        ResponsesAgent liveAgent = new ResponsesAgent(transport, runtime, model, READ_ONLY_POLICY, 768, liveReadTools);
        Session session = Session.forCustomer(1);

        System.out.println("provider: self-hosted open-weight model; no hosted-model API key");
        System.out.printf("endpoint: %s%nmodel: %s%n", endpoint, model);
        System.out.println("database reads: APP_TC True Cache; exact stock checks: APP primary");
        System.out.println("writes: disabled; application result cache: bypassed for this evaluation");

        if (langCacheDemo) {
            double threshold = Double.parseDouble(System.getenv().getOrDefault("LANGCACHE_SIMILARITY_THRESHOLD", "0.90"));
            LangCacheAgent cachedCatalogAgent = new LangCacheAgent(langCache.orElseThrow(), threshold);
            int catalogPrefixChars = CATALOG_ONLY_POLICY.length() + ToolSchemas.responsesToolsJson(catalogTools).length();
            System.out.println("catalog semantic cache: Redis LangCache before the local agent; True Cache on LangCache misses");
            System.out.printf("catalog model prefix: %d chars (roughly %d tokens)%n", catalogPrefixChars, catalogPrefixChars / 4);

            List<String> catalogPrompts = List.of(
                    "Find the lowest-priced speaker in audio and give its name and listed price.",
                    "Which audio speaker is the least expensive? Tell me its product name and listed price.");
            for (int run = 0; run < catalogPrompts.size(); run++) {
                long start = System.nanoTime();
                LangCacheAgent.Result cachedResult = cachedCatalogAgent.run(catalogAgent, session,
                        catalogPrompts.get(run), "shopping-catalog-v1-audio-speaker-cheapest", 6);
                long elapsedMs = (System.nanoTime() - start) / 1_000_000;
                printRun("catalog " + (run + 1), cachedResult.agentResult(), elapsedMs,
                        "langcache=" + cachedResult.status().name().toLowerCase());
                System.out.println("assistant: " + cachedResult.agentResult().text().strip());
                if (!cachedResult.detail().isBlank()) System.out.println("langcache detail: " + cachedResult.detail());
            }

            long start = System.nanoTime();
            ResponsesAgent.Result liveResult = liveAgent.run(session, prompt, 6);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            printRun("live inventory (LangCache bypass)", liveResult, elapsedMs, "");
            System.out.println("assistant: " + liveResult.text().strip());
        } else {
            int prefixChars = READ_ONLY_POLICY.length() + ToolSchemas.responsesToolsJson(liveReadTools).length();
            System.out.println("model-side prefix: static shopping policy + read-only tool schemas");
            System.out.printf("prefix size: %d chars (roughly %d tokens)%n", prefixChars, prefixChars / 4);
            for (int run = 1; run <= 2; run++) {
                long start = System.nanoTime();
                ResponsesAgent.Result result = liveAgent.run(session, prompt, 6);
                long elapsedMs = (System.nanoTime() - start) / 1_000_000;
                printRun("run " + run, result, elapsedMs, "langcache=bypassed");
                System.out.println("assistant: " + result.text().strip());
            }
        }

        System.out.println("tool routes:");
        runtime.routeCounts().forEach((name, count) -> System.out.printf("  %-58s %d%n", name, count));
        System.out.println("tool cache: " + runtime.cache().stats());
    }

    private static void printRun(String label, ResponsesAgent.Result result, long elapsedMs, String cacheStatus) {
        ResponsesAgent.Usage usage = result.usage();
        System.out.printf("%s: %s model_calls=%d tool_calls=%d elapsed_ms=%d input_tokens=%d cached_tokens=%d output_tokens=%d%n",
                label, cacheStatus, result.modelCalls(), result.toolCalls(), elapsedMs,
                usage.inputTokens(), usage.cachedTokens(), usage.outputTokens());
    }
}
