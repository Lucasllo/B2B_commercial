package com.orderflow.auth.company.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Payload único de criação de empresa compradora + usuário BUYER vinculado, numa só requisição
 * transacional (AUTH-01/COMP-01, decisão de "Claude's Discretion" do 01-CONTEXT.md).
 *
 * <p>Não declara nenhum campo de papel/role — o papel é sempre {@code BUYER}, fixado no servidor
 * por {@code CompanyService} (T-01-24, defesa contra mass assignment). {@code @JsonIgnoreProperties}
 * garante que um campo extra de papel enviado pelo cliente (ex.: {@code "role":"SELLER_ADMIN"}) é
 * silenciosamente ignorado na desserialização, em vez de derrubar a requisição inteira com um erro
 * de parsing — o servidor nunca lê esse valor de qualquer forma.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateCompanyRequest(
        @NotBlank @Size(max = 255) String name,

        // @Digits(fraction = 2) é o mecanismo que rejeita (400) em vez de arredondar um valor com
        // mais de 2 casas decimais — implementação da proibição registrada em
        // must_haves.prohibitions do plano 01-04 (T-01-25).
        @NotNull @DecimalMin(value = "0.00") @Digits(integer = 17, fraction = 2) BigDecimal creditLimit,

        @NotNull @Valid BuyerUser buyerUser
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BuyerUser(
            @NotBlank @Email String email,
            @NotBlank @Size(min = 8) String password
    ) {
    }
}
