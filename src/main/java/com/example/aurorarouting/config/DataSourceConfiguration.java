package com.example.aurorarouting.config;

import com.example.aurorarouting.mode.DbModeCoordinator;
import com.example.aurorarouting.mode.DbRoute;
import com.zaxxer.hikari.HikariDataSource;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration(proxyBeanMethods = false)
public class DataSourceConfiguration {

    @Bean
    @ConfigurationProperties("app.datasource.writer")
    DataSourceProperties writerProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @ConfigurationProperties("app.datasource.writer.hikari")
    HikariDataSource writerDataSource(
            @Qualifier("writerProperties") DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean
    @ConfigurationProperties("app.datasource.reader")
    DataSourceProperties readerProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @ConfigurationProperties("app.datasource.reader.hikari")
    HikariDataSource readerDataSource(
            @Qualifier("readerProperties") DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean
    @Primary
    DataSource dataSource(
            DbModeCoordinator coordinator,
            @Qualifier("writerDataSource") DataSource writer,
            @Qualifier("readerDataSource") DataSource reader) {

        MaintenanceRoutingDataSource routing = new MaintenanceRoutingDataSource(coordinator);
        routing.setTargetDataSources(Map.of(
                DbRoute.WRITER, writer,
                DbRoute.READER, reader));
        routing.setDefaultTargetDataSource(reader);
        routing.afterPropertiesSet();
        return routing;
    }
}
