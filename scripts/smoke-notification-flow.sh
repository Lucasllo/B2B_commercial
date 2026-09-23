#!/usr/bin/env bash
# Smoke de ponta a ponta contra a stack real (docker compose up): um PUT /api/inventory/{id} pelo
# Gateway aparece, em segundos, como um evento STOCK_ADJUSTED em GET /api/notifications/{id} pelo
# mesmo Gateway — dois serviços reais conversando só pela fila notification-events-queue, sem
# nenhuma chamada REST entre inventory-service e notification-service (03-03-PLAN.md Task 1).
#
# MSYS_NO_PATHCONV=1 evita que o Git Bash do Windows reescreva argumentos como
# /proc/sys/kernel/random/uuid como caminhos do Windows antes de repassá-los ao
# `docker compose exec`.
set -euo pipefail
export MSYS_NO_PATHCONV=1

cd "$(dirname "${BASH_SOURCE[0]}")/.."

DEMO_EMAIL="admin@orderflow.local"
DEMO_PASSWORD="ChangeMe!123"
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

# 1. Autentica com a credencial de demonstração e extrai o accessToken — nunca impresso.
LOGIN_BODY=$(exec_gateway curl -s -X POST "${GATEWAY_URL}/api/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"${DEMO_EMAIL}\",\"password\":\"${DEMO_PASSWORD}\"}")
TOKEN=$(printf '%s' "$LOGIN_BODY" | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
[ -n "$TOKEN" ] || fail "login nao devolveu accessToken"
echo "1/6 autenticado como ${DEMO_EMAIL}"

# 2. Gera um productId novo dentro do próprio container do Gateway.
PRODUCT_ID=$(exec_gateway cat /proc/sys/kernel/random/uuid)
[ -n "$PRODUCT_ID" ] || fail "nao foi possivel gerar um UUID de produto"
echo "2/6 productId gerado: ${PRODUCT_ID}"

# 3. Sem token, GET /api/notifications/{productId} tem que devolver 401 — nem 404 (rota ausente
# ou prefixo nao removido), nem 200 (endpoint nao exigindo JWT).
STATUS_NO_TOKEN=$(exec_gateway curl -s -o /dev/null -w '%{http_code}' \
    "${GATEWAY_URL}/api/notifications/${PRODUCT_ID}")
[ "$STATUS_NO_TOKEN" = "401" ] || fail "GET /api/notifications/{id} sem token devolveu ${STATUS_NO_TOKEN}, esperado 401"
echo "3/6 GET sem token confirma 401"

# 4. Ajusta o estoque do produto pelo Gateway — o inventory-service publica o evento depois do
# commit no Postgres.
PUT_STATUS=$(exec_gateway curl -s -o /dev/null -w '%{http_code}' -X PUT \
    "${GATEWAY_URL}/api/inventory/${PRODUCT_ID}" \
    -H "Authorization: Bearer ${TOKEN}" -H "Content-Type: application/json" \
    -d '{"quantityOnHand":42}')
[ "$PUT_STATUS" = "200" ] || fail "PUT /api/inventory/{id} devolveu ${PUT_STATUS}, esperado 200"
echo "4/6 PUT /api/inventory/${PRODUCT_ID} confirmado (200)"

# 5. Consulta o histórico pelo Gateway a cada segundo, por no máximo 15 segundos, até o evento
# STOCK_ADJUSTED aparecer com a quantidade nova correta.
WAIT_START=$SECONDS
ELAPSED=0
FOUND=0
while [ "$ELAPSED" -le 15 ]; do
    HISTORY_BODY=$(exec_gateway curl -s "${GATEWAY_URL}/api/notifications/${PRODUCT_ID}" \
        -H "Authorization: Bearer ${TOKEN}")
    if printf '%s' "$HISTORY_BODY" | grep -q '"eventType":"STOCK_ADJUSTED"' \
        && printf '%s' "$HISTORY_BODY" | grep -q '"newQuantityOnHand":42'; then
        FOUND=1
        break
    fi
    sleep 1
    ELAPSED=$((SECONDS - WAIT_START))
done
[ "$FOUND" -eq 1 ] || fail "evento STOCK_ADJUSTED nao apareceu no historico de ${PRODUCT_ID} em 15s"
echo "5/6 evento STOCK_ADJUSTED visivel no historico em ${ELAPSED}s"

# 6. Linha final — qualquer verificação futura acrescentada por planos seguintes entra ANTES desta
# linha (03-03-PLAN.md Task 2: reentrega e ajustes distintos).
echo "SMOKE OK ${PRODUCT_ID} ${ELAPSED}s"
