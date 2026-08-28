package com.fluxmes.alarm.exception;

public class AlarmNotFoundException extends RuntimeException {
    public AlarmNotFoundException(String id) {
        super("报警不存在: " + id);
    }
}
