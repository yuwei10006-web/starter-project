# Code Review 報告

## 1. 專案編譯不過：Record 用了不存在的 getter

**嚴重度：🔴**
**檔案位置：** `service/ProductService.java`（`createProduct`、`updateProduct`）

**問題描述：**
`ProductRequest` 是 `record ProductRequest(String name, double price, Integer stock)`，Java record 自動產生的 accessor 是 `name()`/`price()`/`stock()`，不是傳統 JavaBean 的 `getName()`/`getPrice()`/`getStock()`。`ProductService` 的 `createProduct` 與 `updateProduct` 呼叫的是後者，這些方法在 record 上不存在。

**為什麼是問題：**
專案無法編譯，屬於最基本的 blocking 問題。值得注意的是，在本機環境用 `mvnw spring-boot:run`（未加 clean）測試時，若 `target/classes` 裡已有舊版編譯產物，Maven 會判斷「Nothing to compile」而跳過重新編譯，直接沿用舊 class 檔執行，造成「看起來啟動成功」的假象，掩蓋了這個編譯錯誤。必須用 `mvnw clean spring-boot:run` 才能真實反映目前原始碼是否真的能編譯過。

**修法：**
將 `request.getName()`/`getPrice()`/`getStock()` 改為 `request.name()`/`price()`/`stock()`，對齊 record 的 accessor 命名規則，`createProduct` 與 `updateProduct` 兩處都需修改。

**驗證：**
執行 `mvnw.cmd clean spring-boot:run`（強制清除舊編譯產物，避免快取造成的假陽性），確認 compile 階段實際編譯了原始碼且無 `cannot find symbol` 等編譯錯誤，應用程式成功啟動。

**對應 commit：**
`fix: ProductService 呼叫 record 不存在的 getter 導致編譯失敗（對應 Code Review #1）`

---

## 2. 啟動即崩潰：Repository 查詢不存在的欄位

**嚴重度：🔴**
**檔案位置：** `repository/ProductRepository.java`

**問題描述：**
`ProductRepository` 定義了 `findByCategory(String category)`，但 `Product` entity 完全沒有 `category` 欄位。Spring Data JPA 在啟動時會依方法名稱推導查詢，找不到對應欄位就會失敗。

**為什麼是問題：**
ApplicationContext 初始化 `productRepository` 這個 bean 時，Spring Data JPA 嘗試解析 `findByCategory` 方法簽名，丟出 `PropertyReferenceException`，導致整個應用程式無法啟動。

**修法：**
全專案搜尋後確認 `findByCategory` 沒有被任何 Controller、Service 呼叫，屬於未使用的死程式碼；`schema.sql`/`data.sql` 也完全沒有 category 相關欄位或資料，代表這不是「功能沒做完」而是殘留的多餘方法。因此選擇直接刪除 `findByCategory`，而不是幫 `Product` 補一個沒人用的 category 欄位——後者只是讓編譯通過，並沒有解決「這個方法本不該存在」的根本問題。

**驗證：**
重新執行 `mvnw.cmd spring-boot:run`，確認 log 不再出現 `PropertyReferenceException`/`No property 'category' found`，啟動流程繼續往下推進。

**對應 commit：**
`fix: 移除 ProductRepository.findByCategory 死方法導致啟動失敗（對應 Code Review #2）`

---

## 3. 權限控管形同虛設：一般用戶可任意增刪改商品

**嚴重度：🔴**
**檔案位置：** `config/SecurityConfig.java`、`security/JwtAuthenticationFilter.java`、`security/JwtUtil.java`

**問題描述：**
`SecurityConfig` 上的註解宣稱商品異動限 ADMIN，實際規則只用 `.anyRequest().authenticated()` 驗證「已登入」，沒有角色限制。更根本的是 `JwtAuthenticationFilter` 建立 `Authentication` 時給的權限清單是 `Collections.emptyList()`，JWT payload 裡也沒帶角色資訊——就算補上 `hasRole("ADMIN")` 規則，也不會有任何人擁有這個角色。

**為什麼是問題：**
USER（alice）可以呼叫 `POST/PUT/DELETE /api/products` 任意上下架商品，權限機制完全沒作用，屬於權限繞過（authorization bypass）。

**修法：**
`JwtUtil.generateToken()` 簽章改為 `(username, role)`，將角色寫入 JWT claim；新增 `getRole(token)` 解析角色。`AuthService.login()` 呼叫端同步補上 `user.getRole()` 參數（依賴 #4 已修好的 BCrypt 驗證流程）。`JwtAuthenticationFilter` 從 token 解出角色後，組成 `SimpleGrantedAuthority("ROLE_" + role)` 塞進 `UsernamePasswordAuthenticationToken`，取代原本的空權限清單。`SecurityConfig` 新增 `requestMatchers` 規則，將 `POST/PUT /api/products/**` 與 `/api/products/delete/**` 限定 `hasRole("ADMIN")`，其餘瀏覽性 API 維持 `authenticated()` 即可。

