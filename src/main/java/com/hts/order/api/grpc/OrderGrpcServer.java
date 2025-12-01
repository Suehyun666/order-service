package com.hts.order.api.grpc;

import com.hts.generated.grpc.*;
import com.hts.order.domain.model.ServiceResult;
import com.hts.order.domain.service.OrderCommandService;
import io.quarkus.grpc.GrpcService;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@GrpcService
public class OrderGrpcServer implements OrderService {

    private static final Logger log = Logger.getLogger(OrderGrpcServer.class);

    @Inject OrderCommandService orderCommandService;

    @Override
    public Uni<OrderResponse> placeOrder(PlaceOrderRequest request) {
        log.infof("PlaceOrder: symbol=%s, side=%s, quantity=%d, price=%d",
                  request.getSymbol(), request.getSide(), request.getQuantity(), request.getPrice());

        return orderCommandService.handlePlace(request)
                .map(this::toResponse)
                .onFailure().recoverWithItem(t -> {
                    log.errorf(t, "PlaceOrder failed: symbol=%s", request.getSymbol());
                    return buildErrorResponse(0, t.getMessage());
                });
    }

    @Override
    public Uni<OrderResponse> cancelOrder(CancelOrderRequest request) {
        log.infof("CancelOrder: orderId=%d", request.getOrderId());

        return orderCommandService.handleCancel(request)
                .map(this::toResponse)
                .onFailure().recoverWithItem(t -> {
                    log.errorf(t, "CancelOrder failed: orderId=%d", request.getOrderId());
                    return buildErrorResponse(request.getOrderId(), t.getMessage());
                });
    }

    private OrderResponse toResponse(ServiceResult result) {
        return OrderResponse.newBuilder()
                .setOrderId(result.orderId())
                .setStatus(result.status())
                .setMessage(result.message())
                .setTimestamp(System.currentTimeMillis())
                .build();
    }

    private OrderResponse buildErrorResponse(long orderId, String message) {
        return OrderResponse.newBuilder()
                .setOrderId(orderId)
                .setStatus(OrderStatus.REJECTED)
                .setMessage(message)
                .setTimestamp(System.currentTimeMillis())
                .build();
    }
}
