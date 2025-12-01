package com.hts.order.domain.service;

import com.hts.generated.grpc.*;
import com.hts.order.api.grpc.AccountGrpcClient;
import com.hts.order.domain.model.OrderEntity;
import com.hts.order.domain.model.ServiceResult;
import com.hts.order.exceptions.DatabaseException;
import com.hts.order.exceptions.OrderNotFoundException;
import com.hts.order.infrastructure.repository.OrderWriteRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.jooq.DSLContext;

import java.util.UUID;

@ApplicationScoped
public class OrderCommandService {

    private static final Logger log = Logger.getLogger(OrderCommandService.class);

    @Inject AccountGrpcClient accountClient;
    @Inject OrderWriteRepository orderWriteRepository;
    @Inject DSLContext dslContext;

    public Uni<ServiceResult> handlePlace(PlaceOrderRequest request) {
        // Validation
        long accountId = extractAccountId(request.getSessionId());
        if (accountId <= 0) {
            return Uni.createFrom().item(
                ServiceResult.failure(OrderStatus.REJECTED, "Invalid session")
            );
        }

        if (request.getSide() != Side.BUY && request.getSide() != Side.SELL) {
            return Uni.createFrom().item(
                ServiceResult.failure(OrderStatus.REJECTED, "Invalid side")
            );
        }

        // Generate IDs
        long orderId = generateOrderId();
        String reserveId = generateReserveId();

        // Build order entity
        OrderEntity order = OrderEntity.from(
                orderId, accountId, request.getSymbol(), request.getSide(),
                request.getOrderType(), request.getQuantity(), request.getPrice(),
                request.getTimeInForce(), reserveId
        );

        // Execute order placement
        return request.getSide() == Side.BUY
            ? handleBuyOrder(order)
            : handleSellOrder(order);
    }

    private Uni<ServiceResult> handleBuyOrder(OrderEntity order) {
        long amountMicroUnits = order.price() * order.quantity();

        return accountClient.reserveCash(
                order.accountId(),
                amountMicroUnits,
                "USD",
                order.reserveId(),
                String.valueOf(order.orderId())
        )
        .onItem().transformToUni(reply -> {
            if (reply.getCode() == AccoutResult.SUCCESS) {
                return persistOrder(order);
            } else {
                log.warnf("Cash reserve failed: accountId=%d, orderId=%d, code=%s",
                         order.accountId(), order.orderId(), reply.getCode());
                return Uni.createFrom().item(
                    ServiceResult.failure(OrderStatus.REJECTED, "Insufficient funds")
                );
            }
        })
        .onFailure().invoke(t ->
            log.errorf(t, "Reserve cash RPC failed: accountId=%d, orderId=%d",
                      order.accountId(), order.orderId())
        )
        .onFailure().recoverWithItem(t ->
            ServiceResult.failure(OrderStatus.REJECTED, "Account service unavailable")
        );
    }

    private Uni<ServiceResult> handleSellOrder(OrderEntity order) {
        return accountClient.reservePosition(
                order.accountId(),
                order.symbol(),
                order.quantity(),
                order.reserveId(),
                String.valueOf(order.orderId())
        )
        .onItem().transformToUni(reply -> {
            if (reply.getCode() == AccoutResult.SUCCESS) {
                return persistOrder(order);
            } else {
                log.warnf("Position reserve failed: accountId=%d, orderId=%d, symbol=%s, code=%s",
                         order.accountId(), order.orderId(), order.symbol(), reply.getCode());
                return Uni.createFrom().item(
                    ServiceResult.failure(OrderStatus.REJECTED, "Insufficient position")
                );
            }
        })
        .onFailure().invoke(t ->
            log.errorf(t, "Reserve position RPC failed: accountId=%d, orderId=%d",
                      order.accountId(), order.orderId())
        )
        .onFailure().recoverWithItem(t ->
            ServiceResult.failure(OrderStatus.REJECTED, "Account service unavailable")
        );
    }

