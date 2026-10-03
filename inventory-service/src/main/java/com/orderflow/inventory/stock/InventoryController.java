package com.orderflow.inventory.stock;

import com.orderflow.inventory.config.ErrorResponse;
import com.orderflow.inventory.stock.dto.ReserveStockRequest;
import com.orderflow.inventory.stock.dto.SetStockRequest;
import com.orderflow.inventory.stock.dto.StockResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * {@code PUT /inventory/{productId}} — so o papel SELLER_ADMIN define estoque (INV-01). O ajuste
 * grava o evento {@code STOCK_ADJUSTED} no outbox NA MESMA transacao ({@code
 * InventoryService#setStock}, D-60, 05-04) — o unico caminho de publicacao deste servico e o
 * outbox (relay {@code @Scheduled}), nunca uma chamada direta ao SQS depois do commit; um SQS fora
 * do ar atrasa a entrega em vez de perder o evento (fecha a limitacao de dual-write D-29/D-30 da
 * Fase 3). {@code GET /inventory/{productId}} — qualquer autenticado consulta a disponibilidade
 * exata (D-25), sem restricao de papel alem de {@code authenticated()} vinda de {@code
 * SecurityConfig}. {@code POST .../reservations} e {@code DELETE .../reservations/{reservationId}}
 * — continuam restritos a SELLER_ADMIN como ferramenta administrativa (Open Question 1 da
 * pesquisa: menor churn, nenhum criterio pede remocao; {@code REST_RESERVATION_ENDPOINTS=kept}); a
 * saga da Fase 5 fala com o inventory-service so por SQS (order e inventory nunca por REST entre
 * si), entao a "identidade de servico" prevista antes deixou de ser necessaria. O {@code DELETE}
 * mantem a semantica D-14 (liberar reservationId inexistente e no-op, sem lapide) — a lapide so
 * existe no caminho {@code ReleaseStock} da fila, o unico exposto a entrega fora de ordem da fila
 * padrao (D-66).
 */
@RestController
@RequestMapping("/inventory")
@Tag(name = "Estoque", description = "Posição de estoque por produto. A consulta exige usuário autenticado; definir estoque, reservar e liberar exigem SELLER_ADMIN.")
public class InventoryController {

    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @PutMapping("/{productId}")
    @PreAuthorize("hasRole('SELLER_ADMIN')")
    @Operation(summary = "Definir estoque do produto",
            description = "Exige o papel SELLER_ADMIN. Define a quantidade física (quantityOnHand), criando a posição se ela ainda não existir; o valor não pode ficar abaixo da quantidade já reservada. O ajuste publica o evento STOCK_ADJUSTED pelo outbox, na mesma transação.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Estoque definido",
                    content = @Content(schema = @Schema(implementation = StockResponse.class))),
            @ApiResponse(responseCode = "400", description = "Corpo inválido (validation_failed) ou malformado (malformed_request)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Token ausente ou inválido (unauthorized)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Papel diferente de SELLER_ADMIN (forbidden)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Quantidade abaixo da já reservada (stock_below_reserved) ou conflito de dados (data_conflict)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public StockResponse setStock(
            @Parameter(description = "Identificador do produto no catálogo", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
            @PathVariable UUID productId,
            @Valid @RequestBody SetStockRequest request) {
        return inventoryService.setStock(productId, request.quantityOnHand());
    }

    @GetMapping("/{productId}")
    @Operation(summary = "Consultar estoque do produto",
            description = "Exige usuário autenticado. Devolve a disponibilidade exata como número: quantityAvailable é quantityOnHand menos quantityReserved.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Posição de estoque encontrada",
                    content = @Content(schema = @Schema(implementation = StockResponse.class))),
            @ApiResponse(responseCode = "401", description = "Token ausente ou inválido (unauthorized)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Produto sem posição de estoque (inventory_not_found)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public StockResponse getStock(
            @Parameter(description = "Identificador do produto no catálogo", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
            @PathVariable UUID productId) {
        return inventoryService.getStock(productId);
    }

    @PostMapping("/{productId}/reservations")
    @PreAuthorize("hasRole('SELLER_ADMIN')")
    @Operation(summary = "Reservar estoque",
            description = "Exige o papel SELLER_ADMIN (ferramenta administrativa; a saga reserva por SQS). A reserva é idempotente por reservationId, escolhido pelo chamador e único por produto: repetir o mesmo valor não reserva de novo. Falha com 409 se a quantidade pedida passa da disponível.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Reserva registrada; devolve a posição de estoque atualizada",
                    content = @Content(schema = @Schema(implementation = StockResponse.class))),
            @ApiResponse(responseCode = "400", description = "Corpo inválido (validation_failed) ou malformado (malformed_request)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Token ausente ou inválido (unauthorized)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Papel diferente de SELLER_ADMIN (forbidden)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Produto sem posição de estoque (inventory_not_found)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Quantidade acima da disponível (insufficient_stock, com available e requested) ou conflito de dados (data_conflict)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "503", description = "Disputa concorrente esgotou as tentativas; repetir a chamada (reservation_conflict)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public StockResponse reserve(
            @Parameter(description = "Identificador do produto no catálogo", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
            @PathVariable UUID productId,
            @Valid @RequestBody ReserveStockRequest request) {
        return inventoryService.reserve(productId, request.reservationId(), request.quantity());
    }

    @DeleteMapping("/{productId}/reservations/{reservationId}")
    @PreAuthorize("hasRole('SELLER_ADMIN')")
    @Operation(summary = "Liberar reserva de estoque",
            description = "Exige o papel SELLER_ADMIN. Idempotente: liberar um reservationId inexistente, já liberado ou já expedido não altera nada e devolve a posição atual. Só um produto sem posição de estoque responde 404.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Reserva liberada ou nenhuma alteração; devolve a posição de estoque",
                    content = @Content(schema = @Schema(implementation = StockResponse.class))),
            @ApiResponse(responseCode = "401", description = "Token ausente ou inválido (unauthorized)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Papel diferente de SELLER_ADMIN (forbidden)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Produto sem posição de estoque (inventory_not_found)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Conflito de dados (data_conflict)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "503", description = "Disputa concorrente esgotou as tentativas; repetir a chamada (reservation_conflict)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public StockResponse release(
            @Parameter(description = "Identificador do produto no catálogo", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
            @PathVariable UUID productId,
            @Parameter(description = "Identificador da reserva informado na criação", example = "7c9e6679-7425-40de-944b-e07fc1f90ae7")
            @PathVariable String reservationId) {
        return inventoryService.release(productId, reservationId);
    }
}
