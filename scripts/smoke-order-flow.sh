#!/usr/bin/env bash
# Smoke de ponta a ponta contra a stack real (docker compose up): prova, pelo Gateway e com
# auth-service/catalog-service reais (nunca os stubs dos testes), os Success Criteria 1 a 4 do
# ROADMAP — criação de pedido decidida pelo limite de crédito (aprovado automático e pendente),
# decisão manual do vendedor com auditoria, recusa de produto descontinuado pelo catálogo real, e
# isolamento por empresa (04-05-PLAN.md Task 1).
#
# MSYS_NO_PATHCONV=1 evita que o Git Bash do Windows reescreva argumentos como
# /proc/sys/kernel/random/uuid como caminhos do Windows antes de repassá-los ao
# `docker compose exec`.
set -euo pipefail
export MSYS_NO_PATHCONV=1

cd "$(dirname "${BASH_SOURCE[0]}")/.."

SELLER_EMAIL="admin@orderflow.local"
SELLER_PASSWORD="ChangeMe!123"
GATEWAY_URL="http://localhost:8080"

fail() {
    echo "SMOKE FALHOU: $1" >&2
    exit 1
}

# Todo texto vindo do container é filtrado por strip_cr — um \r sobrando quebraria comparações
# de string exatas (ex.: código de status HTTP).
strip_cr() {
    tr -d '\r'
}

exec_gateway() {
    docker compose exec -T gateway "$@" | strip_cr
}

# Executa uma chamada HTTP pelo Gateway e devolve, numa única ida ao container (sem repetir a
# requisição — importante para POST com efeito colateral), tanto o código de status quanto o
# corpo. Preenche as variáveis globais REQ_STATUS e REQ_BODY.
do_request() {
    local out
    out=$(exec_gateway curl -s -w '\n%{http_code}' "$@")
    REQ_STATUS=$(printf '%s' "$out" | tail -n1)
    REQ_BODY=$(printf '%s' "$out" | sed '$d')
}

# Extrai o primeiro valor de uma chave string simples ("chave":"valor") do corpo JSON — greedy
# por padrão do sed, mas cada chave usada aqui aparece só uma vez no nível relevante do corpo.
extract_string() {
    local body="$1" field="$2"
    printf '%s' "$body" | sed -n "s/.*\"${field}\":\"\([^\"]*\)\".*/\1/p"
}

# Extrai a chave string do INÍCIO do corpo JSON — usado quando o mesmo nome de campo também
# aparece aninhado mais adiante (ex.: CompanyResponse.id no topo vs. buyerUser.id aninhado), caso
# em que o padrão greedy de extract_string capturaria a última ocorrência, não a primeira.
extract_first_string() {
    local body="$1" field="$2"
    printf '%s' "$body" | sed -n "s/^{\"${field}\":\"\([^\"]*\)\".*/\1/p"
}

# Extrai o primeiro valor de uma chave numérica/booleana simples ("chave":valor, sem aspas).
extract_raw() {
    local body="$1" field="$2"
    printf '%s' "$body" | sed -n "s/.*\"${field}\":\([0-9tfaunl]*\).*/\1/p"
}

# 1. Login do vendedor de demonstração e leitura do userId via GET /api/auth/me — decidedBy
#    registrado pelo vendedor nos passos seguintes tem que bater com este valor.
SELLER_LOGIN_BODY=$(exec_gateway curl -s -X POST "${GATEWAY_URL}/api/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"${SELLER_EMAIL}\",\"password\":\"${SELLER_PASSWORD}\"}")
SELLER_TOKEN=$(extract_string "$SELLER_LOGIN_BODY" accessToken)
[ -n "$SELLER_TOKEN" ] || fail "login do vendedor nao devolveu accessToken"

SELLER_ME_BODY=$(exec_gateway curl -s "${GATEWAY_URL}/api/auth/me" \
    -H "Authorization: Bearer ${SELLER_TOKEN}")
SELLER_USER_ID=$(extract_string "$SELLER_ME_BODY" userId)
[ -n "$SELLER_USER_ID" ] || fail "GET /api/auth/me do vendedor nao devolveu userId"
echo "1/15 vendedor autenticado (${SELLER_EMAIL}), userId=${SELLER_USER_ID}"

# Sufixo único por execução, gerado dentro do próprio container do Gateway — entra em e-mails e
# SKUs para que o script rode quantas vezes for preciso na mesma base sem colidir com execuções
# anteriores.
SUFFIX=$(exec_gateway cat /proc/sys/kernel/random/uuid)
[ -n "$SUFFIX" ] || fail "nao foi possivel gerar o sufixo unico da execucao"

