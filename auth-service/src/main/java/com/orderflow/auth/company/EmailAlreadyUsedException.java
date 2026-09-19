package com.orderflow.auth.company;

/**
 * Lançada quando o email do BUYER informado em {@code POST /companies} já pertence a um usuário
 * existente. Sem eco do email recebido na mensagem (T-01-27 — não vazar dado de entrada em erro).
 */
public class EmailAlreadyUsedException extends RuntimeException {

    public EmailAlreadyUsedException(String message) {
        super(message);
    }
}
