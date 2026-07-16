package com.example.assetinspection.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;

import javax.validation.ConstraintViolationException;
import java.util.stream.Collectors;

/** 把 Java 异常转成稳定、可读的 JSON，而不是把堆栈暴露给调用方。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiError> handleBusiness(BusinessException ex, ServletWebRequest request) {
        return ResponseEntity.status(ex.getStatus())
                .body(new ApiError(ex.getCode(), ex.getMessage(), request.getRequest().getRequestURI()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleBodyValidation(MethodArgumentNotValidException ex,
                                                          ServletWebRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("；"));
        return ResponseEntity.badRequest()
                .body(new ApiError("INVALID_REQUEST", message, request.getRequest().getRequestURI()));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleParameterValidation(ConstraintViolationException ex,
                                                               ServletWebRequest request) {
        return ResponseEntity.badRequest()
                .body(new ApiError("INVALID_PARAMETER", ex.getMessage(), request.getRequest().getRequestURI()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, ServletWebRequest request) {
        // 服务端记录完整堆栈，客户端只收到通用描述，避免泄露 SQL、账号等内部信息。
        LOGGER.error("未处理异常，path={}", request.getRequest().getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("INTERNAL_ERROR",
                        "服务内部错误，请查看应用日志和 ShardingSphere Actual SQL",
                        request.getRequest().getRequestURI()));
    }
}