**驗證：**
以 Postman 分別測試 5 種情境：(1) admin 登入取得帶 ADMIN 角色的 JWT；(2) admin token 呼叫 `POST /api/products` 成功建立商品；(3) alice token 呼叫同一支 API，正確回傳 403 Forbidden；(4) alice token 呼叫 `GET /api/products`（純瀏覽）成功回傳 200，確認一般瀏覽未被誤鎖；(5) 未帶 token 呼叫 `GET /api/products`，回傳 403（非預期的 401，已另立一條 🟡 記錄，原因為 `SecurityConfig` 未設定 formLogin/httpBasic，Spring Security fallback 使用 `Http403ForbiddenEntryPoint`，不影響本項角色控管的判定，見下一條）。五項情境行為皆符合預期，確認角色控管實際生效。

**對應 commit：**
`fix: JWT 補上角色資訊並落實 ADMIN 權限控管（對應 Code Review #3）`

---

## 4. 未登入請求回傳 403 而非 401（於驗證 #3 時發現）

**嚴重度：🟡**
**檔案位置：** `config/SecurityConfig.java`

**問題描述：**
`SecurityFilterChain` 原本未設定 formLogin/httpBasic，也未自訂 `AuthenticationEntryPoint`。Spring Security 找不到重新認證機制時 fallback 使用 `Http403ForbiddenEntryPoint`，導致未帶 token 的請求回傳 403 而非語意正確的 401。此問題於驗證 Code Review #3（權限控管）時一併發現。

第一版修法（新增 `authenticationEntryPoint`）套用後，測試又發現新的狀態碼互相覆蓋問題：alice（USER）呼叫 `POST /api/products` 本應回 403（已登入但角色不足），卻始終回傳 401。追查後定位出根因為 Servlet 容器的 error dispatch 機制——`accessDeniedHandler` 呼叫 `response.sendError(403)` 後，容器會將請求內部轉發至 `/error` 重新產生錯誤內容，這次內部轉發會重新跑過整條 Security filter chain；但 `JwtAuthenticationFilter` 繼承 `OncePerRequestFilter`，其設計上預設會跳過 error dispatch，導致轉發時 filter 未執行，`SecurityContext` 內沒有登入資訊，Spring Security 判定為完全未認證，`authenticationEntryPoint` 因而被觸發，第二次的 401 蓋掉了第一次正確產生的 403。

**為什麼是問題：**
呼叫端完全無法依狀態碼分辨「未登入」與「已登入但權限不足」，前端或串接方的錯誤處理邏輯會失準；error dispatch 未被排除在權限規則之外，是容易被忽略的 Spring Security 細節陷阱。

**修法：**
在 `SecurityConfig.filterChain()` 新增 `exceptionHandling` 設定，明確分開 `authenticationEntryPoint`（回 401）與 `accessDeniedHandler`（回 403）；並將 `"/error"` 加入 `authorizeHttpRequests` 的 `permitAll()`，避免內部錯誤轉發被權限規則二次攔截、覆蓋掉第一次已經正確判定的狀態碼。

**驗證：**
以 Postman 分別測試：(1) 未帶 token 呼叫 `GET /api/products`，回傳 401；(2) alice token（已登入但角色不足）呼叫 `POST /api/products`，回傳 403，不再被 401 覆蓋；(3) admin token 呼叫同一支 API 仍正常成功，確認此次修改未影響既有授權邏輯。排查過程中曾在 `JwtAuthenticationFilter` 與 `authenticationEntryPoint`/`accessDeniedHandler` 加入暫時性 debug log，確認 `accessDeniedHandler` 先以 `AuthorizationDeniedException` 正確觸發，緊接著 `authenticationEntryPoint` 又以 `InsufficientAuthenticationException` 被觸發，兩者皆有輸出，藉此定位出 error dispatch 導致的雙重觸發問題；修正後重新測試僅有對應的單一 handler 被觸發，debug log 已全數移除。

**對應 commit：**
`fix: SecurityConfig 明確區分 401/403，並排除 /error 避免狀態碼被覆蓋（對應 Code Review 🟡 401/403 項）`

---

## 5. 密碼明文儲存＋明文比對

**嚴重度：🔴**
**檔案位置：** `service/AuthService.java`、`config/SecurityConfig.java`、`resources/data.sql`

**問題描述：**
`AuthService.login()` 原本用 `user.getPassword().equals(request.getPassword())` 做明文相等比對，上方註解寫「密碼以 BCrypt 雜湊比對，資料庫不保存明文」，但實作跟註解完全不符。`resources/data.sql` 也是直接用明文 `'admin123'`、`'alice123'` 塞入種子資料。

**為什麼是問題：**
資料庫一旦外洩，所有帳號密碼直接以明文曝光，是最基本的資安漏洞；註解與實作不符也會誤導後續接手的工程師。

