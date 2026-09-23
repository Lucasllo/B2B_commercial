package com.orderflow.notification;

import com.orderflow.notification.history.NotificationRepository;
import com.orderflow.notification.support.TestJwt;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.core.exception.SdkClientException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 3: indisponibilidade do DynamoDB vira 503 sem vazar nada do SDK da AWS. Usa
 * {@code @MockitoBean} e por isso sobe um contexto proprio, reaproveitando o mesmo container
 * LocalStack.
 */
class NotificationStoreUnavailableIT extends AbstractIntegrationTest {

    @MockitoBean
    private NotificationRepository notificationRepository;

    @Test
    void dynamoDbFailureReturns503WithoutLeakingSdkDetails() throws Exception {
        when(notificationRepository.findByProductId(anyString()))
                .thenThrow(SdkClientException.create("Unable to execute HTTP request"));
        String token = TestJwt.sellerAdminToken();

        var result = mockMvc.perform(get("/notifications/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("notification_store_unavailable"))
                .andExpect(jsonPath("$.message").exists())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("software.amazon")
                .doesNotContain("Exception")
                .doesNotContain("notification-history");
    }
}
