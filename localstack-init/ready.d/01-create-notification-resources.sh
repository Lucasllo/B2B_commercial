#!/bin/bash
# Este e o UNICO lugar do projeto que cria a fila notification-events-queue e a tabela
# notification-history — nenhuma aplicacao Java cria estes recursos (Success Criteria 4 do
# ROADMAP; notification-service/application.yml usa queue-not-found-strategy=fail
# propositalmente, para que um init hook quebrado quebre a subida em vez de mascarar o problema
# criando a fila em silencio).
#
# Regiao us-east-1 e credenciais "test" precisam bater com a configuracao dos servicos Java
# (spring.cloud.aws.region.static / credentials.access-key / secret-key), porque o LocalStack
# separa recursos por regiao e por conta derivada da access key.
#
# D-80 (Fase 6): a particao da tabela e o atributo generico "entityId" — o id do produto nos
# eventos STOCK_ADJUSTED e o id do pedido nos eventos ORDER_*, na mesma tabela (realiza a
# intencao de D-32, "particao por entidade"). O nome precisa bater com o getter
# @DynamoDbPartitionKey de NotificationRecord (getEntityId).
set -euo pipefail

REGION="us-east-1"
QUEUE_NAME="notification-events-queue"
TABLE_NAME="notification-history"

awslocal --region "$REGION" sqs create-queue --queue-name "$QUEUE_NAME"

# Pitfall 11: um LocalStack que NAO reiniciou desde a Fase 5 ainda guarda a tabela com o key-schema
# antigo (particao "productId"). O hook e idempotente para a tabela com a chave nova, mas esta
# unica excecao apaga e recria a tabela — o estado do LocalStack nao e persistido (PERSISTENCE=0),
# entao nao ha dado real a migrar. "docker compose down" tambem resolve.
if awslocal --region "$REGION" dynamodb describe-table --table-name "$TABLE_NAME" >/dev/null 2>&1; then
    CURRENT_HASH_KEY=$(awslocal --region "$REGION" dynamodb describe-table --table-name "$TABLE_NAME" \
        --query "Table.KeySchema[?KeyType=='HASH'].AttributeName" --output text)
    if [ "$CURRENT_HASH_KEY" != "entityId" ]; then
        echo "notification-history com particao '$CURRENT_HASH_KEY' (esperado entityId) — recriando a tabela"
        awslocal --region "$REGION" dynamodb delete-table --table-name "$TABLE_NAME" >/dev/null
        awslocal --region "$REGION" dynamodb wait table-not-exists --table-name "$TABLE_NAME"
    fi
fi

if ! awslocal --region "$REGION" dynamodb describe-table --table-name "$TABLE_NAME" >/dev/null 2>&1; then
    awslocal --region "$REGION" dynamodb create-table \
        --table-name "$TABLE_NAME" \
        --attribute-definitions \
            AttributeName=entityId,AttributeType=S \
            AttributeName=sortKey,AttributeType=S \
        --key-schema \
            AttributeName=entityId,KeyType=HASH \
            AttributeName=sortKey,KeyType=RANGE \
        --billing-mode PAY_PER_REQUEST
fi

awslocal --region "$REGION" dynamodb wait table-exists --table-name "$TABLE_NAME"

echo "notification-events-queue e notification-history prontos"