# 2. Cria as empresas A e B, cada uma com limite de crédito 1000.00 e um BUYER de e-mail único; faz
#    login dos dois BUYERs.
create_company() {
    local label="$1" email="$2"
    exec_gateway curl -s -X POST "${GATEWAY_URL}/api/companies" \
        -H "Authorization: Bearer ${SELLER_TOKEN}" -H "Content-Type: application/json" \
        -d "{\"name\":\"Smoke Empresa ${label} ${SUFFIX}\",\"creditLimit\":1000.00,\"buyerUser\":{\"email\":\"${email}\",\"password\":\"ChangeMe!123\"}}"
}

COMPANY_A_BODY=$(create_company "A" "buyer-a-${SUFFIX}@orderflow.local")
COMPANY_A_ID=$(extract_first_string "$COMPANY_A_BODY" id)
[ -n "$COMPANY_A_ID" ] || fail "criacao da empresa A nao devolveu id"

COMPANY_B_BODY=$(create_company "B" "buyer-b-${SUFFIX}@orderflow.local")
COMPANY_B_ID=$(extract_first_string "$COMPANY_B_BODY" id)
[ -n "$COMPANY_B_ID" ] || fail "criacao da empresa B nao devolveu id"

login_buyer() {
    local email="$1"
    local body
    body=$(exec_gateway curl -s -X POST "${GATEWAY_URL}/api/auth/login" \
        -H "Content-Type: application/json" \
        -d "{\"email\":\"${email}\",\"password\":\"ChangeMe!123\"}")
    extract_string "$body" accessToken
}

BUYER_A_TOKEN=$(login_buyer "buyer-a-${SUFFIX}@orderflow.local")
[ -n "$BUYER_A_TOKEN" ] || fail "login do BUYER da empresa A nao devolveu accessToken"

BUYER_B_TOKEN=$(login_buyer "buyer-b-${SUFFIX}@orderflow.local")
[ -n "$BUYER_B_TOKEN" ] || fail "login do BUYER da empresa B nao devolveu accessToken"
echo "2/15 empresas A (${COMPANY_A_ID}) e B (${COMPANY_B_ID}) criadas, limite 1000.00, BUYERs autenticados"

# 3. O vendedor cria o produto P1 a 100.00 e o produto P2 a 50.00, e descontinua P2.
create_product() {
    local sku="$1" name="$2" price="$3"
    exec_gateway curl -s -X POST "${GATEWAY_URL}/api/products" \
        -H "Authorization: Bearer ${SELLER_TOKEN}" -H "Content-Type: application/json" \
        -d "{\"sku\":\"${sku}\",\"name\":\"${name}\",\"price\":${price}}"
}

P1_BODY=$(create_product "SMOKE-P1-${SUFFIX}" "Produto Smoke P1" "100.00")
P1_ID=$(extract_string "$P1_BODY" id)
[ -n "$P1_ID" ] || fail "criacao do produto P1 nao devolveu id"

P2_BODY=$(create_product "SMOKE-P2-${SUFFIX}" "Produto Smoke P2" "50.00")
P2_ID=$(extract_string "$P2_BODY" id)
[ -n "$P2_ID" ] || fail "criacao do produto P2 nao devolveu id"

DISCONTINUE_STATUS=$(exec_gateway curl -s -o /dev/null -w '%{http_code}' -X PUT \
    "${GATEWAY_URL}/api/products/${P2_ID}/status" \
    -H "Authorization: Bearer ${SELLER_TOKEN}" -H "Content-Type: application/json" \
    -d '{"status":"DISCONTINUED"}')
[ "$DISCONTINUE_STATUS" = "200" ] \
    || fail "PUT /api/products/${P2_ID}/status devolveu ${DISCONTINUE_STATUS}, esperado 200"
echo "3/15 produtos P1 (${P1_ID}, 100.00) e P2 (${P2_ID}, 50.00, DISCONTINUED) criados"

# 4. POST /api/orders sem token → 401.
NO_TOKEN_STATUS=$(exec_gateway curl -s -o /dev/null -w '%{http_code}' -X POST \
    "${GATEWAY_URL}/api/orders" \
    -H "Content-Type: application/json" \
    -d "{\"items\":[{\"productId\":\"${P1_ID}\",\"quantity\":1}]}")
[ "$NO_TOKEN_STATUS" = "401" ] || fail "POST /api/orders sem token devolveu ${NO_TOKEN_STATUS}, esperado 401"
echo "4/15 POST /api/orders sem token confirma 401"

