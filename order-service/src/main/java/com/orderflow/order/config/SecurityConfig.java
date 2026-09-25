package com.orderflow.order.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code order-service} é apenas resource server — nunca emite token, só valida o que o
 * auth-service emitiu (por isso não há {@code passwordEncoder}/{@code authenticationManager}
 * aqui, ao contrário do {@code auth-service}). {@code SecurityFilterChain} via DSL de
 * {@code HttpSecurity} — {@code WebSecurityConfigurerAdapter} foi removido no Spring Security 6
 * (CLAUDE.md §What NOT to Use).
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * Lê o claim {@code role} (uma única string, não uma lista) e converte na authority
     * {@code ROLE_} + valor do claim. Sem este conversor explícito, o
     * {@code JwtGrantedAuthoritiesConverter} padrão procura uma lista em {@code scope}/{@code scp}
     * e toda checagem de papel falha em silêncio, devolvendo 403 para o BUYER/SELLER_ADMIN.
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            String role = jwt.getClaimAsString("role");
            if (role == null) {
                return List.<GrantedAuthority>of();
            }
            return List.<GrantedAuthority>of(new SimpleGrantedAuthority("ROLE_" + role));
        });
        return converter;
    }

    /**
     * Rejeição de JWT (assinatura de outra chave, {@code exp} no passado, {@code iss} diferente,
     * ausência de token) acontece no filtro de segurança, ANTES do {@code DispatcherServlet} — o
     * {@code GlobalExceptionHandler} (um {@code @RestControllerAdvice}) nunca vê essa exceção, e
     * sem este ponto de entrada customizado o corpo do 401 sai vazio, quebrando o mesmo envelope
     * uniforme ({@code {"error","message"}}) usado por todo o resto do serviço (04-02 Task 2).
     */
    @Bean
    public AuthenticationEntryPoint authenticationEntryPoint(ObjectMapper objectMapper) {
        return (request, response, authException) -> {
            response.setStatus(org.springframework.http.HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "unauthorized");
            body.put("message", "Authentication is required");
            objectMapper.writeValue(response.getWriter(), body);
        };
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationConverter jwtAuthenticationConverter,
                                                     AuthenticationEntryPoint authenticationEntryPoint)
            throws Exception {
        return http
                // API stateless, sem cookie de sessão — não há estado de sessão para um token
                // CSRF proteger (nenhum formulário HTML, apenas Authorization: Bearer).
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Superfície não autenticada aberta deliberadamente para ferramenta local
                        // de desenvolvimento (Swagger UI); expõe o formato da API, nunca dado nem
                        // lógica. A porta direta 8085 está ligada a 127.0.0.1 no docker-compose.yml
                        // e não é roteada pelo gateway. Deve ser fechada se algum dia existir um
                        // profile de produção neste projeto (hoje não existe).
                        .requestMatchers("/actuator/health/**", "/swagger-ui.html", "/swagger-ui/**",
                                "/v3/api-docs", "/v3/api-docs/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                        .authenticationEntryPoint(authenticationEntryPoint))
                .build();
    }
}
