package com.example.aurorarouting.mode;

public record DbModeSnapshot(
        DesiredMode desiredMode,
        DbRoute actualRoute,
        RuntimeState state,
        long switchVersion,
        String lastError) {
}
