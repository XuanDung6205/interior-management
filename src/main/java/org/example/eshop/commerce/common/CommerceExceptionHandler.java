package org.example.eshop.commerce.common;

import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice(basePackages = "org.example.eshop.commerce")
public class CommerceExceptionHandler {
    @ExceptionHandler(CommerceException.class)
    public ResponseEntity<ApiError> commerce(CommerceException exception) {
        return ResponseEntity.status(exception.getStatus()).contentType(MediaType.APPLICATION_JSON).body(new ApiError(exception.getCode(),exception.getMessage(),exception.getFieldErrors()));
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> validation(MethodArgumentNotValidException exception) {
        Map<String,String> fields = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error -> fields.putIfAbsent(error.getField(),error.getDefaultMessage() == null ? "Invalid value" : error.getDefaultMessage()));
        return ResponseEntity.badRequest().body(new ApiError("VALIDATION_ERROR","Please correct the invalid fields.",fields));
    }
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> constraint(ConstraintViolationException exception) {
        Map<String,String> fields = new LinkedHashMap<>();
        exception.getConstraintViolations().forEach(error -> fields.put(error.getPropertyPath().toString(),error.getMessage()));
        return ResponseEntity.badRequest().body(new ApiError("VALIDATION_ERROR","Please correct the invalid fields.",fields));
    }
    @ExceptionHandler({HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> malformed(Exception exception) {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_REQUEST","Request contains invalid or unsupported values."));
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> integrity(DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getErrorCode() == 1451) {
                return ResponseEntity.status(409).body(new ApiError("RESOURCE_IN_USE","This resource is referenced and cannot be deleted. Deactivate it instead."));
            }
        }
        return ResponseEntity.status(409).body(new ApiError("DATA_CONFLICT","The request conflicts with current data. Refresh and try again."));
    }
}
