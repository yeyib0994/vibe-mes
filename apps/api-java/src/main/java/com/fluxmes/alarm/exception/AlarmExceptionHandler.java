package com.fluxmes.alarm.exception;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一错误响应，返回结构化 body，前端据此降级提示（constitution「前端错误须有降级展示」）。
 */
@RestControllerAdvice
public class AlarmExceptionHandler {

    @ExceptionHandler(AlarmNotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(AlarmNotFoundException ex) {
        return body(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(IllegalAlarmTransitionException.class)
    public ResponseEntity<Map<String, Object>> conflict(IllegalAlarmTransitionException ex) {
        return body(HttpStatus.CONFLICT, ex.getMessage());
    }

    private ResponseEntity<Map<String, Object>> body(HttpStatus status, String message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("timestamp", OffsetDateTime.now().toString());
        map.put("status", status.value());
        map.put("error", status.getReasonPhrase());
        map.put("message", message);
        return ResponseEntity.status(status).body(map);
    }
}
