package com.security.security.utils;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.security.security.domain.Response;
import com.security.security.exception.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.util.BiConsumer;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;
import java.util.function.BiFunction;

import static java.util.Collections.emptyMap;
//import static org.apache.commons.lang3.exception.ExceptionUtils.getRootCauseMessage;
import static org.apache.logging.log4j.util.Strings.EMPTY;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;
import static org.springframework.http.HttpStatus.FORBIDDEN;
public class RequestUtils {
    private static final BiConsumer<HttpServletResponse, Response> writeResponse = ((httpServletResponse, response) -> {
        try {
            var outputStream = httpServletResponse.getOutputStream();
            new ObjectMapper().writeValue(outputStream, response);
            outputStream.flush();
        }catch (Exception e) {
            throw new ApiException(e.getMessage());
        }
    });
    private static final BiFunction<Exception, HttpStatus, String> errorReason = (exception, httpStatus)->{
        if(httpStatus.isSameCodeAs(FORBIDDEN)){return "You do not have enough permissions";}
        if(httpStatus.isSameCodeAs(UNAUTHORIZED)){return "You do not have enough permissions";}
        if(exception instanceof DisabledException || exception instanceof LockedException|| exception instanceof BadCredentialsException || exception instanceof CredentialsExpiredException || exception instanceof ApiException){
            return exception.getMessage();
        }
        if(httpStatus.is5xxServerError()) {return "An internal server error occurred"; }
        else {
            return "An error occurred, Please try again";
        }
    };


    public static Response getResponse(HttpServletRequest request , Map<?, ?> data, String message , HttpStatus status) {
        return new Response(LocalDateTime.now().toString(), status.value() ,request.getRequestURI(), HttpStatus.valueOf(status.value()) ,message, EMPTY , data);
    }
//    public static void   handleErrorResponse(HttpServletRequest request, HttpServletResponse response, Exception e){
//        if(e instanceof ResponseStatusException){
//            var apiResponse  = getErrorResponse(request,response, e , FORBIDDEN);
//            writeResponse.accept(response, apiResponse);
//        }
//    }
//
//
//    private static Response getErrorResponse(HttpServletRequest request, HttpServletResponse response, Exception e, HttpStatus httpStatus) {
//        response.setContentType(APPLICATION_JSON_VALUE);
//        response.setStatus(httpStatus.value());
////        return new Response(LocalDateTime.now().toString(),httpStatus.value(), request.getRequestURI(), HttpStatus.valueOf(httpStatus.value()),  errorReason.apply(e,httpStatus), getRootCauseMessage(e),emptyMap() );
//        return null;
//    }

    public static void handleErrorResponse(HttpServletRequest request, HttpServletResponse response, Exception e) {
        HttpStatus status;
        Map<String, Object> errorData = new java.util.HashMap<>();
        String message;

        // Determine status and error details based on exception type
        if (e instanceof BadCredentialsException) {

            String errorCode;

            // ==> BẮT LỖI "TÊN ĐĂNG NHẬP KHÔNG KHỚP" Ở ĐÂY
            if (e.getMessage() != null && e.getMessage().contains("Tên đăng nhập không khớp")) {
                message = "Tên đăng nhập không khớp";
                errorCode = "AUTH_004"; // Email không tồn tại
                errorData.put("error", "EMAIL_NOT_FOUND");
            } else {
                message = "Email hoặc mật khẩu không đúng. Vui lòng thử lại";
                errorCode = "AUTH_001"; // Mật khẩu sai hoặc lỗi chung
                errorData.put("error", "INVALID_CREDENTIALS");
            }

            errorData.put("errorCode", errorCode);
            errorData.put("errorType", "AUTHENTICATION");
            status = HttpStatus.UNAUTHORIZED;
        } else if (e instanceof LockedException) {
            status = HttpStatus.LOCKED;
            errorData.put("errorCode", "AUTH_002");
            errorData.put("error", "ACCOUNT_LOCKED");
            errorData.put("errorType", "AUTHENTICATION");
            message = "Tài khoản của bạn đã bị khóa. Vui lòng liên hệ quản trị viên";
        }

        else if (e instanceof DisabledException) {

            String errorCode;
            if (e.getMessage() != null && e.getMessage().contains("đang đợi admin duyệt")) {
                // ==> BẮT LỖI CHỜ DUYỆT Ở ĐÂY
                message = e.getMessage(); // "Tài khoản đang đợi admin duyệt..."
                errorCode = "AUTH_006";
                errorData.put("error", "PENDING_APPROVAL");
            } else {
                message = "Tài khoản chưa được kích hoạt. Vui lòng kiểm tra email để kích hoạt tài khoản";
                errorCode = "AUTH_003";
                errorData.put("error", "ACCOUNT_DISABLED");
            }

            errorData.put("errorCode", errorCode);
            errorData.put("errorType", "AUTHENTICATION");
            status = HttpStatus.FORBIDDEN;
        }

        else if (e instanceof CredentialsExpiredException) {
            status = UNAUTHORIZED;
            errorData.put("errorCode", "AUTH_005");
            errorData.put("error", "CREDENTIALS_EXPIRED");
            errorData.put("errorType", "AUTHENTICATION");
            message = "Mật khẩu đã hết hạn. Vui lòng đặt lại mật khẩu";
        } else if (e instanceof ResponseStatusException) {
            status = FORBIDDEN;
            errorData.put("error", "FORBIDDEN");
            message = "You do not have enough permissions";
        } else {
            status = UNAUTHORIZED;
            errorData.put("error", "AUTHENTICATION_FAILED");
            message = errorReason.apply(e, status);
        }

        var apiResponse = getErrorResponse(request, response, e, status, errorData, message);
        writeResponse.accept(response, apiResponse);
    }

    private static Response getErrorResponse(HttpServletRequest request, HttpServletResponse response,
                                             Exception e, HttpStatus httpStatus,
                                             Map<String, Object> errorData, String message) {
        response.setContentType(APPLICATION_JSON_VALUE);
        response.setStatus(httpStatus.value());
        return new Response(
                LocalDateTime.now().toString(),
                httpStatus.value(),
                request.getRequestURI(),
                HttpStatus.valueOf(httpStatus.value()),
                message,
                EMPTY,
                errorData
        );
    }
}