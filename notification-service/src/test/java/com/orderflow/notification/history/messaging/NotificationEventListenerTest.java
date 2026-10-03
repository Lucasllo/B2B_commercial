package com.orderflow.notification.history.messaging;

import com.orderflow.notification.history.InvalidNotificationEventException;
import com.orderflow.notification.history.NotificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

@ExtendWith(OutputCaptureExtension.class)
class NotificationEventListenerTest {

    @Test
    void invalidNotificationEventExceptionIsSwallowedSoMessageGetsAcknowledged() {
        NotificationService notificationService = mock(NotificationService.class);
        doThrow(new InvalidNotificationEventException("bad event")).when(notificationService).record(anyString());
        NotificationEventListener listener = new NotificationEventListener(
                notificationService, "notification-events-queue");

        assertThatCode(() -> listener.onMessage("payload")).doesNotThrowAnyException();
    }

    @Test
    void anyOtherRuntimeExceptionIsRethrownSoMessageIsNotAcknowledged() {
        NotificationService notificationService = mock(NotificationService.class);
        doThrow(new RuntimeException("dynamo down")).when(notificationService).record(anyString());
        NotificationEventListener listener = new NotificationEventListener(
                notificationService, "notification-events-queue");

        assertThatThrownBy(() -> listener.onMessage("payload"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("dynamo down");
    }

    @Test
    void discardedOrderEventIsLoggedWithOrderIdAndEventTypeAndNeverEscapes(CapturedOutput output) {
        UUID orderId = UUID.randomUUID();
        NotificationService notificationService = mock(NotificationService.class);
        doThrow(new InvalidNotificationEventException("Campo companyId ausente", orderId.toString(), "ORDER_SHIPPED"))
                .when(notificationService).record(anyString());
        NotificationEventListener listener = new NotificationEventListener(
                notificationService, "notification-events-queue");

        assertThatCode(() -> listener.onMessage("payload", "abc-123")).doesNotThrowAnyException();

        assertThat(output.getAll().split("\\R")).anyMatch(l -> l.contains("WARN")
                && l.contains("orderId=" + orderId)
                && l.contains("eventType=ORDER_SHIPPED")
                && l.contains("Campo companyId ausente"));
    }

    @Test
    void discardedEventWithoutContextLogsDashes(CapturedOutput output) {
        NotificationService notificationService = mock(NotificationService.class);
        doThrow(new InvalidNotificationEventException("bad event")).when(notificationService).record(anyString());
        NotificationEventListener listener = new NotificationEventListener(
                notificationService, "notification-events-queue");

        listener.onMessage("payload");

        assertThat(output.getAll()).contains("orderId=-").contains("eventType=-");
    }
}
