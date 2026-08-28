package com.fluxmes.alarm.exception;

import com.fluxmes.alarm.domain.AlarmStatus;

public class IllegalAlarmTransitionException extends RuntimeException {
    public IllegalAlarmTransitionException(AlarmStatus from, AlarmStatus to) {
        super("非法状态流转: " + from.wire() + " → " + to.wire());
    }
}
