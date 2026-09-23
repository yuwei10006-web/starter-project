package com.trading.platform.service;

import com.trading.platform.dto.ProductRequest;
import com.trading.platform.dto.ProductSearchResponse;
import com.trading.platform.entity.Product;
import com.trading.platform.repository.ProductRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        p.setName(request.name());
        p.setPrice(request.price());
        p.setStock(request.stock());
        return productRepository.save(p);
    }

    public Product updateProduct(Long id, ProductRequest request) {
        Product p = productRepository.findById(id).orElseThrow(() -> notFound("商品不存在"));
        p.setName(request.name());
        p.setPrice(request.price());
        p.setStock(request.stock());
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

    // ===== 商品進階查詢：模糊搜尋 + 價格區間 + 排序 + 分頁 =====

    static final int MAX_PAGE_SIZE = 100;
    /** offset 分頁越深越慢；超過此上限請縮小條件（或改用 keyset 分頁）。 */
    static final long MAX_OFFSET = 10_000;
    /** pg_trgm 需要至少 3 個字元才能篩選；更短的關鍵字索引無效，實測 100 萬筆會退化成約 500 ms 的全索引掃描。 */
    static final int MIN_KEYWORD_LENGTH = 3;
    static final int MAX_KEYWORD_LENGTH = 100;

    /** 排序欄位白名單：API 參數 -> 資料表欄位。使用者輸入永遠不會直接拼進 SQL。 */
    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "id", "id",
            "name", "name",
            "price", "price",
            "createdAt", "created_at");

    @Transactional(readOnly = true)
    public ProductSearchResponse searchProducts(String keyword, Double minPrice, Double maxPrice,
            String sort, int page, int size) {
        // --- 參數驗證（不合法一律 400）---
        if (page < 0) {
            throw badRequest("page 不可小於 0");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw badRequest("size 必須介於 1 到 " + MAX_PAGE_SIZE);
        }
        long offset = (long) page * size;
        if (offset > MAX_OFFSET) {
            throw badRequest("分頁過深（offset 上限 " + MAX_OFFSET + "），請縮小搜尋條件");
        }
        if (minPrice != null && (!Double.isFinite(minPrice) || minPrice < 0)) {
            throw badRequest("minPrice 必須是不小於 0 的數字");
        }
        if (maxPrice != null && (!Double.isFinite(maxPrice) || maxPrice < 0)) {
            throw badRequest("maxPrice 必須是不小於 0 的數字");
        }
        if (minPrice != null && maxPrice != null && minPrice > maxPrice) {
            throw badRequest("minPrice 不可大於 maxPrice");
        }
        String trimmedKeyword = keyword == null ? "" : keyword.trim();
        if (!trimmedKeyword.isEmpty()
                && (trimmedKeyword.length() < MIN_KEYWORD_LENGTH || trimmedKeyword.length() > MAX_KEYWORD_LENGTH)) {
            throw badRequest("keyword 長度必須介於 " + MIN_KEYWORD_LENGTH + " 到 " + MAX_KEYWORD_LENGTH);
        }

        String[] sortParts = parseSort(sort);
        String orderColumn = sortParts[0];
        String orderDirection = sortParts[1];

        // --- 動態組 SQL：只有「有帶的條件」才出現在 WHERE，避免 (:p IS NULL OR ...) 讓索引失效 ---
        StringBuilder sql = new StringBuilder("SELECT * FROM products WHERE 1=1");
        Map<String, Object> params = new LinkedHashMap<>();

        if (!trimmedKeyword.isEmpty()) {
            // ESCAPE '!'：把使用者輸入的 % _ 當成一般字元，而不是萬用字元
            sql.append(" AND name ILIKE :kw ESCAPE '!'");
            params.put("kw", "%" + escapeLike(trimmedKeyword) + "%");
        }
        if (minPrice != null) {
            sql.append(" AND price >= :minPrice");
            params.put("minPrice", minPrice);
        }
        if (maxPrice != null) {
            sql.append(" AND price <= :maxPrice");
            params.put("maxPrice", maxPrice);
        }

        // 一律補上 id 當 tie-breaker，否則同價格的商品在翻頁時順序不固定，會重複或漏資料
        sql.append(" ORDER BY ").append(orderColumn).append(' ').append(orderDirection);
        if (!"id".equals(orderColumn)) {
            sql.append(", id ").append(orderDirection);
        }
        sql.append(" LIMIT :limit OFFSET :offset");

        Query query = entityManager.createNativeQuery(sql.toString(), Product.class);
        params.forEach(query::setParameter);
        query.setParameter("limit", size + 1); // 多撈 1 筆，用來判斷有沒有下一頁，省掉 count(*)
        query.setParameter("offset", offset);

        @SuppressWarnings("unchecked")
        List<Product> rows = query.getResultList();
        boolean hasNext = rows.size() > size;
        return new ProductSearchResponse(hasNext ? rows.subList(0, size) : rows, page, size, hasNext);
    }

    /** 解析 "price,desc" 這種格式，回傳 {資料表欄位, ASC|DESC}。 */
    static String[] parseSort(String sort) {
        String[] parts = (sort == null || sort.isBlank() ? "createdAt,desc" : sort).split(",");
        if (parts.length > 2) {
            throw badRequest("sort 格式應為 欄位[,asc|desc]");
        }
        String column = SORT_COLUMNS.get(parts[0].trim());
        if (column == null) {
            throw badRequest("sort 只支援：" + String.join("、", SORT_COLUMNS.keySet()));
        }
        String direction = parts.length == 2 ? parts[1].trim().toLowerCase() : "asc";
        if (!direction.equals("asc") && !direction.equals("desc")) {
            throw badRequest("排序方向只能是 asc 或 desc");
        }
        return new String[] { column, direction.toUpperCase() };
    }

    /** 跳脫 LIKE 萬用字元；搭配 SQL 的 ESCAPE '!'。 */
    static String escapeLike(String input) {
        return input.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }
}