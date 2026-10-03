package com.orderflow.auth.auth;

import com.orderflow.auth.auth.dto.CurrentUserResponse;
import com.orderflow.auth.auth.dto.LoginRequest;
import com.orderflow.auth.auth.dto.LoginResponse;
import com.orderflow.auth.config.ErrorResponse;
import com.orderflow.auth.user.User;
import com.orderflow.auth.user.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Autenticação", description = "Login público e leitura do usuário atual a partir do JWT.")
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

    // Login é a única operação pública: é onde o avaliador obtém o token para o botão Authorize (D-89).
    @SecurityRequirements
    @PostMapping("/auth/login")
    @Operation(summary = "Entrar", description = "Operação pública. Não envie bearerAuth; use a resposta para preencher o Authorize.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Token emitido",
                    content = @Content(schema = @Schema(implementation = LoginResponse.class))),
            @ApiResponse(responseCode = "400", description = "Corpo inválido (validation_failed)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "E-mail ou senha incorretos (unauthorized)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.email(), request.password()));

        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new BadCredentialsException("Credenciais inválidas"));

        String token = tokenService.issueToken(user);
        return new LoginResponse(token, "Bearer", tokenService.getTokenTtlSeconds());
    }

    @GetMapping("/auth/me")
    @Operation(summary = "Usuário atual", description = "Exige bearerAuth. Devolve os claims do JWT, sem consultar o banco.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Claims do token",
                    content = @Content(schema = @Schema(implementation = CurrentUserResponse.class))),
            @ApiResponse(responseCode = "401", description = "Token ausente ou inválido (unauthorized)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public CurrentUserResponse me(@AuthenticationPrincipal Jwt jwt) {
        return new CurrentUserResponse(
                jwt.getSubject(),
                jwt.getClaimAsString("role"),
                jwt.getClaimAsString("company_id"));
    }
}
