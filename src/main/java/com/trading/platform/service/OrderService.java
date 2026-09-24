package com.trading.platform.service;

import com.trading.platform.dto.OrderRequest;
import com.trading.platform.entity.Order;
import com.trading.platform.entity.Product;
import com.trading.platform.entity.User;
import com.trading.platform.repository.OrderRepository;
import com.trading.platform.repository.ProductRepository;
import com.trading.platform.repository.UserRepository;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;

    public OrderService(OrderRepository orderRepository, ProductRepository productRepository,
            UserRepository userRepository) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public Order placeOrder(String username, OrderRequest request) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("使用者不存在"));
        Product product = productRepository.findById(request.getProductId()).orElse(null);

        if (product == null) {
            throw new RuntimeException("商品不存在");
        }

        if (!validateOrder(request)) {
            throw new RuntimeException("訂單資料有誤");
        }

        if (product.getStock() < request.getQuantity()) {
            throw new RuntimeException("庫存不足");
        }

        try {
            product.setStock(product.getStock() - request.getQuantity());
            productRepository.saveAndFlush(product);
        } catch (OptimisticLockingFailureException e) {
            throw new RuntimeException("庫存異動衝突，請重新下單");
        }

        System.out.println("建立訂單成功，使用者: " + username + "，商品: " + product.getName() + "，數量: " + request.getQuantity());

        Order order = new Order();
        order.setUser(user);
        order.setProduct(product);
        order.setQuantity(request.getQuantity());
        order.setTotalPrice(product.getPrice() * request.getQuantity());
        return orderRepository.save(order);
    }

    private boolean validateOrder(OrderRequest request) {
        return request.getProductId() != null
                && request.getQuantity() != null
                && request.getQuantity() > 0;
    }

    public List<Order> getUserOrders(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("使用者不存在"));
        return orderRepository.findByUserId(user.getId());
    }
}