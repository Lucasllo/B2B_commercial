package com.orderflow.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.order.support.OrderTestInfrastructure;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prova isolada da migração de dados D-51 (JUnit puro, sem contexto Spring — a migração precisa
 * ser correta antes de qualquer relay existir, então este teste roda a V2 sozinha contra um schema
 * exclusivo, sem subir a aplicação inteira). Usa {@link OrderTestInfrastructure#POSTGRES}, o mesmo
 * container singleton Postgres compartilhado pelas demais suítes do módulo.
 */
class OrderSagaMigrationIT {

    private static final String SCHEMA = "order_migration_it";

    @Test
    void applyingV2OverAV1BaseMovesEveryApprovedOrderToReservingAndInsertsOneReserveStockEventPerOrder()
            throws Exception {
        dropSchemaIfExists();

        FluentConfiguration config = Flyway.configure()
                .dataSource(OrderTestInfrastructure.POSTGRES.getJdbcUrl(),
                        OrderTestInfrastructure.POSTGRES.getUsername(),
                        OrderTestInfrastructure.POSTGRES.getPassword())
                .schemas(SCHEMA)
                .defaultSchema(SCHEMA)
                .createSchemas(true)
                .locations("classpath:db/migration");

        // Só a V1 — nesta base, RESERVING/outbox_event ainda não existem (D-51 prova a migração de
        // pedidos legados criados ANTES da Fase 5 existir).
        config.target("1");
        config.load().migrate();

        UUID approvedOrderId = UUID.randomUUID();
        UUID productA = UUID.randomUUID();
        UUID productB = UUID.randomUUID();
        UUID pendingOrderId = UUID.randomUUID();
        UUID rejectedOrderId = UUID.randomUUID();

        try (Connection connection = openConnection()) {
            insertOrder(connection, approvedOrderId, "APPROVED");
            insertOrderItem(connection, approvedOrderId, 1, productA, "SKU-A", "Item A", "10.00", 2, "20.00");
            insertOrderItem(connection, approvedOrderId, 2, productB, "SKU-B", "Item B", "5.00", 3, "15.00");
            insertOrder(connection, pendingOrderId, "PENDING_APPROVAL");
            insertOrder(connection, rejectedOrderId, "REJECTED");
        }

        // Agora aplica até a última versão (V2) — a migração de dados D-51 roda aqui.
        config.target(MigrationVersion.LATEST);
        config.load().migrate();

        try (Connection connection = openConnection()) {
            // O APPROVED legado está RESERVING com reservation_started_at preenchido.
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT status, reservation_started_at FROM " + SCHEMA + ".orders WHERE id = ?")) {
                ps.setObject(1, approvedOrderId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("status")).isEqualTo("RESERVING");
                    assertThat(rs.getObject("reservation_started_at")).isNotNull();
                }
            }

            // Exatamente uma linha em outbox_event para o pedido migrado.
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT id, aggregate_id, event_type, payload, published_at, attempts "
                            + "FROM " + SCHEMA + ".outbox_event WHERE aggregate_id = ?")) {
                ps.setString(1, approvedOrderId.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    UUID outboxId = (UUID) rs.getObject("id");
                    assertThat(rs.getString("event_type")).isEqualTo("ReserveStock");
                    assertThat(rs.getObject("published_at")).isNull();
                    assertThat(rs.getInt("attempts")).isZero();

                    ObjectMapper objectMapper = new ObjectMapper();
                    JsonNode payload = objectMapper.readTree(rs.getString("payload"));
                    assertThat(UUID.fromString(payload.get("eventId").asText())).isEqualTo(outboxId);
                    assertThat(payload.get("reservationId").asText()).isEqualTo(approvedOrderId.toString());
                    assertThat(Instant.parse(payload.get("occurredAt").asText())).isNotNull();
                    JsonNode items = payload.get("items");
                    assertThat(items).hasSize(2);
                    assertThat(items.get(0).get("productId").asText()).isEqualTo(productA.toString());
                    assertThat(items.get(0).get("quantity").asInt()).isEqualTo(2);
                    assertThat(items.get(1).get("productId").asText()).isEqualTo(productB.toString());
                    assertThat(items.get(1).get("quantity").asInt()).isEqualTo(3);

                    assertThat(rs.next()).isFalse();
                }
            }

            // PENDING_APPROVAL e REJECTED ficam intocados e sem linha no outbox.
            assertOrderUntouched(connection, pendingOrderId, "PENDING_APPROVAL");
            assertOrderUntouched(connection, rejectedOrderId, "REJECTED");
        }
    }

    private void assertOrderUntouched(Connection connection, UUID orderId, String expectedStatus) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT status FROM " + SCHEMA + ".orders WHERE id = ?")) {
            ps.setObject(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("status")).isEqualTo(expectedStatus);
            }
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM " + SCHEMA + ".outbox_event WHERE aggregate_id = ?")) {
            ps.setString(1, orderId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isZero();
            }
        }
    }

    private void insertOrder(Connection connection, UUID id, String status) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO " + SCHEMA + ".orders (id, company_id, status, total, created_by, created_at) "
                        + "VALUES (?, ?, ?, ?, 'legacy-tester', now())")) {
            ps.setObject(1, id);
            ps.setObject(2, UUID.randomUUID());
            ps.setString(3, status);
            ps.setBigDecimal(4, new java.math.BigDecimal("35.00"));
            ps.executeUpdate();
        }
    }

    private void insertOrderItem(Connection connection, UUID orderId, int lineNumber, UUID productId, String sku,
                                  String name, String unitPrice, int quantity, String subtotal) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO " + SCHEMA + ".order_items "
                        + "(id, order_id, line_number, product_id, sku, name, unit_price, quantity, subtotal) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, orderId);
            ps.setInt(3, lineNumber);
            ps.setObject(4, productId);
            ps.setString(5, sku);
            ps.setString(6, name);
            ps.setBigDecimal(7, new java.math.BigDecimal(unitPrice));
            ps.setInt(8, quantity);
            ps.setBigDecimal(9, new java.math.BigDecimal(subtotal));
            ps.executeUpdate();
        }
    }

    private void dropSchemaIfExists() throws Exception {
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }

    private Connection openConnection() throws Exception {
        return DriverManager.getConnection(
                OrderTestInfrastructure.POSTGRES.getJdbcUrl(),
                OrderTestInfrastructure.POSTGRES.getUsername(),
                OrderTestInfrastructure.POSTGRES.getPassword());
    }
}
