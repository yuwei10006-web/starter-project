# Code Review 報告 — Trading Platform（接手專案）

## 🔴 嚴重（阻礙上線／資安漏洞／資料錯誤／編譯或啟動失敗）

### 1. 專案編譯不過：Record 用了不存在的 getter
- **位置**：`service/ProductService.java`（`createProduct`、`updateProduct`）
- **問題**：`ProductRequest` 是 `record ProductRequest(String name, double price, Integer stock)`，record 產生的 accessor 是 `name()` / `price()` / `stock()`，不是 `getName()` / `getPrice()` / `getStock()`。程式碼呼叫的是後者，方法不存在。
- **後果**：整包 build 不過，README 宣稱的「跑得起來」不成立。

### 2. 啟動即崩潰：Repository 查詢不存在的欄位
- **位置**：`repository/ProductRepository.java`，`findByCategory(String category)`
- **問題**：`Product` entity 沒有 `category` 欄位，Spring Data JPA 無法從方法名推導查詢。
- **後果**：ApplicationContext 啟動時丟 `PropertyReferenceException`，應用程式根本起不來。

### 3. 權限控管形同虛設：一般用戶可任意增刪改商品
- **位置**：`config/SecurityConfig.java`（`.anyRequest().authenticated()`）＋ `security/JwtAuthenticationFilter.java`（`Collections.emptyList()`）
- **問題**：`SecurityConfig` 上的註解宣稱商品異動限 ADMIN，實際規則只驗證「已登入」，沒有角色限制。更根本的是 `JwtAuthenticationFilter` 建立 `Authentication` 時給的權限清單是空的，JWT payload 裡也沒帶角色——就算補上 `hasRole("ADMIN")` 也不會有人擁有這個角色。
- **後果**：USER（alice）可以呼叫 `POST/PUT/DELETE /api/products` 任意上下架商品，權限機制完全沒作用。

### 4. 密碼明文儲存＋明文比對
- **位置**：`service/AuthService.java`，`login()`：`user.getPassword().equals(request.getPassword())`；`resources/data.sql` 明文塞入密碼
- **問題**：程式上方註解寫「密碼以 BCrypt 雜湊比對，資料庫不保存明文」，但實作是明文相等比對，資料庫存的也是明文。
- **後果**：資料庫外洩＝所有帳密直接曝光。

### 5. JWT 永不過期，且忽略設定檔的 secret
- **位置**：`security/JwtUtil.java`
- **問題**：`generateToken` 沒呼叫 `.expiration(...)`，token 簽發後永久有效；上方註解寫「1 小時自動過期」。`application.yml` 已定義 `jwt.secret` / `jwt.expiration-ms`，但 `JwtUtil` 完全沒讀取，自己寫死一組 demo secret。
- **後果**：token 外洩後永久可用，且 secret 是固定字串，容易被暴力猜出。

### 6. JPQL Injection
- **位置**：`service/ProductService.java`，`searchByName()`
- **問題**：`"...WHERE p.name LIKE '%" + keyword + "%'"` 字串拼接組查詢，上方註解宣稱「已改用參數化查詢」，實作根本沒做。
- **後果**：`keyword` 帶入惡意字串可操縱查詢邏輯，屬注入漏洞。

### 7. 併發下單會超賣，且交易邊界設計錯誤
- **位置**：`service/OrderService.java`，`placeOrder()` + `deductStock()`
- **問題（兩層）**：
  1. 「檢查庫存」與「扣庫存」是分開步驟，只有 `deductStock` 標了 `synchronized`，檢查段完全沒鎖；`Product` entity 也沒有 `@Version`（樂觀鎖）欄位。上方註解宣稱「已使用樂觀鎖保護」，與實作不符。
  2. `placeOrder` 呼叫 `deductStock(...)` 是**同一個 class 內的 self-invocation**，不會經過 Spring proxy，導致 `deductStock` 上的 `@Transactional` 完全沒生效（`synchronized` 因為是 JVM 層級鎖仍有效，但交易邊界沒有）。整個下單流程（扣庫存＋建立訂單）沒有交易包住，中途失敗不會回滾。
- **後果**：多執行緒同時下單庫存可能被扣成負數（超賣），且部分失敗時資料會不一致。

### 8. `placeOrder` 未檢查商品是否存在
- **位置**：`service/OrderService.java`，`placeOrder()`
- **問題**：`productRepository.findById(...).orElse(null)` 後直接呼叫 `product.getStock()`，未做 null 檢查。
- **後果**：商品 ID 不存在時直接 NPE，回應裸 500。

