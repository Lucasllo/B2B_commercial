#!/bin/bash
# Este e o UNICO lugar do projeto que cria as filas da saga de reserva de estoque e suas DLQs
# (D-61) — nenhuma aplicacao Java cria estes recursos (order-service/inventory-service usam
# queue-not-found-strategy=fail propositalmente, para que um init hook quebrado quebre a subida em
# vez de mascarar o problema criando a fila em silencio).
#
# Regiao us-east-1 e credenciais "test" precisam bater com a configuracao dos servicos Java
# (spring.cloud.aws.region.static / credentials.access-key / secret-key), porque o LocalStack
# separa recursos por regiao e por conta derivada da access key.
#
# inventory-commands-queue (order -> inventory: ReserveStock/ReleaseStock) e order-events-queue
# (inventory -> order: resultados) — cada uma com sua propria DLQ e maxReceiveCount 3 (Claude's
# Discretion, 05-CONTEXT.md). Primeiro cria a DLQ, le o ARN dela, e so entao cria a fila principal
# com RedrivePolicy apontando para essa DLQ — create-queue com os mesmos atributos e idempotente,
# entao reiniciar o container LocalStack sem persistencia nao falha nem duplica.
set -euo pipefail

REGION="us-east-1"

create_queue_with_dlq() {
    local queue_name="$1"
    local dlq_name="$2"
    local max_receive_count="$3"

    awslocal --region "$REGION" sqs create-queue --queue-name "$dlq_name"
    local dlq_url
    dlq_url=$(awslocal --region "$REGION" sqs get-queue-url --queue-name "$dlq_name" --query QueueUrl --output text)
    local dlq_arn
    dlq_arn=$(awslocal --region "$REGION" sqs get-queue-attributes --queue-url "$dlq_url" \
        --attribute-names QueueArn --query Attributes.QueueArn --output text)

    local redrive_policy
    redrive_policy=$(printf '{"deadLetterTargetArn":"%s","maxReceiveCount":"%s"}' "$dlq_arn" "$max_receive_count")
    local escaped_redrive_policy
    escaped_redrive_policy=$(printf '%s' "$redrive_policy" | sed 's/"/\\"/g')

    awslocal --region "$REGION" sqs create-queue --queue-name "$queue_name" \
        --attributes "{\"RedrivePolicy\":\"$escaped_redrive_policy\"}"
}

create_queue_with_dlq "inventory-commands-queue" "inventory-commands-dlq" 3
create_queue_with_dlq "order-events-queue" "order-events-dlq" 3

echo "inventory-commands-queue, inventory-commands-dlq, order-events-queue e order-events-dlq prontos"
