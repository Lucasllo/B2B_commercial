package com.orderflow.order.order;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Confronta os diagramas {@code stateDiagram-v2} da documentação com a tabela única de transições
 * {@link OrderStatus#transitions()} (D-83, critério 4 do ROADMAP: o fluxo de status é "documentado com
 * diagrama e corresponde exatamente ao comportamento real da API").
 *
 * <p>Este teste prova a metade "diagrama = tabela"; a metade "API = tabela" é provada pelo {@code
 * OrderLifecycleTransitionsIT}. Cada diagrama precisa ter exatamente as arestas {@code A --> B} da
 * tabela (as que envolvem o marcador {@code [*]} de início/fim são ignoradas), com nomes que existam em
 * {@link OrderStatus}: aresta a mais, aresta a menos e estado inexistente quebram o build.
 *
 * <p>Os documentos são lidos a partir do diretório do módulo (o Surefire usa o diretório do módulo como
 * diretório de trabalho), mesma técnica de {@code LocalStackTestSupport}.
 */
class OrderStatusDiagramConsistencyTest {

    private static final Pattern MERMAID_BLOCK =
            Pattern.compile("```mermaid\\R(.*?)\\R\\s*```", Pattern.DOTALL);
    private static final Pattern EDGE =
            Pattern.compile("^\\s*([A-Za-z_]+)\\s*-->\\s*([A-Za-z_]+)\\s*(?::[^:]*)?$");
    private static final Pattern START_OR_END_EDGE =
            Pattern.compile("^\\s*(\\[\\*\\]\\s*-->\\s*[A-Za-z_]+|[A-Za-z_]+\\s*-->\\s*\\[\\*\\])\\s*(?::[^:]*)?$");

    // ---------------------------------------------------------------------------------------------
    // Os documentos reais
    // ---------------------------------------------------------------------------------------------

    /**
     * Documentos que carregam um diagrama de estados do pedido, relativos ao diretório do módulo
     * ({@code order-service/}): a raiz do repositório fica um nível acima.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"../README.md", "../docs/VISAO-GERAL.md"})
    void everyStateDiagramInTheDocumentMatchesTheTransitionTableExactly(String document) throws IOException {
        Path path = Path.of(document).toAbsolutePath().normalize();
        String markdown = Files.readString(path, StandardCharsets.UTF_8);

        List<String> diagrams = stateDiagrams(markdown);
        assertThat(diagrams)
                .as("%s precisa conter ao menos um bloco ```mermaid com stateDiagram-v2", document)
                .isNotEmpty();
        for (String diagram : diagrams) {
            assertThat(divergences(diagram)).as("diagrama de %s", document).isEmpty();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Prova de que o comparador tem dentes
    // ---------------------------------------------------------------------------------------------

    @Test
    void aDiagramBuiltFromTheTableItselfHasNoDivergence() {
        assertThat(divergences(diagramOf(tableEdges()))).isEmpty();
    }

    @Test
    void anExtraEdgeIsReported() {
        Set<String> edges = tableEdges();
        edges.add("CANCELLED --> SHIPPED");

        assertThat(divergences(diagramOf(edges)))
                .anyMatch(message -> message.contains("a mais") && message.contains("CANCELLED --> SHIPPED"));
    }

    @Test
    void aMissingEdgeIsReported() {
        Set<String> edges = tableEdges();
        edges.remove("SHIPPED --> DELIVERED");

        assertThat(divergences(diagramOf(edges)))
                .anyMatch(message -> message.contains("a menos") && message.contains("SHIPPED --> DELIVERED"));
    }

    @Test
    void aNonexistentStateIsReported() {
        Set<String> edges = tableEdges();
        edges.add("DELIVERED --> RETURNED");

        assertThat(divergences(diagramOf(edges)))
                .anyMatch(message -> message.contains("inexistente") && message.contains("RETURNED"));
    }

    @Test
    void aMalformedEdgeLineIsReportedInsteadOfSilentlyIgnored() {
        String diagram = diagramOf(tableEdges()) + "\n    CONFIRMED --> SHIPPED --> DELIVERED";

        assertThat(divergences(diagram)).anyMatch(message -> message.contains("fora do formato"));
    }

    @Test
    void startAndEndMarkersAreIgnoredAndLabelsAreAccepted() {
        String diagram = "stateDiagram-v2\n"
                + "    [*] --> CREATED\n"
                + "    CREATED --> PENDING_APPROVAL: valor acima do limite\n"
                + "    REJECTED --> [*]\n";

        assertThat(edgesOf(diagram)).containsExactly("CREATED --> PENDING_APPROVAL");
    }

    @Test
    void onlyMermaidBlocksWithStateDiagramAreExtracted() {
        String markdown = "texto\n```mermaid\nflowchart LR\n  A --> B\n```\n\n"
                + "```mermaid\nstateDiagram-v2\n    [*] --> CREATED\n```\n\n"
                + "```bash\nstateDiagram-v2\n```\n";

        assertThat(stateDiagrams(markdown)).hasSize(1);
    }

    // ---------------------------------------------------------------------------------------------
    // Extração e comparação — métodos estáticos que recebem o texto, sem depender de arquivo
    // ---------------------------------------------------------------------------------------------

    static List<String> stateDiagrams(String markdown) {
        List<String> diagrams = new ArrayList<>();
        Matcher matcher = MERMAID_BLOCK.matcher(markdown);
        while (matcher.find()) {
            String block = matcher.group(1);
            if (block.contains("stateDiagram-v2")) {
                diagrams.add(block);
            }
        }
        return diagrams;
    }

    /** As arestas {@code A --> B} do diagrama, sem as de início/fim ({@code [*]}). */
    static Set<String> edgesOf(String diagram) {
        Set<String> edges = new TreeSet<>();
        for (String line : diagram.split("\\R")) {
            Matcher matcher = EDGE.matcher(line);
            if (matcher.matches()) {
                edges.add(matcher.group(1) + " --> " + matcher.group(2));
            }
        }
        return edges;
    }

    /** Descrição de tudo em que o diagrama diverge da tabela; lista vazia quando bate exatamente. */
    static List<String> divergences(String diagram) {
        List<String> messages = new ArrayList<>();
        Set<String> knownStates = Arrays.stream(OrderStatus.values())
                .map(Enum::name)
                .collect(Collectors.toSet());

        for (String line : diagram.split("\\R")) {
            if (line.contains("-->")
                    && !EDGE.matcher(line).matches()
                    && !START_OR_END_EDGE.matcher(line).matches()) {
                messages.add("linha de aresta fora do formato 'A --> B[: rótulo]': " + line.trim());
            }
        }

        Set<String> diagramEdges = edgesOf(diagram);
        for (String edge : diagramEdges) {
            for (String state : edge.split(" --> ")) {
                if (!knownStates.contains(state)) {
                    messages.add("estado inexistente em OrderStatus: " + state + " (aresta " + edge + ")");
                }
            }
        }

        Set<String> expected = tableEdges();
        Set<String> extra = new TreeSet<>(diagramEdges);
        extra.removeAll(expected);
        Set<String> missing = new TreeSet<>(expected);
        missing.removeAll(diagramEdges);
        if (!extra.isEmpty()) {
            messages.add("arestas a mais no diagrama (a tabela não as permite): " + extra);
        }
        if (!missing.isEmpty()) {
            messages.add("arestas a menos no diagrama (a tabela as permite): " + missing);
        }
        return messages;
    }

    /** As arestas de {@link OrderStatus#transitions()} no formato {@code A --> B}. */
    static Set<String> tableEdges() {
        Set<String> edges = new TreeSet<>();
        for (Map.Entry<OrderStatus, Set<OrderStatus>> entry : OrderStatus.transitions().entrySet()) {
            for (OrderStatus target : entry.getValue()) {
                edges.add(entry.getKey() + " --> " + target);
            }
        }
        return edges;
    }

    private static String diagramOf(Set<String> edges) {
        StringBuilder diagram = new StringBuilder("stateDiagram-v2\n    [*] --> CREATED\n");
        edges.forEach(edge -> diagram.append("    ").append(edge).append(": gatilho\n"));
        diagram.append("    DELIVERED --> [*]\n");
        return diagram.toString();
    }
}
