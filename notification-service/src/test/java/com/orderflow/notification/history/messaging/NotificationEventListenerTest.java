package com.orderflow.notification.history.messaging;

import com.orderflow.notification.history.InvalidNotificationEventException;
import com.orderflow.notification.history.NotificationService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

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
}
