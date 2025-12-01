package com.hts.order.domain.model;

import com.hts.generated.grpc.OrderStatus;

public record ServiceResult(OrderStatus status, long orderId, String message) {
    public static ServiceResult success(long orderId) {
        return new ServiceResult(OrderStatus.ACCEPTED, orderId, "");
    }

    public static ServiceResult failure(OrderStatus status, String message) {
        return new ServiceResult(status, 0, message);
    }

    public static ServiceResult of(OrderStatus status, long orderId, String message) {
        return new ServiceResult(status, orderId, message);
    }

    public boolean isSuccess() {
        return status == OrderStatus.ACCEPTED;
    }
}
