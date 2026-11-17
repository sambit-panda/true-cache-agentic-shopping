package agentorders;

import java.util.List;

/**
 * The only Redis operations the cache needs. RespClient implements it with plain sockets; in
 * production adapt Lettuce or Jedis behind this interface (EVAL and GET are all that is used).
 */
public interface RedisOps {
    /** EVAL script numKeys keys... args... Returns String, Long, List or null (RESP mapping). */
    Object eval(String script, List<String> keys, List<String> args);

    String get(String key);
}
