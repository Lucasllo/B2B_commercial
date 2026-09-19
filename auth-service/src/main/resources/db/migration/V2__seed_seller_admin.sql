-- Bootstrap do primeiro SELLER_ADMIN (D-07). Hash BCrypt pré-computado offline com
-- new BCryptPasswordEncoder().encode(...) sobre a senha de demonstração documentada no README
-- do projeto e provada por SeedPasswordHashTest — a senha em texto claro nunca aparece neste
-- arquivo. Este é um usuário de demonstração de portfólio, não uma credencial administrativa
-- real; NUNCA edite este arquivo após aplicado (Pitfall 5) — mudanças de seed vão para
-- V3__...sql.
INSERT INTO users (email, password_hash, role, company_id)
VALUES (
    'admin@orderflow.local',
    '$2a$10$o1M75kWYUb0EXxSu3NdWceaHKgY73niVPTn8eMdhBJycAOfad0I.6',
    'SELLER_ADMIN',
    NULL
);
