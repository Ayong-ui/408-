package com.xiuxian.common;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, String>> api(ApiException e) {
        return error(e.status().value(), e.getMessage());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    public ResponseEntity<Map<String, String>> badInput(Exception e) {
        return error(HttpStatus.BAD_REQUEST.value(), "请求格式有误");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> unexpected(Exception e) {
        // Spring 自己的异常（404 静态资源、405 方法不对等）保留原状态码
        if (e instanceof ErrorResponse er) {
            int status = er.getStatusCode().value();
            return error(status, status == 404 ? "此处乃虚空" : "请求有误（" + status + "）");
        }
        log.error("未处理的异常", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR.value(), "灵脉紊乱：" + e.getMessage());
    }

    private static ResponseEntity<Map<String, String>> error(int status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message == null ? "未知错误" : message));
    }
}
