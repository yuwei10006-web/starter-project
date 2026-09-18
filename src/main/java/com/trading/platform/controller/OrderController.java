package com.trading.platform.controller;

import com.trading.platform.dto.OrderRequest;
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
    public Order placeOrder(@RequestBody OrderRequest request, Authentication authentication) {
        String username = authentication.getName();
        return orderService.placeOrder(username, request);
    }

    @GetMapping
    public List<Order> myOrders(Authentication authentication) {
        return orderService.getUserOrders(authentication.getName());
    }
}
