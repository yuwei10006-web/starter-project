package com.trading.platform.controller;

import com.trading.platform.dto.OrderRequest;
import com.trading.platform.dto.OrderResponse;
import com.trading.platform.entity.Order;
import com.trading.platform.service.OrderService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public OrderResponse placeOrder(@RequestBody OrderRequest request, Authentication authentication) {
        String username = authentication.getName();
        Order order = orderService.placeOrder(username, request);
        return OrderResponse.from(order);
    }

    @GetMapping
    public List<OrderResponse> myOrders(Authentication authentication) {
        return orderService.getUserOrders(authentication.getName())
                .stream()
                .map(OrderResponse::from)
                .toList();
    }
}