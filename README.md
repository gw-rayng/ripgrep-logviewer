# ripgrep-logviewer

以 [ripgrep](https://github.com/BurntSushi/ripgrep) 為底層的 log 分析 UI。
零外部依賴：一個 `src/LogViewer.java`（JDK 內建 HttpServer）＋一個 `static/index.html`，不用 Maven/Gradle。

實測（250 萬行、340 MB 的 Logback log）：關鍵字搜尋約 0.3–0.8 秒；統計（level、Exception 排行、時間分布）約 3–4 秒。

## 需求

- JDK 17 以上
- ripgrep
  - Windows：`winget install BurntSushi.ripgrep.MSVC`（或 `scoop install ripgrep`）
  - macOS：`brew install ripgrep`
  - Linux：`apt install ripgrep`

## 專案結構

```
ripgrep-logviewer\       ← 專案根目錄（IntelliJ 開這一層）
├─ src\LogViewer.java    ← 後端（IntelliJ source root）
├─ static\index.html     ← 前端
├─ logs\                 ← 預設 log 目錄
├─ start.bat / start.sh
└─ README.md
```

程式啟動時會自動找到「程式目錄」（含 `static\index.html` 的那一層），
預設的 `logs` 與相對路徑的 `--root` 都以它為基準。所以不管用 IntelliJ、start.bat、
或從其他資料夾執行，用的都是同一個 `logs`。啟動訊息會印出程式目錄與可搜尋的目錄。

## 啟動

```bash
# Windows：直接雙擊 start.bat（搜尋專案內的 logs），
# 或把 log 資料夾拖到 start.bat 上，或：
start.bat D:\logs\myapp

# macOS / Linux
./start.sh /var/log/myapp

# IntelliJ：直接執行 LogViewer（main）。要搜尋其他資料夾時，在 Run Configuration 的
# Program arguments 加上 --root=D:\logs\app1 --root=logs

# 或直接執行（可放多個 --root）
java src/LogViewer.java --root=D:/logs/app1 --root=D:/logs/app2 --port=8099
```

開啟 http://127.0.0.1:8099

| 參數 | 預設 | 說明 |
|---|---|---|
| `--root=PATH` | `logs` | 允許搜尋的目錄，可多個；相對路徑以程式目錄為準；UI 無法讀取這些目錄以外的檔案 |
| `--port` | `8099` | 只綁定 127.0.0.1，區網其他人連不到 |
| `--rg` | `rg` | ripgrep 路徑，例如 `--rg=C:/tools/rg.exe` |
| `--max-results` | `200000` | 單次搜尋最多回傳幾行（含前後文），超過就停止 rg |
| `--max-line` | `2000` | 單行最多顯示字元數 |
| `--home` | 自動尋找 | 手動指定程式目錄（也可用 `-Drglv.home=`） |
| `--ui` | `<程式目錄>/static` | index.html 所在目錄 |

## 功能

- **搜尋**：regex / 純文字（-F）、忽略大小寫、整字比對、檔名 glob（`*.log,!*debug*`）、前後 N 行、搜尋 .gz
- **串流顯示＋虛擬捲動**：rg 一邊找、畫面一邊出結果；畫面只畫出看得到的列，20 萬筆也能順暢捲動；`Esc` 取消時會真的終止 rg
- **前後文**：點任一行，開啟該行前後 50 行（可再擴大），長行自動換行完整顯示，適合看完整的 stack trace
- **追蹤 ID（traceId / sessionId）**：兩種用法
  - 結果和前後文裡可追蹤的 ID 會有虛線底線，**點一下**就開始追蹤
  - 把 ID 貼到搜尋框，按「**追蹤 ID**」按鈕（適合從錯誤畫面、同事訊息拿到的 ID）

  追蹤會搜尋目前「路徑」下的所有檔案，**依時間排序**，多台 VM 的檔案合併成同一條時間軸，每行標示來源檔案；
  頂端顯示行數、檔案數、時間範圍與總耗時。追蹤不會改動你的搜尋選項，也不會寫進搜尋歷史。
  要跨 VM 追蹤，請把路徑設成包含所有 VM 的上層目錄。
- **統計**：
  - 各 log level 行數（點卡片可直接搜尋）
  - **訊息排行**：把每行 log 只保留 level＋時間戳之後的內容，再把時間、數字、UUID、IP、session id 等變動部分換成 `<N>`、`<UUID>`…，歸類計數；可切換全部 / ERROR / WARN / INFO / DEBUG，點擊看實際範例的前後文。有輸入搜尋字串時只統計命中的行
  - Exception / Error 類別排行（點擊即搜尋）
  - 時間分布長條圖：有搜尋字串時統計命中行，否則統計 ERROR 行。stack trace 這類沒有時間戳的行，會歸到上方最近一筆 log 的時間
  - 各檔案命中行數
- 搜尋歷史、表單設定會記在瀏覽器裡

## 架構

```
瀏覽器 ──EventSource──▶ /api/search ──▶ rg --json ──逐行解析──▶ 每 500 筆或 150ms 推一批
        ──fetch──────▶ /api/stats  ──▶ 同時跑 4 個 rg（-o / -c）在 Java 端彙總
        ──fetch──────▶ /api/context ─▶ Java 直接讀檔取前後 N 行（.gz 自動解壓）
```

設計重點：

- **不經過 shell**：用 `ProcessBuilder` 參數陣列呼叫 rg。搜尋字串寫進 UTF-8 暫存檔再用 `-f` 讀，
  路徑放在 `--` 之後，所以輸入內容不會被當成 rg 參數，在非 UTF-8 的系統 locale 下中文也不會變成亂碼。
- **byte offset 轉 char index**：rg 回傳的命中位置是 UTF-8 byte offset，中文 log 要先換算成字元位置，highlight 才不會偏移。
- **路徑白名單**：所有路徑都會 `toRealPath()` 後檢查是否在 `--root` 之內，`../` 和 symlink 都跳不出去。
- **斷線即停**：瀏覽器關閉或重新搜尋時，寫入 SSE 會失敗，程式會在這時 kill rg（連同子程序）。
  rg 一段時間沒有輸出時，每 2 秒送一次 heartbeat，最慢約 3 秒內就能發現斷線。
- **即時顯示**：rg 加上 `--line-buffered`，讀取執行緒與送出分開，找到的結果最慢 150ms 內就會出現在畫面上。
- **防 DNS rebinding**：只接受 `Host` 為 `127.0.0.1:port` / `localhost:port` 的請求，其他網站無法透過瀏覽器讀取你的 log；
  會改變狀態的 API（取消搜尋）只接受 POST。
- **警告不吞掉**：rg 回報的錯誤（檔案無法讀取、壓縮檔損壞等）會在畫面上顯示「⚠ 部分檔案無法讀取」，滑鼠移上可看詳細內容。

## 支援的 log 格式

任何文字 log 都能搜尋。統計功能的假設如下：
- level：每行前 120 個字元內出現的 `TRACE|DEBUG|INFO|WARN|WARNING|ERROR|FATAL`
- 時間戳：每行前 200 個字元內的 `yyyy-MM-dd HH:mm`（也支援 `T` 分隔和 `/` 日期），例如 `LEVEL|...|2026-11-10 00:02:52|...` 這種 pipe 分隔格式也可以
  → Spring Boot / Logback 預設格式可以直接使用。

## 讓 log 可以被追蹤

工具會自動認出下列 ID（之後新增的 log 欄位只要符合其中一種，就能直接點擊追蹤）：

| 形式 | 範例 |
|---|---|
| 具名欄位 `key=value`、`key: value`、`"key":"value"` | `traceId=a1b2c3d4e5f60718`、`sessionId=9f86d081884c`、`"traceId":"7d3f9a"` |
| 認得的欄位名稱（不分大小寫） | traceId、trace_id、X-Trace-Id、requestId、reqId、correlationId、spanId、sessionId、session_id、JSESSIONID、sid |
| UUID | `123e4567-e89b-12d3-a456-426614174000` |
| 12～64 位、同時含數字與字母的 hex | gate_web 的 session id、`trace code:` 後面的值 |

**建議**：新系統或要加 traceId 的系統，在 log 格式裡寫成具名欄位，例如：

```
%p|%d{yyyy-MM-dd HH:mm:ss}|%c|%F|%M|%L|%m|traceId=%X{traceId}|sessionId=%X{sessionId}%n
```

具名欄位不論值是什麼格式（純數字、短字串）都一定認得到；只放裸值的話，必須是 12 位以上且同時含數字與字母的 hex 才會被認出。
traceId / sessionId 要由 Servlet Filter 在每個請求開始時放進 MDC（`MDC.put("traceId", ...)`），結束時 `MDC.clear()`。

## 分享與版控注意事項

- `logs\` 底下是正式環境的 log，可能含客戶 IP、帳號、session id，**不要**隨專案分享或提交到版控（`.gitignore` 已排除）。
- `.idea\workspace.xml`、`out\` 是個人 IDE 狀態與編譯輸出，也已排除。

## 修改程式後的自我測試

```bash
java src/LogViewer.java --selftest      # 後端：Host 檢查、訊息正規化、中文位置換算、JSON、錯誤訊息
```

前端（ID 偵測、HTML 跳脫）：啟動後開 http://127.0.0.1:8099/?selftest ，狀態列會顯示結果。

## 可以再加的功能

- 結果匯出 CSV
- 打包成單一 jar：`javac -d out src/LogViewer.java && jar cfe ripgrep-logviewer.jar LogViewer -C out .`（static 資料夾需放在 jar 旁邊）
