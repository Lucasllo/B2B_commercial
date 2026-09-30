package com.orderflow.order.shipping;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Mock determinístico de transportadora (D-71, D-72): sem I/O, sem estado, nunca lança. */
class SimulatedCarrierGatewayTest {

    @Test
    void sameOrderIdAlwaysYieldsTheSameAssignmentEvenAcrossInstances() {
        UUID orderId = UUID.randomUUID();
        SimulatedCarrierGateway first = new SimulatedCarrierGateway();

        CarrierAssignment a = first.assign(orderId);
        CarrierAssignment b = first.assign(orderId);
        CarrierAssignment c = new SimulatedCarrierGateway().assign(orderId);

        assertThat(b).isEqualTo(a);
        assertThat(c).isEqualTo(a);
    }

    @Test
    void thousandRandomOrdersNeverThrowAndAlwaysProduceValidAssignments() {
        SimulatedCarrierGateway gateway = new SimulatedCarrierGateway();
        Set<String> carriersSeen = new HashSet<>();

        for (int i = 0; i < 1000; i++) {
            CarrierAssignment assignment = gateway.assign(UUID.randomUUID());
            assertThat(TrackingCodes.isValid(assignment.trackingCode())).isTrue();
            assertThat(SimulatedCarrierGateway.CARRIERS).contains(assignment.carrier());
            carriersSeen.add(assignment.carrier());
        }

        assertThat(carriersSeen.size()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void carrierListIsExactlyTheFiveFictionalNamesAndImmutable() {
        assertThat(SimulatedCarrierGateway.CARRIERS).containsExactly(
                "Expresso Cerrado", "TransSul Cargas", "Rapido Paulista", "Norte Entregas", "Litoral Log");
        assertThatThrownBy(() -> SimulatedCarrierGateway.CARRIERS.add("Outra"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void carrierAssignmentRejectsBlankNullOrTooLongCarrierAndInvalidTrackingCode() {
        String validCode = new SimulatedCarrierGateway().assign(UUID.randomUUID()).trackingCode();

        assertThatThrownBy(() -> new CarrierAssignment("  ", validCode)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CarrierAssignment(null, validCode)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CarrierAssignment("x".repeat(65), validCode))
                .isInstanceOf(IllegalArgumentException.class);
        // dígito verificador errado: para "12345678" o correto é 5, não 0.
        assertThat(TrackingCodes.checkDigit("12345678")).isEqualTo(5);
        assertThatThrownBy(() -> new CarrierAssignment("Norte Entregas", "AB123456780BR"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
