# AI 使用說明

## 使用的 AI 與分工方式

這次作業我同時使用 **Claude、GPT、Gemini** 三個 AI（皆為免費版），依各自優勢分工，而不是單一 AI 從頭做到尾：

- **Claude**：主力，負責整體方向規劃與實作。包括讀程式碼定位 bug、給修正方案與程式碼、第三部分（商品進階查詢）的功能設計、索引與效能驗證的規劃。
- **GPT**：交叉驗證。針對 Claude 給出的診斷、修正方向或程式碼，另外拿給 GPT 檢查與分析，藉由不同 AI 的觀點確認問題是否成立，並補足單一 AI 分析可能遺漏的問題（見下方「User 查詢 NPE」案例）。
- **Gemini**：處理較單純、明確的小工作（例如簡單的語法確認、單一函式的小修改），把單一 AI 的用量留給需要深入分析的部分。

這樣分工的原因：多個 AI 交叉檢查可以降低「單一 AI 看漏或自信地講錯」的風險；同時免費版都有用量限制，把簡單工作分給 Gemini、驗證工作分給 GPT，可以避免把 Claude 的額度耗在不需要深度分析的地方。

但無論哪個 AI 提出的方案，**最終是否成立都由我自己在本機驗證**：實際跑程式、查資料庫、用 Postman／終端機測試，而不是看 AI 講得有道理就採信。

---

## 第一、二部分：Code Review 與修正

### 哪些部分借助了 AI

- **讀程式碼、找問題**：通讀 starter-project 原始碼時，藉助 AI 快速定位問題根因，例如 record 型別 accessor 命名不符（`getName()` vs `name()`）、密碼比對邏輯與程式碼註解不一致（註解宣稱 BCrypt 雜湊，實作卻是明文比對）、`JwtAuthenticationFilter` 缺少 `@Component` 導致啟動失敗等。
- **理解程式架構與技術原理**：遇到不熟悉的 Java、Spring Boot、JPA、Spring Security 等概念時，使用 AI 協助理解 Controller、Service、Repository、Entity、DTO、Dependency Injection、JWT、`SecurityContext`、`@Transactional`、`@Version` 等元件的用途，以及各元件之間的資料流。
- **協助修正與 Debug**：針對已確認的問題，請 AI 提供修正方向與程式碼，並根據實際執行時的錯誤訊息、Console Log、Swagger／Postman 回應及資料庫結果協助分析問題，再由我修改程式並重新執行確認，例如處理 401／403 權限問題、`/error` 轉發、併發問題及例外處理等。
- **測試驗證與報告整理**：使用 AI 協助設計正常及異常測試情境，以及理解 PostgreSQL `EXPLAIN ANALYZE`、GIN、`pg_trgm`、複合索引等效能相關內容；實際透過 Swagger、Postman、Log 及資料庫進行驗證，最後再使用 AI 協助整理 Code Review 問題、修正方式及測試結果。

### 如何驗證 AI 的產出

- 每次修正後，都重新執行 `mvnw.cmd clean spring-boot:run`，確認應用程式能重新編譯並正常啟動，並用 `docker exec ... psql` 直接查詢資料庫，確認資料（例如密碼欄位）真的變成預期的格式（BCrypt hash），而不是只看程式碼「看起來」有改。
- 用 Postman 針對每個角色（ADMIN／USER）、每種情境（有 token／無 token／角色不符）分別測試，確認狀態碼符合預期，而不是只測「正常路徑」。
- 針對併發或狀態相關的修正，額外做了「重複啟動、確認資料不會重複插入」的冪等性測試。
- 針對版本升級（如 springdoc-openapi），不只依賴官方文件判斷相容性，而是實際在升級前後分別測試同一組功能（Swagger UI、`/v3/api-docs`），並比對 log 是否乾淨，確認升級前是否「已經壞了」還是「規格上不支援但暫時還能動」，避免把尚未重現的潛在風險寫成已修正的 bug。

### AI「自信地改錯」的案例，以及我怎麼發現的

**案例一：修正權限控管時，把已修好的問題重新引入。**
修正 Code Review #3（權限控管）時，AI 提供的 `JwtAuthenticationFilter` 重寫版本遺漏了 `@Component` 註解——等於把先前已經修好的 Code Review #11（Bean 未註冊）問題重新引入。這個錯誤是我實際重新執行 `mvnw.cmd clean spring-boot:run` 時，從 `UnsatisfiedDependencyException: No qualifying bean of type 'JwtAuthenticationFilter'` 這則錯誤 log 才發現的——如果沒有每次修改後都實際跑一次驗證，這個回歸問題會被忽略。

