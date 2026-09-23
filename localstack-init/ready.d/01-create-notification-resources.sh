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
set -euo pipefail

REGION="us-east-1"
QUEUE_NAME="notification-events-queue"
TABLE_NAME="notification-history"

awslocal --region "$REGION" sqs create-queue --queue-name "$QUEUE_NAME"

if ! awslocal --region "$REGION" dynamodb describe-table --table-name "$TABLE_NAME" >/dev/null 2>&1; then
    awslocal --region "$REGION" dynamodb create-table \
        --table-name "$TABLE_NAME" \
        --attribute-definitions \
            AttributeName=productId,AttributeType=S \
            AttributeName=sortKey,AttributeType=S \
        --key-schema \
            AttributeName=productId,KeyType=HASH \
            AttributeName=sortKey,KeyType=RANGE \
        --billing-mode PAY_PER_REQUEST
fi

awslocal --region "$REGION" dynamodb wait table-exists --table-name "$TABLE_NAME"

echo "notification-events-queue e notification-history prontos"
