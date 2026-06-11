package com.security.security.event;

import com.security.security.domain.Response;
import com.security.security.exception.ApiException;
import com.security.security.exception.ResourceNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.*;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;


import static com.security.security.utils.RequestUtils.getResponse;
import static org.springframework.http.HttpStatus.*;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(com.security.security.exception.TooManyRequestsException.class)
    public ResponseEntity<Response> handleTooManyRequestsException(com.security.security.exception.TooManyRequestsException ex, HttpServletRequest request) {
        log.warn("Too many requests error: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(getResponse(request, Collections.emptyMap(), ex.getMessage(), HttpStatus.TOO_MANY_REQUESTS));
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<Response> handleAccessDeniedException(org.springframework.security.access.AccessDeniedException ex, HttpServletRequest request) {
        log.warn("Access denied error: {}", ex.getMessage());
        return ResponseEntity.status(FORBIDDEN)
                .body(getResponse(request, Collections.emptyMap(), ex.getMessage(), FORBIDDEN));
    }

    // 1. Xử lý lỗi logic nghiệp vụ (RuntimeException, ApiException)
    @ExceptionHandler({RuntimeException.class, ApiException.class})
    public ResponseEntity<Response> handleRuntimeException(RuntimeException ex, HttpServletRequest request) {
        log.error("Runtime/API Error: {}", ex.getMessage());
        // Trả về lỗi 400 Bad Request kèm thông báo lỗi cụ thể từ Service ném ra
        return ResponseEntity.badRequest()
                .body(getResponse(request, Collections.emptyMap(), ex.getMessage(), BAD_REQUEST));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Response> handleResourceNotFoundException(ResourceNotFoundException ex, HttpServletRequest request) {
        log.warn("Không tìm thấy tài nguyên: {}", ex.getMessage());
        return ResponseEntity.status(NOT_FOUND)
                .body(getResponse(request, Collections.emptyMap(), ex.getMessage(), NOT_FOUND));
    }

    // 2. Xử lý sai thông tin đăng nhập (Sai email hoặc pass)
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Response> handleBadCredentialsException(BadCredentialsException ex, HttpServletRequest request) {
        log.error("BadCredentialsException caught - Message: '{}'", ex.getMessage());

        Map<String, Object> errorData = new HashMap<>();
        String message;
        String errorCode;

        // Phân biệt giữa email không tồn tại và mật khẩu sai
        if (ex.getMessage() != null && ex.getMessage().contains("Tên đăng nhập không khớp")) {
            log.info("Detected EMAIL_NOT_FOUND error");
            message = "Tên đăng nhập không khớp";
            errorCode = "AUTH_004"; // Email không tồn tại
            errorData.put("error", "EMAIL_NOT_FOUND");
        } else {
            log.info("Detected INVALID_CREDENTIALS error");
            message = "Email hoặc mật khẩu không đúng. Vui lòng thử lại";
            errorCode = "AUTH_001"; // Mật khẩu sai hoặc lỗi chung
            errorData.put("error", "INVALID_CREDENTIALS");
        }

        errorData.put("errorCode", errorCode);
        errorData.put("errorType", "AUTHENTICATION");

        log.info("Returning error response - Code: {}, Message: {}", errorCode, message);
        return ResponseEntity.status(UNAUTHORIZED)
                .body(getResponse(request, errorData, message, UNAUTHORIZED));
    }

    // 3. Xử lý tài khoản bị khóa
    @ExceptionHandler(LockedException.class)
    public ResponseEntity<Response> handleLockedException(LockedException ex, HttpServletRequest request) {
        log.warn("Tài khoản bị khóa: {}", ex.getMessage());

        Map<String, Object> errorData = new HashMap<>();
        errorData.put("errorCode", "AUTH_002");
        errorData.put("error", "ACCOUNT_LOCKED");
        errorData.put("errorType", "AUTHENTICATION");

        return ResponseEntity.status(LOCKED)
                .body(getResponse(request, errorData,
                        "Tài khoản của bạn đã bị khóa. Vui lòng liên hệ quản trị viên.", LOCKED));
    }

    // 4. Xử lý tài khoản bị vô hiệu hóa (Disabled) hoặc chưa duyệt (Pending)
    @ExceptionHandler(DisabledException.class)
    public ResponseEntity<Response> handleDisabledException(DisabledException ex, HttpServletRequest request) {
        log.error("DisabledException caught - Message: '{}'", ex.getMessage());

        Map<String, Object> errorData = new HashMap<>();
        String message;
        String errorCode;

        // Kiểm tra nếu là lỗi pending approval
        if (ex.getMessage() != null && ex.getMessage().contains("đang đợi admin duyệt")) {
            log.info("Detected PENDING_APPROVAL error");
            message = ex.getMessage();
            errorCode = "AUTH_006"; // New error code for pending approval
            errorData.put("error", "PENDING_APPROVAL");
        } else {
            log.info("Detected ACCOUNT_DISABLED error");
            message = "Tài khoản chưa được kích hoạt. Vui lòng kiểm tra email để kích hoạt tài khoản";
            errorCode = "AUTH_003";
            errorData.put("error", "ACCOUNT_DISABLED");
        }

        errorData.put("errorCode", errorCode);
        errorData.put("errorType", "AUTHENTICATION");

        log.info("Returning error response - Code: {}, Message: {}", errorCode, message);
        return ResponseEntity.status(FORBIDDEN)
                .body(getResponse(request, errorData, message, FORBIDDEN));
    }

    // 5. Xử lý các lỗi xác thực chung khác (AuthenticationException)
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Response> handleAuthenticationException(AuthenticationException ex, HttpServletRequest request) {
        log.error("Lỗi xác thực chung: {}", ex.getMessage());
        return ResponseEntity.status(UNAUTHORIZED)
                .body(getResponse(request, Collections.emptyMap(),
                        "Xác thực thất bại. Vui lòng đăng nhập lại.", UNAUTHORIZED));
    }

    // 6. Xử lý lỗi Validate dữ liệu (như @NotBlank, @Size...)
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Response> handleValidationException(MethodArgumentNotValidException ex, HttpServletRequest request) {
        // Gom tất cả lỗi validate thành một Map đơn giản
        Map<String, String> errors = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(FieldError::getField, FieldError::getDefaultMessage, (v1, v2) -> v1));

        log.warn("Dữ liệu không hợp lệ: {}", errors);
        return ResponseEntity.badRequest()
                .body(getResponse(request, Map.of("errors", errors),
                        "Dữ liệu đầu vào không hợp lệ", BAD_REQUEST));
    }

    // 7. Xử lý lỗi hệ thống không mong muốn (Lỗi code, NullPointer...)
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Response> handleGenericException(Exception ex, HttpServletRequest request) {
        log.error("Lỗi hệ thống không mong muốn: ", ex); // Log full stack trace để debug
        return ResponseEntity.status(INTERNAL_SERVER_ERROR)
                .body(getResponse(request, Collections.emptyMap(),
                        "Đã xảy ra lỗi hệ thống. Vui lòng thử lại sau.", INTERNAL_SERVER_ERROR));
    }
}