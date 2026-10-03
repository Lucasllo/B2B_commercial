#!/usr/bin/env bash
# Verificacao mecanica dos ADRs em docs/adr/ (INFRA-03, D-108, D-110). Sem dependencias alem de
# coreutils/grep/sed. Para cada docs/adr/NNNN-*.md confere:
#   - front matter com "status: Aceito" e "date:";
#   - as secoes do formato MADR em portugues (Contexto e problema, Fatores de decisao, Alternativas
#     consideradas, Decisao, Consequencias, Pros e contras das alternativas, Mais informacoes);
#   - ao menos uma alternativa marcada como rejeitada (D-109);
#   - a linha "Fase de origem:" (D-110);
#   - todo link markdown relativo resolve a partir de docs/adr/;
#   - todo D-<n> citado existe como "**D-<n>:**" em algum .planning/phases/*/*-CONTEXT.md;
#   - o arquivo esta listado em docs/adr/README.md e nenhum link do indice aponta para o vazio;
#   - nenhum valor que lembre token/segredo (T-07-21).
# E confere que os quatro ADRs obrigatorios do criterio 5 existem (por tema no titulo).
#
# Saida: uma linha "ADR CHECK FALHOU: <arquivo>: <problema>" por problema (stderr) e exit 1; sem
# problemas, a ultima linha e "ADR CHECK OK <n> ADRs".
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

ADR_DIR="docs/adr"
INDEX="$ADR_DIR/README.md"
PROBLEMS=0

problem() {
    echo "ADR CHECK FALHOU: $1: $2" >&2
    PROBLEMS=$((PROBLEMS + 1))
}

shopt -s nullglob
ADR_FILES=("$ADR_DIR"/[0-9][0-9][0-9][0-9]-*.md)
shopt -u nullglob

if [ "${#ADR_FILES[@]}" -eq 0 ]; then
    problem "$ADR_DIR" "nenhum ADR encontrado"
fi

# D-<n> definidos nos CONTEXT.md de todas as fases (formato "**D-<n>:**").
DEFINED_DECISIONS=" $(cat .planning/phases/*/*-CONTEXT.md 2>/dev/null | tr -d '\r' \
    | grep -oE '^- \*\*D-[0-9]+:\*\*' | grep -oE 'D-[0-9]+' | sort -u | tr '\n' ' ')"

# Confere os links markdown relativos de um arquivo (resolvidos a partir de docs/adr/).
check_links() {
    local file="$1" target path
    while IFS= read -r target; do
        [ -z "$target" ] && continue
        case "$target" in
            http://*|https://*|mailto:*|\#*) continue ;;
        esac
        path="${target%%#*}"
        [ -z "$path" ] && continue
        if [ ! -e "$ADR_DIR/$path" ]; then
            problem "$file" "link quebrado $target"
        fi
    done < <(tr -d '\r' < "$file" | grep -oE '\]\([^)]+\)' | sed -E 's/^\]\(//; s/\)$//' || true)
}

for file in "${ADR_FILES[@]}"; do
    base="$(basename "$file")"
    content="$(tr -d '\r' < "$file")"

    if ! printf '%s\n' "$content" | grep -qE '^status: Aceito[[:space:]]*$'; then
        problem "$file" "status diferente de Aceito"
    fi
    if ! printf '%s\n' "$content" | grep -qE '^date: [0-9]{4}-[0-9]{2}-[0-9]{2}'; then
        problem "$file" "sem 'date' no front matter"
    fi

    for section in '## Contexto e problema' '## Fatores de decisão' '## Alternativas consideradas' \
                   '## Decisão' '## Prós e contras das alternativas' '## Mais informações'; do
        if ! printf '%s\n' "$content" | grep -qE "^${section}[[:space:]]*\$"; then
            problem "$file" "sem seção '${section#\#\# }'"
        fi
    done
    if ! printf '%s\n' "$content" | grep -qE '^#{2,3} Consequências[[:space:]]*$'; then
        problem "$file" "sem seção 'Consequências'"
    fi

    if ! printf '%s\n' "$content" | grep -q 'rejeitada'; then
        problem "$file" "sem alternativa rejeitada"
    fi
    if ! printf '%s\n' "$content" | grep -q 'Fase de origem:'; then
        problem "$file" "sem 'Fase de origem'"
    fi

    check_links "$file"

    while IFS= read -r decision; do
        [ -z "$decision" ] && continue
        case "$DEFINED_DECISIONS" in
            *" $decision "*) ;;
            *) problem "$file" "$decision não existe em nenhum CONTEXT.md" ;;
        esac
    done < <(printf '%s\n' "$content" | grep -oE 'D-[0-9]+' | sort -u || true)

    if printf '%s\n' "$content" | grep -qE 'LOCALSTACK_AUTH_TOKEN[=:][[:space:]]*[A-Za-z0-9_-]{8,}'; then
        problem "$file" "possível valor de token/segredo"
    fi

    if [ ! -f "$INDEX" ] || ! grep -qF "$base" "$INDEX"; then
        problem "$file" "ausente do índice"
    fi
done

if [ ! -f "$INDEX" ]; then
    problem "$INDEX" "índice ausente"
else
    check_links "$INDEX"
    # Todo ADR citado no indice precisa existir.
    while IFS= read -r entry; do
        [ -z "$entry" ] && continue
        if [ ! -f "$ADR_DIR/$entry" ]; then
            problem "$INDEX" "entrada do índice sem arquivo $entry"
        fi
    done < <(tr -d '\r' < "$INDEX" | grep -oE '\([0-9]{4}-[^)]*\.md\)' | tr -d '()' | sort -u || true)
fi

# ADRs obrigatorios do criterio 5 (tema no titulo de nivel 1).
for theme in 'orquestração' 'Outbox' 'LocalStack' 'service discovery'; do
    found=0
    for file in "${ADR_FILES[@]}"; do
        if tr -d '\r' < "$file" | grep -iqE "^# .*${theme}"; then
            found=1
            break
        fi
    done
    if [ "$found" -eq 0 ]; then
        problem "$ADR_DIR" "ADR obrigatório ausente: $theme"
    fi
done

if [ "$PROBLEMS" -ne 0 ]; then
    exit 1
fi

echo "ADR CHECK OK ${#ADR_FILES[@]} ADRs"