**修法：**
在 `SecurityConfig` 新增 `PasswordEncoder`（`BCryptPasswordEncoder`）Bean，`AuthService.login()` 改用 `passwordEncoder.matches(...)` 比對雜湊值。由於密碼雜湊需要呼叫 Java 端的加密邏輯，`data.sql` 這種純 SQL 檔案無法在種子資料階段完成加密，因此將 users 的種子資料改用實作 `CommandLineRunner` 的 `UserSeeder`（新檔案）處理：啟動時先以 `findByUsername` 查詢，若使用者不存在才呼叫 `passwordEncoder.encode(...)` 寫入雜湊後的密碼，避免重複插入。`data.sql` 中原本兩行明文 `INSERT INTO users` 已移除，products 種子資料維持不變。

此次修正刻意不處理同一支檔案內的其他既有問題（`user.getRole() == "ADMIN"` 的字串比較 bug、`System.out.println` 記錄、`catch (Exception e) { // ignore }` 吞例外），這些分別對應 🟡「字串用 == 比較角色」、🟢「應改用正式 logging」、以及 🔴 #9（帳號重複）三個不同根因，留待各自的 commit 處理，避免這次 commit 職責過於分散。

**驗證：**
先清空本機 users 表（`docker exec starter-project-db-1 psql -U trading -d trading -c "DELETE FROM users;"`），重新執行 `mvnw.cmd clean spring-boot:run`，確認 `UserSeeder` 依序對 admin、alice 執行 select → insert，應用程式正常啟動無例外。以 `docker exec ... psql -c "SELECT id, username, password, role FROM users;"` 查詢，確認兩筆密碼欄位皆為 `$2a$10$` 開頭的 BCrypt 雜湊值，非明文。以 curl 呼叫 `POST /api/auth/login`，admin/admin123 與 alice/alice123 皆成功取得 JWT token；故意帶入 admin/wrongpassword 正確回傳「登入失敗」，未拋出例外或回應 500。另外重新執行一次 `mvnw.cmd spring-boot:run`（未加 clean），確認 log 僅出現 select 未再出現 insert，users 表筆數維持 2 筆，驗證 `UserSeeder` 具冪等性、不會重複插入。

**對應 commit：**
`fix: AuthService 密碼明文比對改為 BCrypt 雜湊驗證（對應 Code Review #4）`

---

## 6. JWT 永不過期，且忽略設定檔的 secret

**嚴重度：🔴**
**檔案位置：** `security/JwtUtil.java`

**問題描述：**
`generateToken()` 沒有呼叫 `.expiration(...)`，token 簽發後永久有效，上方原本並無明確過期邏輯。`application.yml` 已定義 `jwt.secret`（環境變數搭配安全提示字樣的預設值）與 `jwt.expiration-ms`（3600000，即 1 小時），但 `JwtUtil` 完全沒有讀取這兩個設定，自行寫死一組 demo secret（`SECRET` 常數）。

**為什麼是問題：**
token 外洩後永久可用，攻擊者拿到一次就能無限期冒用身分；secret 是固定字串寫在原始碼裡，一旦程式碼外洩（例如上傳到公開 repo）等同金鑰也外洩，且無法透過環境變數在不同環境（開發/正式）分別設定不同的 secret，也無法輪替金鑰。

**修法：**
`JwtUtil` 改用建構子注入，以 `@Value("${jwt.secret}")` 讀取 `application.yml` 的 `jwt.secret`，拿掉寫死的 `SECRET` 常數；同時以 `@Value("${jwt.expiration-ms}")` 讀取過期時間毫秒數，不再寫死。`generateToken()` 新增 `.issuedAt(now).expiration(expiry)`，token 簽發時即帶入到期時間。`validate()` 不需額外改動——`Jwts.parser().parseSignedClaims(token)` 本身在解析階段就會檢查過期時間，過期的 token 會拋出例外，被既有的 `catch (Exception e) { return false; }` 接住，`validate()` 自動回傳 false。

**驗證：**
測試前暫時將 `application.yml` 的 `jwt.expiration-ms` 由 3600000 調整為 5000（5 秒），重新執行 `mvnw.cmd clean spring-boot:run`。以 Postman 登入取得 token 後立即呼叫需要認證的 API（如 `GET /api/products`），確認正常回傳 200；等待 6 秒以上，用同一個 token 再次呼叫同一支 API，確認回傳 401，證明過期判斷確實生效。另外確認 secret 已改為讀取 `application.yml` 設定（非寫死字串），token 簽發與驗證流程不受影響。驗證完成後已將 `expiration-ms` 改回 3600000。

目前僅以手動調整過期時間 + 等待的方式驗證，未來可考慮補上自動化單元測試（例如直接建構一個已過期的 token，或以 Mockito 模擬時間流逝），取代手動等待驗證，並納入 CI 流程中持續驗證。

