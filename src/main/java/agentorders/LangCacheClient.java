package agentorders;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Small dependency-free client for the Redis LangCache REST API. */
@SuppressWarnings("unchecked")
public final class LangCacheClient {
    private static final String BASE_URL_ENV = "LANGCACHE_BASE_URL";
    private static final String CACHE_ID_ENV = "LANGCACHE_CACHE_ID";
    private static final String API_KEY_ENV = "LANGCACHE_API_KEY";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private final String baseUrl;
    private final String cacheId;
    private final String apiKey;

    private LangCacheClient(String baseUrl, String cacheId, String apiKey) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.cacheId = cacheId;
        this.apiKey = apiKey;
    }

    /** Returns empty when LangCache is not configured, and rejects partial configuration. */
    public static Optional<LangCacheClient> fromEnvironment() {
        String baseUrl = System.getenv(BASE_URL_ENV);
        String cacheId = System.getenv(CACHE_ID_ENV);
        String apiKey = System.getenv(API_KEY_ENV);
        boolean allMissing = blank(baseUrl) && blank(cacheId) && blank(apiKey);
        if (allMissing) return Optional.empty();
        if (blank(baseUrl) || blank(cacheId) || blank(apiKey)) {
            throw new IllegalStateException("Set LANGCACHE_BASE_URL, LANGCACHE_CACHE_ID, and LANGCACHE_API_KEY together");
        }
        URI uri;
        try {
            uri = URI.create(baseUrl);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("LANGCACHE_BASE_URL must be an absolute HTTP(S) URL");
        }
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null) {
            throw new IllegalStateException("LANGCACHE_BASE_URL must be an absolute HTTP(S) URL");
        }
        return Optional.of(new LangCacheClient(baseUrl, cacheId, apiKey));
    }

    /** Search returns the first matching response, or empty on a cache miss. */
    public Optional<String> search(String prompt, Map<String, Object> attributes, double threshold) throws IOException {
        Map<String, Object> body = Map.of(
                "prompt", prompt,
                "attributes", attributes,
                "similarityThreshold", threshold,
                "searchStrategies", List.of("semantic"));
        Object parsed = MiniJson.read(post("/entries/search", MiniJson.write(body), "application/json"));
        if (!(parsed instanceof List<?> entries)) throw new IOException("LangCache search returned an unexpected response");
        if (entries.isEmpty()) return Optional.empty();
        Object first = entries.get(0);
        if (!(first instanceof Map<?, ?> entry) || !(entry.get("response") instanceof String response)) {
            throw new IOException("LangCache search returned an entry without a response");
        }
        return Optional.of(response);
    }

    /** Stores a response with an explicit short TTL for the demo's catalog data. */
    public void store(String prompt, String response, Map<String, Object> attributes, long ttlMillis) throws IOException {
        Map<String, Object> body = Map.of(
                "prompt", prompt,
                "response", response,
                "attributes", attributes,
                "ttlMillis", ttlMillis);
        post("/entries", MiniJson.write(body), "application/json");
    }

    private String post(String endpoint, String body, String contentType) throws IOException {
        String encodedCacheId = URLEncoder.encode(cacheId, StandardCharsets.UTF_8).replace("+", "%20");
        URI uri = URI.create(baseUrl + "/v1/caches/" + encodedCacheId + endpoint);
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(8))
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json")
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<String> response = http.send(req, HttpResponse.BodyHandlers.ofString());
            int code = response.statusCode();
            if (code / 100 != 2) throw new IOException("LangCache HTTP " + code);
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("LangCache request interrupted", e);
        }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
