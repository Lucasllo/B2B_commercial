package com.orderflow.auth.company;

import com.orderflow.auth.company.dto.CompanyResponse;
import com.orderflow.auth.company.dto.CreateCompanyRequest;
import com.orderflow.auth.company.dto.CreditLimitResponse;
import com.orderflow.auth.user.Role;
import com.orderflow.auth.user.User;
import com.orderflow.auth.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regras de empresa e comprador sem Spring nem Docker (TEST-01; D-06, AUTH-02, COMP-03): papel BUYER
 * fixado no código, senha sempre codificada, e-mail duplicado recusado antes de gravar a empresa e
 * limite de crédito gravado exatamente como informado.
 */
@ExtendWith(MockitoExtension.class)
class CompanyServiceTest {

    @Mock
    private CompanyRepository companyRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;

    private CompanyService service() {
        return new CompanyService(companyRepository, userRepository, passwordEncoder);
    }

    private static CreateCompanyRequest request(String email, String password) {
        return new CreateCompanyRequest("Atacadao Silva", new BigDecimal("1500.50"),
                new CreateCompanyRequest.BuyerUser(email, password));
    }

    private static Company companyWithId(UUID id, String creditLimit) {
        Company company = new Company("Empresa", new BigDecimal(creditLimit));
        ReflectionTestUtils.setField(company, "id", id);
        return company;
    }

    /** E-mail já usado: nada é gravado (AUTH-02). */
    @Test
    void createWithAnEmailAlreadyInUseThrowsAndSavesNeitherCompanyNorUser() {
        when(userRepository.findByEmail("dup@silva.com"))
                .thenReturn(Optional.of(new User("dup@silva.com", "hash", Role.BUYER, UUID.randomUUID())));

        assertThatThrownBy(() -> service().createCompanyWithBuyer(request("dup@silva.com", "Comprador!123")))
                .isInstanceOf(EmailAlreadyUsedException.class);

        verify(companyRepository, never()).save(any(Company.class));
        verify(userRepository, never()).save(any(User.class));
    }

    /** O comprador nasce BUYER, ligado à empresa salva, com a senha devolvida pelo encoder. */
    @Test
    void createSavesABuyerLinkedToTheSavedCompanyWithTheEncodedPassword() {
        UUID companyId = UUID.randomUUID();
        when(userRepository.findByEmail("novo@silva.com")).thenReturn(Optional.empty());
        when(companyRepository.save(any(Company.class))).thenReturn(companyWithId(companyId, "1500.50"));
        when(passwordEncoder.encode("Comprador!123")).thenReturn("{encoded}hash");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CompanyResponse response = service().createCompanyWithBuyer(request("novo@silva.com", "Comprador!123"));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User saved = captor.getValue();
        assertThat(saved.getRole()).isEqualTo(Role.BUYER);
        assertThat(saved.getCompanyId()).isEqualTo(companyId);
        assertThat(saved.getEmail()).isEqualTo("novo@silva.com");
        assertThat(saved.getPasswordHash()).isEqualTo("{encoded}hash").isNotEqualTo("Comprador!123");
        assertThat(response.id()).isEqualTo(companyId);
        assertThat(response.buyerUser().role()).isEqualTo("BUYER");
    }

    /** O limite é gravado com o BigDecimal recebido, sem arredondar (D-06). */
    @Test
    void updateCreditLimitStoresTheExactValueReceived() {
        UUID id = UUID.randomUUID();
        Company company = companyWithId(id, "100.00");
        when(companyRepository.findById(id)).thenReturn(Optional.of(company));

        CreditLimitResponse response = service().updateCreditLimit(id, new BigDecimal("12345.67"));

        assertThat(company.getCreditLimit()).isEqualTo(new BigDecimal("12345.67"));
        assertThat(company.getCreditLimit().scale()).isEqualTo(2);
        assertThat(response.creditLimit()).isEqualTo(new BigDecimal("12345.67"));
        assertThat(response.companyId()).isEqualTo(id);
    }

    /** Empresa inexistente: atualizar o limite lança CompanyNotFoundException (COMP-03). */
    @Test
    void updateCreditLimitOfUnknownCompanyThrowsCompanyNotFound() {
        UUID id = UUID.randomUUID();
        when(companyRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().updateCreditLimit(id, new BigDecimal("10.00")))
                .isInstanceOf(CompanyNotFoundException.class);
    }

    /** A consulta devolve o limite gravado. */
    @Test
    void getCreditLimitReturnsTheStoredLimit() {
        UUID id = UUID.randomUUID();
        when(companyRepository.findById(id)).thenReturn(Optional.of(companyWithId(id, "777.70")));

        CreditLimitResponse response = service().getCreditLimit(id);

        assertThat(response.companyId()).isEqualTo(id);
        assertThat(response.creditLimit()).isEqualByComparingTo("777.70");
    }

    /** Empresa inexistente: consultar o limite lança CompanyNotFoundException. */
    @Test
    void getCreditLimitOfUnknownCompanyThrowsCompanyNotFound() {
        UUID id = UUID.randomUUID();
        when(companyRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().getCreditLimit(id)).isInstanceOf(CompanyNotFoundException.class);
    }
}
