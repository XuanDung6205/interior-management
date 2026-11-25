package org.example.eshop.commerce.common;
import org.springframework.http.HttpStatus;
import java.util.Map;
public class CommerceException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final Map<String,String> fieldErrors;
    private CommerceException(HttpStatus status,String code,String message,Map<String,String> fields) {
        super(message); this.status=status; this.code=code; this.fieldErrors=Map.copyOf(fields);
    }
    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
    public Map<String,String> getFieldErrors() { return fieldErrors; }
    public static CommerceException badRequest(String code,String message) { return new CommerceException(HttpStatus.BAD_REQUEST,code,message,Map.of()); }
    public static CommerceException notFound(String message) { return new CommerceException(HttpStatus.NOT_FOUND,"NOT_FOUND",message,Map.of()); }
    public static CommerceException notFound(String code,String message) { return new CommerceException(HttpStatus.NOT_FOUND,code,message,Map.of()); }
    public static CommerceException conflict(String code,String message) { return new CommerceException(HttpStatus.CONFLICT,code,message,Map.of()); }
    public static CommerceException forbidden(String message) { return new CommerceException(HttpStatus.FORBIDDEN,"FORBIDDEN",message,Map.of()); }
    public static CommerceException invalid(Map<String,String> fields) { return new CommerceException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Please correct the invalid fields.",fields); }
}
