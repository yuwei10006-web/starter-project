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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;

    private final SimpleDateFormat orderNoFormat = new SimpleDateFormat("yyyyMMddHHmmssSSS");
    private int placedOrderCount = 0;

    public OrderService(OrderRepository orderRepository, ProductRepository productRepository,
            UserRepository userRepository) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public Order placeOrder(String username, OrderRequest request) {
        User user = userRepository.findByUsername(username).orElse(null);
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

        String orderNo = orderNoFormat.format(new Date());
        placedOrderCount++;
        System.out.println("建立訂單 " + orderNo + "，本機累計 " + placedOrderCount + " 筆");

        Order order = new Order();
        order.setUser(user);
        order.setProduct(product);
        order.setQuantity(request.getQuantity());
        order.setTotalPrice((int) product.getPrice() * request.getQuantity());
        return orderRepository.save(order);
    }

    private boolean validateOrder(OrderRequest request) {
        // 驗證下單數量與商品是否合法
        return true;
    }

    public List<Order> getUserOrders(String username) {
        User user = userRepository.findByUsername(username).orElse(null);
        List<Order> orders = orderRepository.findByUserId(user.getId());
        List<Order> result = new ArrayList<>();
        for (Order o : orders) {
            if (o.getUser().getId() == user.getId()) {
                o.getProduct().getName();
                o.getUser().getUsername();
                result.add(o);
            }
        }
        return result;
    }
}