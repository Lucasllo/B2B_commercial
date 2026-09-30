package com.orderflow.order.shipping;

/**
 * Resultado da atribuição de transportadora (D-70): nome da transportadora (até 64 caracteres, a
 * largura da coluna {@code carrier}) e código de rastreio no padrão {@code ^[A-Z]{2}[0-9]{9}BR$} com
 * dígito verificador UPU S10 correto. O construtor compacto impede que um valor inválido chegue ao
 * agregado ou ao banco (a V3 tem CHECK para o formato).
 */
public record CarrierAssignment(String carrier, String trackingCode) {

    public static final int MAX_CARRIER_LENGTH = 64;

    public CarrierAssignment {
        if (carrier == null || carrier.isBlank()) {
            throw new IllegalArgumentException("carrier must not be blank");
        }
        if (carrier.length() > MAX_CARRIER_LENGTH) {
            throw new IllegalArgumentException("carrier must have at most " + MAX_CARRIER_LENGTH + " characters");
        }
        if (!TrackingCodes.isValid(trackingCode)) {
            throw new IllegalArgumentException("trackingCode is not a valid S10 code: " + trackingCode);
        }
    }
}
