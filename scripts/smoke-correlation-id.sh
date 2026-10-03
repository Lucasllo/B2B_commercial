#!/usr/bin/env bash
# Smoke de rastreabilidade por Correlation-ID contra a stack real (docker compose up), pelo Gateway
# (D-99, criterio 2 do ROADMAP da Fase 7): cria um pedido mandando um X-Correlation-Id conhecido e
# prova, com um grep, que o MESMO identificador aparece nos logs do Gateway e dos cinco servicos
# (order, inventory, notification, catalog e auth) — a requisicao atravessa Gateway -> order-service
# -> outbox -> SQS -> inventory-service -> SQS -> order-service -> SQS -> notification-service sem
# perder o ID. Tambem prova que:
#   - a resposta do Gateway traz exatamente UM header X-Correlation-Id, com o valor enviado (D-92);
#   - um valor invalido ("bad value!") e trocado por um UUID novo e nunca aparece cru nos logs (D-92).
#
# Nenhum passo exige acao manual: o script cria empresa, comprador, produto, estoque e pedido
# sozinho; a ultima linha e "SMOKE OK correlation-id <id>" ou o script falha com
# "SMOKE FALHOU: ...". Nunca imprime tokens nem segredos do .env (T-06-23, T-07-40).
#
# Para repetir a prova manualmente (qualquer servico: gateway, order-service, inventory-service,
# notification-service, catalog-service, auth-service):
#   docker compose logs --no-color order-service | grep -F "[<id>]"
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
UUID_PATTERN='^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
LOG_SERVICES="gateway order-service inventory-service notification-service catalog-service auth-service"

fail() {
    echo "SMOKE FALHOU: $1" >&2
    exit 1
}

# Todo texto vindo do container e filtrado por strip_cr — um \r sobrando quebraria comparacoes
# de string exatas (ex.: codigo de status HTTP, valor de header).
strip_cr() {
    tr -d '\r'
}

exec_gateway() {
    docker compose exec -T gateway "$@" | strip_cr
}

# Executa uma chamada HTTP pelo Gateway e devolve, numa unica ida ao container, o codigo de status e
# o corpo. Preenche REQ_STATUS e REQ_BODY.
do_request() {
    local out
    out=$(exec_gateway curl -s -w '\n%{http_code}' "$@")
    REQ_STATUS=$(printf '%s' "$out" | tail -n1)
    REQ_BODY=$(printf '%s' "$out" | sed '$d')
}

# Variante de do_request que tambem captura os headers da resposta (curl -i). Preenche REQ_STATUS,
# REQ_HEADERS (tudo antes da primeira linha em branco) e REQ_BODY (o que vem depois).
do_request_with_headers() {
    local out
    out=$(exec_gateway curl -s -i -w '\n%{http_code}' "$@")
    REQ_STATUS=$(printf '%s' "$out" | tail -n1)
    out=$(printf '%s' "$out" | sed '$d')
    REQ_HEADERS=$(printf '%s\n' "$out" | sed -n '1,/^$/p')
    REQ_BODY=$(printf '%s\n' "$out" | sed '1,/^$/d')
}

# Valores do header X-Correlation-Id da ultima resposta (um por linha, sem caixa no nome do header).
response_correlation_ids() {
    printf '%s\n' "$REQ_HEADERS" | sed -n 's/^[Xx]-[Cc]orrelation-[Ii]d:[[:space:]]*\(.*[^[:space:]]\)[[:space:]]*$/\1/p'
}

extract_string() {
    local body="$1" field="$2"
    printf '%s' "$body" | sed -n "s/.*\"${field}\":\"\([^\"]*\)\".*/\1/p"
}

# Extrai a chave string do INICIO do corpo JSON (ex.: CompanyResponse.id no topo vs. buyerUser.id).
extract_first_string() {
    local body="$1" field="$2"
    printf '%s' "$body" | sed -n "s/^{\"${field}\":\"\([^\"]*\)\".*/\1/p"
}

# Consulta GET /api/orders/{id} a cada 1s por ate 30s ate o status chegar ao esperado; falha se o
# pedido chegar a um estado terminal diferente ou ao estourar o prazo.
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
            fail "pedido ${order_id} chegou a ${status}, esperado ${expected} - corpo: ${body}"
        fi
        sleep 1
        elapsed=$((elapsed + 1))
    done
    fail "pedido ${order_id} nao chegou a ${expected} em 30s - ultimo corpo: ${body}"
}

