package com.example.aurorarouting.mode;

public enum RuntimeState {
    STARTING,
    READ_WRITE_READY,
    READ_ONLY_WAITING,
    READ_ONLY_READY,
    SWITCHING,
    ERROR
}
