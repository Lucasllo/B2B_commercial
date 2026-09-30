package com.orderflow.order.shipping;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.UUID;

/**
 * Transportadora SIMULADA (D-71, D-72) — não há integração com nenhuma transportadora real. Os
 * cinco nomes abaixo são fictícios e o código de rastreio tem a forma de um código Correios/S10,
 * mas não é consultável em nenhum serviço real.
 *
 * <p>{@code CARRIER_ALGORITHM=sha256-orderId}: SHA-256 de {@code orderId.toString()} em UTF-8; os
 * bytes 0-1 escolhem a transportadora e {@link TrackingCodes#fromDigest} monta o código. Sem estado,
 * sem I/O, sem dependências e sem exceção de negócio: o mesmo pedido resulta sempre na mesma
 * atribuição, então reentregar o mesmo {@code StockReserved} não troca nada (D-64).
 * {@code TRACKING_CODE_UNIQUENESS=probabilistic}: a unicidade é estatística (26² x 10^8
 * combinações) e deliberadamente não imposta por {@code UNIQUE} no banco — uma violação ali
 * derrubaria a transação do {@code CONFIRMED} a cada reentrega.
 */
@Component
public class SimulatedCarrierGateway implements CarrierGateway {

    /** {@code CARRIER_LIST}: nomes fictícios, sem acento. */
    public static final List<String> CARRIERS = List.of(
            "Expresso Cerrado", "TransSul Cargas", "Rapido Paulista", "Norte Entregas", "Litoral Log");

    @Override
    public CarrierAssignment assign(UUID orderId) {
        byte[] digest = sha256(orderId.toString());
        int index = Math.floorMod(((digest[0] & 0xFF) << 8) | (digest[1] & 0xFF), CARRIERS.size());
        return new CarrierAssignment(CARRIERS.get(index), TrackingCodes.fromDigest(digest));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 é obrigatório em toda JVM (javadoc de MessageDigest).
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