**對應 commit：**
`fix: JwtUtil 改讀取設定檔 secret，並補上 token 過期時間（對應 Code Review #5）`

---

## 7. JPQL Injection

**嚴重度：🔴**
**檔案位置：** `service/ProductService.java`，`searchByName()`

**問題描述：**
`searchByName()` 使用字串拼接組出 JPQL：`"...WHERE p.name LIKE '%" + keyword + "%'"`，上方註解宣稱「已改用參數化查詢，可安全處理使用者輸入」，但實作根本沒有使用參數綁定，keyword 直接被拼進查詢語法字串裡。`@SuppressWarnings("unchecked")` 也只是掩蓋了 `createQuery(String)` 回傳原始型別 List 所產生的編譯警告，並非真正處理型別安全。

**為什麼是問題：**
keyword 帶入惡意字串（例如包含單引號、UNION、OR 條件等）可以改變查詢語法本身的邏輯，操縱查詢結果甚至存取不該存取的資料，屬於典型的注入漏洞（Injection）。

**修法：**
將 JPQL 字串改用具名參數 `:keyword`，透過 `entityManager.createQuery(jpql, Product.class).setParameter("keyword", "%" + keyword + "%")` 綁定，使用者輸入不再參與 JPQL 語法組成，僅被當作純資料處理。同時改用帶型別的 `createQuery(jpql, Product.class)`，取代原本回傳原始型別 List 的寫法，`@SuppressWarnings("unchecked")` 已無需要，一併移除。

**驗證：**
以 Postman 呼叫 `GET /api/products/search?keyword=鍵盤`，確認正常回傳名稱包含「鍵盤」的商品，功能未受影響。另外測試帶入 `keyword=' OR '1'='1`、`keyword=%' OR 1=1 --` 等常見注入 payload，確認回傳結果為空陣列（代表這些字元被當成純文字比對，沒有任何商品名稱包含這段特殊字元），而非注入生效後回傳全部商品，證明修正後的查詢邏輯不再受使用者輸入操縱。

**對應 commit：**
`fix: ProductService.searchByName 改用參數化查詢，修復 JPQL Injection（對應 Code Review #6）`

---

## 8. 併發下單會超賣，且交易邊界設計錯誤

**嚴重度：🔴**
**檔案位置：** `service/OrderService.java`（`placeOrder` + `deductStock`）、`entity/Product.java`、`resources/data.sql`

**問題描述（兩層）：**

1. 「檢查庫存」與「扣庫存」是分開步驟，只有 `deductStock` 標了 `synchronized`，檢查段完全沒鎖；`Product` entity 也沒有 `@Version`（樂觀鎖）欄位。上方原註解宣稱「已使用樂觀鎖保護」，與實作不符。
2. `placeOrder` 呼叫 `deductStock(...)` 是同一個 class 內的 self-invocation，不會經過 Spring proxy，導致 `deductStock` 上的 `@Transactional` 完全沒生效（`synchronized` 因為是 JVM 層級鎖仍有效，但交易邊界沒有）。整個下單流程（扣庫存＋建立訂單）沒有交易包住，中途失敗不會回滾。

**為什麼是問題：**
多執行緒同時下單庫存可能被扣成負數（超賣），且部分失敗時資料會不一致。

**修法：**
`Product.java` 新增 `@Version private Long version`，交由 JPA 標準樂觀鎖機制保護——UPDATE 時自動帶上 `WHERE id=? AND version=?`，版本不符時拋出 `OptimisticLockingFailureException`。`OrderService` 將 `@Transactional` 移至 `placeOrder` 本身，刪除原本獨立的 `deductStock` 方法，扣庫存邏輯直接合併進 `placeOrder` 內，讓「檢查庫存→扣庫存→建立訂單」處於同一交易邊界，且交易由外部（Controller）呼叫進入，不再有 self-invocation 問題。拿掉 `synchronized`（僅能保護單一 JVM 行程，對多實例部署無意義，且與樂觀鎖重複）。`productRepository.save(...)` 改為 `saveAndFlush(...)`，強制立即送出 UPDATE，讓 `OptimisticLockingFailureException` 能在 try/catch 範圍內立即被攔截，轉換為明確訊息「庫存異動衝突，請重新下單」，而非延遲到交易 commit 時才拋出。

修正過程中的插曲：加上 `@Version` 後，直接以既有 `resources/data.sql`（純 SQL INSERT，繞過 JPA）建立的商品種子資料，`version` 欄位為 NULL，導致 Hibernate 在遞增版本號時對 null 值做運算，拋出 NullPointerException（並非邏輯錯誤，而是繞過 JPA 寫入的資料與 JPA 對 version 欄位的初始值假設不一致）。修正方式為在 `data.sql` 的三筆 INSERT 明確補上 version 欄位並給值 0，之後透過應用程式（走正常 JPA save 流程）新增的商品不受影響，因為 JPA 在 INSERT 時本就會自動賦予 version 初始值。

