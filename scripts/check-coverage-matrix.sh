#!/usr/bin/env bash
# Verificacao mecanica da matriz regra -> teste da Fase 7 (TEST-01, TEST-02, D-105). Sem dependencias
# alem de coreutils/grep/sed. Le .planning/phases/07-*/07-COVERAGE.md e confere:
#   - existe a secao "## <modulo>" dos 7 modulos (auth-service, catalog-service, inventory-service,
#     notification-service, order-service, gateway, e2e-tests) e cada uma tem ao menos uma regra;
#   - todo nome entre crases terminado em Test ou IT (com "#metodo" opcional) existe como arquivo
#     <Nome>.java em */src/test/java e, quando ha metodo, o metodo existe no arquivo;
#   - toda linha de modulo de servico cita um teste unitario (coluna 4) e um teste de integracao
#     contra dependencia real (coluna 5); toda linha de e2e-tests cita um teste E2E;
#   - o status de toda linha e "coberta", "fechada em 07-NN" ou "LACUNA";
#   - fora do modo --report, nenhuma linha termina como LACUNA.
# Linhas com status LACUNA nao tem os nomes conferidos (o teste ainda nao existe, por definicao).
#
# Uso: bash scripts/check-coverage-matrix.sh [--report]
#   --report  lista as lacunas como "LACUNA: <modulo> #<n> <regra>" e NAO falha por elas.
#
# Saida: uma linha "COVERAGE CHECK FALHOU: <problema>" por problema (stderr) e exit 1; sem problemas,
# a ultima linha e "COVERAGE CHECK OK <n> regras".
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

MODE="strict"
if [ "${1:-}" = "--report" ]; then
    MODE="report"
fi

MATRIX=""
for candidate in .planning/phases/07-*/07-COVERAGE.md; do
    if [ -f "$candidate" ]; then
        MATRIX="$candidate"
    fi
done
if [ -z "$MATRIX" ]; then
    echo "COVERAGE CHECK FALHOU: matriz 07-COVERAGE.md nao encontrada em .planning/phases/07-*/" >&2
    exit 1
fi

ALL_MODULES="auth-service catalog-service inventory-service notification-service order-service gateway e2e-tests"

PROBLEMS=0
RULES=0

problem() {
    echo "COVERAGE CHECK FALHOU: $1" >&2
    PROBLEMS=$((PROBLEMS + 1))
}

# Arquivos de teste existentes (versionados ou ainda nao commitados, fora do .gitignore).
TEST_FILES="$(git ls-files --cached --others --exclude-standard '*/src/test/java/*' | grep -E '\.java$' || true)"

# Imprime o caminho do arquivo de teste <Nome>.java DO MODULO indicado (varias classes tem o mesmo
# nome em modulos diferentes, ex.: CorrelationIdFilterTest), ou nada.
test_file() {
    printf '%s\n' "$TEST_FILES" | grep -E "^$2/.*/$1\.java$" | head -1 || true
}

# Extrai os nomes entre crases de um campo, um por linha.
backticked() {
    printf '%s\n' "$1" | grep -oE '`[^`]+`' | tr -d '`' || true
}

# Confere os testes citados num campo; imprime "<n>" de testes citados que sao classes *Test/*IT.
# Retorna o numero de nomes de teste validos em CITED e registra problemas para os inexistentes.
CITED=0
check_field() {
    local module="$1" row="$2" field="$3" name base method file
    CITED=0
    while IFS= read -r name; do
        [ -z "$name" ] && continue
        base="${name%%#*}"
        case "$base" in
            *Test|*IT) ;;
            *) continue ;;
        esac
        file="$(test_file "$base" "$module")"
        if [ -z "$file" ]; then
            problem "teste inexistente $base ($module #$row)"
            continue
        fi
        if [ "$base" != "$name" ]; then
            method="${name#*#}"
            if ! grep -qE "void $method\(" "$file"; then
                problem "metodo inexistente $name ($module #$row)"
                continue
            fi
        fi
        CITED=$((CITED + 1))
    done < <(backticked "$field")
}

trim() {
    local v="$1"
    v="${v#"${v%%[![:space:]]*}"}"
    v="${v%"${v##*[![:space:]]}"}"
    printf '%s' "$v"
}

SECTIONS_SEEN=" "
SECTION_ROWS=" "
CURRENT=""

while IFS= read -r line; do
    if [[ "$line" =~ ^##[[:space:]]+([a-z0-9-]+)[[:space:]]*$ ]]; then
        CURRENT="${BASH_REMATCH[1]}"
        SECTIONS_SEEN="$SECTIONS_SEEN$CURRENT "
        continue
    fi
    [ -z "$CURRENT" ] && continue
    case "$ALL_MODULES" in
        *"$CURRENT"*) ;;
        *) continue ;;
    esac
    # Linha de dados: comeca por "| <numero> |".
    if ! [[ "$line" =~ ^\|[[:space:]]*([0-9]+)[[:space:]]*\| ]]; then
        continue
    fi
    row="${BASH_REMATCH[1]}"
    IFS='|' read -ra F <<< "$line"
    rule="$(trim "${F[2]:-}")"
    RULES=$((RULES + 1))
    SECTION_ROWS="$SECTION_ROWS$CURRENT "

    if [ "$CURRENT" = "e2e-tests" ]; then
        e2e_field="${F[4]:-}"
        status="$(trim "${F[5]:-}")"
        unit_field=""
        it_field=""
    else
        unit_field="${F[4]:-}"
        it_field="${F[5]:-}"
        status="$(trim "${F[6]:-}")"
    fi

    if [[ "$status" == *LACUNA* ]]; then
        if [ "$MODE" = "report" ]; then
            echo "LACUNA: $CURRENT #$row $rule"
        else
            problem "lacuna aberta: $CURRENT #$row"
        fi
        continue
    fi

    case "$status" in
        coberta*|"fechada em 07-"*) ;;
        *) problem "status invalido '$status' ($CURRENT #$row): use coberta, fechada em 07-NN ou LACUNA" ;;
    esac

    if [ "$CURRENT" = "e2e-tests" ]; then
        check_field "$CURRENT" "$row" "$e2e_field"
        if [ "$CITED" -eq 0 ]; then
            problem "linha $row de $CURRENT sem teste E2E"
        fi
        continue
    fi

    check_field "$CURRENT" "$row" "$unit_field"
    if [ "$CITED" -eq 0 ]; then
        problem "linha $row de $CURRENT sem teste unitario"
    fi
    check_field "$CURRENT" "$row" "$it_field"
    if [ "$CITED" -eq 0 ]; then
        problem "linha $row de $CURRENT sem teste de integracao"
    fi
done < <(tr -d '\r' < "$MATRIX")

for module in $ALL_MODULES; do
    case "$SECTIONS_SEEN" in
        *" $module "*) ;;
        *) problem "secao ausente: ## $module" ;;
    esac
    case "$SECTIONS_SEEN" in
        *" $module "*)
            case "$SECTION_ROWS" in
                *" $module "*) ;;
                *) problem "secao $module sem nenhuma regra" ;;
            esac
            ;;
    esac
done

if [ "$PROBLEMS" -gt 0 ]; then
    exit 1
fi

echo "COVERAGE CHECK OK $RULES regras"
