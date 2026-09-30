package com.orderflow.order.shipping;

import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Algoritmo UPU S10 (D-73): dois caracteres, oito dígitos, dígito verificador, sufixo {@code BR}.
 * Sem contexto Spring e sem I/O.
 */
class TrackingCodesTest {

    @Test
    void checkDigitMatchesTheOfficialS10Example() {
        // soma = 4*8 + 7*6 + 3*4 + 1*2 + 2*3 + 4*5 + 8*9 + 2*7 = 200; 200 mod 11 = 2; 11 - 2 = 9.
        assertThat(TrackingCodes.checkDigit("47312482")).isEqualTo(9);
    }

    @Test
    void checkDigitMapsRemainderZeroToFive() {
        // soma 0 -> C = 11 - 0 = 11 -> 5.
        assertThat(TrackingCodes.checkDigit("00000000")).isEqualTo(5);
    }

    @Test
    void checkDigitMapsRemainderOneToZero() {
        // soma 12 (3 + 9) -> 12 mod 11 = 1 -> C = 10 -> 0.
        assertThat(TrackingCodes.checkDigit("00001010")).isZero();
    }

    @Test
    void checkDigitRejectsInputThatIsNotExactlyEightDigits() {
        assertThatThrownBy(() -> TrackingCodes.checkDigit("1234567")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TrackingCodes.checkDigit("123456789")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TrackingCodes.checkDigit("1234567A")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TrackingCodes.checkDigit(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fromDigestBuildsAThirteenCharacterCodeWithTheS10CheckDigit() {
        byte[] digest = sha256("qualquer-coisa");

        String code = TrackingCodes.fromDigest(digest);

        assertThat(code).hasSize(13).matches(TrackingCodes.PATTERN.pattern());
        assertThat(code).endsWith("BR");
        assertThat(Character.getNumericValue(code.charAt(10)))
                .isEqualTo(TrackingCodes.checkDigit(code.substring(2, 10)));
    }

    @Test
    void fromDigestPadsTheSerialWithLeadingZeros() {
        // bytes 4..11 todos zero -> serial 0 -> "00000000"; dígito verificador de oito zeros = 5.
        byte[] digest = new byte[32];
        digest[2] = 0;
        digest[3] = 1;

        assertThat(TrackingCodes.fromDigest(digest)).isEqualTo("AB000000005BR");
    }

    @Test
    void isValidAcceptsGeneratedCodesAndRejectsEverythingElse() {
        String valid = TrackingCodes.fromDigest(sha256("abc"));
        assertThat(TrackingCodes.isValid(valid)).isTrue();

        assertThat(TrackingCodes.isValid("AB12345678BR")).isFalse();
        assertThat(TrackingCodes.isValid("ab123456789BR")).isFalse();
        assertThat(TrackingCodes.isValid("AB123456789US")).isFalse();
        assertThat(TrackingCodes.isValid(null)).isFalse();

        int digit = Character.getNumericValue(valid.charAt(10));
        char wrong = (char) ('0' + ((digit + 1) % 10));
        String tampered = valid.substring(0, 10) + wrong + valid.substring(11);
        assertThat(TrackingCodes.isValid(tampered)).isFalse();
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
