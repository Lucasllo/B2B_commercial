package com.orderflow.auth.auth;

import com.orderflow.auth.auth.dto.LoginRequest;
import com.orderflow.auth.auth.dto.LoginResponse;
import com.orderflow.auth.config.GlobalExceptionHandler;
import com.orderflow.auth.user.User;
import com.orderflow.auth.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Regra do login, sem Spring e sem Docker (TEST-01, AUTH-02): o token só é emitido depois que o
 * {@link AuthenticationManager} aceita a credencial; credencial recusada propaga a exceção sem
 * consultar o usuário nem emitir token; e o corpo do 401 é o mesmo para qualquer {@link
 * AuthenticationException} — e-mail desconhecido e senha errada não são distinguíveis por quem chama.
 */
@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private UserRepository userRepository;

    @Mock
    private TokenService tokenService;

    @Mock
    private User user;

    private AuthController controller() {
        return new AuthController(authenticationManager, userRepository, tokenService);
    }

    @Test
    void validCredentialsAuthenticateLoadTheUserAndIssueABearerTokenWithTheConfiguredTtl() {
        when(userRepository.findByEmail("buyer@example.com")).thenReturn(Optional.of(user));
        when(tokenService.issueToken(user)).thenReturn("jwt-value");
        when(tokenService.getTokenTtlSeconds()).thenReturn(3600L);

        LoginResponse response = controller().login(new LoginRequest("buyer@example.com", "secret"));

        assertThat(response.accessToken()).isEqualTo("jwt-value");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(3600L);
        verify(authenticationManager).authenticate(
                new UsernamePasswordAuthenticationToken("buyer@example.com", "secret"));
    }

    @Test
    void aCredentialRefusedByTheAuthenticationManagerNeverLoadsTheUserNorIssuesAToken() {
        when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("Bad credentials"));

        assertThatThrownBy(() -> controller().login(new LoginRequest("buyer@example.com", "wrong")))
                .isInstanceOf(BadCredentialsException.class);

        verifyNoInteractions(userRepository, tokenService);
    }

    @Test
    void anAuthenticatedEmailWithoutAUserRowIsStillABadCredentialAndNoTokenIsIssued() {
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller().login(new LoginRequest("ghost@example.com", "secret")))
                .isInstanceOf(BadCredentialsException.class);

        verify(tokenService, never()).issueToken(any());
    }

    @Test
    void theUnauthorizedBodyIsTheSameForAnyAuthenticationExceptionSoUnknownEmailAndWrongPasswordLookAlike() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ResponseEntity<Map<String, Object>> wrongPassword =
                handler.handleAuthentication(new BadCredentialsException("Bad credentials"));
        ResponseEntity<Map<String, Object>> unknownEmail =
                handler.handleAuthentication(new UsernameNotFoundException("no such user ghost@example.com"));

        assertThat(wrongPassword.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unknownEmail.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unknownEmail.getBody()).isEqualTo(wrongPassword.getBody());
        assertThat(wrongPassword.getBody()).containsEntry("error", "unauthorized");
        assertThat(String.valueOf(unknownEmail.getBody())).doesNotContain("ghost@example.com");
    }
}
