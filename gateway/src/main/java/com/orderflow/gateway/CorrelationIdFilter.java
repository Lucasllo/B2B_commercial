package com.orderflow.gateway;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Origem do Correlation-ID na borda (D-92, D-97, T-07-01). O valor do cliente só entra se
 * casar {@link #VALID_ID}; qualquer outra coisa (CR/LF, espaço, mais de 64 caracteres, vazio)
 * vira um UUID novo antes do MDC, para um header de fora não conseguir injetar linhas de log.
 *
 * <p>Dois wrappers travam o header duplicado (T-07-02). O de pedido devolve um único valor em
 * qualquer caixa do nome e monta {@code getHeaderNames()} com {@link String#CASE_INSENSITIVE_ORDER}:
 * sem isso, {@code x-correlation-id} do cliente e {@code X-Correlation-Id} do Gateway seguiriam
 * os dois para o serviço. O {@link HttpServletResponseWrapper} ignora {@code setHeader}/{@code
 * addHeader} desse nome, porque o serviço de destino pode ecoar outro valor e o cliente tem que
 * receber só o do Gateway.
 *
 * <p>A linha de acesso é só método, {@code getRequestURI()} (sem query string) e status. Nada de
 * header nem de corpo. {@code /actuator} fica de fora: o healthcheck do compose chamaria isso o
 * tempo todo. O MDC é removido no {@code finally}, inclusive quando a cadeia lança exceção — a
 * thread volta para o pool e não pode levar o ID do pedido anterior.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";
    public static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String id = (incoming != null && VALID_ID.matcher(incoming).matches())
                ? incoming
                : UUID.randomUUID().toString();
        response.setHeader(HEADER, id);
        MDC.put(MDC_KEY, id);
        try {
            filterChain.doFilter(wrapRequest(request, id), wrapResponse(response));
        } finally {
            String path = request.getRequestURI();
            if (path == null || !path.startsWith("/actuator")) {
                log.info("{} {} -> {}", request.getMethod(), path, response.getStatus());
            }
            MDC.remove(MDC_KEY);
        }
    }

    private static HttpServletRequest wrapRequest(HttpServletRequest request, String id) {
        return new HttpServletRequestWrapper(request) {
            @Override
            public String getHeader(String name) {
                if (isCorrelation(name)) {
                    return id;
                }
                return super.getHeader(name);
            }

            @Override
            public Enumeration<String> getHeaders(String name) {
                if (isCorrelation(name)) {
                    return Collections.enumeration(List.of(id));
                }
                return super.getHeaders(name);
            }

            @Override
            public Enumeration<String> getHeaderNames() {
                Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                Enumeration<String> existing = super.getHeaderNames();
                if (existing != null) {
                    names.addAll(Collections.list(existing));
                }
                names.add(HEADER);
                return Collections.enumeration(names);
            }
        };
    }

    private static HttpServletResponse wrapResponse(HttpServletResponse response) {
        return new HttpServletResponseWrapper(response) {
            @Override
            public void setHeader(String name, String value) {
                if (isCorrelation(name)) {
                    return;
                }
                super.setHeader(name, value);
            }

            @Override
            public void addHeader(String name, String value) {
                if (isCorrelation(name)) {
                    return;
                }
                super.addHeader(name, value);
            }
        };
    }

    private static boolean isCorrelation(String name) {
        return name != null && HEADER.equalsIgnoreCase(name);
    }
}
