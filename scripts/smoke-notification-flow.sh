#!/usr/bin/env bash
# Smoke de ponta a ponta contra a stack real (docker compose up): um PUT /api/inventory/{id} pelo
# Gateway aparece, em segundos, como um evento STOCK_ADJUSTED em GET /api/notifications/{id} pelo
# mesmo Gateway — dois serviços reais conversando só pela fila notification-events-queue, sem
# nenhuma chamada REST entre inventory-service e notification-service (03-03-PLAN.md Task 1);
# reentrega da mesma mensagem não duplica o histórico e ajustes distintos do mesmo produto
# acumulam (03-03-PLAN.md Task 2, Success Criteria 3 e D-35).
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
QUEUE_NAME="notification-events-queue"

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

exec_localstack() {
    docker compose exec -T localstack "$@" | strip_cr
}

# Conta elementos do histórico contando ocorrências de "recordedAt" — eventId e eventType
# aparecem duas vezes por elemento (no nível do elemento e dentro do payload aninhado), mas
# recordedAt só existe no nível do elemento.
count_history_entries() {
    grep -o '"recordedAt"' | wc -l | tr -d ' '
}

# 1. Autentica com a credencial de demonstração e extrai o accessToken — nunca impresso.
LOGIN_BODY=$(exec_gateway curl -s -X POST "${GATEWAY_URL}/api/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"${DEMO_EMAIL}\",\"password\":\"${DEMO_PASSWORD}\"}")
TOKEN=$(printf '%s' "$LOGIN_BODY" | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
[ -n "$TOKEN" ] || fail "login nao devolveu accessToken"
echo "1/7 autenticado como ${DEMO_EMAIL}"

# 2. Gera um productId novo dentro do próprio container do Gateway.
PRODUCT_ID=$(exec_gateway cat /proc/sys/kernel/random/uuid)
[ -n "$PRODUCT_ID" ] || fail "nao foi possivel gerar um UUID de produto"
echo "2/7 productId gerado: ${PRODUCT_ID}"

# 3. Sem token, GET /api/notifications/{productId} tem que devolver 401 — nem 404 (rota ausente
# ou prefixo nao removido), nem 200 (endpoint nao exigindo JWT).
STATUS_NO_TOKEN=$(exec_gateway curl -s -o /dev/null -w '%{http_code}' \
    "${GATEWAY_URL}/api/notifications/${PRODUCT_ID}")
[ "$STATUS_NO_TOKEN" = "401" ] || fail "GET /api/notifications/{id} sem token devolveu ${STATUS_NO_TOKEN}, esperado 401"
echo "3/7 GET sem token confirma 401"

# 4. Ajusta o estoque do produto pelo Gateway — o inventory-service publica o evento depois do
# commit no Postgres.
PUT_STATUS=$(exec_gateway curl -s -o /dev/null -w '%{http_code}' -X PUT \
    "${GATEWAY_URL}/api/inventory/${PRODUCT_ID}" \
    -H "Authorization: Bearer ${TOKEN}" -H "Content-Type: application/json" \
    -d '{"quantityOnHand":42}')
[ "$PUT_STATUS" = "200" ] || fail "PUT /api/inventory/{id} devolveu ${PUT_STATUS}, esperado 200"
echo "4/7 PUT /api/inventory/${PRODUCT_ID} confirmado (200)"

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
echo "5/7 evento STOCK_ADJUSTED visivel no historico em ${ELAPSED}s"
FLOW_ELAPSED=$ELAPSED

# 6. Reentrega (Success Criteria 3): envia exatamente o mesmo corpo de evento duas vezes direto na
# fila real, espera a fila esvaziar (ambas entregas consumidas e confirmadas) e confirma que o
# histórico desse produto tem exatamente 1 elemento — reentrega sobrescreve, nunca duplica.
REDELIVERY_PRODUCT_ID=$(exec_gateway cat /proc/sys/kernel/random/uuid)
REDELIVERY_EVENT_ID=$(exec_gateway cat /proc/sys/kernel/random/uuid)
[ -n "$REDELIVERY_PRODUCT_ID" ] && [ -n "$REDELIVERY_EVENT_ID" ] \
    || fail "nao foi possivel gerar UUIDs para o teste de reentrega"
REDELIVERY_BODY="{\"eventId\":\"${REDELIVERY_EVENT_ID}\",\"eventType\":\"STOCK_ADJUSTED\",\"productId\":\"${REDELIVERY_PRODUCT_ID}\",\"previousQuantityOnHand\":1,\"newQuantityOnHand\":2,\"occurredAt\":\"2026-01-01T00:00:00Z\"}"

QUEUE_URL=$(exec_localstack awslocal --region us-east-1 sqs get-queue-url \
    --queue-name "$QUEUE_NAME" --query QueueUrl --output text)
[ -n "$QUEUE_URL" ] || fail "nao foi possivel obter a URL da fila ${QUEUE_NAME}"

exec_localstack awslocal --region us-east-1 sqs send-message \
    --queue-url "$QUEUE_URL" --message-body "$REDELIVERY_BODY" >/dev/null
exec_localstack awslocal --region us-east-1 sqs send-message \
    --queue-url "$QUEUE_URL" --message-body "$REDELIVERY_BODY" >/dev/null

WAIT_START=$SECONDS
ELAPSED=0
DRAINED=0
while [ "$ELAPSED" -le 15 ]; do
    QUEUE_ATTRS=$(exec_localstack awslocal --region us-east-1 sqs get-queue-attributes \
        --queue-url "$QUEUE_URL" \
        --attribute-names ApproximateNumberOfMessages ApproximateNumberOfMessagesNotVisible \
        --output json)
    VISIBLE=$(printf '%s' "$QUEUE_ATTRS" | sed -n 's/.*"ApproximateNumberOfMessages"[^0-9]*\([0-9]\+\).*/\1/p')
    NOT_VISIBLE=$(printf '%s' "$QUEUE_ATTRS" | sed -n 's/.*"ApproximateNumberOfMessagesNotVisible"[^0-9]*\([0-9]\+\).*/\1/p')
    if [ "$VISIBLE" = "0" ] && [ "$NOT_VISIBLE" = "0" ]; then
        DRAINED=1
        break
    fi
    sleep 1
    ELAPSED=$((SECONDS - WAIT_START))
done
[ "$DRAINED" -eq 1 ] || fail "fila ${QUEUE_NAME} nao esvaziou apos as duas entregas de reentrega em 15s"

REDELIVERY_HISTORY=$(exec_gateway curl -s "${GATEWAY_URL}/api/notifications/${REDELIVERY_PRODUCT_ID}" \
    -H "Authorization: Bearer ${TOKEN}")
REDELIVERY_COUNT=$(printf '%s' "$REDELIVERY_HISTORY" | count_history_entries)
[ "$REDELIVERY_COUNT" = "1" ] \
    || fail "reentrega duplicou: historico de ${REDELIVERY_PRODUCT_ID} tem ${REDELIVERY_COUNT} elemento(s), esperado 1"
echo "6/7 reentrega nao duplica (1 elemento no historico apos duas entregas do mesmo corpo)"

# 7. Ajustes distintos (D-35): mais dois PUT no produto do fluxo principal e confirma que o
# historico dele acumula 3 elementos (o ajuste original de 42, mais 50 e 60).
for QTY in 50 60; do
    PUT_STATUS_DISTINCT=$(exec_gateway curl -s -o /dev/null -w '%{http_code}' -X PUT \
        "${GATEWAY_URL}/api/inventory/${PRODUCT_ID}" \
        -H "Authorization: Bearer ${TOKEN}" -H "Content-Type: application/json" \
        -d "{\"quantityOnHand\":${QTY}}")
    [ "$PUT_STATUS_DISTINCT" = "200" ] \
        || fail "PUT /api/inventory/{id} (ajuste distinto ${QTY}) devolveu ${PUT_STATUS_DISTINCT}, esperado 200"
done

WAIT_START=$SECONDS
ELAPSED=0
FOUND_THREE=0
while [ "$ELAPSED" -le 15 ]; do
    DISTINCT_HISTORY=$(exec_gateway curl -s "${GATEWAY_URL}/api/notifications/${PRODUCT_ID}" \
        -H "Authorization: Bearer ${TOKEN}")
    DISTINCT_COUNT=$(printf '%s' "$DISTINCT_HISTORY" | count_history_entries)
    if [ "$DISTINCT_COUNT" = "3" ]; then
        FOUND_THREE=1
        break
    fi
    sleep 1
    ELAPSED=$((SECONDS - WAIT_START))
done
[ "$FOUND_THREE" -eq 1 ] \
    || fail "ajustes distintos nao acumularam: historico de ${PRODUCT_ID} tem ${DISTINCT_COUNT:-?} elemento(s) apos 15s, esperado 3"
echo "7/7 ajustes distintos acumulam (3 elementos no historico apos PUT 42, 50 e 60)"

# Linha final — qualquer verificação futura acrescentada por planos seguintes entra ANTES desta
# linha.
echo "SMOKE OK ${PRODUCT_ID} ${FLOW_ELAPSED}s fluxo, reentrega sem duplicacao e ajustes distintos confirmados"
