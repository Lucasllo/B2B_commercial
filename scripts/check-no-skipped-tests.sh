#!/usr/bin/env bash
# Guarda do CI (D-103, T-07-39): nenhum teste pode ser desabilitado ou condicionado em silencio.
# Um teste pulado deixa o CI verde sem provar nada — o mesmo problema de "sem o token, pule os testes
# de LocalStack". Procura, em todo diretorio */src/test/java (pelo sistema de arquivos, nao so pelo
# indice do git), as anotacoes e chamadas do JUnit 5 e 4 que desligam ou condicionam um teste:
#   @Disabled, @DisabledIf..., @EnabledIf..., @Ignore, Assumptions.assume..., assumeTrue, assumeFalse
# inclusive na forma totalmente qualificada (@org.junit.jupiter.api.Disabled) — por isso o regex
# aceita um prefixo de pacote opcional depois do "@".
#
# Saida: uma linha "TESTE PULADO: <arquivo>:<linha>" por ocorrencia e exit 1; sem ocorrencias,
# "NENHUM TESTE PULADO" e exit 0.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

PATTERN='@([A-Za-z0-9_]+\.)*(Disabled[A-Za-z]*|EnabledIf[A-Za-z]*|EnabledOn[A-Za-z]*|EnabledFor[A-Za-z]*|Ignore)\b|Assumptions\.assume[A-Za-z]*|\bassume(True|False|That|NotNull)\b'

FOUND=0
while IFS= read -r test_dir; do
    while IFS= read -r hit; do
        [ -n "$hit" ] || continue
        # hit = arquivo:linha:texto -> imprime so arquivo:linha
        echo "TESTE PULADO: $(printf '%s' "$hit" | cut -d: -f1,2)" >&2
        FOUND=1
    done < <(grep -rnE --include='*.java' "$PATTERN" "$test_dir" || true)
done < <(find . -type d -path '*/src/test/java' -not -path '*/target/*' -not -path './.git/*' | sort)

if [ "$FOUND" -ne 0 ]; then
    exit 1
fi
echo "NENHUM TESTE PULADO"