    public Uni<ServiceResult> handleCancel(CancelOrderRequest request) {
        long accountId = extractAccountId(request.getSessionId());
        if (accountId <= 0) {
            return Uni.createFrom().item(
                ServiceResult.failure(OrderStatus.REJECTED, "Invalid session")
            );
        }

        return Uni.createFrom().item(() -> {
            org.jooq.Record record = dslContext.transactionResult(tx ->
                orderWriteRepository.markCancelRequested(tx.dsl(), request.getOrderId(), accountId)
            );

            if (record == null) {
                throw new OrderNotFoundException("Order not found");
            }

            return new CancelInfo(
                record.get("side", String.class),
                record.get("reserve_id", String.class)
            );
        })
        .onFailure(OrderNotFoundException.class).recoverWithItem(e -> {
            log.warnf("Order not found: orderId=%d, accountId=%d", request.getOrderId(), accountId);
            return null;
        })
        .onFailure().invoke(t ->
            log.errorf(t, "Cancel DB failed: orderId=%d, accountId=%d",
                      request.getOrderId(), accountId)
        )
        .onFailure().recoverWithItem(t -> null)
        .onItem().transformToUni(info -> {
            if (info == null) {
                return Uni.createFrom().item(
                    ServiceResult.failure(OrderStatus.REJECTED, "Order not found or database error")
                );
            }
            return releaseReserve(accountId, request.getOrderId(), info);
        });
    }

    private Uni<ServiceResult> releaseReserve(long accountId, long orderId, CancelInfo info) {
        Uni<CommonReply> releaseCall = "BUY".equals(info.side)
            ? accountClient.releaseCash(accountId, info.reserveId)
            : accountClient.releasePosition(accountId, info.reserveId);

        return releaseCall
            .onItem().transform(reply ->
                ServiceResult.of(OrderStatus.CANCEL_REQUESTED, orderId, "Cancel requested")
            )
            .onFailure().invoke(t ->
                log.errorf(t, "Failed to release reserve: accountId=%d, orderId=%d, reserveId=%s, side=%s",
                          accountId, orderId, info.reserveId, info.side)
            )
            .onFailure().recoverWithItem(t ->
                ServiceResult.of(OrderStatus.CANCEL_REQUESTED, orderId, "Cancel requested (release failed)")
            );
    }

    private record CancelInfo(String side, String reserveId) {}

    private Uni<ServiceResult> persistOrder(OrderEntity order) {
        return Uni.createFrom().item(() -> {
            dslContext.transaction(tx -> {
                int orderRows = orderWriteRepository.insertOrder(tx.dsl(), order);
                int outboxRows = orderWriteRepository.insertOutbox(tx.dsl(), order, "ORDER_PLACED");

                if (orderRows != 1 || outboxRows != 1) {
                    log.errorf("DB insert failed: orderId=%d, orderRows=%d, outboxRows=%d",
                              order.orderId(), orderRows, outboxRows);
                    throw new DatabaseException("Failed to persist order");
                }
            });
            return ServiceResult.success(order.orderId());
        })
        .onFailure().invoke(t ->
            log.errorf(t, "Persist order failed: orderId=%d, accountId=%d",
                      order.orderId(), order.accountId())
        )
        .onFailure().recoverWithItem(t ->
            ServiceResult.failure(OrderStatus.REJECTED, "Database error")
        );
    }

    private long generateOrderId() {
        return System.currentTimeMillis() * 1000 + (long) (Math.random() * 1000);
    }

    private String generateReserveId() {
        return UUID.randomUUID().toString();
    }

    private long extractAccountId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            log.warn("Empty sessionId provided");
            return 0L;
        }

        try {
            return Long.parseLong(sessionId);
        } catch (NumberFormatException e) {
            log.errorf(e, "Failed to parse accountId from sessionId: %s", sessionId);
            return 0L;
        }
    }
}
