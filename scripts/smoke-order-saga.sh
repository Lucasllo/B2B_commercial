#!/usr/bin/env bash
# Smoke da saga de reserva de estoque contra a stack real (docker compose up), pelo Gateway (D-69):
# demonstra de ponta a ponta o Core Value do projeto — um pedido com estoque suficiente chega a
# CONFIRMED com a reserva refletida no inventory-service (Success Criteria 2 do ROADMAP); um pedido
# acima do disponível ou de produto sem linha de estoque termina CANCELLED com motivo legível
# (Success Criteria 3); a aprovação manual do vendedor também confirma via saga (D-48); e o ajuste de
# estoque do passo 3 chega ao histórico de notificações pelo mesmo Transactional Outbox do
# inventory-service (D-60), fechando a limitação de dual-write da Fase 3 (D-29/D-30).
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

# Cria um pedido pelo Gateway; preenche REQ_STATUS/REQ_BODY via do_request.
create_order() {
    local token="$1" product_id="$2" quantity="$3"
    do_request -X POST "${GATEWAY_URL}/api/orders" \
        -H "Authorization: Bearer ${token}" -H "Content-Type: application/json" \
        -d "{\"items\":[{\"productId\":\"${product_id}\",\"quantity\":${quantity}}]}"
}

# Consulta GET /api/orders/{id} a cada 1s por até 30s até o status chegar ao esperado. Falha
# imediatamente se o pedido chegar a um estado terminal (CONFIRMED/CANCELLED) diferente do
# esperado; falha com o último corpo ao estourar o prazo. Preenche a variável global WAIT_BODY.
wait_for_order_status() {
    local token="$1" order_id="$2" expected="$3"
    local elapsed=0 body status
    while [ "$elapsed" -le 30 ]; do
        body=$(exec_gateway curl -s "${GATEWAY_URL}/api/orders/${order_id}" \
            -H "Authorization: Bearer ${token}" || true)
        status=$(extract_string "$body" status)
        if [ "$status" = "$expected" ]; then
            WAIT_BODY="$body"
            return 0
        fi
        if { [ "$status" = "CONFIRMED" ] || [ "$status" = "CANCELLED" ]; } && [ "$status" != "$expected" ]; then
            WAIT_BODY="$body"
            fail "pedido ${order_id} chegou a ${status}, esperado ${expected} - corpo: ${body}"
        fi
        sleep 1
        elapsed=$((elapsed + 1))
    done
    WAIT_BODY="$body"
    fail "pedido ${order_id} nao chegou a ${expected} em 30s - ultimo corpo: ${body}"
}

# 1. Login do vendedor de demonstracao.
SELLER_LOGIN_BODY=$(exec_gateway curl -s -X POST "${GATEWAY_URL}/api/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"${SELLER_EMAIL}\",\"password\":\"${SELLER_PASSWORD}\"}")
SELLER_TOKEN=$(extract_string "$SELLER_LOGIN_BODY" accessToken)
[ -n "$SELLER_TOKEN" ] || fail "login do vendedor nao devolveu accessToken"
echo "1/10 vendedor autenticado (${SELLER_EMAIL})"

# Sufixo unico por execucao, gerado dentro do proprio container do Gateway — permite reexecutar o
# smoke quantas vezes for preciso na mesma base sem colidir com execucoes anteriores.
SUFFIX=$(exec_gateway cat /proc/sys/kernel/random/uuid)
[ -n "$SUFFIX" ] || fail "nao foi possivel gerar o sufixo unico da execucao"

# 2. Cria uma empresa com creditLimit 1000.00 e um BUYER; faz login do BUYER.
COMPANY_BODY=$(exec_gateway curl -s -X POST "${GATEWAY_URL}/api/companies" \
    -H "Authorization: Bearer ${SELLER_TOKEN}" -H "Content-Type: application/json" \
    -d "{\"name\":\"Smoke Saga ${SUFFIX}\",\"creditLimit\":1000.00,\"buyerUser\":{\"email\":\"buyer-saga-${SUFFIX}@orderflow.local\",\"password\":\"ChangeMe!123\"}}")
COMPANY_ID=$(extract_first_string "$COMPANY_BODY" id)
[ -n "$COMPANY_ID" ] || fail "criacao da empresa nao devolveu id"

BUYER_LOGIN_BODY=$(exec_gateway curl -s -X POST "${GATEWAY_URL}/api/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"buyer-saga-${SUFFIX}@orderflow.local\",\"password\":\"ChangeMe!123\"}")
BUYER_TOKEN=$(extract_string "$BUYER_LOGIN_BODY" accessToken)
[ -n "$BUYER_TOKEN" ] || fail "login do BUYER nao devolveu accessToken"
echo "2/10 empresa ${COMPANY_ID} criada (limite 1000.00), BUYER autenticado"

