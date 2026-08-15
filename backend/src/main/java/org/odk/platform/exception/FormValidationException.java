package org.odk.platform.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Exception thrown when XForm validation fails.
 * This includes XML parsing errors, XPath errors, and schema validation errors.
 */
@Getter
public class FormValidationException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;
    private final Object details;

    public FormValidationException(String message) {
        this(HttpStatus.UNPROCESSABLE_ENTITY, message, "FORM_VALIDATION_ERROR", null);
    }

    public FormValidationException(String message, Throwable cause) {
        this(HttpStatus.UNPROCESSABLE_ENTITY, message, "FORM_VALIDATION_ERROR", null);
        initCause(cause);
    }

    public FormValidationException(HttpStatus status, String message, String errorCode) {
        this(status, message, errorCode, null);
    }

    public FormValidationException(HttpStatus status, String message, String errorCode, Object details) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
        this.details = details;
    }

    public static FormValidationException invalidXml(String message) {
        return new FormValidationException(HttpStatus.UNPROCESSABLE_ENTITY, message, "INVALID_XML");
    }

    public static FormValidationException invalidXml(String message, Throwable cause) {
        FormValidationException ex = new FormValidationException(HttpStatus.UNPROCESSABLE_ENTITY, message, "INVALID_XML");
        ex.initCause(cause);
        return ex;
    }

    public static FormValidationException invalidXPath(String message) {
        return new FormValidationException(HttpStatus.UNPROCESSABLE_ENTITY, message, "INVALID_XPATH");
    }

    public static FormValidationException invalidXPath(String message, Throwable cause) {
        FormValidationException ex = new FormValidationException(HttpStatus.UNPROCESSABLE_ENTITY, message, "INVALID_XPATH");
        ex.initCause(cause);
        return ex;
    }

    public static FormValidationException duplicateForm(String message) {
        return new FormValidationException(HttpStatus.CONFLICT, message, "DUPLICATE_FORM");
    }

    public static FormValidationException duplicateForm(String message, Object details) {
        return new FormValidationException(HttpStatus.CONFLICT, message, "DUPLICATE_FORM", details);
    }

    public static FormValidationException invalidFormStructure(String message) {
        return new FormValidationException(HttpStatus.UNPROCESSABLE_ENTITY, message, "INVALID_FORM_STRUCTURE");
    }

    public static FormValidationException invalidFormStructure(String message, Object details) {
        return new FormValidationException(HttpStatus.UNPROCESSABLE_ENTITY, message, "INVALID_FORM_STRUCTURE", details);
    }

    public static FormValidationException missingRequiredField(String fieldName) {
        return new FormValidationException(HttpStatus.UNPROCESSABLE_ENTITY, 
                "Required field missing: " + fieldName, "MISSING_REQUIRED_FIELD", fieldName);
    }

    public static FormValidationException invalidFormId(String message) {
        return new FormValidationException(HttpStatus.UNPROCESSABLE_ENTITY, message, "INVALID_FORM_ID");
    }

    public static FormValidationException invalidVersion(String message) {
        return new FormValidationException(HttpStatus.UNPROCESSABLE_ENTITY, message, "INVALID_VERSION");
    }
}