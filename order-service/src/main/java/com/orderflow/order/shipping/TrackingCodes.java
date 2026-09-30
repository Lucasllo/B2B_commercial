package com.orderflow.order.shipping;

import java.util.regex.Pattern;

/**
 * Montagem e validação do código de rastreio no padrão Correios/UPU S10 (D-73): duas letras, oito
 * dígitos de serial, um dígito verificador e o sufixo {@code BR} — 13 caracteres. O código é
 * SIMULADO: tem a forma de um código real, mas não é consultável em nenhum serviço de transportadora.
 *
 * <p>Dígito verificador S10: pesos 8, 6, 4, 2, 3, 5, 9, 7 sobre os oito dígitos; {@code C = 11 -
 * (soma mod 11)}; {@code C = 10} vira {@code 0} e {@code C = 11} vira {@code 5}.
 */
public final class TrackingCodes {

    /** Formato do código — o mesmo regex do CHECK {@code chk_orders_tracking_code_format} da V3. */
    public static final Pattern PATTERN = Pattern.compile("^[A-Z]{2}[0-9]{9}BR$");

    private static final int[] WEIGHTS = {8, 6, 4, 2, 3, 5, 9, 7};
    private static final long SERIAL_MODULUS = 100_000_000L;

    private TrackingCodes() {
    }

    /** Dígito verificador S10 de exatamente oito dígitos; qualquer outra entrada é rejeitada. */
    public static int checkDigit(String eightDigits) {
        if (eightDigits == null || eightDigits.length() != WEIGHTS.length) {
            throw new IllegalArgumentException("S10 serial must have exactly 8 digits");
        }
        int sum = 0;
        for (int i = 0; i < WEIGHTS.length; i++) {
            char c = eightDigits.charAt(i);
            if (c < '0' || c > '9') {
                throw new IllegalArgumentException("S10 serial must contain only digits");
            }
            sum += (c - '0') * WEIGHTS[i];
        }
        int check = 11 - (sum % 11);
        if (check == 10) {
            return 0;
        }
        if (check == 11) {
            return 5;
        }
        return check;
    }

    /**
     * Monta o código a partir de um digest (SHA-256, 32 bytes): bytes 2 e 3 escolhem as duas
     * letras, bytes 4 a 11 (como {@code long} sem sinal, módulo 10^8) formam o serial.
     */
    public static String fromDigest(byte[] digest) {
        char first = (char) ('A' + Math.floorMod(digest[2], 26));
        char second = (char) ('A' + Math.floorMod(digest[3], 26));
        long value = 0;
        for (int i = 4; i <= 11; i++) {
            value = (value << 8) | (digest[i] & 0xFFL);
        }
        String serial = String.format(java.util.Locale.ROOT, "%08d", Long.remainderUnsigned(value, SERIAL_MODULUS));
        return "" + first + second + serial + checkDigit(serial) + "BR";
    }

    /** {@code true} se o código casa o padrão e o dígito verificador confere. */
    public static boolean isValid(String code) {
        if (code == null || !PATTERN.matcher(code).matches()) {
            return false;
        }
        return code.charAt(10) - '0' == checkDigit(code.substring(2, 10));
    }
}