**案例二：403 誤判成 401，AI 兩次猜錯方向。**
排查「alice 呼叫需要 ADMIN 權限的 API 應回 403，實際卻回傳 401」的問題時，AI 一開始給了兩個錯誤方向的猜測（先猜是 CSRF 保護沒關掉、後猜是 Spring Security 把已登入的使用者誤判為匿名），兩次都被我拿現有程式碼一一核對後排除。最後是在 AI 建議下於 filter 與例外處理器中加入暫時性 debug log，實際重現問題並比對 log 輸出，才共同定位出真正根因：`accessDeniedHandler` 觸發 `response.sendError(403)` 後，Servlet 容器會將請求內部轉發至 `/error`，重新跑過整條 Security filter chain，而 `JwtAuthenticationFilter` 繼承 `OncePerRequestFilter` 預設會跳過這種內部轉發，導致第二次判斷被誤認為未登入，401 蓋掉了原本正確的 403。這個問題如果只憑 AI 的文字推論、不實際加 log 驗證，很可能會被誤判成別的原因去修正錯的地方。

**案例三：修一個問題，順帶引入新的 NullPointerException。**
修 Code Review #7（併發下單超賣）時，AI 建議在 Product entity 加上 `@Version` 欄位做樂觀鎖，並把交易邊界從獨立的 `deductStock()` 移到 `placeOrder()` 本身，解決呼叫自己方法導致 `@Transactional` 失效的問題。套用後第一次測試就跳出新的 NullPointerException——追查後發現 `resources/data.sql` 是用純 SQL INSERT 建立商品種子資料，沒有指定 `version` 欄位，資料庫存的是 NULL，Hibernate 做版本號遞增運算時對 null 值出錯，補上 `version` 初始值 0 才解決。

**案例四：Spring Boot 4 套件搬家，AI 一開始用舊版本的 import 路徑。**
修正「Spring Security 預設隨機帳密未清除」時，AI 給的 `@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)` 用的是 Spring Boot 3.x 的 import 路徑（`org.springframework.boot.autoconfigure.security.servlet`）。我實際編譯後跳出 `cannot be resolved` 的錯誤，回報給 AI 後，AI 才查證官方 API 文件，確認 Spring Boot 4 把原本集中的 autoconfigure 模組拆成多個小模組，這個類別實際搬到了 `org.springframework.boot.security.autoconfigure`，換路徑後才編譯成功。這個案例顯示：AI 對於框架大版本升級後的套件搬遷未必即時掌握，同樣需要靠實際編譯結果驗證才能發現。

### 我否決 AI 建議的案例

AI 第一版針對 Code Review #4（密碼明文儲存）的修正，除了改密碼比對邏輯外，一併把 `AuthService.login()` 裡原本吞掉例外的 `catch (Exception e) { // ignore }` 和 `System.out.println` 除錯輸出都拿掉了。我認為這樣會把三個不同根因的問題（明文密碼、帳號重複導致的例外吞噬、缺乏正式 logging）混進同一個 commit，不利於之後用 commit 追蹤問題，因此要求 AI 縮小這次修正範圍，只改密碼比對邏輯本身，其餘問題留給各自對應的 Code Review 項目分別修正。

### 修正過程發現原始診斷位置有誤的案例

Code Review #9（帳號重複）原始診斷認為問題出在 `resources/schema.sql` 的 `users` 表缺少 UNIQUE 約束，我請 AI 依此提供修正（在 `schema.sql` 的 `CREATE TABLE` 語句加上 UNIQUE）。套用後，我依照先啟動應用程式、再手動插入重複帳號的方式測試，結果插入仍然成功（`INSERT 0 1`），約束並未生效。

排查後發現：這張表實際上是由 Hibernate（`ddl-auto`）依照 `User` entity 的定義自動建表，而 `schema.sql` 使用的是 `CREATE TABLE IF NOT EXISTS`——Hibernate 已經先把表建好，`schema.sql` 執行時判斷「表已存在」而整段跳過，包含我新加的 UNIQUE 約束從未真正被執行。真正需要修改的位置其實是 `entity/User.java` 本身，加上 `@Column(unique = true)` 才能讓 Hibernate 建表時真正產生約束。

這個案例讓我意識到：即使 AI 給的修正方向（「加 UNIQUE 約束」）本身是對的，套用的**位置**如果錯了，實務上依然完全無效——如果沒有實際動手測試（清空資料庫、手動插入重複資料、觀察資料庫是否真的拒絕），單看程式碼「看起來」有加約束，很可能就會誤判這個問題已經修好。這也是我在這次作業中堅持每一項修正都要重新啟動、實際下指令驗證，而不是只看 AI 給的程式碼是否合理的原因。