# 3. Cria P1 (10.00, estoque 10), P2 (10.00, sem estoque) e P3 (100.00, estoque 50).
create_product() {
    local sku="$1" name="$2" price="$3"
    exec_gateway curl -s -X POST "${GATEWAY_URL}/api/products" \
        -H "Authorization: Bearer ${SELLER_TOKEN}" -H "Content-Type: application/json" \
        -d "{\"sku\":\"${sku}\",\"name\":\"${name}\",\"price\":${price}}"
}

set_stock() {
    local product_id="$1" quantity="$2"
    exec_gateway curl -s -o /dev/null -w '%{http_code}' -X PUT \
        "${GATEWAY_URL}/api/inventory/${product_id}" \
        -H "Authorization: Bearer ${SELLER_TOKEN}" -H "Content-Type: application/json" \
        -d "{\"quantityOnHand\":${quantity}}"
}

P1_BODY=$(create_product "SAGA-P1-${SUFFIX}" "Saga Smoke P1" "10.00")
P1_ID=$(extract_string "$P1_BODY" id)
[ -n "$P1_ID" ] || fail "criacao do produto P1 nao devolveu id"
P1_STOCK_STATUS=$(set_stock "$P1_ID" 10)
[ "$P1_STOCK_STATUS" = "200" ] \
    || fail "PUT /api/inventory/${P1_ID} (estoque 10) devolveu ${P1_STOCK_STATUS}, esperado 200"

P2_BODY=$(create_product "SAGA-P2-${SUFFIX}" "Saga Smoke P2" "10.00")
P2_ID=$(extract_string "$P2_BODY" id)
[ -n "$P2_ID" ] || fail "criacao do produto P2 nao devolveu id"

P3_BODY=$(create_product "SAGA-P3-${SUFFIX}" "Saga Smoke P3" "100.00")
P3_ID=$(extract_string "$P3_BODY" id)
[ -n "$P3_ID" ] || fail "criacao do produto P3 nao devolveu id"
P3_STOCK_STATUS=$(set_stock "$P3_ID" 50)
[ "$P3_STOCK_STATUS" = "200" ] \
    || fail "PUT /api/inventory/${P3_ID} (estoque 50) devolveu ${P3_STOCK_STATUS}, esperado 200"
echo "3/10 produtos P1 (${P1_ID}, estoque 10), P2 (${P2_ID}, sem estoque) e P3 (${P3_ID}, estoque 50) criados"

# 4. BUYER pede 3 x P1 (30.00) -> 201 com status RESERVING (D-54).
create_order "$BUYER_TOKEN" "$P1_ID" 3
[ "$REQ_STATUS" = "201" ] || fail "pedido de 3x P1 nao devolveu 201 (${REQ_STATUS})"
ORDER_CONFIRMED_ID=$(extract_string "$REQ_BODY" id)
ORDER_CONFIRMED_STATUS_FIELD=$(extract_string "$REQ_BODY" status)
[ -n "$ORDER_CONFIRMED_ID" ] || fail "pedido de 3x P1 nao devolveu id"
[ "$ORDER_CONFIRMED_STATUS_FIELD" = "RESERVING" ] \
    || fail "pedido de 3x P1 nasceu com status ${ORDER_CONFIRMED_STATUS_FIELD:-<vazio>}, esperado RESERVING"
echo "4/10 pedido ${ORDER_CONFIRMED_ID} (3x P1) nasceu RESERVING"

# 5. Espera o pedido chegar a CONFIRMED; confirmedAt precisa vir preenchido.
wait_for_order_status "$BUYER_TOKEN" "$ORDER_CONFIRMED_ID" CONFIRMED
CONFIRMED_AT=$(extract_string "$WAIT_BODY" confirmedAt)
[ -n "$CONFIRMED_AT" ] || fail "pedido ${ORDER_CONFIRMED_ID} chegou a CONFIRMED com confirmedAt vazio"
echo "5/10 pedido ${ORDER_CONFIRMED_ID} chegou a CONFIRMED (confirmedAt=${CONFIRMED_AT})"

# 6. GET /api/inventory/{P1} do vendedor -> onHand 10, reserved 3, available 7 (Success Criteria 2).
P1_STOCK_BODY=$(exec_gateway curl -s "${GATEWAY_URL}/api/inventory/${P1_ID}" \
    -H "Authorization: Bearer ${SELLER_TOKEN}")
