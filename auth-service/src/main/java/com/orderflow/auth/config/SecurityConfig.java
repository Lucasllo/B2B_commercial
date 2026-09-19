package com.orderflow.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.List;

/**
 * {@code SecurityFilterChain} via DSL de {@code HttpSecurity} — {@code WebSecurityConfigurerAdapter}
 * foi removido no Spring Security 6 e nem compila (CLAUDE.md §What NOT to Use). API stateless, sem
 * cookie de sessão: CSRF é desabilitado com justificativa explícita, não por padrão descuidado.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Spring Boot conecta automaticamente o único {@code UserDetailsService} e o único
     * {@code PasswordEncoder} presentes no contexto a um {@code DaoAuthenticationProvider} do
     * {@code AuthenticationManagerBuilder} global — não é preciso declarar o provider à mão. O
     * comportamento padrão desse provider esconde {@code UsernameNotFoundException} como
     * {@code BadCredentialsException}, que é o que faz senha errada e email inexistente
     * devolverem exatamente o mesmo 401 (T-01-01), sem virar oráculo de enumeração de usuários.
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    /**
     * Lê o claim {@code role} (uma única string, não uma lista) e converte na authority
     * {@code ROLE_} + valor do claim. {@code JwtGrantedAuthoritiesConverter} padrão espera uma
     * lista em {@code scope}/{@code scp}; como nosso claim de papel é uma string singular com
     * outro nome, um conversor explícito evita esse descompasso.
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
                        .requestMatchers("/auth/login", "/.well-known/jwks.json", "/actuator/health/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
                .build();
    }
}
