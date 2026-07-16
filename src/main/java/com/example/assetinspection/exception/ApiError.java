package com.example.assetinspection.exception;

import java.time.LocalDateTime;

/** 统一错误响应，便于 curl 实验时快速判断错误原因。 */
public class ApiError {

    private final String code;
    private final String message;
    private final String path;
    private final LocalDateTime timestamp;

    public ApiError(String code, String message, String path) {
        this.code = code;
        this.message = message;
        this.path = path;
        this.timestamp = LocalDateTime.now();
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public String getPath() {
        return path;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }
}