P1_ON_HAND=$(extract_raw "$P1_STOCK_BODY" quantityOnHand)
P1_RESERVED=$(extract_raw "$P1_STOCK_BODY" quantityReserved)
P1_AVAILABLE=$(extract_raw "$P1_STOCK_BODY" quantityAvailable)
[ "$P1_ON_HAND" = "10" ] || fail "GET /api/inventory/${P1_ID} tem quantityOnHand ${P1_ON_HAND:-<vazio>}, esperado 10"
[ "$P1_RESERVED" = "3" ] || fail "GET /api/inventory/${P1_ID} tem quantityReserved ${P1_RESERVED:-<vazio>}, esperado 3"
[ "$P1_AVAILABLE" = "7" ] \
    || fail "GET /api/inventory/${P1_ID} tem quantityAvailable ${P1_AVAILABLE:-<vazio>}, esperado 7"
echo "6/10 P1 (${P1_ID}) com onHand=10, reserved=3, available=7"

# 7. BUYER pede 20 x P1 (acima do disponivel) -> RESERVING -> CANCELLED com INSUFFICIENT_STOCK,
#    motivo citando disponivel 7 e solicitado 20; a reserva de P1 do pedido confirmado continua
#    intacta (Success Criteria 3).
create_order "$BUYER_TOKEN" "$P1_ID" 20
[ "$REQ_STATUS" = "201" ] || fail "pedido de 20x P1 nao devolveu 201 (${REQ_STATUS})"
ORDER_INSUFFICIENT_ID=$(extract_string "$REQ_BODY" id)
ORDER_INSUFFICIENT_STATUS_FIELD=$(extract_string "$REQ_BODY" status)
[ -n "$ORDER_INSUFFICIENT_ID" ] || fail "pedido de 20x P1 nao devolveu id"
[ "$ORDER_INSUFFICIENT_STATUS_FIELD" = "RESERVING" ] \
    || fail "pedido de 20x P1 nasceu com status ${ORDER_INSUFFICIENT_STATUS_FIELD:-<vazio>}, esperado RESERVING"

wait_for_order_status "$BUYER_TOKEN" "$ORDER_INSUFFICIENT_ID" CANCELLED
INSUFFICIENT_CODE=$(extract_string "$WAIT_BODY" cancellationCode)
INSUFFICIENT_REASON=$(extract_string "$WAIT_BODY" cancellationReason)
[ "$INSUFFICIENT_CODE" = "INSUFFICIENT_STOCK" ] \
    || fail "pedido ${ORDER_INSUFFICIENT_ID} cancelado com cancellationCode ${INSUFFICIENT_CODE:-<vazio>}, esperado INSUFFICIENT_STOCK"
printf '%s' "$INSUFFICIENT_REASON" | grep -q "disponível 7" \
    || fail "cancellationReason do pedido ${ORDER_INSUFFICIENT_ID} nao cita 'disponivel 7': ${INSUFFICIENT_REASON}"
printf '%s' "$INSUFFICIENT_REASON" | grep -q "solicitado 20" \
    || fail "cancellationReason do pedido ${ORDER_INSUFFICIENT_ID} nao cita 'solicitado 20': ${INSUFFICIENT_REASON}"

P1_STOCK_AFTER_FAIL=$(exec_gateway curl -s "${GATEWAY_URL}/api/inventory/${P1_ID}" \
    -H "Authorization: Bearer ${SELLER_TOKEN}")
P1_RESERVED_AFTER_FAIL=$(extract_raw "$P1_STOCK_AFTER_FAIL" quantityReserved)
[ "$P1_RESERVED_AFTER_FAIL" = "3" ] \
    || fail "apos a falha de 20x P1, quantityReserved de P1 e ${P1_RESERVED_AFTER_FAIL:-<vazio>}, esperado 3 (reserva do pedido confirmado intacta)"
echo "7/10 pedido ${ORDER_INSUFFICIENT_ID} (20x P1) RESERVING -> CANCELLED INSUFFICIENT_STOCK, reserva de P1 intacta (3)"

# 8. BUYER pede 1 x P2 (sem linha de estoque) -> CANCELLED com PRODUCT_NOT_STOCKED (D-58).
create_order "$BUYER_TOKEN" "$P2_ID" 1
[ "$REQ_STATUS" = "201" ] || fail "pedido de 1x P2 nao devolveu 201 (${REQ_STATUS})"
ORDER_NOT_STOCKED_ID=$(extract_string "$REQ_BODY" id)
[ -n "$ORDER_NOT_STOCKED_ID" ] || fail "pedido de 1x P2 nao devolveu id"

