package agentorders;

import oracle.ucp.jdbc.PoolDataSource;
import oracle.ucp.jdbc.PoolDataSourceFactory;

/**
 * Creates UCP pools for the Oracle primary and True Cache services. Built by the `oracle` Maven
 * profile (ojdbc11 + ucp11); the read-only demo entry point is LocalModelOracleDemo.
 *
 * Env: ORA_USER, ORA_PASSWORD, ORA_PRIMARY_URL (jdbc:oracle:thin:@//host:1521/primary_svc),
 *      ORA_CACHE_URL (jdbc:oracle:thin:@//host:1521/true_cache_svc)
 */
public final class OracleMain {
    static PoolDataSource pool(String url) throws Exception {
        PoolDataSource p = PoolDataSourceFactory.getPoolDataSource();
        p.setConnectionFactoryClassName("oracle.jdbc.pool.OracleDataSource");
        p.setURL(url);
        p.setUser(System.getenv("ORA_USER"));
        p.setPassword(System.getenv("ORA_PASSWORD"));
        p.setInitialPoolSize(5);
        p.setMinPoolSize(5);
        p.setMaxPoolSize(50);
        p.setMaxStatements(100);          // UCP statement cache
        return p;
    }

}
