package com.orderflow.inventory.saga.messaging;

import com.orderflow.inventory.stock.InventoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unitario do descarte seletivo do listener (D-67 + D-107/WR-01): {@code ShipStock} invalido e
 * anomalia tecnica e propaga (reentrega -> DLQ) com log ERROR; {@code ReserveStock}/{@code
 * ReleaseStock} invalidos continuam sendo descartados com WARN.
 */
@ExtendWith(OutputCaptureExtension.class)
class ReservationCommandListenerTest {

    private static final String ORDER_ID = "6f1d6c0e-5a0e-4a3c-8e41-0d6f2d4b9c11";

    private final SagaCommandParser parser = mock(SagaCommandParser.class);
    private final InventoryService inventoryService = mock(InventoryService.class);
    private final ReservationCommandListener listener =
            new ReservationCommandListener(parser, inventoryService, "inventory-commands-queue");

    @Test
    void invalidShipStockIsRethrownLoggedAsErrorAndInventoryIsNotCalled(CapturedOutput output) {
        InvalidShipStockException invalid = new InvalidShipStockException("Campo items nao pode ser vazio", ORDER_ID);
        when(parser.parse("corpo-com-segredo")).thenThrow(invalid);

        assertThatThrownBy(() -> listener.onMessage("corpo-com-segredo"))
                .isSameAs(invalid);

        assertThat(output.getOut() + output.getErr())
                .contains("ERROR")
                .contains("ShipStock")
                .contains(ORDER_ID)
                .contains("Campo items nao pode ser vazio")
                .doesNotContain("corpo-com-segredo");
        verifyNoInteractions(inventoryService);
    }

    @Test
    void genericInvalidMessageIsDiscardedWithWarnAndDoesNotThrow(CapturedOutput output) {
        when(parser.parse("lixo")).thenThrow(new InvalidSagaMessageException("Corpo da mensagem nao e um JSON valido"));

        assertThatCode(() -> listener.onMessage("lixo")).doesNotThrowAnyException();

        assertThat(output.getOut() + output.getErr())
                .contains("WARN")
                .contains("Mensagem descartada");
        verifyNoInteractions(inventoryService);
    }
}