wait_for_order_status "$BUYER_TOKEN" "$ORDER_NOT_STOCKED_ID" CANCELLED
NOT_STOCKED_CODE=$(extract_string "$WAIT_BODY" cancellationCode)
[ "$NOT_STOCKED_CODE" = "PRODUCT_NOT_STOCKED" ] \
    || fail "pedido ${ORDER_NOT_STOCKED_ID} cancelado com cancellationCode ${NOT_STOCKED_CODE:-<vazio>}, esperado PRODUCT_NOT_STOCKED"
echo "8/10 pedido ${ORDER_NOT_STOCKED_ID} (1x P2, sem estoque) CANCELLED com PRODUCT_NOT_STOCKED"

# 9. BUYER pede 11 x P3 (1100.00, acima do limite) -> PENDING_APPROVAL; vendedor aprova -> RESERVING
#    -> CONFIRMED; P3 com quantityReserved 11 (D-48).
create_order "$BUYER_TOKEN" "$P3_ID" 11
[ "$REQ_STATUS" = "201" ] || fail "pedido de 11x P3 nao devolveu 201 (${REQ_STATUS})"
ORDER_MANUAL_ID=$(extract_string "$REQ_BODY" id)
ORDER_MANUAL_STATUS_FIELD=$(extract_string "$REQ_BODY" status)
[ -n "$ORDER_MANUAL_ID" ] || fail "pedido de 11x P3 nao devolveu id"
[ "$ORDER_MANUAL_STATUS_FIELD" = "PENDING_APPROVAL" ] \
    || fail "pedido de 11x P3 nasceu com status ${ORDER_MANUAL_STATUS_FIELD:-<vazio>}, esperado PENDING_APPROVAL"

do_request -X POST "${GATEWAY_URL}/api/orders/${ORDER_MANUAL_ID}/approve" \
    -H "Authorization: Bearer ${SELLER_TOKEN}" -H "Content-Type: application/json" \
    -d '{"reason":"cliente estratégico"}'
[ "$REQ_STATUS" = "200" ] || fail "aprovacao do pedido ${ORDER_MANUAL_ID} devolveu ${REQ_STATUS}, esperado 200"
APPROVE_STATUS_FIELD=$(extract_string "$REQ_BODY" status)
[ "$APPROVE_STATUS_FIELD" = "RESERVING" ] \
    || fail "aprovacao do pedido ${ORDER_MANUAL_ID} devolveu status ${APPROVE_STATUS_FIELD:-<vazio>}, esperado RESERVING"

wait_for_order_status "$BUYER_TOKEN" "$ORDER_MANUAL_ID" CONFIRMED

P3_STOCK_BODY=$(exec_gateway curl -s "${GATEWAY_URL}/api/inventory/${P3_ID}" \
    -H "Authorization: Bearer ${SELLER_TOKEN}")
P3_RESERVED=$(extract_raw "$P3_STOCK_BODY" quantityReserved)
[ "$P3_RESERVED" = "11" ] \
    || fail "GET /api/inventory/${P3_ID} tem quantityReserved ${P3_RESERVED:-<vazio>}, esperado 11"
echo "9/10 pedido ${ORDER_MANUAL_ID} (11x P3, 1100.00) PENDING_APPROVAL -> aprovado manualmente -> RESERVING -> CONFIRMED, P3 reservado=11"

# 10. GET /api/notifications/{P1} do vendedor contem STOCK_ADJUSTED em ate 15s (ajuste do passo 3
#     saiu pelo outbox do inventory-service, D-60).
WAIT_START=$SECONDS
ELAPSED=0
FOUND_NOTIF=0
while [ "$ELAPSED" -le 15 ]; do
    # "|| true" protege o laco de um erro de conexao transitorio, no mesmo espirito do smoke de
    # notificacoes: sem isso, "set -e" mataria o script na primeira tentativa.
    NOTIF_BODY=$(exec_gateway curl -s "${GATEWAY_URL}/api/notifications/${P1_ID}" \
        -H "Authorization: Bearer ${SELLER_TOKEN}" || true)
    if printf '%s' "$NOTIF_BODY" | grep -q '"eventType":"STOCK_ADJUSTED"'; then
        FOUND_NOTIF=1
        break
    fi
    sleep 1
    ELAPSED=$((SECONDS - WAIT_START))
done
[ "$FOUND_NOTIF" -eq 1 ] || fail "evento STOCK_ADJUSTED nao apareceu no historico de P1 (${P1_ID}) em 15s"
echo "10/10 evento STOCK_ADJUSTED do ajuste de estoque de P1 visivel no historico em ${ELAPSED}s"

# Linha final — qualquer verificação futura acrescentada por planos seguintes entra ANTES desta
# linha.
echo "SMOKE OK ${ORDER_CONFIRMED_ID} ${ORDER_INSUFFICIENT_ID} ${ORDER_NOT_STOCKED_ID} ${ORDER_MANUAL_ID}"