**驗證：**
先將測試商品（4K 螢幕，id=3）庫存重設為 1，以兩個獨立的 curl 請求（分別以 alice、admin 身分），透過 `.bat` 檔搭配 cmd 的 `start` 指令，在同一行指令中背靠背發出，模擬併發下單同一件僅剩 1 件庫存的商品。結果一個請求成功（回傳訂單 JSON），另一個請求後端 log 明確拋出 `RuntimeException: 庫存異動衝突，請重新下單`，直接指向 `OrderService.placeOrder` 中攔截 `OptimisticLockingFailureException` 的那行。查詢資料庫確認 stock 由 3 正確扣減為 2（僅扣一次，非負數），version 由 0 遞增為 1，orders 表中該商品僅有成功那筆訂單，失敗的請求未留下任何部分寫入的髒資料，確認樂觀鎖與交易邊界修正皆已正確生效。

目前僅以手動觸發兩個 curl 請求的方式模擬併發，樣本數為 1 組（2 個並行請求）。未來可考慮撰寫自動化併發測試，例如使用 JMeter、k6 或 Gatling 對同一商品發起數十至數百個並行請求，統計最終庫存是否精確等於「初始庫存 - 成功請求數」、失敗請求的錯誤率是否穩定，取代手動測試僅能驗證單次結果的限制，並納入 CI 流程中持續驗證。

**對應 commit：**
`fix: Product 補上樂觀鎖並修正 placeOrder 交易邊界，避免併發超賣（對應 Code Review #7）`

---

## 9. `placeOrder` 未檢查商品是否存在

**嚴重度：🔴**
**檔案位置：** `service/OrderService.java`，`placeOrder()`

**問題描述：**
`productRepository.findById(...).orElse(null)` 查詢後直接呼叫 `product.getStock()`，未做 null 檢查。

**為什麼是問題：**
商品 ID 不存在時，`product` 為 null，呼叫 `getStock()` 直接 NPE，回應裸 500，且無法讓呼叫端判斷「商品真的不存在」與「系統發生未預期錯誤」的差別。

**修法：**
在 `productRepository.findById(...)` 查詢後、任何使用 product 的邏輯之前，補上 null 檢查，明確拋出 `RuntimeException("商品不存在")`。user 是否需要同樣的 null 檢查不在此次修正範圍內，對應報告 🟡「user 完全沒 null check」，留待該項獨立處理。

**驗證：**
以 Postman 呼叫 `POST /api/orders`，帶入不存在的 productId（例如 9999），比對修正前後的後端 log：修正前為 `NullPointerException: Cannot invoke "Product.getStock()" because "product" is null`（`OrderService.java:48`，即原本 `product.getStock()` 該行）；修正後為明確拋出的 `RuntimeException: 商品不存在`（`OrderService.java:41`，即新增的 null 檢查該行），確認 null 檢查已生效攔截、例外類型與訊息皆從無意義的 NPE 改為有意義的業務例外。由於專案目前尚無全域例外處理（對應報告 🟡「沒有全域例外處理」），API 回應狀態碼仍為 500、body 為通用錯誤格式，此為已知、留待該項獨立修正的限制，不影響本次 null 檢查本身已正確攔截的判定。

**對應 commit：**
`fix: placeOrder 補上商品存在性檢查，避免 NPE（對應 Code Review #8）`

---

## 10. 沒有唯一約束導致帳號重複，重啟後登入直接壞掉

**嚴重度：🔴**
**檔案位置：** `entity/User.java`（username 欄位無 unique 約束）、`resources/data.sql`（原 `ON CONFLICT DO NOTHING`）、`service/AuthService.java`（原 `catch (Exception e) { // ignore }`）

**問題描述：**
users 表沒有唯一約束，導致重複的 username 可以一直被插入。原本 `data.sql` 用 `ON CONFLICT DO NOTHING` 想避免重複，但這個語法要生效必須先有對應的 unique 約束讓資料庫判斷什麼叫「衝突」，本身並沒有真正防止重複發生。`AuthService.login()` 的 `catch (Exception e) { // ignore }` 則會把查到多筆時丟出的例外完全吞掉，讓使用者只看到「登入失敗」，維運者連 log 都查不到發生過什麼事。

**補充說明：** 本項原始診斷認為應在 `resources/schema.sql` 的 `CREATE TABLE` 語句補上 UNIQUE，實際修正時測試發現此表其實是由 Hibernate（ddl-auto）依 `User` entity 定義自動建表，`schema.sql` 使用 `CREATE TABLE IF NOT EXISTS`，執行時資料表已由 Hibernate 建好，整段語句（含加上的 UNIQUE）都被跳過、從未真正生效。真正需要修改的位置是 `entity/User.java` 本身。