# Le GET /api/notifications/orders/{id} a cada 1s por ate 15s ate a linha do tempo conter o tipo de
# evento esperado. Preenche TIMELINE_BODY.
wait_for_timeline_event() {
    local token="$1" order_id="$2" expected_type="$3"
    local wait_start=$SECONDS elapsed=0
    TIMELINE_BODY=""
    while [ "$elapsed" -le 15 ]; do
        TIMELINE_BODY=$(exec_gateway curl -s "${GATEWAY_URL}/api/notifications/orders/${order_id}" \
            -H "Authorization: Bearer ${token}" || true)
        if printf '%s' "$TIMELINE_BODY" | grep -q "\"eventType\":\"${expected_type}\""; then
            return 0
        fi
        sleep 1
        elapsed=$((SECONDS - wait_start))
    done
    fail "linha do tempo do pedido ${order_id} nao ganhou ${expected_type} em 15s - ultimo corpo: ${TIMELINE_BODY}"
}

# Espera ate 15s o ID aparecer nos logs do servico (a escrita do log do container pode atrasar um
# pouco em relacao a resposta HTTP). Nao imprime as linhas — so decide se achou.
wait_for_id_in_logs() {
    local svc="$1" cid="$2"
    local wait_start=$SECONDS elapsed=0
    while [ "$elapsed" -le 15 ]; do
        if docker compose logs --no-color "$svc" 2>&1 | grep -qF "[${cid}]"; then
            return 0
        fi
        sleep 1
        elapsed=$((SECONDS - wait_start))
    done
    return 1
}

# 1. Login do vendedor de demonstracao.
SELLER_LOGIN_BODY=$(exec_gateway curl -s -X POST "${GATEWAY_URL}/api/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"${SELLER_EMAIL}\",\"password\":\"${SELLER_PASSWORD}\"}")
SELLER_TOKEN=$(extract_string "$SELLER_LOGIN_BODY" accessToken)
[ -n "$SELLER_TOKEN" ] || fail "login do vendedor nao devolveu accessToken"
echo "1/8 vendedor autenticado (${SELLER_EMAIL})"

# 2. Sufixo unico por execucao + empresa com comprador (limite 1000.00) e login do comprador.
SUFFIX=$(exec_gateway cat /proc/sys/kernel/random/uuid)
[ -n "$SUFFIX" ] || fail "nao foi possivel gerar o sufixo unico da execucao"
COMPANY_BODY=$(exec_gateway curl -s -X POST "${GATEWAY_URL}/api/companies" \
    -H "Authorization: Bearer ${SELLER_TOKEN}" -H "Content-Type: application/json" \
    -d "{\"name\":\"Smoke CorrId ${SUFFIX}\",\"creditLimit\":1000.00,\"buyerUser\":{\"email\":\"buyer-corrid-${SUFFIX}@orderflow.local\",\"password\":\"ChangeMe!123\"}}")
COMPANY_ID=$(extract_first_string "$COMPANY_BODY" id)
[ -n "$COMPANY_ID" ] || fail "criacao da empresa nao devolveu id"
BUYER_LOGIN_BODY=$(exec_gateway curl -s -X POST "${GATEWAY_URL}/api/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"buyer-corrid-${SUFFIX}@orderflow.local\",\"password\":\"ChangeMe!123\"}")
BUYER_TOKEN=$(extract_string "$BUYER_LOGIN_BODY" accessToken)
[ -n "$BUYER_TOKEN" ] || fail "login do BUYER nao devolveu accessToken"
echo "2/8 empresa ${COMPANY_ID} criada (limite 1000.00), comprador autenticado"

# 3. Produto a 10.00 com estoque 10.
PRODUCT_BODY=$(exec_gateway curl -s -X POST "${GATEWAY_URL}/api/products" \
    -H "Authorization: Bearer ${SELLER_TOKEN}" -H "Content-Type: application/json" \
    -d "{\"sku\":\"CORRID-${SUFFIX}\",\"name\":\"CorrId Smoke\",\"price\":10.00}")
PRODUCT_ID=$(extract_string "$PRODUCT_BODY" id)
[ -n "$PRODUCT_ID" ] || fail "criacao do produto nao devolveu id"
STOCK_STATUS=$(exec_gateway curl -s -o /dev/null -w '%{http_code}' -X PUT \
    "${GATEWAY_URL}/api/inventory/${PRODUCT_ID}" \
    -H "Authorization: Bearer ${SELLER_TOKEN}" -H "Content-Type: application/json" \
    -d '{"quantityOnHand":10}')
[ "$STOCK_STATUS" = "200" ] \
    || fail "PUT /api/inventory/${PRODUCT_ID} (estoque 10) devolveu ${STOCK_STATUS}, esperado 200"
echo "3/8 produto ${PRODUCT_ID} a 10.00 com estoque 10"

