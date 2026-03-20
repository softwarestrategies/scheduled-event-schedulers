package io.softwarestrategies.scheduledevent.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * Configures the data sources and routing logic.
 */
@Configuration
public class DatabaseRoutingConfig {

    @Bean(name = "primaryDataSource")
    @ConfigurationProperties(prefix = "spring.datasource.hikari")
    public DataSource primaryDataSource(org.springframework.core.env.Environment env) {
        return buildDataSource(
                env.getProperty("spring.datasource.url"),
                env.getProperty("spring.datasource.username"),
                env.getProperty("spring.datasource.password"),
                env.getProperty("spring.datasource.driver-class-name")
        );
    }

    @Bean(name = "replicaDataSource")
    @ConfigurationProperties(prefix = "spring.datasource.replica.hikari")
    public DataSource replicaDataSource(org.springframework.core.env.Environment env) {
        String replicaUrl = env.getProperty("spring.datasource.replica.url");
        if (replicaUrl != null) {
            return buildDataSource(
                    replicaUrl,
                    env.getProperty("spring.datasource.replica.username"),
                    env.getProperty("spring.datasource.replica.password"),
                    env.getProperty("spring.datasource.replica.driver-class-name")
            );
        } else {
            // Fallback to primary if replica is not configured
            return buildDataSource(
                    env.getProperty("spring.datasource.url"),
                    env.getProperty("spring.datasource.username"),
                    env.getProperty("spring.datasource.password"),
                    env.getProperty("spring.datasource.driver-class-name")
            );
        }
    }

    private DataSource buildDataSource(String url, String username, String password, String driver) {
        HikariDataSource ds = DataSourceBuilder.create().type(HikariDataSource.class).build();
        if (url != null) ds.setJdbcUrl(url);
        if (username != null) ds.setUsername(username);
        if (password != null) ds.setPassword(password);
        if (driver != null) ds.setDriverClassName(driver);
        return ds;
    }

    @Bean(name = "routingDataSource")
    public DataSource routingDataSource(
            @Qualifier("primaryDataSource") DataSource primaryDataSource,
            @Qualifier("replicaDataSource") DataSource replicaDataSource) {

        RoutingDataSource routingDataSource = new RoutingDataSource();
        Map<Object, Object> targetDataSources = new HashMap<>();
        targetDataSources.put(DataSourceType.PRIMARY, primaryDataSource);
        targetDataSources.put(DataSourceType.REPLICA, replicaDataSource);

        routingDataSource.setTargetDataSources(targetDataSources);
        routingDataSource.setDefaultTargetDataSource(primaryDataSource);

        return routingDataSource;
    }

    @Bean(name = "dataSource")
    @Primary
    public DataSource dataSource(@Qualifier("routingDataSource") DataSource routingDataSource) {
        // Use LazyConnectionDataSourceProxy to evaluate the actual DataSource
        // right when a connection is actually requested. This ensures the 
        // ThreadLocal has been set by our Aspect before the connection is acquired.
        return new LazyConnectionDataSourceProxy(routingDataSource);
    }
}
