package com.orderflow.auth.company;

import com.orderflow.auth.company.dto.CompanyResponse;
import com.orderflow.auth.company.dto.CreateCompanyRequest;
import com.orderflow.auth.user.Role;
import com.orderflow.auth.user.User;
import com.orderflow.auth.user.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cria uma empresa compradora e o usuário BUYER vinculado a ela numa única transação (AUTH-01,
 * COMP-01). {@code @Transactional} é o que garante que um email duplicado detectado pela
 * constraint única do banco não deixe uma empresa órfã (T-01-29).
 */
@Service
public class CompanyService {

    private final CompanyRepository companyRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public CompanyService(CompanyRepository companyRepository,
                           UserRepository userRepository,
                           PasswordEncoder passwordEncoder) {
        this.companyRepository = companyRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public CompanyResponse createCompanyWithBuyer(CreateCompanyRequest request) {
        // Checagem antecipada, defensiva: a UNIQUE constraint em users.email é a garantia real
        // (T-01-29), mas checar antes evita gravar a empresa em cenários sem condição de corrida.
        if (userRepository.findByEmail(request.buyerUser().email()).isPresent()) {
            throw new EmailAlreadyUsedException("Email already in use");
        }

        Company company = companyRepository.save(new Company(request.name(), request.creditLimit()));

        // Role.BUYER é fixado aqui por literal no código — o request nunca carrega um campo de
        // papel, então não há valor de cliente a ignorar/mapear (T-01-24, mass assignment).
        User buyer = userRepository.save(new User(
                request.buyerUser().email(),
                passwordEncoder.encode(request.buyerUser().password()),
                Role.BUYER,
                company.getId()));

        return new CompanyResponse(
                company.getId(),
                company.getName(),
                company.getCreditLimit(),
                company.getCreatedAt(),
                new CompanyResponse.BuyerUserSummary(buyer.getId(), buyer.getEmail(), buyer.getRole().name()));
    }
}
