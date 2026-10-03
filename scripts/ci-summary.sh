#!/usr/bin/env bash
# Resumo dos relatorios de teste de um modulo, em markdown, para o "job summary" do GitHub Actions
# (D-104): uso `bash scripts/ci-summary.sh <modulo> >> "$GITHUB_STEP_SUMMARY"`.
#
# Soma tests/failures/errors/skipped das tags <testsuite ...> dos TEST-*.xml de surefire-reports
# (testes unitarios) e failsafe-reports (testes de integracao, *IT) e lista as suites com falha ou
# erro. Sem Python/Node — so grep/sed/awk. Sempre sai com 0: o resumo nunca pode mascarar nem causar
# a falha do job (quem decide e o passo `./mvnw verify`).
set -uo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

MODULE="${1:-}"
if [ -z "$MODULE" ]; then
    echo "uso: scripts/ci-summary.sh <modulo>" >&2
    exit 0
fi

echo "### ${MODULE}"
echo

HAS_REPORTS=0
for kind in surefire failsafe; do
    ls "${MODULE}/target/${kind}-reports"/TEST-*.xml >/dev/null 2>&1 && HAS_REPORTS=1
done

if [ "$HAS_REPORTS" -eq 0 ]; then
    echo "sem relatórios — o build falhou antes dos testes"
    exit 0
fi

# Le o valor de um atributo numerico da tag <testsuite ...> de um relatorio.
attr() {
    local file="$1" name="$2"
    grep -m1 -o "<testsuite [^>]*" "$file" | grep -o " ${name}=\"[0-9]*\"" | head -n1 | sed 's/[^0-9]//g'
}

echo "| Relatório | Testes | Falhas | Erros | Ignorados |"
echo "|---|---|---|---|---|"

FAILED_SUITES=""
for kind in surefire failsafe; do
    dir="${MODULE}/target/${kind}-reports"
    tests=0; failures=0; errors=0; skipped=0; found=0
    for f in "$dir"/TEST-*.xml; do
        [ -f "$f" ] || continue
        found=1
        t=$(attr "$f" tests); fl=$(attr "$f" failures); er=$(attr "$f" errors); sk=$(attr "$f" skipped)
        tests=$((tests + ${t:-0})); failures=$((failures + ${fl:-0}))
        errors=$((errors + ${er:-0})); skipped=$((skipped + ${sk:-0}))
        if [ "${fl:-0}" -gt 0 ] || [ "${er:-0}" -gt 0 ]; then
            suite=$(grep -m1 -o "<testsuite [^>]*" "$f" | grep -o ' name="[^"]*"' | head -n1 | sed 's/ name="//; s/"$//')
            FAILED_SUITES="${FAILED_SUITES}- \`${suite}\` (${kind}): ${fl:-0} falha(s), ${er:-0} erro(s)"$'\n'
        fi
    done
    if [ "$found" -eq 1 ]; then
        echo "| ${kind}-reports | ${tests} | ${failures} | ${errors} | ${skipped} |"
    else
        echo "| ${kind}-reports | — | — | — | — |"
    fi
done

if [ -n "$FAILED_SUITES" ]; then
    echo
    echo "**Suítes com falha ou erro:**"
    echo
    printf '%s' "$FAILED_SUITES"
fi
exit 0