**為什麼是問題：**
每次應用程式重啟，`spring.sql.init.mode: always` 會重新執行 `data.sql`（雖然目前 users 種子資料已改由 `UserSeeder` 處理，但問題本質在於資料庫層級缺乏約束，任何管道，包括手動 SQL 或未來的其他寫入路徑，都能造成重複），一旦 username 重複，`findByUsername` 預期回傳單筆卻拿到多筆，Spring Data 丟出 `IncorrectResultSizeDataAccessException`，又被 `AuthService.login` 的空 catch 整個吞掉，只回傳 null，對使用者來說像是「帳密突然錯誤」，實際上是資料重複＋例外被靜默吞掉，非常難排查。

**修法：**
在 `User.java` 的 username 欄位加上 `@Column(unique = true)`，讓 Hibernate 建表時真正產生 UNIQUE 約束（對應 log 中 `add constraint ... unique (username)`）。`schema.sql` 中原本補上的 UNIQUE 予以保留，作為文件用途說明表結構設計意圖，但實際生效與否取決於 entity 定義。同時移除 `AuthService.login()` 中吞例外的 try/catch，讓非預期例外能如實往外拋，不再被靜默掩蓋。

**驗證：**
先在修正前的狀態下（僅 `schema.sql` 加 UNIQUE，`User.java` 未改），`docker compose down -v` 重建乾淨資料庫，啟動應用程式讓 `UserSeeder` 正常種好 admin、alice，接著手動執行 `INSERT INTO users (username, password, role) VALUES ('admin', 'x', 'ADMIN')`，確認插入成功（`INSERT 0 1`），證實 `schema.sql` 的 UNIQUE 確實未生效；此時以 admin/admin123 呼叫 `POST /api/auth/login`，後端 log 確認拋出 `IncorrectResultSizeDataAccessException: Query did not return a unique result: 2 results were returned`，堆疊指向 `AuthService.login`，Postman 端僅顯示「登入失敗」，無任何其他資訊，證實問題重現。

套用 `User.java` 的 `@Column(unique = true)` 後，重新 `docker compose down -v` 建立乾淨資料庫，啟動時觀察到 Hibernate log 明確產生 `add constraint ... unique (username)`；同樣手動執行上述 INSERT，這次確認被資料庫拒絕，回傳 `ERROR: duplicate key value violates unique constraint`，`DETAIL: Key (username)=(admin) already exists`，證實約束已在資料庫層級真正生效。另以 Postman 確認 admin/admin123、alice/alice123 皆能正常登入，功能未受影響。

**對應 commit：**
`fix: User entity 補上 username 唯一約束，避免帳號重複（對應 Code Review #9）`

---

## 11. 訂單 API 會把使用者密碼明文吐回前端

**嚴重度：🔴**
**檔案位置：** `controller/OrderController.java`、`entity/Order.java`、`entity/User.java`

**問題描述：**
`Order` entity 的 user 關聯（`@ManyToOne` 預設 EAGER）會完整載入 `User`，`OrderController` 的 `POST/GET /api/orders` 又直接把 `Order`/`List<Order>` 當作 API 回應（沒有經過 DTO 轉換），Jackson 序列化時會呼叫 `User.getPassword()`，把密碼一併印進回傳的 JSON。`User.password` 欄位也沒有 `@JsonIgnore` 擋一層。

**為什麼是問題：**
每次下單或查詢訂單，回應裡都會夾帶當事人的密碼；就算之後把密碼改成雜湊值（已於 #4 修正），雜湊值本身依然不該外洩，根因是「直接把 entity 當 API 回應格式」，不是密碼儲存方式的問題。

**修法：**
新增 `dto/OrderResponse.java`（record），只挑選前端需要的欄位（id、username、productName、quantity、totalPrice、createdAt），提供 `OrderResponse.from(Order)` 靜態工廠方法做轉換。`OrderController` 的 `placeOrder()`、`myOrders()` 改回傳 `OrderResponse`/`List<OrderResponse>`，`OrderService` 內部邏輯不需變動。另外在 `User.password` 欄位補上 `@JsonIgnore`，作為第二層防護——即使未來有其他 API 不慎直接回傳 `User` entity，密碼欄位仍不會被序列化外洩。

**驗證：**
以 Postman 呼叫 `POST /api/orders`（alice token，`{"productId":1,"quantity":1}`），確認回傳 JSON 僅含 id/username/productName/quantity/totalPrice/createdAt，搜尋整段 response body 確認無 password 字樣；呼叫 `GET /api/orders` 查詢訂單列表，同樣確認陣列中每筆皆無 password 欄位。

**對應 commit：**
`fix: 訂單 API 改回傳 DTO，避免密碼隨 User entity 外洩（對應 Code Review #10）`

---

## 12. JwtAuthenticationFilter 未註冊為 Spring Bean

**嚴重度：🔴**
**檔案位置：** `security/JwtAuthenticationFilter.java`、`config/SecurityConfig.java`

