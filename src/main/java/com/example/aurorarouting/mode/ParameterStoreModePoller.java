package com.example.aurorarouting.mode;

import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;

@Component
@EnableConfigurationProperties(DbModeProperties.class)
public class ParameterStoreModePoller {

    private static final Logger log = LoggerFactory.getLogger(ParameterStoreModePoller.class);

    private final SsmClient ssm;
    private final DbModeProperties properties;
    private final DbModeCoordinator coordinator;
    private final JdbcTemplate writerJdbc;

    public ParameterStoreModePoller(
            SsmClient ssm,
            DbModeProperties properties,
            DbModeCoordinator coordinator,
            @Qualifier("writerDataSource") DataSource writerDataSource) {
        this.ssm = ssm;
        this.properties = properties;
        this.coordinator = coordinator;
        this.writerJdbc = new JdbcTemplate(writerDataSource);
    }

    @Scheduled(fixedDelayString = "${app.db-mode.poll-interval:2s}")
    public void poll() {
        try {
            String value = ssm.getParameter(GetParameterRequest.builder()
                            .name(properties.parameterName())
                            .withDecryption(true)
                            .build())
                    .parameter()
                    .value()
                    .trim();

            DesiredMode requested = DesiredMode.valueOf(value);
            coordinator.applyDesiredMode(
                    requested, properties.gracePeriod(), this::isWriterHealthy);
        } catch (Exception e) {
            // 一次读取失败时保持当前路由，不能擅自恢复写入。
            log.error("Failed to refresh DB mode from Parameter Store", e);
        }
    }

    private boolean isWriterHealthy() {
        try {
            return Integer.valueOf(1).equals(writerJdbc.queryForObject("SELECT 1", Integer.class));
        } catch (Exception e) {
            log.error("Writer health check failed", e);
            return false;
        }
    }
}
