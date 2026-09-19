package com.orderflow.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.auth.company.CompanyRepository;
import com.orderflow.auth.support.TestTokens;
import com.orderflow.auth.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prova de AUTH-01 e COMP-01 contra PostgreSQL real: o vendedor cria empresas compradoras reais
 * com limite de crédito e o usuário BUYER vinculado, numa única requisição transacional, com
 * autorização por papel e validação monetária exata (T-01-23 a T-01-29).
 */
class CompanyControllerIT extends AbstractIntegrationTest {

    private static final String ADMIN_EMAIL = "admin@orderflow.local";
    private static final String ADMIN_PASSWORD = "ChangeMe!123";

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtDecoder jwtDecoder;

    private String adminToken() throws Exception {
        return TestTokens.loginAndGetToken(mockMvc, objectMapper, ADMIN_EMAIL, ADMIN_PASSWORD);
    }

    private String createCompanyPayload(String name, String creditLimit, String buyerEmail, String buyerPassword) {
        return """
                {"name":"%s","creditLimit":"%s","buyerUser":{"email":"%s","password":"%s"}}
                """.formatted(name, creditLimit, buyerEmail, buyerPassword);
    }

    @Test
    void createCompanyWithBuyerReturns201WithExpectedBody() throws Exception {
        String token = adminToken();

        mockMvc.perform(post("/companies")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createCompanyPayload(
                                "Atacadao Silva", "1500.50", "comprador1@silva.com", "Comprador!123")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.name").value("Atacadao Silva"))
                .andExpect(jsonPath("$.creditLimit").value(1500.50))
                .andExpect(jsonPath("$.buyerUser.role").value("BUYER"));
    }

    @Test
    void createCompanyIncreasesCompaniesAndUsersRowCountsByOne() throws Exception {
        long companiesBefore = companyRepository.count();
        long usersBefore = userRepository.count();

        String token = adminToken();
        MvcResult result = mockMvc.perform(post("/companies")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createCompanyPayload(
                                "Contagem Ltda", "200.00", "comprador2@silva.com", "Comprador!123")))
                .andExpect(status().isCreated())
                .andReturn();

        assertThat(companyRepository.count()).isEqualTo(companiesBefore + 1);
        assertThat(userRepository.count()).isEqualTo(usersBefore + 1);

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        String companyId = json.get("id").asText();
        var createdUser = userRepository.findByEmail("comprador2@silva.com").orElseThrow();
        assertThat(createdUser.getRole().name()).isEqualTo("BUYER");
        assertThat(createdUser.getCompanyId().toString()).isEqualTo(companyId);
    }

