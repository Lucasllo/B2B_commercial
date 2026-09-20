package com.orderflow.inventory.config;

import com.orderflow.inventory.stock.InsufficientStockException;
import com.orderflow.inventory.stock.InventoryNotFoundException;
import com.orderflow.inventory.stock.ReservationConflictException;
import com.orderflow.inventory.stock.StockBelowReservedException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Corpo de erro uniforme para todo o servico — sempre as chaves {@code error} e {@code message};
 * {@code fields} e adicionado apenas para erro de validacao. Nenhum handler inclui stack trace,
 * nome de classe de excecao, fragmento de SQL ou nome de constraint do banco no corpo da
 * resposta. {@code insufficient_stock} (409, D-10) e {@code reservation_conflict} (503, D-21) sao
 * deliberadamente codigos diferentes — falta de estoque real contra disputa transitoria que vale
 * a pena repetir nunca se confundem.
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

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleMalformedRequest(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(errorBody("malformed_request", "Request body is malformed"));
    }

    @ExceptionHandler(InventoryNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleInventoryNotFound(InventoryNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(errorBody("inventory_not_found", "Inventory not found"));
    }

    @ExceptionHandler(InsufficientStockException.class)
    public ResponseEntity<Map<String, Object>> handleInsufficientStock(InsufficientStockException ex) {
        Map<String, Object> body = errorBody("insufficient_stock", "Requested quantity exceeds available stock");
        body.put("available", ex.getAvailable());
        body.put("requested", ex.getRequested());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(StockBelowReservedException.class)
    public ResponseEntity<Map<String, Object>> handleStockBelowReserved(StockBelowReservedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(errorBody("stock_below_reserved", "Requested on-hand quantity is below the quantity already reserved"));
    }

    @ExceptionHandler(ReservationConflictException.class)
    public ResponseEntity<Map<String, Object>> handleReservationConflict(ReservationConflictException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(errorBody("reservation_conflict", "Concurrent contention exhausted retries, please try again"));
    }

    /**
     * Rede de seguranca para uma violacao de integridade que escape das reexecucoes do
     * {@code InventoryService} — nunca a mensagem bruta do banco nem o nome da constraint.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(errorBody("data_conflict", "The request conflicts with existing data"));
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
