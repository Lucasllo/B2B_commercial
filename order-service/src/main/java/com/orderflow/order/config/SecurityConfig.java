package com.orderflow.order.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.List;

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

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationConverter jwtAuthenticationConverter)
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
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
                .build();
    }
}
