package com.example.aurorarouting.mode;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.stereotype.Component;

@Component
@Endpoint(id = "dbmode")
public class DbModeEndpoint {

    private final DbModeCoordinator coordinator;

    public DbModeEndpoint(DbModeCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @ReadOperation
    public DbModeSnapshot status() {
        return coordinator.snapshot();
    }
}
