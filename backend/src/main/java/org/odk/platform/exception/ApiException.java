package org.odk.platform.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;
    private final Object details;

    public ApiException(HttpStatus status, String message, String errorCode) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
        this.details = null;
    }

    public ApiException(HttpStatus status, String message, String errorCode, Object details) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
        this.details = details;
    }

    public static ApiException badRequest(String message, String errorCode) {
        return new ApiException(HttpStatus.BAD_REQUEST, message, errorCode);
    }

    public static ApiException badRequest(String message, String errorCode, Object details) {
        return new ApiException(HttpStatus.BAD_REQUEST, message, errorCode, details);
    }

    public static ApiException unauthorized(String message, String errorCode) {
        return new ApiException(HttpStatus.UNAUTHORIZED, message, errorCode);
    }

    public static ApiException forbidden(String message, String errorCode) {
        return new ApiException(HttpStatus.FORBIDDEN, message, errorCode);
    }

    public static ApiException notFound(String message, String errorCode) {
        return new ApiException(HttpStatus.NOT_FOUND, message, errorCode);
    }

    public static ApiException conflict(String message, String errorCode) {
        return new ApiException(HttpStatus.CONFLICT, message, errorCode);
    }

    public static ApiException internal(String message, String errorCode) {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, message, errorCode);
    }

    public static ApiException unprocessableEntity(String message, String errorCode) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, message, errorCode);
    }

    public static ApiException unprocessableEntity(String message, String errorCode, Object details) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, message, errorCode, details);
    }
}