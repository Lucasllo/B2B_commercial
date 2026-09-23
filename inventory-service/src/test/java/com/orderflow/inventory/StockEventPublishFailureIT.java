package com.orderflow.inventory;

import com.orderflow.inventory.support.TestJwt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prova de que uma falha de publicacao no SQS nao derruba o {@code PUT /inventory/{productId}}
 * (D-30): a transacao ja foi commitada quando {@code StockEventPublisher} roda, entao a resposta
 * continua 200 e o ajuste continua gravado, com uma linha ERROR identificando o evento perdido.
 *
 * <p>A propriedade sobrescrita aponta para uma fila que o init hook nao cria; com a estrategia de
 * fila inexistente em modo falha configurada na Task 1 ({@code queue-not-found-strategy: fail}),
 * o envio lanca em vez de criar a fila — o que tambem prova, de passagem, que essa estrategia esta
 * ativa. Por sobrescrever uma propriedade, esta classe sobe um contexto Spring proprio,
 * reaproveitando os mesmos containers estaticos de {@link AbstractIntegrationTest}.
 */
@TestPropertySource(properties = "orderflow.messaging.notification-events-queue=fila-inexistente-03-02")
@ExtendWith(OutputCaptureExtension.class)
class StockEventPublishFailureIT extends AbstractIntegrationTest {

    private String setStockPayload(int quantityOnHand) {
        return """
                {"quantityOnHand":%d}
                """.formatted(quantityOnHand);
    }

    @Test
    void publishFailureDoesNotFailThePutKeepsTheAdjustmentAndLogsErrorWithProductId(CapturedOutput output) throws Exception {
        String token = TestJwt.sellerAdminToken();
        UUID productId = UUID.randomUUID();

        mockMvc.perform(put("/inventory/" + productId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(setStockPayload(9)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantityOnHand").value(9));

        mockMvc.perform(get("/inventory/" + productId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantityOnHand").value(9));

        assertThat(output.getOut() + output.getErr())
                .contains("ERROR")
                .contains("Falha ao publicar")
                .contains("STOCK_ADJUSTED")
                .contains(productId.toString());
    }
}
