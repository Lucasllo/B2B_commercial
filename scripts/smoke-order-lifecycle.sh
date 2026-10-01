#!/usr/bin/env bash
# Smoke do ciclo de vida completo do pedido contra a stack real (docker compose up), pelo Gateway
# (D-85, criterios 1-3 do ROADMAP da Fase 6): demonstra de ponta a ponta um pedido nascer, ser
# confirmado com transportadora simulada e codigo de rastreio, ser expedido e entregue pelo vendedor,
# baixar o estoque (quantityOnHand e quantityReserved) no inventory-service e deixar a jornada inteira
# na linha do tempo do notification-service lida pelo proprio comprador. Tambem prova o caminho
# triste (cancelamento por falta de estoque), a rejeicao pelo vendedor e as recusas de transicao
# (409 invalid_order_transition), de papel (403) e de empresa alheia (404 order_not_found).
#
# Nenhum passo exige acao manual: o script cria empresas, compradores, produto, estoque e pedidos
# sozinho; a ultima linha e "SMOKE OK ..." ou o script falha com "SMOKE FALHOU: ...". Nunca imprime
# tokens nem segredos do .env (T-06-23).
#
# MSYS_NO_PATHCONV=1 evita que o Git Bash do Windows reescreva argumentos como
# /proc/sys/kernel/random/uuid como caminhos do Windows antes de repassa-los ao
# `docker compose exec`.
set -euo pipefail
export MSYS_NO_PATHCONV=1

cd "$(dirname "${BASH_SOURCE[0]}")/.."

SELLER_EMAIL="admin@orderflow.local"
SELLER_PASSWORD="ChangeMe!123"
GATEWAY_URL="http://localhost:8080"
TRACKING_PATTERN='^[A-Z]{2}[0-9]{9}BR$'

fail() {
    echo "SMOKE FALHOU: $1" >&2
    exit 1
}

# Todo texto vindo do container e filtrado por strip_cr — um \r sobrando quebraria comparacoes
# de string exatas (ex.: codigo de status HTTP).
strip_cr() {
    tr -d '\r'
}

exec_gateway() {
    docker compose exec -T gateway "$@" | strip_cr
}

# Executa uma chamada HTTP pelo Gateway e devolve, numa unica ida ao container (sem repetir a
# requisicao — importante para POST com efeito colateral), tanto o codigo de status quanto o
# corpo. Preenche as variaveis globais REQ_STATUS e REQ_BODY.
do_request() {
    local out
    out=$(exec_gateway curl -s -w '\n%{http_code}' "$@")
    REQ_STATUS=$(printf '%s' "$out" | tail -n1)
    REQ_BODY=$(printf '%s' "$out" | sed '$d')
}

# Extrai o valor de uma chave string simples ("chave":"valor") do corpo JSON — greedy por padrao
# do sed (pega a ULTIMA ocorrencia); so e usado para campos que aparecem uma vez no nivel relevante.
extract_string() {
    local body="$1" field="$2"
    printf '%s' "$body" | sed -n "s/.*\"${field}\":\"\([^\"]*\)\".*/\1/p"
}

# Extrai a chave string do INICIO do corpo JSON (ex.: CompanyResponse.id no topo vs. buyerUser.id).
extract_first_string() {
    local body="$1" field="$2"
    printf '%s' "$body" | sed -n "s/^{\"${field}\":\"\([^\"]*\)\".*/\1/p"
}

# Extrai o primeiro valor de uma chave numerica/booleana/nula simples ("chave":valor, sem aspas).
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

# POST sem corpo numa acao de expedicao (ship/deliver); preenche REQ_STATUS/REQ_BODY.
order_action() {
    local token="$1" order_id="$2" action="$3"
    do_request -X POST "${GATEWAY_URL}/api/orders/${order_id}/${action}" \
        -H "Authorization: Bearer ${token}"
}

