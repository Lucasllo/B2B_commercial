package com.orderflow.order.config;

import com.orderflow.order.client.AuthServiceUnavailableException;
import com.orderflow.order.client.CatalogServiceUnavailableException;
import com.orderflow.order.order.exception.DuplicateOrderItemsException;
import com.orderflow.order.order.exception.InvalidOrderItemsException;
import com.orderflow.order.order.exception.OrderNotFoundException;
import com.orderflow.order.order.exception.OrderTotalOutOfRangeException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Corpo de erro uniforme para todo o serviço — sempre as chaves {@code error} e {@code message};
 * {@code fields} só em erro de validação, {@code productIds} só em item de pedido inválido.
 * Nenhum handler inclui stack trace, mensagem de exceção do vizinho, URL de downstream ou nome de
 * classe no corpo da resposta.
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

    /** Path variable que não converte para o tipo esperado — em particular um {@code orderId} que não é UUID. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidParameter(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(errorBody("invalid_parameter", "Request parameter is invalid"));
    }

    /**
     * Produto repetido sai no mesmo envelope {@code validation_failed} que a validação de bean usa
     * — {@code fields.items} com o texto fixo da exceção — para o cliente tratar um formato só de
     * erro 400, em vez de um código novo (D-44).
     */
    @ExceptionHandler(DuplicateOrderItemsException.class)
    public ResponseEntity<Map<String, Object>> handleDuplicateItems(DuplicateOrderItemsException ex) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("items", ex.getMessage());

        Map<String, Object> body = errorBody("validation_failed", "One or more fields are invalid");
        body.put("fields", fields);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleOrderNotFound(OrderNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(errorBody("order_not_found", "Order not found"));
    }

    /**
     * 422 em vez de 409 (Claude's Discretion, 04-RESEARCH.md Assumption A1): o pedido é bem
     * formado mas referencia item que não pode ser vendido — não há conflito de estado.
     */
    @ExceptionHandler(InvalidOrderItemsException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidOrderItems(InvalidOrderItemsException ex) {
        Map<String, Object> body = errorBody("invalid_order_items", "One or more items are invalid or unavailable");
        body.put("productIds", ex.getProductIds());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }

    /** Total que não cabe em {@code NUMERIC(19,2)} — 422, nunca 500 com detalhe do banco (04-02 Task 3). */
    @ExceptionHandler(OrderTotalOutOfRangeException.class)
    public ResponseEntity<Map<String, Object>> handleTotalOutOfRange(OrderTotalOutOfRangeException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(errorBody("order_total_out_of_range", "Order total does not fit in the allowed range"));
    }

    @ExceptionHandler(CatalogServiceUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleCatalogServiceUnavailable(CatalogServiceUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(errorBody("catalog_service_unavailable", "No order was created"));
    }

    @ExceptionHandler(AuthServiceUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleAuthServiceUnavailable(AuthServiceUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(errorBody("auth_service_unavailable", "No order was created"));
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