# 5. BUYER de A pede 4 x P1 (400.00) → 201 APPROVED, decidedBy SYSTEM — Success Criteria 1.
create_order() {
    local token="$1" product_id="$2" quantity="$3"
    exec_gateway curl -s -X POST "${GATEWAY_URL}/api/orders" \
        -H "Authorization: Bearer ${token}" -H "Content-Type: application/json" \
        -d "{\"items\":[{\"productId\":\"${product_id}\",\"quantity\":${quantity}}]}"
}

ORDER_APPROVED_BODY=$(create_order "$BUYER_A_TOKEN" "$P1_ID" 4)
ORDER_APPROVED_ID=$(extract_string "$ORDER_APPROVED_BODY" id)
ORDER_APPROVED_STATUS_FIELD=$(extract_string "$ORDER_APPROVED_BODY" status)
ORDER_APPROVED_DECIDED_BY=$(extract_string "$ORDER_APPROVED_BODY" decidedBy)
[ -n "$ORDER_APPROVED_ID" ] || fail "pedido de 400.00 (4x P1) nao devolveu id"
[ "$ORDER_APPROVED_STATUS_FIELD" = "APPROVED" ] \
    || fail "pedido de 400.00 (4x P1) nasceu com status ${ORDER_APPROVED_STATUS_FIELD:-<vazio>}, esperado APPROVED"
[ "$ORDER_APPROVED_DECIDED_BY" = "SYSTEM" ] \
    || fail "pedido de 400.00 (4x P1) tem decidedBy ${ORDER_APPROVED_DECIDED_BY:-<vazio>}, esperado SYSTEM"
echo "5/15 pedido ${ORDER_APPROVED_ID} (400.00) nasceu APPROVED, decidedBy=SYSTEM"

# 6. BUYER de A pede 7 x P1 (700.00) → 201 PENDING_APPROVAL — Success Criteria 2.
ORDER_PENDING_BODY=$(create_order "$BUYER_A_TOKEN" "$P1_ID" 7)
ORDER_PENDING_ID=$(extract_string "$ORDER_PENDING_BODY" id)
ORDER_PENDING_STATUS_FIELD=$(extract_string "$ORDER_PENDING_BODY" status)
[ -n "$ORDER_PENDING_ID" ] || fail "pedido de 700.00 (7x P1) nao devolveu id"
[ "$ORDER_PENDING_STATUS_FIELD" = "PENDING_APPROVAL" ] \
    || fail "pedido de 700.00 (7x P1) nasceu com status ${ORDER_PENDING_STATUS_FIELD:-<vazio>}, esperado PENDING_APPROVAL"
echo "6/15 pedido ${ORDER_PENDING_ID} (700.00) nasceu PENDING_APPROVAL"

# 7. BUYER de A pede P2 (DISCONTINUED) → 422 invalid_order_items com o id de P2 no corpo.
do_request -X POST "${GATEWAY_URL}/api/orders" \
    -H "Authorization: Bearer ${BUYER_A_TOKEN}" -H "Content-Type: application/json" \
    -d "{\"items\":[{\"productId\":\"${P2_ID}\",\"quantity\":1}]}"
[ "$REQ_STATUS" = "422" ] \
    || fail "pedido com produto DISCONTINUED devolveu ${REQ_STATUS}, esperado 422"
printf '%s' "$REQ_BODY" | grep -q '"invalid_order_items"' \
    || fail "corpo do 422 nao contem invalid_order_items: ${REQ_BODY}"
printf '%s' "$REQ_BODY" | grep -q "$P2_ID" \
    || fail "corpo do 422 nao contem o id do produto descontinuado (${P2_ID}): ${REQ_BODY}"
echo "7/15 pedido com produto P2 descontinuado recusado com 422 invalid_order_items"

# 8. POST /api/orders com o token do vendedor → 403 (só BUYER cria pedido).
SELLER_CREATE_STATUS=$(exec_gateway curl -s -o /dev/null -w '%{http_code}' -X POST \
    "${GATEWAY_URL}/api/orders" \
    -H "Authorization: Bearer ${SELLER_TOKEN}" -H "Content-Type: application/json" \
    -d "{\"items\":[{\"productId\":\"${P1_ID}\",\"quantity\":1}]}")
[ "$SELLER_CREATE_STATUS" = "403" ] \
    || fail "POST /api/orders com token do vendedor devolveu ${SELLER_CREATE_STATUS}, esperado 403"
