package com.trading.platform.dto;

import com.trading.platform.entity.Order;

import java.time.LocalDateTime;

public record OrderResponse(
        Long id,
        String username,
        String productName,
        Integer quantity,
        double totalPrice,
        LocalDateTime createdAt) {
    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getUser().getUsername(),
                order.getProduct().getName(),
                order.getQuantity(),
                order.getTotalPrice(),
                order.getCreatedAt());
    }
}