### 9. 沒有唯一約束導致帳號重複，重啟後登入直接壞掉
- **位置**：`entity/User.java`（username 欄位缺乏 unique 約束，實際建表由 Hibernate ddl-auto 依此定義產生）＋ `resources/data.sql`（`ON CONFLICT DO NOTHING`，缺乏對應 unique 約束時不生效）＋ `service/AuthService.java`（`catch (Exception e) { // ignore }`）
- **問題**：`ON CONFLICT DO NOTHING` 要生效，資料表上必須有對應的 unique/exclusion constraint 讓資料庫判斷什麼叫「衝突」。這裡完全沒有，所以每次 `spring.sql.init.mode: always` 執行都會**再插入一筆**新的 admin/alice。重開機兩次後，`username = admin` 會有兩筆，`findByUsername` 期待單筆卻拿到多筆，Spring Data 丟 `IncorrectResultSizeDataAccessException`，又被 `AuthService.login` 的 `catch (Exception e) { // ignore }` 整個吞掉，只回傳 null。
- **後果**：對使用者來說像是「帳密突然錯誤」，實際上是資料重複＋例外被靜默吞掉，非常難排查。

### 10. 訂單 API 會把使用者密碼明文吐回前端
- **位置**：`controller/OrderController.java`（`POST /api/orders`、`GET /api/orders`）＋ `entity/Order.java`（`@ManyToOne User user`，預設 EAGER）＋ `entity/User.java`（`password` 欄位無 `@JsonIgnore`）
- **問題**：`Order` entity 的 `user` 關聯預設 EAGER 抓取，`Order` / `List<Order>` 又直接被當作 API 回應（沒經過 DTO 轉換），Jackson 序列化時會呼叫 `User.getPassword()` 把密碼印進 JSON。
- **後果**：每次下單或查訂單，回應裡都夾帶當事人明文密碼；就算之後把密碼改成雜湊，這個洞依然存在（雜湊值一樣不該外洩），根因是「直接把 entity 當 API 回應格式」。

### 11. `JwtAuthenticationFilter` 沒有註冊成 Spring Bean，啟動即掛
- **位置**：`security/JwtAuthenticationFilter.java` ＋ `config/SecurityConfig.java`
- **問題**：`JwtAuthenticationFilter` 沒有 `@Component`，全專案也找不到任何 `@Bean` 方法產生它，但 `SecurityConfig` 的建構子卻直接注入 `JwtAuthenticationFilter jwtAuthenticationFilter`。Spring 在建立 `SecurityConfig` 這個 Bean 時找不到對應型別的 Bean 可注入。
- **後果**：就算先修好第 1、2 項的編譯問題，ApplicationContext 還是會在啟動階段丟 `UnsatisfiedDependencyException: No qualifying bean of type 'JwtAuthenticationFilter'`，是第三個獨立的啟動阻礙。

---

## 🟡 中等

