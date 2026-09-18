package com.trading.platform.service;

import com.trading.platform.dto.ProductRequest;
import com.trading.platform.entity.Product;
import com.trading.platform.repository.ProductRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ProductService {

    private final ProductRepository productRepository;

    @PersistenceContext
    private EntityManager entityManager;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    public Product createProduct(ProductRequest request) {
        Product p = new Product();
        p.setName(request.getName());
        p.setPrice(request.getPrice());
        p.setStock(request.getStock());
        return productRepository.save(p);
    }

    public Product updateProduct(Long id, ProductRequest request) {
        Product p = productRepository.findById(id).get();
        p.setName(request.getName());
        p.setPrice(request.getPrice());
        p.setStock(request.getStock());
        return productRepository.save(p);
    }

    public void deleteProduct(Long id) {
        productRepository.deleteById(id);
    }

    public List<Product> listProducts() {
        return productRepository.findAll();
    }

    public Product getProduct(Long id) {
        return productRepository.findById(id).orElse(null);
    }

    // 已改用參數化查詢，可安全處理使用者輸入
    @SuppressWarnings("unchecked")
    public List<Product> searchByName(String keyword) {
        String jpql = "SELECT p FROM Product p WHERE p.name LIKE '%" + keyword + "%'";
        return entityManager.createQuery(jpql).getResultList();
    }
}