# 4. Pedido de 2 x produto com um X-Correlation-Id conhecido (casa [A-Za-z0-9-]{1,64}); confere que
#    o Gateway devolve exatamente UM header X-Correlation-Id com o mesmo valor.
CID="smoke-cid-$(date +%s)-${RANDOM}"
do_request_with_headers -X POST "${GATEWAY_URL}/api/orders" \
    -H "X-Correlation-Id: ${CID}" \
    -H "Authorization: Bearer ${BUYER_TOKEN}" -H "Content-Type: application/json" \
    -d "{\"items\":[{\"productId\":\"${PRODUCT_ID}\",\"quantity\":2}]}"
[ "$REQ_STATUS" = "201" ] || fail "criacao do pedido nao devolveu 201 (${REQ_STATUS}): ${REQ_BODY}"
ORDER_ID=$(extract_first_string "$REQ_BODY" id)
[ -n "$ORDER_ID" ] || fail "criacao do pedido nao devolveu id: ${REQ_BODY}"
RESPONSE_IDS=$(response_correlation_ids)
RESPONSE_ID_COUNT=$(printf '%s\n' "$RESPONSE_IDS" | grep -c . || true)
[ "$RESPONSE_ID_COUNT" = "1" ] \
    || fail "resposta do Gateway trouxe ${RESPONSE_ID_COUNT} headers X-Correlation-Id, esperado exatamente 1"
[ "$RESPONSE_IDS" = "$CID" ] \
    || fail "X-Correlation-Id da resposta ('${RESPONSE_IDS}') diferente do enviado ('${CID}')"
echo "4/8 pedido ${ORDER_ID} criado com X-Correlation-Id ${CID}; o Gateway devolveu um unico header com o mesmo valor"

# 5. Espera CONFIRMED (saga completa: reserva de estoque + transportadora) e o ORDER_CONFIRMED na
#    linha do tempo (notification-service).
wait_for_order_status "$BUYER_TOKEN" "$ORDER_ID" CONFIRMED
wait_for_timeline_event "$BUYER_TOKEN" "$ORDER_ID" ORDER_CONFIRMED
echo "5/8 pedido ${ORDER_ID} CONFIRMED e ORDER_CONFIRMED na linha do tempo"

# 6. O mesmo ID tem que estar nos logs do Gateway e dos cinco servicos ("[<id>]" e o formato do
#    padrao de log com MDC). catalog-service e auth-service entram pela propagacao HTTP (D-96).
for svc in $LOG_SERVICES; do
    wait_for_id_in_logs "$svc" "$CID" \
        || fail "Correlation-ID ${CID} ausente nos logs de ${svc}"
    echo "    [${CID}] encontrado nos logs de ${svc}"
done
echo "6/8 o mesmo Correlation-ID aparece nos logs dos 6 servicos"

# 7. Caminho do ID invalido: o Gateway troca por um UUID e o valor cru nunca chega aos logs.
BAD_VALUE='bad value!'
do_request_with_headers "${GATEWAY_URL}/api/orders/${ORDER_ID}" \
    -H "X-Correlation-Id: ${BAD_VALUE}" \
    -H "Authorization: Bearer ${BUYER_TOKEN}"
[ "$REQ_STATUS" = "200" ] || fail "GET /api/orders/${ORDER_ID} com ID invalido devolveu ${REQ_STATUS}, esperado 200: ${REQ_BODY}"
REPLACED_IDS=$(response_correlation_ids)
REPLACED_COUNT=$(printf '%s\n' "$REPLACED_IDS" | grep -c . || true)
[ "$REPLACED_COUNT" = "1" ] \
    || fail "resposta com ID invalido trouxe ${REPLACED_COUNT} headers X-Correlation-Id, esperado exatamente 1"
printf '%s' "$REPLACED_IDS" | grep -Eq "$UUID_PATTERN" \
    || fail "ID invalido nao foi trocado por um UUID (devolvido: '${REPLACED_IDS}')"
[ "$REPLACED_IDS" != "$BAD_VALUE" ] || fail "ID invalido foi devolvido sem troca"
echo "7/8 ID invalido trocado pelo Gateway por ${REPLACED_IDS}"

# 8. O valor invalido nao pode aparecer cru em nenhum log do Gateway (e nem o dos servicos).
for svc in $LOG_SERVICES; do
    RAW_COUNT=$(docker compose logs --no-color "$svc" 2>&1 | grep -cF "$BAD_VALUE" || true)
    [ "$RAW_COUNT" = "0" ] \
        || fail "valor invalido '${BAD_VALUE}' apareceu ${RAW_COUNT}x nos logs de ${svc}"
done
echo "8/8 o valor invalido nao aparece cru em nenhum log"

# Linha final — qualquer verificacao futura entra ANTES desta linha.
echo "SMOKE OK correlation-id ${CID}"