| 問題 | 位置 | 說明 |
|---|---|---|
| 總金額計算截斷小數 | `OrderService.placeOrder`：`(int) product.getPrice() * quantity` | 價格先轉 `int` 再相乘，2999.99 會變 2999，長期會對不上帳 |
| 下單驗證是空殼 | `OrderService.validateOrder` | 永遠回傳 `true`，數量 0 或負數都會被接受 |
| 用 GET 做刪除 | `ProductController`：`GET /api/products/delete/{id}` | 違反 HTTP 語意，可能被爬蟲/預抓取誤觸發 |
| `.get()` 未處理找不到 | `ProductService.updateProduct` | 商品不存在時丟未攔截的 `NoSuchElementException` → 裸 500 |
| 找不到資源仍回 200 | `ProductController.get(id)` | 回傳 `null` 但狀態碼仍是 200，不是 404 |
| 沒有全域例外處理 | 全專案缺 `@ControllerAdvice` | 所有例外都變成預設 500，可能洩漏堆疊資訊 |
| 登入回應格式不一致 | `AuthController.login` | 成功回裸字串 token、失敗回「登入失敗」字串，都是 200 |
| `Long` 用 `==` 比較 | `OrderService.getUserOrders` | 超出 Long cache 範圍時比較結果不可靠，且此過濾本身是多餘的 |
| 字串用 `==` 比較角色 | `AuthService.login`：`user.getRole() == "ADMIN"` | 能動全靠字串池巧合，非正確寫法 |
| 死程式碼／共用非執行緒安全物件 | `OrderService`：`orderNoFormat` 算出的 `orderNo` | `Order` entity 沒有對應欄位可存，算完即丟；共用 `SimpleDateFormat` 併發下也不安全 |
| 靜默吞例外 | `AuthService.login`：`catch (Exception e) { // ignore }` | 把任何非預期例外都偽裝成「登入失敗」，掩蓋真正的系統錯誤（見 🔴 9） |
| **springdoc-openapi 版本可能與 Spring Boot 4 不相容（實際不影響但已升級來排除潛在風險）** | `pom.xml`：`springdoc.version = 2.8.8` | springdoc-openapi 2.x 系列是為 Spring Boot 3 設計，Boot 4 支援是從 3.0.0 開始。需要實際跑一次才能確認會不會影響啟動或 Swagger UI（見下方測試方式） |
| Spring Security 預設產生隨機帳密未清除 | 啟動 log：`UserDetailsServiceAutoConfiguration` | 專案已用 JWT 做認證，但未明確停用/覆寫預設的 `InMemoryUserDetailsManager`，導致每次啟動都產生一組隨機密碼並印在 log。目前 `SecurityConfig` 未開啟 `httpBasic`/`formLogin`，此帳密尚無法被利用，但屬於自動配置未收尾，建議提供自訂 `UserDetailsService` 或明確排除該自動配置，避免日後有人誤開啟表單/Basic 認證後形成一個帳密已印在 log 裡的後門 |
| 未登入請求回傳 403 而非 401 | config/SecurityConfig.java | SecurityFilterChain 未設定 formLogin/httpBasic，也未自訂 AuthenticationEntryPoint，導致 Spring Security 找不到「如何要求重新認證」的機制，fallback 使用 Http403ForbiddenEntryPoint，未帶 token 的請求會回 403 而非語意正確的 401。建議在 exceptionHandling() 中自訂 authenticationEntryPoint，對未認證請求明確回傳 401 |
| `user` 完全沒 null check | `OrderService.placeOrder` / `getUserOrders` | `placeOrder` 透過登入後的 username 查詢 User，正常流程下通常能找到，但若資料庫中的 User 已被刪除或資料異常，`findByUsername(...).orElse(null)` 仍可能回傳 `null`；`placeOrder` 可能建立沒有有效 owner 的訂單，`getUserOrders` 則直接呼叫 `user.getId()` 導致 NPE。建議找不到 User 時明確拋出「使用者不存在」。 |

---

## 🟢 輕微

- JPA entity 使用 `@Data`（`Product`、`Order`、`User`）：連帶產生的 `equals/hashCode` 基於全部欄位，對關聯欄位、延遲代理物件容易踩雷。
- `placedOrderCount` 為普通 `int` 累加，多執行緒下計數不準（僅用於 log，影響有限）。
- 大量使用 `System.out.println` 記錄登入/下單狀態，沒有使用正式 logging framework。
- 引入 `spring-boot-starter-validation` 但全專案沒用到任何 `@Valid`/`@NotNull`。
- `OrderService.getUserOrders` 裡 `o.getProduct().getName()`、`o.getUser().getUsername()` 是沒被使用的死讀取（`@ManyToOne` 預設 EAGER，本來就已經載入，這兩行是多餘的）。
- 未明確設定 `spring.jpa.open-in-view`（目前為預設 `true`），Hibernate session 會延伸到 view 渲染層，可能在 controller 之外觸發非預期的延遲查詢，建議顯式設為 `false` 並確認各層是否依賴 lazy loading 行為。

---

## 保留不改的寫法（看起來怪但其實是合理取捨）

1. **`SecurityConfig` 關掉 CSRF**（`.csrf(AbstractHttpConfigurer::disable)`）——純 JWT、無 cookie/session 的 stateless API，認證資訊不會被瀏覽器自動夾帶，關閉 CSRF 是這種架構下的標準合理做法。
2. **`OrderRepository.findByUserId(Long userId)`**，`Order` entity 只有 `user` 沒有 `userId` 欄位——這是 Spring Data JPA 支援的巢狀屬性推導（`findByUserId` → `user.id`），能正常運作，跟「查詢不存在欄位」（如 `findByCategory`）是不同性質，不用修。
3. **依賴用 `spring-boot-starter-webmvc` 而不是 `spring-boot-starter-web`**——這是 Spring Boot 4.0 官方重新命名後的正確 artifact 名稱（Boot 4 模組化後 `spring-boot-starter-web` 改名為 `spring-boot-starter-webmvc`），不是打錯字。
4. **`jwt.secret: ${JWT_SECRET:change-me-in-prod-please-rotate-this-secret}`**——用環境變數搭配安全提示字樣的預設值，是合理且良好的作法，問題只出在 `JwtUtil` 完全沒去讀這個設定（已列在 🔴 5），設定檔本身沒問題。
5. **`schema.sql` 手動建表 + `ddl-auto: update` 同時開著**——正式環境通常會用 Flyway/Liquibase 二選一而不是混用，但對這種本機/demo 用途的接手小專案而言，是常見、可接受的簡化，只是要知道正式環境不會這樣搭。