echo "8/15 POST /api/orders com token do vendedor confirma 403"

# 9. GET /api/orders?status=PENDING_APPROVAL&size=100 do vendedor contem o id do pedido do passo 6.
SELLER_PENDING_LIST=$(exec_gateway curl -s \
    "${GATEWAY_URL}/api/orders?status=PENDING_APPROVAL&size=100" \
    -H "Authorization: Bearer ${SELLER_TOKEN}")
printf '%s' "$SELLER_PENDING_LIST" | grep -q "$ORDER_PENDING_ID" \
    || fail "GET /api/orders?status=PENDING_APPROVAL do vendedor nao contem o pedido ${ORDER_PENDING_ID}"
echo "9/15 fila de aprovacao do vendedor contem o pedido ${ORDER_PENDING_ID}"

# 10. O proprio BUYER de A tenta aprovar o proprio pedido pendente → 403.
BUYER_APPROVE_STATUS=$(exec_gateway curl -s -o /dev/null -w '%{http_code}' -X POST \
    "${GATEWAY_URL}/api/orders/${ORDER_PENDING_ID}/approve" \
    -H "Authorization: Bearer ${BUYER_A_TOKEN}" -H "Content-Type: application/json" \
    -d '{"reason":"tentativa invalida"}')
[ "$BUYER_APPROVE_STATUS" = "403" ] \
    || fail "BUYER aprovando o proprio pedido devolveu ${BUYER_APPROVE_STATUS}, esperado 403"
echo "10/15 BUYER tentando aprovar o proprio pedido confirma 403"

# 11. O vendedor aprova o pedido do passo 6 com motivo → 200 APPROVED, decidedBy = userId do
#     vendedor — Success Criteria 3.
APPROVE_BODY=$(exec_gateway curl -s -X POST \
    "${GATEWAY_URL}/api/orders/${ORDER_PENDING_ID}/approve" \
    -H "Authorization: Bearer ${SELLER_TOKEN}" -H "Content-Type: application/json" \
    -d '{"reason":"cliente estrategico"}')
APPROVE_STATUS_FIELD=$(extract_string "$APPROVE_BODY" status)
APPROVE_DECIDED_BY=$(extract_string "$APPROVE_BODY" decidedBy)
[ "$APPROVE_STATUS_FIELD" = "APPROVED" ] \
    || fail "aprovacao do pedido ${ORDER_PENDING_ID} devolveu status ${APPROVE_STATUS_FIELD:-<vazio>}, esperado APPROVED"
[ "$APPROVE_DECIDED_BY" = "$SELLER_USER_ID" ] \
    || fail "aprovacao do pedido ${ORDER_PENDING_ID} tem decidedBy ${APPROVE_DECIDED_BY:-<vazio>}, esperado ${SELLER_USER_ID}"
echo "11/15 vendedor aprova pedido ${ORDER_PENDING_ID}, decidedBy=${SELLER_USER_ID}"

# 12. BUYER de A pede 1 x P1 (100.00) → 201 PENDING_APPROVAL (1100.00 aprovados + 100.00 passa do
#     limite de 1000.00 — D-38: a aprovacao manual acima do limite passou a consumir credito).
ORDER_OVER_LIMIT_BODY=$(create_order "$BUYER_A_TOKEN" "$P1_ID" 1)
ORDER_OVER_LIMIT_ID=$(extract_string "$ORDER_OVER_LIMIT_BODY" id)
ORDER_OVER_LIMIT_STATUS_FIELD=$(extract_string "$ORDER_OVER_LIMIT_BODY" status)
[ -n "$ORDER_OVER_LIMIT_ID" ] || fail "pedido de 100.00 apos aprovacao manual acima do limite nao devolveu id"
[ "$ORDER_OVER_LIMIT_STATUS_FIELD" = "PENDING_APPROVAL" ] \
    || fail "pedido de 100.00 apos aprovacao manual acima do limite nasceu com status ${ORDER_OVER_LIMIT_STATUS_FIELD:-<vazio>}, esperado PENDING_APPROVAL"
echo "12/15 pedido ${ORDER_OVER_LIMIT_ID} (100.00) nasceu PENDING_APPROVAL (D-38: exposicao ja acima do limite)"

# 13. Rejeicao: sem corpo → 400; com motivo → 200 REJECTED; tentar aprovar depois → 409
#     order_not_pending.
REJECT_NO_BODY_STATUS=$(exec_gateway curl -s -o /dev/null -w '%{http_code}' -X POST \
    "${GATEWAY_URL}/api/orders/${ORDER_OVER_LIMIT_ID}/reject" \
    -H "Authorization: Bearer ${SELLER_TOKEN}" -H "Content-Type: application/json")
