package com.orderflow.auth.config;

import com.orderflow.auth.company.EmailAlreadyUsedException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Corpo de erro uniforme para todo o serviço — sempre as chaves {@code error} (código curto e
 * estável, em inglês) e {@code message} (texto curto e genérico); {@code fields} é adicionado
 * apenas para erro de validação. Nenhum handler inclui stack trace, nome de classe de exceção,
 * fragmento de SQL ou qualquer valor de campo de senha no corpo da resposta (T-01-27).
 *
 * <p>Trata {@code AccessDeniedException} explicitamente para que o 403 vindo de
 * {@code @PreAuthorize} (lançado durante a invocação do método do controller, dentro do
 * despacho normal do Spring MVC) tenha o mesmo formato de corpo dos demais erros — o plano
 * {@code 01-05} depende dessa uniformidade. O 401 de um request sem token nunca chega aqui: é
 * respondido pelo filtro do OAuth2 Resource Server, antes do {@code DispatcherServlet}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            fields.put(fieldError.getField(), fieldError.getDefaultMessage());
        }

        Map<String, Object> body = errorBody("validation_failed", "One or more fields are invalid");
        body.put("fields", fields);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler({EmailAlreadyUsedException.class, DataIntegrityViolationException.class})
    public ResponseEntity<Map<String, Object>> handleConflict(Exception ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(errorBody("email_already_used", "Email is already in use"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(errorBody("forbidden", "You do not have permission to perform this action"));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Map<String, Object>> handleAuthentication(AuthenticationException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(errorBody("unauthorized", "Authentication is required"));
    }

    private Map<String, Object> errorBody(String error, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("message", message);
        return body;
    }
}
