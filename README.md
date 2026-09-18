# Trading Platform（接手專案）

商品交易平台後端服務。**這是一份從前一位開發者手上接手的專案，目前「跑得起來」，但品質與正確性尚待檢視。**

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
   ```
   （或用 IDE 執行 `TradeApplication`）
3. Swagger UI： http://localhost:8080/swagger-ui.html

## 預設帳號
| 帳號 | 密碼 | 角色 |
|------|------|------|
| admin | admin123 | ADMIN |
| alice | alice123 | USER |

## 主要 API
- `POST /api/auth/login` 登入，回傳 JWT
- `GET/POST/PUT /api/products`、`GET /api/products/delete/{id}` 商品
- `GET /api/products/search?keyword=` 商品搜尋
- `POST /api/orders`、`GET /api/orders` 下單與查詢

> 呼叫受保護 API 時，於 Header 帶入 `Authorization: Bearer <token>`。
