package com.orderflow.auth.auth;

import com.orderflow.auth.auth.dto.CurrentUserResponse;
import com.orderflow.auth.auth.dto.LoginRequest;
import com.orderflow.auth.auth.dto.LoginResponse;
import com.orderflow.auth.user.User;
import com.orderflow.auth.user.UserRepository;
import jakarta.validation.Valid;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /auth/login} e {@code GET /auth/me} — o tracer de ponta a ponta desta fase.
 *
 * <p>Falhas de autenticação (senha errada, email inexistente) propagam como
 * {@code AuthenticationException} e são tratadas pelo {@link com.orderflow.auth.config.GlobalExceptionHandler},
 * não por um handler local — isso mantém o corpo de erro no mesmo formato uniforme
 * ({@code error}/{@code message}) que todo o resto do serviço usa (T-01-27).
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
}