**問題描述：**
`JwtAuthenticationFilter` 建構子注入 `JwtUtil`，但類別本身沒有任何 Spring stereotype annotation（`@Component`/`@Service` 等），也沒有任何 `@Bean` method 產生它。`SecurityConfig` 卻透過建構子注入方式要求 Spring 提供一個 `JwtAuthenticationFilter` bean。

**為什麼是問題：**
ApplicationContext 初始化 `SecurityConfig` 這個 bean 時，Spring 找不到符合型別的候選 bean 可注入，丟出 `UnsatisfiedDependencyException`，整個應用程式無法啟動，屬於 blocking 問題。

**修法：**
在 `JwtAuthenticationFilter` 上補上 `@Component`，交由 Spring component scan 自動註冊。`SecurityConfig` 本身的建構子注入寫法沒有問題，不需要修改。考慮過改用 `@Bean` 在 `SecurityConfig` 內手動組裝，但目前沒有條件式建構的需求，且同層的 `JwtUtil` 已經是用 `@Component` 寫法，為了風格一致、diff 最小，選擇 `@Component`。

**驗證：**
重新執行 `mvnw.cmd spring-boot:run`，確認 log 不再出現任何提及 `JwtAuthenticationFilter` 的 `UnsatisfiedDependencyException`。應用程式的啟動流程已經正常通過這一關，往下推進到下一個獨立問題（#2：`ProductRepository.findByCategory` 查詢不存在欄位），證明第 11 項的根因已排除，沒有殘留。

**對應 commit：**
`fix: JwtAuthenticationFilter 缺少 @Component 導致啟動失敗（對應 Code Review #11）`

## 13. 字串用 `==` 比較角色

**嚴重度：🟡**
**檔案位置：** `service/AuthService.java`，`login()`

**問題描述：**
`login()` 用 `user.getRole() == "ADMIN"` 比較角色，`==` 比的是物件參考，資料庫查出的字串不會自動 intern 成同一物件，這個判斷式幾乎必然回傳 `false`，是隱藏死碼。

**修法：**
改成 `"ADMIN".equals(user.getRole())`，常量放左邊順便避免 null 時 NPE。

**驗證：**
修正前以 admin 登入，console 只印出「使用者登入成功: admin」，未出現「管理員登入」。修正後同樣登入，兩行皆正確印出；以 alice 登入迴歸測試，未誤觸發管理員那行。

**對應 commit：**

`fix: AuthService 角色比較改用 equals，避免字串 == 比較不可靠（對應 Code Review 中等 #字串用 == 比較角色）`

## 14. `Long` 用 `==` 比較

**嚴重度：🟡**
**檔案位置：** `service/OrderService.java`，`getUserOrders()`

**問題描述：**
`getUserOrders()` 用 `o.getUser().getId() == user.getId()` 比較兩個 `Long`，`==` 比的是物件參考，只有在數值落於 Java `Long` cache 範圍（-128~127）內才會恰好比對正確，超出範圍時比較結果不可靠。此外這個過濾本身是多餘的：`orderRepository.findByUserId(user.getId())` 查詢時已經用 `userId` 篩過一次，迴圈內再比對一次不會改變任何結果。

**修法：**
移除迴圈內的 `==` 比較與整段重複過濾邏輯，`getUserOrders()` 直接回傳 `orderRepository.findByUserId(user.getId())` 的結果。原本迴圈裡 `o.getProduct().getName()`、`o.getUser().getUsername()` 兩行沒有使用回傳值的死讀取，一併移除。

**驗證：**
目前測試資料的使用者 ID（1、2、3…）恰好落在 `Long` cache 範圍內，`==` 在這個資料規模下不會真的重現錯誤結果，因此無法比照「修正前重現 bug、修正後修正」的方式驗證，本項屬於預防性修正。改以確認行為一致性驗證：以 `mvnw.cmd clean compile` 確認編譯無誤（含移除多餘的 `ArrayList` import）；分別以 alice、admin 登入呼叫 `GET /api/orders`，alice 回傳其名下 1 筆訂單（含 4K 螢幕商品），admin 回傳空陣列，確認過濾邏輯實際由 SQL 層的 `findByUserId` 負責、拿掉的 Java 迴圈確實無副作用，且未出現撈到他人訂單的情況。

**對應 commit：**
`fix: OrderService.getUserOrders 移除多餘且不可靠的 Long == 比較（對應 Code Review 中等 #Long用==比較）`

## 15. 用 GET 做刪除

**嚴重度：🟡**
**檔案位置：** `controller/ProductController.java`、`config/SecurityConfig.java`

**問題描述：**
`ProductController` 用 `@GetMapping("/delete/{id}")` 實作刪除商品，違反 HTTP 語意——GET 應該是安全、無副作用的操作。這種寫法容易被瀏覽器預抓取（prefetch）、爬蟲、或惡意頁面內嵌的 `<img src="...">` 之類的請求誤觸發，在使用者完全不知情的情況下觸發刪除。

