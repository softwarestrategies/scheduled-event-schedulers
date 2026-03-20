package io.softwarestrategies.scheduledevent.config;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

/**
 * Routes database queries to either primary or replica based on thread-local context.
 */
public class RoutingDataSource extends AbstractRoutingDataSource {

    @Override
    protected Object determineCurrentLookupKey() {
        DataSourceType type = DataSourceContextHolder.getDataSourceType();
        return type == null ? DataSourceType.PRIMARY : type;
    }
}
