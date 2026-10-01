package com.orderflow.notification.config;

import com.orderflow.notification.history.NotificationNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import software.amazon.awssdk.core.exception.SdkException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Corpo de erro uniforme para todo o servico — sempre as chaves {@code error} e {@code message}.
 * Nenhum handler inclui mensagem de excecao, stack trace, nome de classe, nome de tabela ou fila,
 * nem texto vindo do SDK da AWS no corpo da resposta.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * O {@code @PathVariable UUID} e a validacao de entrada que impede qualquer texto arbitrario
     * de virar valor de partition key — um identificador que nao e UUID nunca chega ao servico.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidIdentifier(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(errorBody("invalid_identifier", "Identifier must be a UUID"));
    }

    /**
     * Linha do tempo inexistente PARA QUEM PEDIU (D-81): o mesmo corpo para "pedido de outra
     * empresa", "inexistente" e "sem eventos" — nunca revela se o pedido existe.
     */
    @ExceptionHandler(NotificationNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(NotificationNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(errorBody("order_not_found", "Order not found"));
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

    /**
     * Qualquer falha do SDK da AWS ao falar com o DynamoDB (indisponibilidade, timeout, erro de
     * credencial) vira 503 com um codigo estavel — o motivo real fica so no log do servidor.
     */
    @ExceptionHandler(SdkException.class)
    public ResponseEntity<Map<String, Object>> handleAwsSdkFailure(SdkException ex) {
        log.error("Notification store unavailable", ex);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(errorBody("notification_store_unavailable", "Notification history is temporarily unavailable"));
    }

    private Map<String, Object> errorBody(String error, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("message", message);
        return body;
    }
}
