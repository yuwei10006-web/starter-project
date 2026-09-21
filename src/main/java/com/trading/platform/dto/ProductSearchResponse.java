package com.trading.platform.dto;

import com.trading.platform.entity.Product;

import java.util.List;

/**
 * 商品搜尋回應。
 * 為了避免百萬筆資料下每次都執行 count(*)，這裡只回傳 hasNext（多撈 1 筆判斷），不回傳總筆數。
 */
public record ProductSearchResponse(List<Product> content, int page, int size, boolean hasNext) {
}