package com.orderflow.auth.company;

/**
 * Lançada quando um {@code companyId} de path não corresponde a nenhuma empresa persistida. Só
 * pode ser lançada depois que {@code @PreAuthorize} já autorizou a requisição — o guard de
 * isolamento (T-01-33) roda antes de qualquer consulta ao repositório, então este 404 nunca
 * distingue "empresa de outro" de "empresa inexistente" para um comprador (esse caso é sempre
 * 403, produzido pelo guard, e nunca chega a esta exceção).
 */
public class CompanyNotFoundException extends RuntimeException {

    public CompanyNotFoundException(String message) {
        super(message);
    }
}