[ "$REJECT_NO_BODY_STATUS" = "400" ] \
    || fail "rejeicao sem corpo devolveu ${REJECT_NO_BODY_STATUS}, esperado 400"

REJECT_BODY=$(exec_gateway curl -s -X POST \
    "${GATEWAY_URL}/api/orders/${ORDER_OVER_LIMIT_ID}/reject" \
    -H "Authorization: Bearer ${SELLER_TOKEN}" -H "Content-Type: application/json" \
    -d '{"reason":"limite excedido"}')
REJECT_STATUS_FIELD=$(extract_string "$REJECT_BODY" status)
[ "$REJECT_STATUS_FIELD" = "REJECTED" ] \
    || fail "rejeicao do pedido ${ORDER_OVER_LIMIT_ID} devolveu status ${REJECT_STATUS_FIELD:-<vazio>}, esperado REJECTED"

REAPPROVE_STATUS=$(exec_gateway curl -s -o /dev/null -w '%{http_code}' -X POST \
    "${GATEWAY_URL}/api/orders/${ORDER_OVER_LIMIT_ID}/approve" \
    -H "Authorization: Bearer ${SELLER_TOKEN}" -H "Content-Type: application/json" \
    -d '{"reason":"tentativa apos rejeicao"}')
[ "$REAPPROVE_STATUS" = "409" ] \
    || fail "aprovar pedido ja rejeitado devolveu ${REAPPROVE_STATUS}, esperado 409"
echo "13/15 pedido ${ORDER_OVER_LIMIT_ID} recusado sem corpo (400), rejeitado com motivo (REJECTED), decisao repetida confirma 409"

# 14. BUYER de B abre o pedido do passo 5 (empresa A) → 404 order_not_found; e a listagem do BUYER
#     de B tem totalElements 0 — Success Criteria 4 (isolamento por empresa).
CROSS_COMPANY_STATUS=$(exec_gateway curl -s -o /dev/null -w '%{http_code}' \
    "${GATEWAY_URL}/api/orders/${ORDER_APPROVED_ID}" \
    -H "Authorization: Bearer ${BUYER_B_TOKEN}")
[ "$CROSS_COMPANY_STATUS" = "404" ] \
    || fail "BUYER de B abrindo pedido da empresa A devolveu ${CROSS_COMPANY_STATUS}, esperado 404"

BUYER_B_LIST=$(exec_gateway curl -s "${GATEWAY_URL}/api/orders" \
    -H "Authorization: Bearer ${BUYER_B_TOKEN}")
BUYER_B_TOTAL=$(extract_raw "$BUYER_B_LIST" totalElements)
[ "$BUYER_B_TOTAL" = "0" ] \
    || fail "GET /api/orders do BUYER de B tem totalElements ${BUYER_B_TOTAL:-<vazio>}, esperado 0"
echo "14/15 BUYER de B recebe 404 no pedido alheio e totalElements=0 na propria listagem"

# 15. GET /api/orders do BUYER de A tem totalElements 3; o vendedor abre o pedido do passo 5 → 200.
BUYER_A_LIST=$(exec_gateway curl -s "${GATEWAY_URL}/api/orders" \
    -H "Authorization: Bearer ${BUYER_A_TOKEN}")
BUYER_A_TOTAL=$(extract_raw "$BUYER_A_LIST" totalElements)
[ "$BUYER_A_TOTAL" = "3" ] \
    || fail "GET /api/orders do BUYER de A tem totalElements ${BUYER_A_TOTAL:-<vazio>}, esperado 3"

SELLER_GET_STATUS=$(exec_gateway curl -s -o /dev/null -w '%{http_code}' \
    "${GATEWAY_URL}/api/orders/${ORDER_APPROVED_ID}" \
    -H "Authorization: Bearer ${SELLER_TOKEN}")
[ "$SELLER_GET_STATUS" = "200" ] \
    || fail "vendedor abrindo pedido ${ORDER_APPROVED_ID} devolveu ${SELLER_GET_STATUS}, esperado 200"
echo "15/15 BUYER de A ve totalElements=3, vendedor abre qualquer pedido (200)"

# Linha final — qualquer verificação futura acrescentada por planos seguintes entra ANTES desta
# linha.
echo "SMOKE OK ${ORDER_APPROVED_ID} ${ORDER_PENDING_ID} ${ORDER_OVER_LIMIT_ID}"
