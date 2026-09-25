package com.orderflow.order.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Snapshot do item no pedido ({@code order_items}, D-43) — {@code productId}/{@code sku}/{@code
 * name}/{@code unitPrice} congelados no momento da criação, lidos do catalog-service, nunca
 * recalculados depois.
 */
@Entity
@Table(name = "order_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(name = "line_number", nullable = false)
    private int lineNumber;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(nullable = false, length = 64)
    private String sku;

    @Column(nullable = false)
    private String name;

    @Column(name = "unit_price", nullable = false, precision = 19, scale = 2)
    private BigDecimal unitPrice;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal subtotal;

    OrderItem(Order order, PricedItem pricedItem) {
        this.order = order;
        this.lineNumber = pricedItem.lineNumber();
        this.productId = pricedItem.productId();
        this.sku = pricedItem.sku();
        this.name = pricedItem.name();
        this.unitPrice = pricedItem.unitPrice();
        this.quantity = pricedItem.quantity();
        this.subtotal = pricedItem.subtotal();
    }
}