    @Test
    void createCompanyResponseNeverLeaksPassword() throws Exception {
        String token = adminToken();

        MvcResult result = mockMvc.perform(post("/companies")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createCompanyPayload(
                                "Sigilo Ltda", "300.00", "comprador3@silva.com", "SenhaSecreta!1")))
                .andExpect(status().isCreated())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("SenhaSecreta!1");
        assertThat(body).doesNotContainIgnoringCase("\"password\"");
        assertThat(body).doesNotContainIgnoringCase("\"passwordHash\"");
    }

    @Test
    void createCompanyIgnoresClientSuppliedRoleAndAlwaysCreatesBuyer() throws Exception {
        String token = adminToken();
        String payload = """
                {"name":"Papel Ignorado Ltda","creditLimit":"50.00",
                 "buyerUser":{"email":"comprador4@silva.com","password":"Comprador!123","role":"SELLER_ADMIN"}}
                """;

        mockMvc.perform(post("/companies")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.buyerUser.role").value("BUYER"));

        var createdUser = userRepository.findByEmail("comprador4@silva.com").orElseThrow();
        assertThat(createdUser.getRole().name()).isEqualTo("BUYER");
    }

    @Test
    void createCompanyWithoutAuthorizationHeaderReturns401() throws Exception {
        mockMvc.perform(post("/companies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createCompanyPayload(
                                "Sem Token Ltda", "10.00", "comprador5@silva.com", "Comprador!123")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createCompanyWithBuyerTokenReturns403AndDoesNotInsertRow() throws Exception {
        // Cria uma empresa/BUYER de apoio via SELLER_ADMIN, depois loga como esse BUYER.
        String adminToken = adminToken();
        mockMvc.perform(post("/companies")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createCompanyPayload(
                                "Base Buyer Ltda", "10.00", "buyer.autorizacao@silva.com", "Comprador!123")))
                .andExpect(status().isCreated());

        String buyerToken = TestTokens.loginAndGetToken(
                mockMvc, objectMapper, "buyer.autorizacao@silva.com", "Comprador!123");

        long companiesBefore = companyRepository.count();

        mockMvc.perform(post("/companies")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createCompanyPayload(
                                "Nao Deveria Existir Ltda", "10.00", "comprador6@silva.com", "Comprador!123")))
                .andExpect(status().isForbidden());

        assertThat(companyRepository.count()).isEqualTo(companiesBefore);
    }

    @Test
    void createCompanyWithBlankNameReturns400WithFieldErrors() throws Exception {
        String token = adminToken();

        mockMvc.perform(post("/companies")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createCompanyPayload("", "10.00", "comprador7@silva.com", "Comprador!123")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.name").isNotEmpty());
    }

    @Test
    void createCompanyWithNegativeCreditLimitReturns400() throws Exception {
        String token = adminToken();

        mockMvc.perform(post("/companies")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createCompanyPayload(
                                "Negativo Ltda", "-1.00", "comprador8@silva.com", "Comprador!123")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createCompanyWithThreeDecimalCreditLimitReturns400AndCreatesNoCompany() throws Exception {
        String token = adminToken();
        long companiesBefore = companyRepository.count();

        mockMvc.perform(post("/companies")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createCompanyPayload(
                                "Tres Casas Ltda", "100.005", "comprador9@silva.com", "Comprador!123")))
                .andExpect(status().isBadRequest());

        assertThat(companyRepository.count()).isEqualTo(companiesBefore);
    }

    @Test
    void createCompanyWithZeroCreditLimitReturns201() throws Exception {
        String token = adminToken();

        mockMvc.perform(post("/companies")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createCompanyPayload(
                                "Zero Ltda", "0.00", "comprador10@silva.com", "Comprador!123")))
                .andExpect(status().isCreated());
    }

    @Test
    void createCompanyWithDuplicateEmailReturns409AndRollsBackTransaction() throws Exception {
        String token = adminToken();
        mockMvc.perform(post("/companies")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createCompanyPayload(
                                "Primeira Ltda", "10.00", "duplicado@silva.com", "Comprador!123")))
                .andExpect(status().isCreated());

        long companiesBefore = companyRepository.count();

        mockMvc.perform(post("/companies")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createCompanyPayload(
                                "Segunda Ltda", "10.00", "duplicado@silva.com", "Comprador!123")))
                .andExpect(status().isConflict());

        assertThat(companyRepository.count()).isEqualTo(companiesBefore);
    }

    @Test
    void errorBodiesForValidationForbiddenAndConflictShareUniformShapeWithoutLeakage() throws Exception {
        String token = adminToken();

        MvcResult validation = mockMvc.perform(post("/companies")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createCompanyPayload("", "-1.00", "invalido@silva.com", "Comprador!123")))
                .andExpect(status().isBadRequest())
                .andReturn();

        mockMvc.perform(post("/companies")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createCompanyPayload(
                                "Conflito Base Ltda", "10.00", "conflito@silva.com", "Comprador!123")))
                .andExpect(status().isCreated());
        MvcResult conflict = mockMvc.perform(post("/companies")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createCompanyPayload(
                                "Conflito Ltda", "10.00", "conflito@silva.com", "Comprador!123")))
                .andExpect(status().isConflict())
                .andReturn();

        String buyerTokenForForbidden = TestTokens.loginAndGetToken(
                mockMvc, objectMapper, "conflito@silva.com", "Comprador!123");
        MvcResult forbidden = mockMvc.perform(post("/companies")
                        .header("Authorization", "Bearer " + buyerTokenForForbidden)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createCompanyPayload(
                                "Proibido Ltda", "10.00", "outro@silva.com", "Comprador!123")))
                .andExpect(status().isForbidden())
                .andReturn();

        for (MvcResult result : new MvcResult[]{validation, forbidden, conflict}) {
            String body = result.getResponse().getContentAsString();
            JsonNode json = objectMapper.readTree(body);
            assertThat(json.has("error")).isTrue();
            assertThat(json.has("message")).isTrue();
            assertThat(body).doesNotContain("Exception");
            assertThat(body).doesNotContain("at com.orderflow");
            assertThat(body).doesNotContain("Comprador!123");
        }
    }

    /**
     * Task 2 (AUTH-02): o ciclo completo "admin cria empresa → BUYER faz login com o company_id
     * certo" — o claim {@code company_id} do JWT do BUYER compara por igualdade em texto com o
     * {@code id} devolvido por {@code POST /companies}, sem conversão.
     */
    @Test
    void buyerCreatedByAdminLogsInAndReceivesCompanyIdClaimMatchingCreatedCompany() throws Exception {
        String adminToken = adminToken();
        MvcResult creation = mockMvc.perform(post("/companies")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createCompanyPayload(
                                "Encadeado Ltda", "750.25", "encadeado@silva.com", "Comprador!123")))
                .andExpect(status().isCreated())
                .andReturn();
        String companyId = objectMapper.readTree(creation.getResponse().getContentAsString()).get("id").asText();

        String buyerToken = TestTokens.loginAndGetToken(mockMvc, objectMapper, "encadeado@silva.com", "Comprador!123");
        Jwt decoded = jwtDecoder.decode(buyerToken);
        assertThat(decoded.getClaimAsString("role")).isEqualTo("BUYER");
        assertThat(decoded.getClaimAsString("company_id")).isEqualTo(companyId);

        mockMvc.perform(get("/auth/me").header("Authorization", "Bearer " + buyerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companyId").value(companyId))
                .andExpect(jsonPath("$.role").value("BUYER"));
    }
}