---

## 第三部分：商品進階查詢（選項 A）

### 哪些部分借助了 AI

- 選題分析：比較 A（商品進階查詢）與 B（Audit Log）在複雜度、與既有程式碼銜接程度、可驗證性上的差異，決定選 A。
- 搜尋 API 與動態 SQL 的設計、索引方案規劃、造 100 萬筆測試資料與 `EXPLAIN ANALYZE` 驗證腳本、單元測試撰寫。
- 每一步的實測結果都貼回去給 AI 看，再依實測結果決定下一步要驗證什麼、要不要調整設計，而不是一次把所有程式碼生完就結束。

### 如何驗證 AI 的產出

AI 給的程式碼是在沒有 Maven、沒有資料庫的環境下寫出來的，未經編譯、也未經執行。我在本機編譯、啟動，用 **Postman** 逐項驗證功能與 400 錯誤，再灌入 100 萬筆資料、用**終端機**下 `EXPLAIN ANALYZE` 驗證效能，不直接採信 AI 給的效能推論。實測中發現 AI 有以下不準確之處：

**問題一：測試關鍵字設計不良，數字不具代表性。**
AI 第一輪拿 `777777` 當「稀有關鍵字」的測試案例，我實測後發現：因為 trigram 索引是以 3 個字元一組切分，`777777` 切出來的組合全部重複，索引回傳了 3,700 筆候選再逐筆過濾，耗時 38 ms，並不能代表真正稀有關鍵字的效能。我要求換一個切出來組合夠分散的關鍵字（改用商品流水號 `583921`）重測，才拿到真正只命中 1 筆的結果（0.64 ms）。

**問題二：關鍵字最短長度的初始設定沒有實測依據。**
AI 一開始把關鍵字最短長度訂為 2（理由是讓「鍵盤」這類 2 字中文常見詞可以搜尋），但這只是推論、沒有實測驗證。我要求用 `SET enable_seqscan = off` 等方式強制資料庫走索引，實測發現：2 字元關鍵字時，GIN 索引回傳的是**整張表**（100 萬筆全部命中，等同沒有篩選效果）；再測「2 字元關鍵字 + 無命中 + 依價格排序」的最壞情況，資料庫會把 100 萬筆全部掃過一遍才確定沒有結果，耗時約 493 ms。這個實測結果推翻了 AI 一開始的設定，最後依實測數據把最短長度改為 3。

**問題三：效能快不等於用到索引，需要看執行計畫才能判斷。**
2 字元關鍵字（命中率約 10%）在加索引後量測只要 12 ms，乍看是索引發揮作用，但檢查 `EXPLAIN` 的執行計畫後發現，資料庫實際上完全沒有使用 GIN 索引，而是沿著價格索引邊走邊過濾，剛好因為命中率夠高，走了約 250 筆就湊滿需要的筆數。這個「看起來快」但原因不是 AI 所預期的索引在起作用，如果只看耗時數字、不看執行計畫，會對系統的實際行為有錯誤的認知。這也是我後續補測「無命中」情境、找出上述問題二的原因。

### 有沒有否決 AI 的建議

第三部分的實作階段沒有明確否決整套方案，但對 AI 給出的初始參數（關鍵字最短長度 2）不予採信，要求先用實測數據驗證後才定案，最終依實測結果改為 3（見問題二）。這與第一、二部分否決 AI 建議的性質略有不同：不是「不同意做法」，而是「不接受沒有實測支持的參數」。

---

## 小結

這次作業中，AI（Claude、GPT、Gemini）在讀程式碼、定位問題、提出修正方向、設計功能與索引、寫測試等方面提供了很大的效率提升，也透過多個 AI 交叉比對補足了單一 AI 容易遺漏的問題。但幾個案例都顯示：AI 給的方案可能位置錯誤（UNIQUE 約束案例）、可能重新引入舊 bug（`@Component` 遺漏案例）、可能推論方向錯誤需要反覆排查（403/401 案例）、也可能給出沒有實測支持的參數或誤導性的測試設計（keyword 長度、trigram 重複字元案例）。因此全程堅持「AI 給方案、我來實測」的分工：每一項修正或設計都要重新啟動應用程式、實際下指令查資料庫、用 Postman 打 API、用 `EXPLAIN ANALYZE` 看真實執行計畫，確認過才視為完成。
