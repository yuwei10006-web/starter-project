package com.trading.platform.controller;

import com.trading.platform.dto.ProductRequest;
import com.trading.platform.dto.ProductSearchResponse;
import com.trading.platform.entity.Product;
import com.trading.platform.service.ProductService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @PostMapping
    public Product create(@RequestBody ProductRequest request) {
        return productService.createProduct(request);
    }

    @PutMapping("/{id}")
    public Product update(@PathVariable Long id, @RequestBody ProductRequest request) {
        return productService.updateProduct(id, request);
    }

    @DeleteMapping("/{id}")
    public String delete(@PathVariable Long id) {
        productService.deleteProduct(id);
        return "deleted";
    }

    @GetMapping
    public List<Product> list() {
        return productService.listProducts();
    }

    @GetMapping("/{id}")
    public Product get(@PathVariable Long id) {
        return productService.getProduct(id);
    }

    /**
     * 進階商品查詢。範例：
     * GET
     * /api/products/search?keyword=鍵盤&minPrice=1000&maxPrice=5000&sort=price,asc&page=0&size=20
     */
    @GetMapping("/search")
    public ProductSearchResponse search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Double minPrice,
            @RequestParam(required = false) Double maxPrice,
            @RequestParam(defaultValue = "createdAt,desc") String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return productService.searchProducts(keyword, minPrice, maxPrice, sort, page, size);
    }
}