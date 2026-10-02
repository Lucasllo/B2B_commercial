package com.orderflow.order.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Filtro HTTP do order-service (D-93, D-97, T-07-01). Abre o escopo de MDC com o
 * {@code X-Correlation-Id} da requisição e loga uma linha de acesso — método, caminho sem query e
 * status — enquanto o ID ainda está no MDC. Não escreve o header na resposta: quem ecoa para o
 * cliente é só o Gateway (Pitfall 3). {@code /actuator} fica de fora para o healthcheck do compose
 * não encher o log. O código é duplicado por serviço, sem módulo comum (D-62).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        CorrelationContext.Scope scope = CorrelationContext.open(request.getHeader(CorrelationContext.HEADER));
        try {
            filterChain.doFilter(request, response);
        } finally {
            try {
                String path = request.getRequestURI();
                if (path == null || !path.startsWith("/actuator")) {
                    log.info("{} {} -> {}", request.getMethod(), path, response.getStatus());
                }
            } finally {
                scope.close();
            }
        }
    }
}
