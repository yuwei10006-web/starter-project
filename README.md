# Trading Platform（接手專案）

商品交易平台後端服務。這是一份從前一位開發者手上接手的專案，已完成 Code Review、修正主要問題，並擴充商品進階查詢功能（詳見下方）。

## 技術棧
- Java 21 / Spring Boot 4.0
- Spring Security + JWT
- Spring Data JPA / PostgreSQL
- springdoc (Swagger UI)

## 啟動方式

1. 啟動資料庫：
   ```
   docker compose up -d
   ```
2. 啟動應用程式：
   ```
   ./mvnw spring-boot:run
   mvnw.cmd spring-boot:run
   ```
   （或用 IDE 執行 `TradeApplication`）
3. Swagger UI： http://localhost:8080/swagger-ui.html
4. 執行測試：
   ```
   ./mvnw test
   mvnw.cmd test
   ```

## 預設帳號
| 帳號 | 密碼 | 角色 |
|------|------|------|
| admin | admin123 | ADMIN |
| alice | alice123 | USER |

## 主要 API
- `POST /api/auth/login` 登入，回傳 JWT
- `GET/POST/PUT /api/products`、`GET /api/products/delete/{id}` 商品
- `GET /api/products/search` 商品進階查詢（關鍵字模糊搜尋、價格區間、排序、分頁，詳見下方）
- `POST /api/orders`、`GET /api/orders` 下單與查詢

> 呼叫受保護 API 時，於 Header 帶入 `Authorization: Bearer <token>`。

---

## 第三部分：商品進階查詢（選項 A）

**設計理由**：專案原本就有 `/api/products/search`（並已在 Code Review 中修掉 JPQL Injection），A 是同一條線的延伸，範圍明確，且效能可用數據驗證。B（Audit Log）需要決定交易一致性、變更差異儲存、操作者來源等較多設計面向，在有限時間內較難每一項都做到位。

### API
`GET /api/products/search`（需帶 `Authorization: Bearer <token>`）

| 參數 | 說明 | 預設 |
|---|---|---|
| keyword | 名稱模糊搜尋（不分大小寫），長度 3～100 | 無 |
| minPrice / maxPrice | 價格區間，不可為負、minPrice ≤ maxPrice | 無 |
| sort | `id / name / price / createdAt`，格式 `欄位[,asc\|desc]` | `createdAt,desc` |
| page / size | 頁碼從 0 開始；size 上限 100 | 0 / 20 |

回傳 `{ content, page, size, hasNext }`。參數不合法一律回 400。

### 設計重點
- **動態 SQL 只放有帶的條件**：避免 `(:p IS NULL OR ...)` 寫法讓索引失效
- **排序欄位白名單**：使用者輸入不會拼進 SQL，避免 SQL Injection；排序一律補 `id` 當 tie-breaker，避免翻頁時重複或漏資料
- **回傳 `hasNext` 而非總筆數**：多撈 1 筆判斷，省掉百萬筆下昂貴的 `count(*)`
- **LIKE 跳脫**：`%`、`_` 視為一般字元（`ESCAPE '!'`）
- **offset 上限 10,000**：offset 分頁越深越慢，超過請縮小條件
- **索引**（`schema.sql`）：`name` 的 GIN trigram、`(price, id)`、`(created_at DESC, id DESC)`

### 效能驗證（PostgreSQL，100 萬筆商品，`LIMIT 21`）

| 情境 | 加索引前 | 加索引後 |
|---|---|---|
| 稀有關鍵字（1 筆命中） | 145 ms | 0.6 ms |
| 關鍵字無命中 + 依價格排序 | ~ | 0.2 ms |
| 關鍵字 + 價格區間 | 167 ms | 43 ms |
| 只有價格區間 + 依價格排序 | 34 ms | 0.8 ms |
| 無條件 + 預設排序 | 60 ms | 1.3 ms |

原始 `EXPLAIN ANALYZE` 輸出見 `docs/perf/`。

### 已知限制
- **關鍵字需 ≥ 3 字元**：pg_trgm 需 3 個字元才能有效篩選，實測 2 字元時索引無效，最壞情況（無命中 + 依價格排序）約 493 ms，故在 API 層拒絕。若需支援 2 字元中文搜尋，可評估 `pg_bigm` 或 Elasticsearch。
- **深分頁**：目前以 offset 上限保護資料庫，更深的分頁可改用 keyset（seek）分頁。
- **測試範圍**：`mvnw test` 涵蓋參數驗證與純邏輯（排序白名單、LIKE 跳脫）；SQL 實際執行與索引效果以真實 PostgreSQL + EXPLAIN ANALYZE 手動驗證，未來可用 Testcontainers 自動化。

### 如何重現

```
# 造 100 萬筆測試資料
docker compose cp scripts/seed_products_1m.sql db:/tmp/seed.sql
docker compose exec db psql -U trading -d trading -f /tmp/seed.sql

# 量測效能
docker compose cp scripts/explain_queries.sql db:/tmp/explain.sql
docker compose exec db psql -U trading -d trading -f /tmp/explain.sql
```

詳細設計、驗證過程與截圖見 [`docs/商品進階查詢功能說明.docx`]。
