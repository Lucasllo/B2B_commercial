package com.orderflow.inventory.config;

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
 * {@code inventory-service} e apenas resource server — nunca emite token, so valida o que o
 * auth-service emitiu (por isso nao ha {@code passwordEncoder}/{@code authenticationManager}
 * aqui, ao contrario do {@code auth-service}). {@code SecurityFilterChain} via DSL de
 * {@code HttpSecurity} — {@code WebSecurityConfigurerAdapter} foi removido no Spring Security 6
 * (CLAUDE.md §What NOT to Use).
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * Le o claim {@code role} (uma unica string, nao uma lista) e converte na authority
     * {@code ROLE_} + valor do claim. Sem este conversor explicito, o
     * {@code JwtGrantedAuthoritiesConverter} padrao procura uma lista em {@code scope}/{@code scp}
     * e toda checagem de papel falha em silencio, devolvendo 403 para o SELLER_ADMIN.
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
                // API stateless, sem cookie de sessao — nao ha estado de sessao para um token
                // CSRF proteger (nenhum formulario HTML, apenas Authorization: Bearer).
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Superficie nao autenticada aberta deliberadamente para ferramenta local
                        // de desenvolvimento (Swagger UI); expoe o formato da API, nunca dado nem
                        // logica. As portas diretas 8081/8082/8083 estao ligadas a 127.0.0.1 no
                        // docker-compose.yml e nao sao roteadas pelo gateway. Deve ser fechada se
                        // algum dia existir um profile de producao neste projeto (hoje nao existe).
                        .requestMatchers("/actuator/health/**", "/swagger-ui.html", "/swagger-ui/**",
                                "/v3/api-docs", "/v3/api-docs/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
                .build();
    }
}
