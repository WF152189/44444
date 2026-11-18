package com.example.aurorarouting.config;

import com.example.aurorarouting.mode.DbModeCoordinator;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

public class MaintenanceRoutingDataSource extends AbstractRoutingDataSource {

    private final DbModeCoordinator coordinator;

    public MaintenanceRoutingDataSource(DbModeCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @Override
    protected Object determineCurrentLookupKey() {
        return coordinator.currentRoute();
    }
}