# Consulta GET /api/orders/{id} a cada 1s por ate 30s ate o status chegar ao esperado. Falha
# imediatamente se o pedido chegar a um estado terminal da saga (CONFIRMED/CANCELLED) diferente do
# esperado; falha com o ultimo corpo ao estourar o prazo. Preenche a variavel global WAIT_BODY.
wait_for_order_status() {
    local token="$1" order_id="$2" expected="$3"
    local elapsed=0 body="" status
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

# Sequencia de tipos de evento da linha do tempo do pedido, separados por espaco. Cada elemento do
# corpo traz "eventType" duas vezes (no topo e dentro do payload aninhado — Pitfall 10), entao
# extrai todas as ocorrencias em ordem e colapsa repeticoes CONSECUTIVAS com uniq.
timeline_types() {
    local body="$1"
    printf '%s' "$body" | grep -o '"eventType":"[A-Z_]*"' | sed 's/"eventType":"\([A-Z_]*\)"/\1/' | uniq | tr '\n' ' ' | sed 's/ $//'
}

# Le GET /api/notifications/orders/{id} a cada 1s por ate 15s ate a sequencia de tipos ser
# exatamente a esperada. "|| true" na chamada protege o laco de um erro transitorio (set -e mataria
# o script na primeira tentativa). Preenche TIMELINE_BODY e TIMELINE_SEQ; falha ao estourar o prazo.
wait_for_timeline() {
    local token="$1" order_id="$2" expected="$3"
    local wait_start=$SECONDS elapsed=0
    TIMELINE_BODY=""
    TIMELINE_SEQ=""
    while [ "$elapsed" -le 15 ]; do
        TIMELINE_BODY=$(exec_gateway curl -s "${GATEWAY_URL}/api/notifications/orders/${order_id}" \
            -H "Authorization: Bearer ${token}" || true)
        TIMELINE_SEQ=$(timeline_types "$TIMELINE_BODY")
        if [ "$TIMELINE_SEQ" = "$expected" ]; then
            return 0
        fi
        sleep 1
        elapsed=$((SECONDS - wait_start))
    done
    fail "linha do tempo do pedido ${order_id} nao chegou a '${expected}' em 15s - ultima: '${TIMELINE_SEQ}'"
}

# 1. Login do vendedor de demonstracao e leitura do userId via GET /api/auth/me — shippedBy e
#    deliveredBy registrados nos passos seguintes tem que bater com este valor.
SELLER_LOGIN_BODY=$(exec_gateway curl -s -X POST "${GATEWAY_URL}/api/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"${SELLER_EMAIL}\",\"password\":\"${SELLER_PASSWORD}\"}")
SELLER_TOKEN=$(extract_string "$SELLER_LOGIN_BODY" accessToken)
[ -n "$SELLER_TOKEN" ] || fail "login do vendedor nao devolveu accessToken"
SELLER_ME_BODY=$(exec_gateway curl -s "${GATEWAY_URL}/api/auth/me" \
    -H "Authorization: Bearer ${SELLER_TOKEN}")
SELLER_USER_ID=$(extract_string "$SELLER_ME_BODY" userId)
[ -n "$SELLER_USER_ID" ] || fail "GET /api/auth/me do vendedor nao devolveu userId"
echo "1/16 vendedor autenticado (${SELLER_EMAIL}), userId=${SELLER_USER_ID}"

# 2. Sufixo unico por execucao, gerado dentro do proprio container do Gateway — permite reexecutar o
#    smoke quantas vezes for preciso na mesma base. Empresas A e B (limite 1000.00), um BUYER cada.
SUFFIX=$(exec_gateway cat /proc/sys/kernel/random/uuid)
[ -n "$SUFFIX" ] || fail "nao foi possivel gerar o sufixo unico da execucao"

create_company_with_buyer() {
    local label="$1"
    local company_body
    company_body=$(exec_gateway curl -s -X POST "${GATEWAY_URL}/api/companies" \
        -H "Authorization: Bearer ${SELLER_TOKEN}" -H "Content-Type: application/json" \
        -d "{\"name\":\"Smoke Ciclo ${label} ${SUFFIX}\",\"creditLimit\":1000.00,\"buyerUser\":{\"email\":\"buyer-ciclo-${label}-${SUFFIX}@orderflow.local\",\"password\":\"ChangeMe!123\"}}")
    CREATED_COMPANY_ID=$(extract_first_string "$company_body" id)
    [ -n "$CREATED_COMPANY_ID" ] || fail "criacao da empresa ${label} nao devolveu id"
    local login_body
    login_body=$(exec_gateway curl -s -X POST "${GATEWAY_URL}/api/auth/login" \
        -H "Content-Type: application/json" \
        -d "{\"email\":\"buyer-ciclo-${label}-${SUFFIX}@orderflow.local\",\"password\":\"ChangeMe!123\"}")
    CREATED_BUYER_TOKEN=$(extract_string "$login_body" accessToken)
    [ -n "$CREATED_BUYER_TOKEN" ] || fail "login do BUYER da empresa ${label} nao devolveu accessToken"
}

create_company_with_buyer A
COMPANY_A_ID="$CREATED_COMPANY_ID"
BUYER_A_TOKEN="$CREATED_BUYER_TOKEN"
create_company_with_buyer B
COMPANY_B_ID="$CREATED_COMPANY_ID"
BUYER_B_TOKEN="$CREATED_BUYER_TOKEN"
echo "2/16 empresas A (${COMPANY_A_ID}) e B (${COMPANY_B_ID}) criadas (limite 1000.00 cada), compradores autenticados"

# 3. Produto P1 a 10.00 com estoque 10.
P1_BODY=$(exec_gateway curl -s -X POST "${GATEWAY_URL}/api/products" \
    -H "Authorization: Bearer ${SELLER_TOKEN}" -H "Content-Type: application/json" \
    -d "{\"sku\":\"CICLO-P1-${SUFFIX}\",\"name\":\"Ciclo Smoke P1\",\"price\":10.00}")
P1_ID=$(extract_string "$P1_BODY" id)
[ -n "$P1_ID" ] || fail "criacao do produto P1 nao devolveu id"
P1_STOCK_STATUS=$(exec_gateway curl -s -o /dev/null -w '%{http_code}' -X PUT \
    "${GATEWAY_URL}/api/inventory/${P1_ID}" \
    -H "Authorization: Bearer ${SELLER_TOKEN}" -H "Content-Type: application/json" \
    -d '{"quantityOnHand":10}')
[ "$P1_STOCK_STATUS" = "200" ] \
    || fail "PUT /api/inventory/${P1_ID} (estoque 10) devolveu ${P1_STOCK_STATUS}, esperado 200"
echo "3/16 produto P1 (${P1_ID}) a 10.00 com estoque 10"

# 4. BUYER A pede 3 x P1 (30.00) -> 201 RESERVING.
create_order "$BUYER_A_TOKEN" "$P1_ID" 3
[ "$REQ_STATUS" = "201" ] || fail "pedido de 3x P1 nao devolveu 201 (${REQ_STATUS}): ${REQ_BODY}"
ORDER_ID=$(extract_first_string "$REQ_BODY" id)
[ -n "$ORDER_ID" ] || fail "pedido de 3x P1 nao devolveu id"
[ "$(extract_string "$REQ_BODY" status)" = "RESERVING" ] \
    || fail "pedido de 3x P1 nasceu com status diferente de RESERVING: ${REQ_BODY}"
echo "4/16 pedido ${ORDER_ID} (3x P1) nasceu RESERVING"

# 5. Espera CONFIRMED (<= 30s) com transportadora e codigo de rastreio no padrao dos Correios.
wait_for_order_status "$BUYER_A_TOKEN" "$ORDER_ID" CONFIRMED
CARRIER=$(extract_string "$WAIT_BODY" carrier)
TRACKING_CODE=$(extract_string "$WAIT_BODY" trackingCode)
[ -n "$CARRIER" ] || fail "pedido ${ORDER_ID} CONFIRMED com carrier vazio: ${WAIT_BODY}"
printf '%s' "$TRACKING_CODE" | grep -Eq "$TRACKING_PATTERN" \
    || fail "pedido ${ORDER_ID} CONFIRMED com trackingCode '${TRACKING_CODE}' fora do padrao ${TRACKING_PATTERN}"
echo "5/16 pedido ${ORDER_ID} CONFIRMED (transportadora ${CARRIER}, rastreio ${TRACKING_CODE})"

# 6. BUYER chamando /ship -> 403 (so o vendedor expede).
order_action "$BUYER_A_TOKEN" "$ORDER_ID" ship
[ "$REQ_STATUS" = "403" ] || fail "BUYER em /ship devolveu ${REQ_STATUS}, esperado 403: ${REQ_BODY}"
echo "6/16 BUYER em POST /ship recusado com 403"

# 7. Vendedor chamando deliver num pedido CONFIRMED -> 409 invalid_order_transition.
order_action "$SELLER_TOKEN" "$ORDER_ID" deliver
[ "$REQ_STATUS" = "409" ] || fail "deliver em CONFIRMED devolveu ${REQ_STATUS}, esperado 409: ${REQ_BODY}"
printf '%s' "$REQ_BODY" | grep -q 'invalid_order_transition' \
    || fail "deliver em CONFIRMED sem invalid_order_transition no corpo: ${REQ_BODY}"
echo "7/16 deliver em pedido CONFIRMED recusado com 409 invalid_order_transition"

# 8. Vendedor chama ship -> 200 SHIPPED, shippedBy = userId do vendedor, shippedAt presente.
order_action "$SELLER_TOKEN" "$ORDER_ID" ship
[ "$REQ_STATUS" = "200" ] || fail "ship devolveu ${REQ_STATUS}, esperado 200: ${REQ_BODY}"
[ "$(extract_string "$REQ_BODY" status)" = "SHIPPED" ] || fail "ship devolveu status diferente de SHIPPED: ${REQ_BODY}"
[ "$(extract_string "$REQ_BODY" shippedBy)" = "$SELLER_USER_ID" ] \
    || fail "shippedBy diferente do userId do vendedor (${SELLER_USER_ID}): ${REQ_BODY}"
[ -n "$(extract_string "$REQ_BODY" shippedAt)" ] || fail "ship sem shippedAt: ${REQ_BODY}"
echo "8/16 pedido ${ORDER_ID} SHIPPED por ${SELLER_USER_ID}"

# 9. Espera ate 15s a baixa de estoque: quantityOnHand 7 e quantityReserved 0 (ShipStock, D-86).
WAIT_START=$SECONDS
ELAPSED=0
P1_ON_HAND=""
P1_RESERVED=""
while [ "$ELAPSED" -le 15 ]; do
    P1_STOCK_BODY=$(exec_gateway curl -s "${GATEWAY_URL}/api/inventory/${P1_ID}" \
        -H "Authorization: Bearer ${SELLER_TOKEN}" || true)
    P1_ON_HAND=$(extract_raw "$P1_STOCK_BODY" quantityOnHand)
    P1_RESERVED=$(extract_raw "$P1_STOCK_BODY" quantityReserved)
    if [ "$P1_ON_HAND" = "7" ] && [ "$P1_RESERVED" = "0" ]; then
        break
    fi
    sleep 1
    ELAPSED=$((SECONDS - WAIT_START))
done
[ "$P1_ON_HAND" = "7" ] && [ "$P1_RESERVED" = "0" ] \
    || fail "estoque de P1 nao baixou em 15s: quantityOnHand=${P1_ON_HAND:-<vazio>} quantityReserved=${P1_RESERVED:-<vazio>}, esperado 7/0"
echo "9/16 estoque de P1 baixou para onHand=7, reserved=0 em ${ELAPSED}s"

# 10. ship repetido -> 409.
order_action "$SELLER_TOKEN" "$ORDER_ID" ship
[ "$REQ_STATUS" = "409" ] || fail "ship repetido devolveu ${REQ_STATUS}, esperado 409: ${REQ_BODY}"
printf '%s' "$REQ_BODY" | grep -q 'invalid_order_transition' \
    || fail "ship repetido sem invalid_order_transition no corpo: ${REQ_BODY}"
echo "10/16 ship repetido recusado com 409 invalid_order_transition"

# 11. deliver -> 200 DELIVERED com deliveredAt.
order_action "$SELLER_TOKEN" "$ORDER_ID" deliver
[ "$REQ_STATUS" = "200" ] || fail "deliver devolveu ${REQ_STATUS}, esperado 200: ${REQ_BODY}"
[ "$(extract_string "$REQ_BODY" status)" = "DELIVERED" ] || fail "deliver devolveu status diferente de DELIVERED: ${REQ_BODY}"
[ -n "$(extract_string "$REQ_BODY" deliveredAt)" ] || fail "deliver sem deliveredAt: ${REQ_BODY}"
echo "11/16 pedido ${ORDER_ID} DELIVERED"

# 12. BUYER A le a linha do tempo ate ela ter exatamente os cinco eventos do ciclo de vida, e a
#     mensagem de confirmacao cita a transportadora e o rastreio do passo 5.
wait_for_timeline "$BUYER_A_TOKEN" "$ORDER_ID" \
    "ORDER_CREATED ORDER_APPROVED ORDER_CONFIRMED ORDER_SHIPPED ORDER_DELIVERED"
printf '%s' "$TIMELINE_BODY" | grep -q "transportadora ${CARRIER}" \
    || fail "mensagem de confirmacao nao cita a transportadora '${CARRIER}': ${TIMELINE_BODY}"
printf '%s' "$TIMELINE_BODY" | grep -q "rastreio ${TRACKING_CODE}" \
    || fail "mensagem de confirmacao nao cita o rastreio '${TRACKING_CODE}': ${TIMELINE_BODY}"
echo "12/16 linha do tempo do pedido ${ORDER_ID}: ${TIMELINE_SEQ}"

# 13. BUYER B (outra empresa) lendo a mesma linha do tempo -> 404 order_not_found.
do_request "${GATEWAY_URL}/api/notifications/orders/${ORDER_ID}" \
    -H "Authorization: Bearer ${BUYER_B_TOKEN}"
[ "$REQ_STATUS" = "404" ] || fail "BUYER de outra empresa na linha do tempo devolveu ${REQ_STATUS}, esperado 404: ${REQ_BODY}"
printf '%s' "$REQ_BODY" | grep -q 'order_not_found' \
    || fail "404 da linha do tempo sem order_not_found no corpo: ${REQ_BODY}"
echo "13/16 BUYER de outra empresa recebeu 404 order_not_found na linha do tempo"

# 14. Caminho triste: 20 x P1 (acima do disponivel 7) -> CANCELLED INSUFFICIENT_STOCK, sem
#     transportadora; ship nele -> 409; linha do tempo CREATED -> APPROVED -> CANCELLED.
create_order "$BUYER_A_TOKEN" "$P1_ID" 20
[ "$REQ_STATUS" = "201" ] || fail "pedido de 20x P1 nao devolveu 201 (${REQ_STATUS}): ${REQ_BODY}"
ORDER_CANCELLED_ID=$(extract_first_string "$REQ_BODY" id)
[ -n "$ORDER_CANCELLED_ID" ] || fail "pedido de 20x P1 nao devolveu id"
wait_for_order_status "$BUYER_A_TOKEN" "$ORDER_CANCELLED_ID" CANCELLED
[ "$(extract_string "$WAIT_BODY" cancellationCode)" = "INSUFFICIENT_STOCK" ] \
    || fail "pedido ${ORDER_CANCELLED_ID} cancelado sem INSUFFICIENT_STOCK: ${WAIT_BODY}"
[ "$(extract_raw "$WAIT_BODY" carrier)" = "null" ] \
    || fail "pedido cancelado ${ORDER_CANCELLED_ID} com carrier preenchido: ${WAIT_BODY}"
order_action "$SELLER_TOKEN" "$ORDER_CANCELLED_ID" ship
[ "$REQ_STATUS" = "409" ] || fail "ship em pedido CANCELLED devolveu ${REQ_STATUS}, esperado 409: ${REQ_BODY}"
wait_for_timeline "$BUYER_A_TOKEN" "$ORDER_CANCELLED_ID" "ORDER_CREATED ORDER_APPROVED ORDER_CANCELLED"
echo "14/16 pedido ${ORDER_CANCELLED_ID} (20x P1) CANCELLED INSUFFICIENT_STOCK, ship recusado (409), linha do tempo: ${TIMELINE_SEQ}"

# 15. Recusa: 100 x P1 (1000.00; com os 30.00 ja consumidos passa do limite) -> PENDING_APPROVAL;
#     vendedor rejeita com motivo -> REJECTED; linha do tempo CREATED -> PENDING_APPROVAL -> REJECTED.
create_order "$BUYER_A_TOKEN" "$P1_ID" 100
[ "$REQ_STATUS" = "201" ] || fail "pedido de 100x P1 nao devolveu 201 (${REQ_STATUS}): ${REQ_BODY}"
ORDER_REJECTED_ID=$(extract_first_string "$REQ_BODY" id)
[ -n "$ORDER_REJECTED_ID" ] || fail "pedido de 100x P1 nao devolveu id"
[ "$(extract_string "$REQ_BODY" status)" = "PENDING_APPROVAL" ] \
    || fail "pedido de 100x P1 nasceu com status diferente de PENDING_APPROVAL: ${REQ_BODY}"
do_request -X POST "${GATEWAY_URL}/api/orders/${ORDER_REJECTED_ID}/reject" \
    -H "Authorization: Bearer ${SELLER_TOKEN}" -H "Content-Type: application/json" \
    -d '{"reason":"limite excedido"}'
[ "$REQ_STATUS" = "200" ] || fail "rejeicao do pedido ${ORDER_REJECTED_ID} devolveu ${REQ_STATUS}, esperado 200: ${REQ_BODY}"
[ "$(extract_string "$REQ_BODY" status)" = "REJECTED" ] \
    || fail "rejeicao do pedido ${ORDER_REJECTED_ID} devolveu status diferente de REJECTED: ${REQ_BODY}"
wait_for_timeline "$BUYER_A_TOKEN" "$ORDER_REJECTED_ID" "ORDER_CREATED ORDER_PENDING_APPROVAL ORDER_REJECTED"
echo "15/16 pedido ${ORDER_REJECTED_ID} (100x P1) PENDING_APPROVAL -> REJECTED, linha do tempo: ${TIMELINE_SEQ}"

# 16. Linha final — qualquer verificacao futura entra ANTES desta linha.
echo "16/16 ciclo de vida completo demonstrado"
echo "SMOKE OK ${ORDER_ID} ${ORDER_CANCELLED_ID} ${ORDER_REJECTED_ID}"
