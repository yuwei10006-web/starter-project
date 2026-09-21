package com.trading.platform.service;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 只測「不碰資料庫」的邏輯：參數驗證、排序白名單、LIKE 跳脫。
 * 驗證失敗都發生在組 SQL 之前，所以 repository 傳 null 即可。
 * SQL 實際執行與索引效果，已用 PostgreSQL 100 萬筆資料 + EXPLAIN ANALYZE 驗證。
 */
class ProductServiceSearchTest {

    private final ProductService service = new ProductService(null);

    private static void assertBadRequest(ThrowingCallable callable) {
        assertThatThrownBy(callable)
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(400));
    }

    // ---------- 排序 ----------

    @Test
    void parseSort_defaultsToCreatedAtDesc() {
        assertThat(ProductService.parseSort(null)).containsExactly("created_at", "DESC");
        assertThat(ProductService.parseSort("  ")).containsExactly("created_at", "DESC");
    }

    @Test
    void parseSort_acceptsWhitelistedColumnsAndDirections() {
        assertThat(ProductService.parseSort("price,desc")).containsExactly("price", "DESC");
        assertThat(ProductService.parseSort("price")).containsExactly("price", "ASC");
        assertThat(ProductService.parseSort("createdAt,ASC")).containsExactly("created_at", "ASC");
        assertThat(ProductService.parseSort("name, asc")).containsExactly("name", "ASC");
    }

    @Test
    void parseSort_rejectsUnknownColumnAndInjectionAttempt() {
        assertBadRequest(() -> ProductService.parseSort("abc"));
        assertBadRequest(() -> ProductService.parseSort("price; DROP TABLE products;--"));
        assertBadRequest(() -> ProductService.parseSort("stock,asc"));
    }

    @Test
    void parseSort_rejectsBadDirectionOrFormat() {
        assertBadRequest(() -> ProductService.parseSort("price,up"));
        assertBadRequest(() -> ProductService.parseSort("price,asc,name"));
    }

    // ---------- LIKE 跳脫 ----------

    @Test
    void escapeLike_escapesWildcardsAndEscapeChar() {
        assertThat(ProductService.escapeLike("100%")).isEqualTo("100!%");
        assertThat(ProductService.escapeLike("a_b")).isEqualTo("a!_b");
        assertThat(ProductService.escapeLike("hi!")).isEqualTo("hi!!");
        assertThat(ProductService.escapeLike("機械鍵盤")).isEqualTo("機械鍵盤");
    }

    // ---------- 參數驗證 ----------

    @Test
    void search_rejectsBadPaging() {
        assertBadRequest(() -> service.searchProducts(null, null, null, null, -1, 20));
        assertBadRequest(() -> service.searchProducts(null, null, null, null, 0, 0));
        assertBadRequest(() -> service.searchProducts(null, null, null, null, 0, ProductService.MAX_PAGE_SIZE + 1));
    }

    @Test
    void search_rejectsTooDeepOffset() {
        // 101 * 100 = 10100 > MAX_OFFSET(10000)
        assertBadRequest(() -> service.searchProducts(null, null, null, null, 101, 100));
    }

    @Test
    void search_rejectsBadPriceRange() {
        assertBadRequest(() -> service.searchProducts(null, -1.0, null, null, 0, 20));
        assertBadRequest(() -> service.searchProducts(null, null, -5.0, null, 0, 20));
        assertBadRequest(() -> service.searchProducts(null, 9.0, 1.0, null, 0, 20));
        assertBadRequest(() -> service.searchProducts(null, Double.NaN, null, null, 0, 20));
        assertBadRequest(() -> service.searchProducts(null, null, Double.POSITIVE_INFINITY, null, 0, 20));
    }

    @Test
    void search_rejectsKeywordOutOfLengthRange() {
        // 2 個字：pg_trgm 索引無效，實測最壞情況約 500 ms，故拒絕
        assertBadRequest(() -> service.searchProducts("鍵盤", null, null, null, 0, 20));
        assertBadRequest(() -> service.searchProducts("a".repeat(ProductService.MAX_KEYWORD_LENGTH + 1),
                null, null, null, 0, 20));
    }
}