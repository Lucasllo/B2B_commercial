package com.orderflow.auth.observability;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contrato do Correlation-ID no auth-service sem Spring (D-93, D-96, D-97, T-07-18): o escopo valida
 * ou gera, restaura o MDC anterior, e o filtro não ecoa o header na resposta.
 */
class CorrelationIdFilterTest {

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void openPutsTheIdInTheMdcAndCloseRestoresThePreviousValue() {
        assertThat(MDC.get(CorrelationContext.MDC_KEY)).isNull();

        CorrelationContext.Scope scope = CorrelationContext.open("abc-123");
        assertThat(MDC.get(CorrelationContext.MDC_KEY)).isEqualTo("abc-123");
        assertThat(CorrelationContext.current()).isEqualTo("abc-123");
        scope.close();
        assertThat(MDC.get(CorrelationContext.MDC_KEY)).isNull();

        MDC.put(CorrelationContext.MDC_KEY, "previous-id");
        CorrelationContext.Scope nested = CorrelationContext.open("abc-123");
        assertThat(MDC.get(CorrelationContext.MDC_KEY)).isEqualTo("abc-123");
        nested.close();
        assertThat(MDC.get(CorrelationContext.MDC_KEY)).isEqualTo("previous-id");
    }

    @Test
    void openRejectsNullBlankInjectionAndOverlongValuesWithANewUuid() {
        assertGenerated(null);
        assertGenerated("bad value!");
        assertGenerated("bad\r\ninjected");
        assertGenerated("a".repeat(65));
    }

    @Test
    void filterExposesTheHeaderInTheMdcAndDoesNotEchoItOnTheResponse() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/login");
        request.addHeader(CorrelationContext.HEADER, "abc-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) ->
                assertThat(MDC.get(CorrelationContext.MDC_KEY)).isEqualTo("abc-123"));

        assertThat(MDC.get(CorrelationContext.MDC_KEY)).isNull();
        assertThat(response.getHeader(CorrelationContext.HEADER)).isNull();
    }

    @Test
    void filterRestoresTheMdcWhenTheChainThrowsAndStillDoesNotEchoTheHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/login");
        request.addHeader(CorrelationContext.HEADER, "abc-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> filter.doFilter(request, response, (req, res) -> {
            assertThat(MDC.get(CorrelationContext.MDC_KEY)).isEqualTo("abc-123");
            throw new ServletException("falha de proposito");
        })).isInstanceOf(ServletException.class);

        assertThat(MDC.get(CorrelationContext.MDC_KEY)).isNull();
        assertThat(response.getHeader(CorrelationContext.HEADER)).isNull();
    }

    private static void assertGenerated(String raw) {
        CorrelationContext.Scope scope = CorrelationContext.open(raw);
        try {
            String id = CorrelationContext.current();
            assertThat(id).matches(UUID_PATTERN);
            assertThat(id).isNotEqualTo(raw);
        } finally {
            scope.close();
        }
        assertThat(MDC.get(CorrelationContext.MDC_KEY)).isNull();
    }
}
