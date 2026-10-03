package com.orderflow.auth.company.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "Cria a empresa compradora e o usuário BUYER na mesma requisição. O papel é fixado no servidor.")
public record CreateCompanyRequest(
        @Schema(description = "Razão social", example = "Comercial Aurora Ltda")
        @NotBlank @Size(max = 255) String name,

        // @Digits(fraction = 2) é o mecanismo que rejeita (400) em vez de arredondar um valor com
        // mais de 2 casas decimais — implementação da proibição registrada em
        // must_haves.prohibitions do plano 01-04 (T-01-25).
        @Schema(description = "Limite de crédito inicial com duas casas decimais", example = "50000.00")
        @NotNull @DecimalMin(value = "0.00") @Digits(integer = 17, fraction = 2) BigDecimal creditLimit,

        @Schema(description = "Usuário comprador vinculado")
        @NotNull @Valid BuyerUser buyerUser
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "Dados do usuário BUYER. Um campo role enviado pelo cliente é ignorado.")
    public record BuyerUser(
            @Schema(description = "E-mail do comprador", example = "comprador@exemplo.com")
            @NotBlank @Email @Size(max = 255) String email,
            @Schema(description = "Senha fictícia de demonstração, mínimo de 8 caracteres", example = "Comprador!1")
            @NotBlank @Size(min = 8) String password
    ) {
    }
}
