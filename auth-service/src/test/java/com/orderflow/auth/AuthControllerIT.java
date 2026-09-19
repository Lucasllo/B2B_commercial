package com.orderflow.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.orderflow.auth.auth.dto.LoginRequest;
import com.orderflow.auth.user.User;
import com.orderflow.auth.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MvcResult;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prova ponta a ponta do tracer da Fase 1: login real do SELLER_ADMIN semeado por Flyway contra
 * um PostgreSQL real (Testcontainers), emissão de JWT RS256 verificável, contrato JWKS e rejeição
 * de tokens inválidos/expirados/ausentes — tudo sem nenhuma chamada em tempo de execução ao próprio
 * auth-service (D-03/AUTH-03).
 */
class AuthControllerIT extends AbstractIntegrationTest {

    private static final String ADMIN_EMAIL = "admin@orderflow.local";
    private static final String ADMIN_PASSWORD = "ChangeMe!123";

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private UserRepository userRepository;

    private String login(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, password))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return json.get("accessToken").asText();
    }

    @Test
    void loginWithValidCredentialsReturns200WithAccessToken() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(ADMIN_EMAIL, ADMIN_PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(3600));
    }

    @Test
    void issuedTokenHasExpectedClaimsAndHeader() throws Exception {
        String token = login(ADMIN_EMAIL, ADMIN_PASSWORD);
        Jwt decoded = jwtDecoder.decode(token);
        User admin = userRepository.findByEmail(ADMIN_EMAIL).orElseThrow();

        // getClaimAsString, não getIssuer(): "iss" aqui não é uma URL válida de propósito
        // (D-02/D-03), e Jwt#getIssuer() lança exceção ao tentar convertê-la para URL.
        assertThat(decoded.getClaimAsString("iss")).isEqualTo("orderflow-auth-service");
        assertThat(decoded.getSubject()).isEqualTo(admin.getId().toString());
        assertThat(decoded.getClaimAsString("role")).isEqualTo("SELLER_ADMIN");
        assertThat(decoded.getClaimAsString("company_id")).isNull();
        assertThat(decoded.getExpiresAt()).isNotNull();
        assertThat(decoded.getIssuedAt()).isNotNull();
        assertThat(decoded.getExpiresAt().getEpochSecond() - decoded.getIssuedAt().getEpochSecond())
                .isEqualTo(3600L);

        assertThat(decoded.getHeaders().get("alg").toString()).contains("RS256");

        String jwksBody = mockMvc.perform(get("/.well-known/jwks.json"))
                .andReturn().getResponse().getContentAsString();
        JsonNode jwks = objectMapper.readTree(jwksBody);
        String publishedKid = jwks.get("keys").get(0).get("kid").asText();
        assertThat(decoded.getHeaders().get("kid")).isEqualTo(publishedKid);
    }

    @Test
    void loginWithWrongPasswordAndUnknownEmailReturnIdenticalUnauthorizedBody() throws Exception {
        MvcResult wrongPassword = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(ADMIN_EMAIL, "senha-errada"))))
                .andExpect(status().isUnauthorized())
                .andReturn();

        MvcResult unknownEmail = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("naoexiste@orderflow.local", "qualquer-coisa"))))
                .andExpect(status().isUnauthorized())
                .andReturn();

        assertThat(wrongPassword.getResponse().getContentAsString())
                .isEqualTo(unknownEmail.getResponse().getContentAsString());
    }

    @Test
    void loginWithBlankEmailReturns400() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("", ADMIN_PASSWORD))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void loginWithBlankPasswordReturns400() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(ADMIN_EMAIL, ""))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void jwksEndpointReturnsPublicKeyOnly() throws Exception {
        MvcResult result = mockMvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys.length()").value(1))
                .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                .andExpect(jsonPath("$.keys[0].use").value("sig"))
                .andExpect(jsonPath("$.keys[0].alg").value("RS256"))
                .andExpect(jsonPath("$.keys[0].kid").isNotEmpty())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        for (String privateField : List.of("\"d\":", "\"p\":", "\"q\":", "\"dp\":", "\"dq\":", "\"qi\":")) {
            assertThat(body).doesNotContain(privateField);
        }
    }

    @Test
    void meWithValidTokenReturns200WithClaims() throws Exception {
        String token = login(ADMIN_EMAIL, ADMIN_PASSWORD);
        User admin = userRepository.findByEmail(ADMIN_EMAIL).orElseThrow();

        mockMvc.perform(get("/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(admin.getId().toString()))
                .andExpect(jsonPath("$.role").value("SELLER_ADMIN"))
                .andExpect(jsonPath("$.companyId").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void meWithoutAuthorizationHeaderReturns401() throws Exception {
        mockMvc.perform(get("/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void meWithExpiredTokenReturns401() throws Exception {
        Instant past = Instant.now().minusSeconds(7200);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("orderflow-auth-service")
                .issuedAt(past)
                .expiresAt(past.plusSeconds(3600))
                .subject(UUID.randomUUID().toString())
                .claim("role", "SELLER_ADMIN")
                .build();
        String expiredToken = jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();

        mockMvc.perform(get("/auth/me").header("Authorization", "Bearer " + expiredToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void meWithTokenSignedByDifferentKeyReturns401() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair otherKeyPair = generator.generateKeyPair();

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer("orderflow-auth-service")
                .subject(UUID.randomUUID().toString())
                .claim("role", "SELLER_ADMIN")
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(3600)))
                .build();

        SignedJWT signedJwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("chave-diferente").build(),
                claims);
        signedJwt.sign(new RSASSASigner(otherKeyPair.getPrivate()));

        mockMvc.perform(get("/auth/me").header("Authorization", "Bearer " + signedJwt.serialize()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void actuatorHealthIsPubliclyAccessible() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }
}
