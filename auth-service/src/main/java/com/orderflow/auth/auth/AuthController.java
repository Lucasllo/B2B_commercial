package com.orderflow.auth.auth;

import com.orderflow.auth.auth.dto.CurrentUserResponse;
import com.orderflow.auth.auth.dto.LoginRequest;
import com.orderflow.auth.auth.dto.LoginResponse;
import com.orderflow.auth.user.User;
import com.orderflow.auth.user.UserRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * {@code POST /auth/login} e {@code GET /auth/me} — o tracer de ponta a ponta desta fase.
 */
@RestController
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;
    private final TokenService tokenService;

    public AuthController(AuthenticationManager authenticationManager,
                           UserRepository userRepository,
                           TokenService tokenService) {
        this.authenticationManager = authenticationManager;
        this.userRepository = userRepository;
        this.tokenService = tokenService;
    }

    @PostMapping("/auth/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.email(), request.password()));

        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new BadCredentialsException("Credenciais inválidas"));

        String token = tokenService.issueToken(user);
        return new LoginResponse(token, "Bearer", tokenService.getTokenTtlSeconds());
    }

    @GetMapping("/auth/me")
    public CurrentUserResponse me(@AuthenticationPrincipal Jwt jwt) {
        return new CurrentUserResponse(
                jwt.getSubject(),
                jwt.getClaimAsString("role"),
                jwt.getClaimAsString("company_id"));
    }

    /**
     * Corpo idêntico para senha errada e email inexistente — nunca ecoa o email/senha recebidos,
     * sob pena de virar um oráculo de enumeração de usuários (T-01-01).
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Map<String, String>> handleAuthenticationException(AuthenticationException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "Credenciais inválidas"));
    }
}