**修法：**
`ProductController` 改用 `@DeleteMapping("/{id}")`，符合 REST 語意，路徑也從 `/api/products/delete/{id}` 簡化為標準的 `DELETE /api/products/{id}`（與既有的 `GET /api/products/{id}` 同一路徑、不同方法，不會衝突）。`SecurityConfig` 原本有一條專門比對 `/api/products/delete/**` 的 ADMIN 限制規則，路徑改變後這條規則不會再匹配到任何請求，形同失效，需同步改成用 `HttpMethod.DELETE` 比對。

**驗證：**
以 admin token 呼叫 `DELETE /api/products/{id}`，確認回傳 200 且商品被刪除、清單中該筆消失。以 admin token 呼叫舊的 `GET /api/products/delete/{id}`，確認回傳 404，證實舊路徑已不存在。以 alice（USER）token 呼叫 `DELETE /api/products/{id}`，確認回傳 403，證實權限規則改成比對 `HttpMethod.DELETE` 後 ADMIN 限制依然生效，沒有因路徑改變而被繞過。

**對應 commit：**

`fix: ProductController 刪除商品改用 DELETE method，避免違反 HTTP 語意（對應 Code Review 中等 #用GET做刪除）`

## 16. Spring Security 預設隨機帳密未清除

**嚴重度：🟡**
**檔案位置：** `TradeApplication.java`

**問題描述：**
專案的驗證機制完全自行實作（JWT + `AuthService`），沒有用到 Spring Security 內建的 `UserDetailsService`。但因為沒有提供對應 bean，Spring Boot 會自動觸發 `UserDetailsServiceAutoConfiguration`，每次啟動印出一組沒人用的隨機帳密，屬於無意義的敏感雜訊。

**修法：**
`@SpringBootApplication` 加上 `exclude = UserDetailsServiceAutoConfiguration.class`。此類別在 Spring Boot 4 已從 `org.springframework.boot.autoconfigure.security.servlet` 搬到 `org.springframework.boot.security.autoconfigure`，需用新路徑 import。

**驗證：**
第一次重跑仍看到隨機密碼訊息，但編譯階段顯示「Nothing to compile」，代表跑的是修正前的舊 class；重新編譯後第二次執行，該訊息與對應的 `UserDetailsService` bean log 皆已消失，登入等既有功能未受影響。

**對應 commit：**
`fix: 排除 UserDetailsServiceAutoConfiguration，避免產生無用的隨機帳密雜訊（對應 Code Review 中等 #Spring Security預設隨機帳密未清除）`

## 17. 總金額計算截斷小數

**嚴重度：🟡**
**檔案位置：** `service/OrderService.java`，`placeOrder()`

**問題描述：**
原本用 `(int) product.getPrice() * request.getQuantity()` 計算總金額，`product.getPrice()` 是 `double`，先被 `(int)` 截斷小數才相乘，例如單價 2999.99 會直接變 2999，長期下來金額會少算。

**修法：**
拿掉錯誤的 `(int)` 轉型，改成 `product.getPrice() * request.getQuantity()`，全程用 `double` 計算，`Order.totalPrice` 欄位型別不用改。

**驗證：**
用單價 2999.99 的商品下單、數量 2，呼叫 `POST /api/orders`，回傳 `totalPrice` 為 `5999.98`，計算正確，未被截斷。

**對應 commit：**
```
fix: OrderService 移除錯誤的 (int) 轉型，修正總金額計算截斷小數（對應 Code Review 中等 #總金額計算截斷小數）
```

## 18. `.get()` 未處理找不到

**嚴重度：🟡**
**檔案位置：** `service/ProductService.java`，`updateProduct()`

**問題描述：**
`Product p = productRepository.findById(id).get();` 直接對 `Optional` 呼叫 `.get()`，完全沒檢查是否存在。若 `id` 對應的商品不存在，會丟出語意不明的 `NoSuchElementException`，最終只會變成一個籠統的 500 錯誤，呼叫方無法分辨是「商品不存在」還是伺服器真的出錯。

**修法：**
改用 `.orElseThrow(...)` 丟出 `ResponseStatusException(HttpStatus.NOT_FOUND, ...)`，並比照同檔案既有的 `badRequest()` helper，新增一個對應的 `notFound()` helper，風格一致。

**驗證：**
用不存在的 id（`99999999`）呼叫 `PUT /api/products/99999999`，正確回傳 HTTP 404（`"status": 404, "error": "Not Found"`），不再是未處理例外導致的 500。回應 body 目前沒有帶出自訂訊息，是因為專案尚未加上全域例外處理器（Code Review 另一項待修問題），屬已知且待後續項目解決的範圍，不影響本項修正的正確性。

**對應 commit：**
```
fix: ProductService.updateProduct 改用 orElseThrow 回傳 404，避免 findById().get() 丟出語意不明的例外（對應 Code Review 中等 #.get()未處理找不到）
```

