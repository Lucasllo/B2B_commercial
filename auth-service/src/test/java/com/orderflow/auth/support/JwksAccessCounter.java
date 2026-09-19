package com.orderflow.auth.support;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Torna mensurável o critério 3 do ROADMAP — "sem nenhuma chamada em tempo de execução ao
 * auth-service" para validar um token já emitido (D-03, plano 01-05 Task 2). Registra um
 * {@link OncePerRequestFilter} com a maior precedência possível para que a contagem inclua também
 * requisições que a cadeia de segurança rejeita antes de chegar ao controller.
 */
@TestConfiguration
public class JwksAccessCounter {

    public static final String JWKS_PATH = "/.well-known/jwks.json";

    @Bean
    public AtomicInteger jwksAccessCount() {
        return new AtomicInteger(0);
    }

    @Bean
    public FilterRegistrationBean<OncePerRequestFilter> jwksAccessCounterFilter(AtomicInteger jwksAccessCount) {
        OncePerRequestFilter filter = new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                             FilterChain filterChain) throws ServletException, IOException {
                if (JWKS_PATH.equals(request.getRequestURI())) {
                    jwksAccessCount.incrementAndGet();
                }
                filterChain.doFilter(request, response);
            }
        };

        FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
