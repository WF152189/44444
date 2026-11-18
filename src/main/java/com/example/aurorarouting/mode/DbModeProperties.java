package com.example.aurorarouting.mode;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.db-mode")
public record DbModeProperties(
        String parameterName,
        Duration pollInterval,
        Duration drainTimeout) {
}
