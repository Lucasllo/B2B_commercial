package com.orderflow.notification.history;

import com.orderflow.notification.history.dto.NotificationResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * {@code GET /notifications/{productId}} — lista por Query, nunca item unico (D-31). A restricao
 * de papel e o tratamento de erro entram na Task 3.
 */
@RestController
@RequestMapping("/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping("/{productId}")
    public List<NotificationResponse> getHistory(@PathVariable UUID productId) {
        return notificationService.history(productId);
    }
}
