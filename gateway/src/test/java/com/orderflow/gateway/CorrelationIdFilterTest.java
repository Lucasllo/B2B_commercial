package com.orderflow.gateway;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Prova o contrato do {@link CorrelationIdFilter} sem subir o Gateway (D-92, T-07-01,
 * T-07-02): quem chama recebe um único {@code X-Correlation-Id} válido, o pedido
 * encaminhado leva esse mesmo valor uma vez só (mesmo com o nome do header em outra caixa)
 * e o MDC {@code correlationId} não vaza para depois do filtro — inclusive quando a cadeia
 * lança exceção.
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
    void missingHeaderGeneratesOneUuidOnTheResponseTheForwardedRequestAndTheMdc() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders/abc");
        MockHttpServletResponse response = new MockHttpServletResponse();

        Forwarded forwarded = run(request, response);

        String id = response.getHeader(CorrelationIdFilter.HEADER);
        assertThat(id).matches(UUID_PATTERN);
        assertThat(response.getHeaders(CorrelationIdFilter.HEADER)).containsExactly(id);
        assertThat(forwarded.request().getHeader(CorrelationIdFilter.HEADER)).isEqualTo(id);
        assertThat(Collections.list(forwarded.request().getHeaders(CorrelationIdFilter.HEADER))).containsExactly(id);
        assertThat(forwarded.mdcDuringChain()).isEqualTo(id);
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void validHeaderIsReusedOnTheResponseTheForwardedRequestAndTheMdc() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders/abc");
        request.addHeader(CorrelationIdFilter.HEADER, "abc-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        Forwarded forwarded = run(request, response);

        assertThat(response.getHeaders(CorrelationIdFilter.HEADER)).containsExactly("abc-123");
        assertThat(forwarded.request().getHeader(CorrelationIdFilter.HEADER)).isEqualTo("abc-123");
        assertThat(forwarded.mdcDuringChain()).isEqualTo("abc-123");
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void lowercaseHeaderNameIsForwardedOnceIgnoringCase() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders/abc");
        request.addHeader("x-correlation-id", "abc-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        Forwarded forwarded = run(request, response);

        assertThat(Collections.list(forwarded.request().getHeaders(CorrelationIdFilter.HEADER)))
                .containsExactly("abc-123");
        List<String> names = Collections.list(forwarded.request().getHeaderNames());
        assertThat(names.stream().filter(name -> CorrelationIdFilter.HEADER.equalsIgnoreCase(name))).hasSize(1);
        assertThat(response.getHeaders(CorrelationIdFilter.HEADER)).containsExactly("abc-123");
    }

    @Test
    void invalidValuesAreReplacedByANewUuid() throws Exception {
        assertReplaced("bad value!");
        assertReplaced("abc\r\nX-Injected: 1");
        assertReplaced("a".repeat(65));
        assertReplaced("");
    }

    @Test
    void mdcIsClearedAfterTheFilterEvenWhenTheChainThrows() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders/abc");
        request.addHeader(CorrelationIdFilter.HEADER, "abc-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> filter.doFilter(request, response, (req, res) -> {
            throw new ServletException("falha de proposito");
        })).isInstanceOf(ServletException.class);

        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void downstreamEchoDoesNotChangeOrDuplicateTheGatewayHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders/abc");
        request.addHeader(CorrelationIdFilter.HEADER, "abc-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            HttpServletResponse httpResponse = (HttpServletResponse) res;
            httpResponse.setHeader("x-correlation-id", "outro");
            httpResponse.addHeader("X-Correlation-Id", "outro");
        });

        assertThat(response.getHeaders(CorrelationIdFilter.HEADER)).containsExactly("abc-123");
    }

    @Test
    void accessLineCarriesOnlyMethodPathAndStatusNeverTheQueryStringOrAuthorization() throws Exception {
        ListAppender<ILoggingEvent> appender = captureFilterLog();
        try {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/orders");
            request.setQueryString("token=query-secret&x=1");
            request.addHeader("Authorization", "Bearer header-secret");
            MockHttpServletResponse response = new MockHttpServletResponse();
            response.setStatus(201);

            run(request, response);

            assertThat(appender.list).hasSize(1);
            String line = appender.list.get(0).getFormattedMessage();
            assertThat(line).isEqualTo("POST /api/orders -> 201");
            assertThat(line).doesNotContain("query-secret").doesNotContain("header-secret");
        } finally {
            releaseFilterLog(appender);
        }
    }

    @Test
    void actuatorRequestsAreNotWrittenAsAccessLines() throws Exception {
        ListAppender<ILoggingEvent> appender = captureFilterLog();
        try {
            run(new MockHttpServletRequest("GET", "/actuator/health"), new MockHttpServletResponse());

            assertThat(appender.list).isEmpty();
        } finally {
            releaseFilterLog(appender);
        }
    }

    private static ListAppender<ILoggingEvent> captureFilterLog() {
        Logger logger = (Logger) LoggerFactory.getLogger(CorrelationIdFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void releaseFilterLog(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(CorrelationIdFilter.class)).detachAppender(appender);
        appender.stop();
    }

    private void assertReplaced(String invalid) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/orders");
        request.addHeader(CorrelationIdFilter.HEADER, invalid);
        MockHttpServletResponse response = new MockHttpServletResponse();

        Forwarded forwarded = run(request, response);

        String id = response.getHeader(CorrelationIdFilter.HEADER);
        assertThat(id).matches(UUID_PATTERN).isNotEqualTo(invalid);
        assertThat(forwarded.request().getHeader(CorrelationIdFilter.HEADER)).isEqualTo(id);
        assertThat(forwarded.mdcDuringChain()).isEqualTo(id);
    }

    private Forwarded run(MockHttpServletRequest request, MockHttpServletResponse response) throws Exception {
        AtomicReference<Forwarded> captured = new AtomicReference<>();
        filter.doFilter(request, response, (req, res) -> captured.set(
                new Forwarded((HttpServletRequest) req, MDC.get(CorrelationIdFilter.MDC_KEY))));
        return captured.get();
    }

    private record Forwarded(HttpServletRequest request, String mdcDuringChain) {
    }
}
