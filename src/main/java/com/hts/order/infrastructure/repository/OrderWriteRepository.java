package com.hts.order.infrastructure.repository;

import com.hts.order.domain.model.OrderEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jooq.DSLContext;

@ApplicationScoped
public class OrderWriteRepository {

    @Inject DSLContext dslContext;

    public int insertOrder(DSLContext tx, OrderEntity order) {
        return tx.execute("""
            INSERT INTO orders(order_id, account_id, symbol, side, order_type, quantity, price, time_in_force, status, reserve_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
                order.orderId(),
                order.accountId(),
                order.symbol(),
                order.side().name(),
                order.orderType().name(),
                order.quantity(),
                order.price(),
                order.timeInForce().name(),
                order.status().name(),
                order.reserveId());
    }

    public int insertOutbox(DSLContext tx, OrderEntity order, String eventType) {
        return tx.execute("""
           INSERT INTO outbox(event_type, aggregate_id, payload, status)
           VALUES (?, ?, ?, 'PENDING')
        """, eventType, order.orderId(), order.serializeForOutbox());
    }

    public org.jooq.Record markCancelRequested(DSLContext tx, long orderId, long accountId) {
        return tx.fetchOne("""
           UPDATE orders SET status = 'CANCEL_REQUESTED'
           WHERE order_id = ? AND account_id = ? AND status IN ('RECEIVED', 'ACCEPTED')
           RETURNING side, reserve_id
        """, orderId, accountId);
    }
}
