import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * ripgrep-logviewer：以 ripgrep 為底層的 log 分析 UI。零外部依賴，JDK 17+ 直接執行：
 *
 *   java src/LogViewer.java                      （預設搜尋 <程式目錄>/logs）
 *   java src/LogViewer.java --root=D:/logs/app1
 *
 * 程式目錄 = 含 static/index.html 的資料夾（專案根目錄），會自動尋找。
 * 預設的 logs 與相對路徑的 --root 都以程式目錄為基準，與從哪裡啟動無關。
 *
 * 參數：
 *   --root=PATH         允許搜尋的根目錄，可重複指定多個（預設 <程式目錄>/logs；相對路徑以程式目錄為準）
 *   --port=8099         HTTP port（只綁定 127.0.0.1）
 *   --rg=rg             ripgrep 執行檔路徑（Windows 可填 C:/tools/rg.exe）
 *   --max-results=200000 單次搜尋最多回傳筆數（含 context 行）
 *   --max-line=2000     單行最多顯示字元數
 *   --home=PATH         手動指定程式目錄（也可用 -Drglv.home=PATH）
 *   --ui=PATH           index.html 所在目錄（預設 <程式目錄>/static）
 *   --selftest          執行內建自我測試後結束（修改程式後請先跑一次）
 */
public class LogViewer {

    // ======================== 設定 ========================

    static String rgPath = "rg";
    static int port = 8099;
    static int maxResults = 200000;
    static int maxLineChars = 2000;
    static Path uiDir = null;   // 預設 <程式目錄>/static
    static Path appHome = null; // 程式目錄：含 static/index.html 的那一層
    static final List<Path> roots = new ArrayList<>();

    static final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        return t;
    });
    static final Map<String, Process> activeSearches = new ConcurrentHashMap<>();

    public static void main(String[] args) throws Exception {
        List<String> rootArgs = new ArrayList<>();
        String homeArg = System.getProperty("rglv.home");
        if (Arrays.asList(args).contains("--selftest")) {
            System.exit(SelfTest.run());
        }
        for (String a : args) {
            String[] kv = a.split("=", 2);
            String v = kv.length > 1 ? kv[1] : "";
            switch (kv[0]) {
                case "--root" -> rootArgs.add(v);
                case "--port" -> port = Integer.parseInt(v);
                case "--rg" -> rgPath = v;
                case "--max-results" -> maxResults = Integer.parseInt(v);
                case "--max-line" -> maxLineChars = Integer.parseInt(v);
                case "--ui" -> uiDir = Path.of(v);
                case "--home" -> homeArg = v;
                default -> {
                    System.err.println("未知參數: " + a);
                    System.exit(1);
                }
            }
        }
        // 程式目錄（含 static/index.html 的那一層）。預設 logs 與相對路徑的 --root 都以它為基準，
        // 所以不管從 IDE、start.bat 或其他目錄啟動，用的都是同一個 logs 資料夾。
        appHome = findHome(homeArg);
        uiDir = uiDir != null ? uiDir.toAbsolutePath().normalize() : appHome.resolve("static");
        if (rootArgs.isEmpty()) rootArgs.add("logs");
        for (String r : rootArgs) {
            Path p = Path.of(r);
            if (!p.isAbsolute()) p = appHome.resolve(p);
            p = p.normalize();
            Files.createDirectories(p);
            roots.add(p.toRealPath());
        }
        checkRg();
        System.out.println("程式目錄: " + appHome);

        // 只綁定 127.0.0.1：避免同網段的人透過這個工具讀你電腦上的檔案
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(pool);
        server.createContext("/", LogViewer::handle);
        server.start();
        System.out.println("ripgrep-logviewer 已啟動: http://127.0.0.1:" + port);
        System.out.println("可搜尋的目錄: " + roots);
    }

    /**
     * 找程式目錄（含 static/index.html 的那一層）：
     * --home / -Drglv.home 指定 → 目前目錄 → 上一層（例如從 src 執行）→ 下一層子目錄。
     */
    static Path findHome(String given) throws IOException {
        List<Path> tried = new ArrayList<>();
        if (given != null && !given.isBlank()) {
            tried.add(Path.of(given).toAbsolutePath());
        } else {
            Path cwd = Path.of("").toAbsolutePath();
            tried.add(cwd);
            if (cwd.getParent() != null) tried.add(cwd.getParent());
            try (Stream<Path> s = Files.list(cwd)) {
                s.filter(Files::isDirectory).sorted().forEach(tried::add);
            } catch (IOException ignored) {
            }
            // 程式本身所在位置往上找：src/LogViewer.java 或 IDE 編譯輸出的 out/production/xxx/
            try {
                var cs = LogViewer.class.getProtectionDomain().getCodeSource();
                if (cs != null && cs.getLocation() != null) {
                    Path loc = Path.of(cs.getLocation().toURI()).toAbsolutePath();
                    for (int i = 0; i < 5 && loc != null; i++, loc = loc.getParent()) tried.add(loc);
                }
            } catch (Exception ignored) {
            }
        }
        for (Path d : tried) {
            if (Files.isRegularFile(d.resolve("static").resolve("index.html"))) return d.toRealPath();
        }
        System.err.println("找不到程式目錄（含 static/index.html 的資料夾），已找過：");
        tried.forEach(t -> System.err.println("  " + t.resolve("static").resolve("index.html")));
        System.err.println("請在專案根目錄執行，或加上 --home=專案根目錄");
        System.exit(1);
        return null;
    }

    static void checkRg() {
        try {
            Process p = new ProcessBuilder(rgPath, "--version").redirectErrorStream(true).start();
            String v = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).lines().findFirst().orElse("");
            p.waitFor();
            System.out.println("使用 " + v);
        } catch (Exception e) {
            System.err.println("找不到 ripgrep (" + rgPath + ")。請先安裝 rg，或用 --rg=完整路徑 指定。");
            System.exit(1);
        }
    }

    // ======================== 路由 ========================

    static void handle(HttpExchange ex) throws IOException {
        try {
            // 防 DNS rebinding：惡意網站可把自己的網域解析到 127.0.0.1 來呼叫本工具的 API，
            // 但瀏覽器送出的 Host 標頭會是那個網域，所以只接受本機的 Host。
            String host = ex.getRequestHeaders().getFirst("Host");
            if (!isAllowedHost(host, port)) {
                sendJson(ex, 403, Map.of("message", "拒絕存取：Host 不是本機（" + host + "）。請用 http://127.0.0.1:" + port + " 開啟"));
                return;
            }
            String path = ex.getRequestURI().getPath();
            // 會改變狀態的 API 只接受 POST，其餘只接受 GET
            String method = ex.getRequestMethod();
            boolean needPost = path.equals("/api/cancel");
            boolean okMethod = needPost ? "POST".equals(method) : "GET".equals(method);
            if (!okMethod) {
                sendJson(ex, 405, Map.of("message", "不支援的 HTTP 方法: " + method));
                return;
            }
            Map<String, String> q = parseQuery(ex.getRequestURI().getRawQuery());
            switch (path) {
                case "/", "/index.html" -> serveFile(ex, uiDir.resolve("index.html"), "text/html; charset=utf-8");
                case "/api/config" -> sendJson(ex, 200, Map.of(
                        "roots", roots.stream().map(Path::toString).toList(),
                        "maxResults", maxResults));
                case "/api/files" -> sendJson(ex, 200, listFiles(q.get("path")));
                case "/api/search" -> search(ex, SearchRequest.from(q));
                case "/api/cancel" -> sendJson(ex, 200, Map.of("cancelled", cancel(q.get("id"))));
                case "/api/stats" -> sendJson(ex, 200, stats(SearchRequest.from(q)));
                case "/api/messages" -> sendJson(ex, 200, messages(SearchRequest.from(q), q.getOrDefault("level", "ALL")));
                case "/api/context" -> sendJson(ex, 200, context(q));
                default -> sendJson(ex, 404, Map.of("message", "not found"));
            }
        } catch (IllegalArgumentException e) {
            if (ex.getResponseCode() == -1) sendJson(ex, 400, Map.of("message", e.getMessage()));
        } catch (Exception e) {
            e.printStackTrace();
            if (ex.getResponseCode() == -1) {
                sendJson(ex, 500, Map.of("message", "後端錯誤（" + e.getClass().getSimpleName() + "）：" + e.getMessage()));
            }
        } finally {
            ex.close();
        }
    }

    /** 只接受 127.0.0.1 / localhost / [::1] 加上本工具的 port。 */
    static boolean isAllowedHost(String host, int port) {
        if (host == null) return false;
        String h = host.trim().toLowerCase(java.util.Locale.ROOT);   // 指定 ROOT：避免土耳其語系把 I 轉成 ı
        return h.equals("127.0.0.1:" + port) || h.equals("localhost:" + port) || h.equals("[::1]:" + port);
    }

    /** 讀數字參數；格式錯誤時回傳中文訊息（400），不是 Java 例外文字。 */
    static long numParam(Map<String, String> q, String key, long def) {
        String v = q.get(key);
        if (v == null || v.isBlank()) return def;
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("參數 " + key + " 必須是數字：" + v);
        }
    }

    /** rg 的錯誤訊息改成中文說明開頭，保留 rg 原文方便判斷。 */
    static String rgError(String err) {
        if (err.contains("regex parse error")) return "搜尋字串的 regex 語法錯誤（若要搜尋一般文字，請勾選「純文字 -F」）：\n" + err;
        return "ripgrep 錯誤：\n" + err;
    }

    // ======================== 搜尋條件 ========================

    record SearchRequest(String path, String pattern, boolean fixed, boolean ignoreCase, boolean word,
                         List<String> globs, int context, boolean zip, boolean sort) {

        static SearchRequest from(Map<String, String> q) {
            List<String> globs = Arrays.stream(q.getOrDefault("glob", "").split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList();
            int ctx = 0;
            try {
                ctx = Math.max(0, Math.min(20, Integer.parseInt(q.getOrDefault("context", "0"))));
            } catch (NumberFormatException ignored) {
            }
            return new SearchRequest(q.get("path"), q.getOrDefault("pattern", ""),
                    bool(q, "fixed"), bool(q, "ignoreCase"), bool(q, "word"),
                    globs, ctx, bool(q, "zip"), !"false".equals(q.get("sort")));
        }

        static boolean bool(Map<String, String> q, String k) {
            return "true".equals(q.get(k)) || "on".equals(q.get(k));
        }

        boolean hasPattern() {
            return pattern != null && !pattern.isEmpty();
        }
    }

    /**
     * 組 rg 指令：用參數陣列（不經 shell），pattern 放 -e 後面、路徑放 -- 後面，
     * 使用者輸入不可能被當成 rg 的 option。
     */
    static final class Rg {
        final List<String> args = new ArrayList<>(List.of(rgPath, "--no-config", "--no-ignore", "--hidden"));

        Rg files(SearchRequest r) {
            if (r.zip()) args.add("-z");
            for (String g : r.globs()) {
                args.add("-g");
                args.add(g);
            }
            return this;
        }

        Rg match(SearchRequest r) {
            if (r.fixed()) args.add("-F");
            args.add(r.ignoreCase() ? "-i" : "-s");
            if (r.word()) args.add("-w");
            return this;
        }

        Rg arg(String... a) {
            args.addAll(List.of(a));
            return this;
        }

        /**
         * pattern 寫進 UTF-8 暫存檔再用 -f 讀取，而不是放在命令列參數：
         * JVM 會用系統 locale 編碼命令列參數，locale 不是 UTF-8 時中文會變成 ????。
         */
        RgCmd build(String pattern, Path path) {
            try {
                Path pf = Files.createTempFile("rglv-", ".pat");
                Files.writeString(pf, pattern, StandardCharsets.UTF_8);
                List<String> out = new ArrayList<>(args);
                out.addAll(List.of("-f", pf.toString(), "--", path.toString()));
                return new RgCmd(out, pf);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
    }

    record RgCmd(List<String> args, Path patternFile) {
        Process start() throws IOException {
            try {
                Process p = new ProcessBuilder(args).start();
                p.onExit().thenRun(this::cleanup);
                return p;
            } catch (IOException e) {
                cleanup();
                throw e;
            }
        }

        void cleanup() {
            try {
                Files.deleteIfExists(patternFile);
            } catch (IOException ignored) {
            }
        }
    }

    // ======================== 路徑檢查 ========================

    static Path resolve(String raw) {
        if (raw == null || raw.isBlank()) return roots.get(0);
        Path real;
        try {
            real = Path.of(raw).toAbsolutePath().normalize().toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("路徑不存在: " + raw);
        }
        for (Path root : roots) if (real.startsWith(root)) return real;
        throw new IllegalArgumentException("路徑不在允許的 root 目錄內: " + raw
                + "\n目前允許的目錄: " + roots + "\n請用 start.bat \"你的log目錄\" 重新啟動（或把資料夾拖到 start.bat 上）");
    }

    static List<Map<String, Object>> listFiles(String raw) throws IOException {
        Path root = resolve(raw);
        List<Map<String, Object>> out = new ArrayList<>();
        if (Files.isRegularFile(root)) {
            out.add(Map.of("path", root.toString(), "size", Files.size(root)));
            return out;
        }
        List<Path> files;
        try (Stream<Path> s = Files.walk(root, 6)) {
            // 先排序再取前 2000 個，結果才固定、可預期
            files = s.filter(Files::isRegularFile).limit(100_000).sorted().limit(2000).toList();
        }
        for (Path p : files) {
            try {
                out.add(Map.of("path", p.toString(), "size", Files.size(p)));
            } catch (IOException ignored) {
            }
        }
        return out;
    }

    // ======================== 搜尋（SSE 串流） ========================

    /**
     * 執行 rg --json，一邊讀一邊用 Server-Sent Events 分批推給瀏覽器。
     * 結果到上限、使用者取消、或瀏覽器斷線（寫入失敗）時，立即 kill rg。
     */
    static void search(HttpExchange ex, SearchRequest req) throws IOException {
        // 先送出 SSE header：之後任何錯誤都能用 fail 事件傳回前端顯示，
        // 不會因為 EventSource 讀不到 HTTP 400 的內容而只看到「連線中斷」
        ex.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().add("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, 0);
        Sse sse = new Sse(ex.getResponseBody());

        String id = UUID.randomUUID().toString();
        long t0 = System.currentTimeMillis();
        Process process = null;
        AtomicBoolean stop = new AtomicBoolean(false);   // 通知讀取執行緒停止
        try {
            if (!req.hasPattern()) throw new IllegalArgumentException("請輸入搜尋字串");
            Path path = resolve(req.path());

            // --line-buffered：rg 輸出到管道時預設會累積一大塊才寫出，加上它才能找到就立即顯示
            Rg rg = new Rg().files(req).match(req).arg("--json", "--line-buffered");
            if (req.context() > 0) rg.arg("-C", String.valueOf(req.context()));
            if (req.sort()) rg.arg("--sort", "path");
            RgCmd cmd = rg.build(req.pattern(), path);
            System.out.println("[search] " + cmd.args() + "  pattern=" + req.pattern());

            try {
                process = cmd.start();
            } catch (IOException e) {
                sse.trySend("fail", Map.of("message", "無法執行 ripgrep: " + e.getMessage()));
                return;
            }
            activeSearches.put(id, process);
            sse.send("start", Map.of("id", id));

            StringBuilder stderr = new StringBuilder();
            Thread errReader = drain(process.getErrorStream(), stderr);

            int count = 0;
            long matchLines = 0;
            boolean truncated = false;
            int badLines = 0;
            Map<?, ?> summary = null;
            List<Object> batch = new ArrayList<>();
            long lastFlush = System.currentTimeMillis();

            // 另一個執行緒讀 rg 輸出放進佇列；主迴圈用 poll(150ms) 取資料，
            // 即使 rg 暫時沒輸出，也能定時把已找到的結果送出，並定期送 heartbeat 偵測瀏覽器是否已斷線。
            BlockingQueue<String> queue = new ArrayBlockingQueue<>(4096);
            Process proc = process;
            Thread reader = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8), 1 << 16)) {
                    String l;
                    while (!stop.get() && (l = br.readLine()) != null) {
                        while (!stop.get() && !queue.offer(l, 200, TimeUnit.MILLISECONDS)) {
                            // 佇列滿了（瀏覽器接收較慢）就等，形成背壓，避免記憶體無限增加
                        }
                    }
                } catch (IOException | InterruptedException ignored) {
                } finally {
                    try {
                        while (!stop.get() && !queue.offer(EOF, 200, TimeUnit.MILLISECONDS)) {
                        }
                    } catch (InterruptedException ignored) {
                    }
                }
            }, "rg-stdout");
            reader.setDaemon(true);
            reader.start();

            long lastWrite = System.currentTimeMillis();
            while (true) {
                String line = queue.poll(150, TimeUnit.MILLISECONDS);
                long now = System.currentTimeMillis();
                if (line == null) {
                    if (!batch.isEmpty()) {
                        sse.send("hits", batch);
                        batch = new ArrayList<>();
                        lastFlush = lastWrite = now;
                    } else if (now - lastWrite >= 2000) {
                        sse.ping();               // 瀏覽器已關閉的話這裡會丟 IOException → 結束並 kill rg
                        lastWrite = now;
                    }
                    if (!activeSearches.containsKey(id)) break;   // 使用者按了取消
                    continue;
                }
                if (line == EOF) break;
                Map<?, ?> node;
                String type;
                Map<String, Object> hit = null;
                try {
                    node = (Map<?, ?>) Json.parse(line);
                    type = (String) node.get("type");
                    if ("match".equals(type) || "context".equals(type)) hit = toHit(type, (Map<?, ?>) node.get("data"));
                } catch (RuntimeException e) {
                    // 單行解析失敗就跳過，不讓整個搜尋中斷
                    if (badLines++ == 0) {
                        System.err.println("[search] 無法解析 rg 輸出: " + e + "\n  " + line.substring(0, Math.min(300, line.length())));
                    }
                    continue;
                }
                if (hit != null) {
                    batch.add(hit);
                    count++;
                    if ("match".equals(type)) matchLines++;
                    if (count >= maxResults) {
                        truncated = true;
                        break;
                    }
                } else if ("summary".equals(type)) {
                    summary = (Map<?, ?>) ((Map<?, ?>) node.get("data")).get("stats");
                }
                if (batch.size() >= 500 || (!batch.isEmpty() && now - lastFlush >= 150)) {
                    sse.send("hits", batch);
                    batch = new ArrayList<>();
                    lastFlush = lastWrite = now;
                }
            }
            stop.set(true);
            if (!batch.isEmpty()) sse.send("hits", batch);

            boolean cancelled = !activeSearches.containsKey(id);
            if (truncated || cancelled) kill(process);
            process.waitFor(5, TimeUnit.SECONDS);
            errReader.join(1000);
            int exit = process.isAlive() ? -1 : process.exitValue();

            Map<String, Object> done = new LinkedHashMap<>();
            done.put("count", count);
            done.put("matchLines", matchLines);
            done.put("truncated", truncated);
            done.put("cancelled", cancelled && !truncated);
            done.put("elapsedMs", System.currentTimeMillis() - t0);
            done.put("exitCode", exit);
            if (summary != null) {
                done.put("bytesSearched", summary.get("bytes_searched"));
                done.put("filesSearched", summary.get("searches"));
                done.put("filesMatched", summary.get("searches_with_match"));
            }
            if (badLines > 0) done.put("badLines", badLines);
            String err = stderr.toString().trim();
            // exit 2 = rg 錯誤（例如 regex 語法錯）；我們自己 kill 掉的不算
            if (exit == 2 && !truncated && !cancelled && !err.isEmpty() && count == 0) done.put("error", rgError(err));
            else if (!err.isEmpty()) done.put("warnings", err);
            sse.send("done", done);
        } catch (IllegalArgumentException e) {
            sse.trySend("fail", Map.of("message", e.getMessage()));
        } catch (IOException e) {
            // 瀏覽器斷線（關分頁、重新搜尋）會走到這裡，靜默結束並 kill rg
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            e.printStackTrace();
            sse.trySend("fail", Map.of("message", "後端錯誤 " + e.getClass().getSimpleName() + ": " + e.getMessage()));
        } finally {
            stop.set(true);
            activeSearches.remove(id);
            if (process != null) kill(process);
        }
    }

    /** 讀取執行緒結束時放進佇列的標記（用 == 比對，不會和 rg 的輸出混淆）。 */
    static final String EOF = new String("<EOF>");

    static boolean cancel(String id) {
        if (id == null) return false;
        Process p = activeSearches.remove(id);
        if (p == null) return false;
        kill(p);
        return true;
    }

    static void kill(Process p) {
        if (p.isAlive()) {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
        }
    }

    /** rg 的一筆 match / context 轉成前端格式。 */
    static Map<String, Object> toHit(String type, Map<?, ?> data) {
        String file = jsonText(data.get("path"));
        Object lineNo = data.get("line_number");
        Map<?, ?> lines = (Map<?, ?>) data.get("lines");

        byte[] bytes = null;
        String text;
        if (lines.containsKey("text")) {
            text = (String) lines.get("text");
        } else {
            bytes = Base64.getDecoder().decode((String) lines.get("bytes"));
            text = new String(bytes, StandardCharsets.UTF_8);
        }

        // rg 的 submatch start/end 是 UTF-8 byte offset，中文 log 要換算成字元位置
        List<int[]> ranges = new ArrayList<>();
        Object subsObj = data.get("submatches");
        if (subsObj instanceof List<?> subs && !subs.isEmpty()) {
            if (bytes == null) bytes = text.getBytes(StandardCharsets.UTF_8);
            boolean ascii = bytes.length == text.length();
            for (Object o : subs) {
                Map<?, ?> s = (Map<?, ?>) o;
                int bs = ((Number) s.get("start")).intValue();
                int be = ((Number) s.get("end")).intValue();
                ranges.add(new int[]{ascii ? bs : charIndex(bytes, bs), ascii ? be : charIndex(bytes, be)});
            }
        }

        int end = text.length();
        while (end > 0 && (text.charAt(end - 1) == '\n' || text.charAt(end - 1) == '\r')) end--;
        boolean cut = false;
        if (end > maxLineChars) {
            end = maxLineChars;
            cut = true;
        }
        if (end != text.length()) {
            text = text.substring(0, end);
            final int e = end;
            ranges.removeIf(r -> r[0] >= e);
            ranges.forEach(r -> r[1] = Math.min(r[1], e));
        }

        Map<String, Object> hit = new LinkedHashMap<>();
        hit.put("file", file);
        hit.put("line", lineNo);
        hit.put("kind", type);
        hit.put("text", text);
        List<List<Integer>> rs = new ArrayList<>();
        for (int[] r : ranges) rs.add(List.of(r[0], r[1]));
        hit.put("ranges", rs);
        if (cut) hit.put("cut", true);
        return hit;
    }

    static int charIndex(byte[] utf8, int byteOffset) {
        return new String(utf8, 0, Math.min(byteOffset, utf8.length), StandardCharsets.UTF_8).length();
    }

    static String jsonText(Object node) {
        if (!(node instanceof Map<?, ?> m)) return "";
        if (m.containsKey("text")) return (String) m.get("text");
        if (m.containsKey("bytes")) return new String(Base64.getDecoder().decode((String) m.get("bytes")), StandardCharsets.UTF_8);
        return "";
    }

    // ======================== 統計 ========================

    static final String LEVEL_RE = "^.{0,120}?\\b(TRACE|DEBUG|INFO|WARN|WARNING|ERROR|FATAL)\\b";
    static final String EXCEPTION_RE = "\\b(?:[A-Za-z_$][\\w$]*\\.)+[A-Z][\\w$]*(?:Exception|Error)\\b";
    // 支援 2026-11-17 16:29:01 / 2026-11-17T16:29:01 / 2026/11/17 16:29:01
    static final Pattern TS = Pattern.compile("(\\d{4})[-/](\\d{2})[-/](\\d{2})[ T](\\d{2}):(\\d{2})");

    /**
     * 同時跑 4 個 rg，在 Java 端彙總：
     *  levels     各 log level 行數（只看每行前 120 字元內第一個 level）
     *  exceptions Exception / Error 類別排行（前 30）
     *  timeline   每分鐘行數（有搜尋字串 → 命中行；沒有 → ERROR 行）
     *  files      各檔案命中行數（有搜尋字串時）
     */
    static Map<String, Object> stats(SearchRequest req) {
        long t0 = System.currentTimeMillis();
        Path path = resolve(req.path());
        Set<String> warns = ConcurrentHashMap.newKeySet();

        var levels = async(() -> {
            Map<String, Long> m = new HashMap<>();
            addWarnings(warns, runLines(base(req).arg("-o", "-r", "$1").build(LEVEL_RE, path),
                    l -> m.merge(l.equals("WARNING") ? "WARN" : l, 1L, Long::sum)));
            return m;
        });

        var exceptions = async(() -> {
            Map<String, Long> m = new HashMap<>();
            addWarnings(warns, runLines(base(req).arg("-o").build(EXCEPTION_RE, path), l -> m.merge(l, 1L, Long::sum)));
            return top(m, 30, "name");
        });

        var timeline = async(() -> {
            Map<String, Long> m = new TreeMap<>();
            // 命中行若是 stack trace（沒有時間戳），就用往上最近一行的時間戳：
            // 加 -B 50 取前文（stack trace 超過 50 行時，深處的命中行不計入），
            // 輸出格式為「行號:內容」(命中) 或「行號-內容」(前文)
            Rg rg = new Rg().files(req)
                    .arg("--no-filename", "-n", "-B", "50", "--max-columns", "250", "--max-columns-preview");
            RgCmd cmd = req.hasPattern()
                    ? rg.match(req).build(req.pattern(), path)
                    : rg.build("\\bERROR\\b", path);
            String[] lastTs = {null};
            addWarnings(warns, runLines(cmd, l -> {
                if (l.equals("--")) {     // 不同區段的分隔線，前一段的時間戳不能沿用
                    lastTs[0] = null;
                    return;
                }
                int sep = 0;
                while (sep < l.length() && Character.isDigit(l.charAt(sep))) sep++;
                if (sep == 0 || sep >= l.length()) return;
                boolean isMatch = l.charAt(sep) == ':';
                String text = l.substring(sep + 1);
                Matcher mt = TS.matcher(text);
                if (mt.find() && mt.start() < 200) {
                    lastTs[0] = mt.group(1) + "-" + mt.group(2) + "-" + mt.group(3) + " "
                            + mt.group(4) + ":" + mt.group(5);
                }
                if (isMatch && lastTs[0] != null) m.merge(lastTs[0], 1L, Long::sum);
            }));
            return m;
        });

        var files = async(() -> {
            if (!req.hasPattern()) return List.<Map<String, Object>>of();
            Map<String, Long> m = new HashMap<>();
            // -H 強制印檔名、--null 讓檔名後接 \0（Windows 的 C:\ 冒號才不會切錯）
            addWarnings(warns, runLines(new Rg().files(req).match(req).arg("-c", "-H", "--null").build(req.pattern(), path), l -> {
                int i = l.indexOf('\0');
                if (i > 0) m.put(l.substring(0, i), Long.parseLong(l.substring(i + 1).trim()));
            }));
            return top(m, 200, "file");
        });

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("levels", await(levels));
        out.put("exceptions", await(exceptions));
        out.put("timeline", await(timeline));
        out.put("timelineSource", req.hasPattern() ? "search" : "ERROR");
        out.put("files", await(files));
        out.put("warnings", List.copyOf(warns));
        out.put("elapsedMs", System.currentTimeMillis() - t0);
        return out;
    }

    static Rg base(SearchRequest req) {
        return new Rg().files(req).arg("--no-filename", "--no-line-number");
    }

    static List<Map<String, Object>> top(Map<String, Long> m, int n, String keyName) {
        return m.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(n)
                .map(e -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put(keyName, e.getKey());
                    row.put("count", e.getValue());
                    return row;
                })
                .toList();
    }

    interface IoTask<T> {
        T call() throws Exception;
    }

    static <T> CompletableFuture<T> async(IoTask<T> task) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return task.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, pool);
    }

    /** 執行 rg 並逐行處理輸出。regex 語法錯誤丟 IllegalArgumentException（400）；其他錯誤以字串回傳當警告。 */
    static String runLines(RgCmd cmd, Consumer<String> consumer) throws IOException, InterruptedException {
        Process p = cmd.start();
        StringBuilder err = new StringBuilder();
        Thread errReader = drain(p.getErrorStream(), err);
        boolean ok = false;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8), 1 << 16)) {
            String l;
            while ((l = br.readLine()) != null) consumer.accept(l);
            ok = true;
        } finally {
            // 正常讀完時先等 rg 自己結束：rg 關閉輸出後可能還沒真正結束，
            // 這時 kill 會把結束代碼從 2 蓋成 137，regex 錯誤就會被漏掉。只有發生例外時才立即 kill。
            if (!ok) kill(p);
        }
        if (!p.waitFor(30, TimeUnit.SECONDS)) kill(p);
        int exit = p.waitFor();
        errReader.join(1000);
        String e = err.toString().trim();
        if (exit == 2 && e.contains("regex parse error")) throw new IllegalArgumentException(rgError(e));
        return e;
    }

    /** 把多個 rg 的警告去重、最多保留 10 行。 */
    static void addWarnings(Set<String> sink, String err) {
        if (err == null || err.isBlank()) return;
        for (String l : err.split("\n")) {
            if (sink.size() >= 10) return;
            String t = l.trim();
            if (!t.isEmpty() && !t.matches("-+")) sink.add(t);   // 略過 rg 的分隔線
        }
    }

    /** CompletableFuture.join() 會把例外包成 CompletionException，拆開才能回傳正確的 400 / 訊息。 */
    static <T> T await(CompletableFuture<T> f) {
        try {
            return f.join();
        } catch (CompletionException e) {
            Throwable c = e.getCause();
            if (c instanceof RuntimeException r) throw r;
            throw new RuntimeException(c);
        }
    }

    static Thread drain(InputStream in, StringBuilder sink) {
        Thread t = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String l;
                while ((l = br.readLine()) != null) {
                    synchronized (sink) {
                        if (sink.length() < 8000) sink.append(l).append('\n');
                    }
                }
            } catch (IOException ignored) {
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    // ======================== 訊息排行 ========================

    static final Pattern LEVEL_P = Pattern.compile("^.{0,120}?\\b(TRACE|DEBUG|INFO|WARN|WARNING|ERROR|FATAL)\\b");
    static final int MAX_TEMPLATES = 100_000;

    /**
     * 把一行 log 轉成「訊息樣板」：
     *  1. 只保留 level ＋ 時間戳之後的內容（時間戳前面常是 IP、使用者、session 等請求資訊，會讓同一種訊息被拆散）
     *  2. 把會變動的部分換成代號：<UUID>、<IP>、<HEX>（12 位以上含數字的 hex，例如 session/trace id）、<時間>、<N>（數字）
     * 手寫單次掃描，比 regex 取代快很多（百萬行等級很重要）。
     */
    static String normalize(String level, String text) {
        Matcher tm = TS.matcher(text);
        int from = 0;
        if (tm.find() && tm.start() < 200) {
            from = tm.end();
            // 略過秒數與毫秒，例如 :52 或 :52.123 或 :52,123
            while (from < text.length() && (Character.isDigit(text.charAt(from)) || text.charAt(from) == ':'
                    || text.charAt(from) == '.' || text.charAt(from) == ',')) from++;
        }
        StringBuilder sb = new StringBuilder(text.length() - from + 8);
        // level 已經在時間戳之後（例如 Logback 預設格式）就不用再加
        int lvAt = text.indexOf(level, from);
        if (lvAt < 0 || lvAt - from > 40) sb.append(level).append(' ');
        int n = text.length();
        int i = from;
        while (i < n) {
            char ch = text.charAt(i);
            if (!isAlnum(ch) || (i > 0 && isAlnum(text.charAt(i - 1)) && i != from)) {
                sb.append(ch);
                i++;
                continue;
            }
            int j = i;
            boolean allDigit = true, allHex = true, hasDigit = false;
            while (j < n && isAlnum(text.charAt(j))) {
                char c = text.charAt(j);
                boolean d = c >= '0' && c <= '9';
                hasDigit |= d;
                allDigit &= d;
                allHex &= d || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
                j++;
            }
            int len = j - i;
            int end;
            if (allHex && len == 8 && (end = uuidEnd(text, j)) > 0) {
                sb.append("<UUID>");
                i = end;
            } else if (allDigit && len <= 3 && (end = ipEnd(text, i)) > 0) {
                sb.append("<IP>");
                i = end;
            } else if (allDigit && len == 4 && (end = tsEnd(text, i)) > 0) {
                sb.append("<時間>");
                i = end;
            } else if (allHex && hasDigit && len >= 12) {
                sb.append("<HEX>");
                i = j;
            } else if (allDigit) {
                sb.append("<N>");
                i = j;
            } else {
                // 一般單字：裡面的數字段換成 <N>（例如 exec23 → exec<N>）
                for (int k = i; k < j; ) {
                    char c = text.charAt(k);
                    if (c >= '0' && c <= '9') {
                        while (k < j && text.charAt(k) >= '0' && text.charAt(k) <= '9') k++;
                        sb.append("<N>");
                    } else {
                        sb.append(c);
                        k++;
                    }
                }
                i = j;
            }
        }
        return sb.toString().trim();
    }

    static boolean isAlnum(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    /** 8 位 hex 後面接 -4-4-4-12 → UUID 結尾位置，否則 -1。 */
    static int uuidEnd(String s, int j) {
        int[] parts = {4, 4, 4, 12};
        for (int p : parts) {
            if (j >= s.length() || s.charAt(j) != '-') return -1;
            j++;
            for (int k = 0; k < p; k++, j++) {
                if (j >= s.length()) return -1;
                char c = s.charAt(j);
                if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) return -1;
            }
        }
        return (j < s.length() && isAlnum(s.charAt(j))) ? -1 : j;
    }

    /** a.b.c.d(:port) → 結尾位置，否則 -1。 */
    static int ipEnd(String s, int i) {
        int j = i;
        for (int part = 0; part < 4; part++) {
            if (part > 0) {
                if (j >= s.length() || s.charAt(j) != '.') return -1;
                j++;
            }
            int st = j;
            while (j < s.length() && Character.isDigit(s.charAt(j)) && j - st < 3) j++;
            if (j == st) return -1;
        }
        if (j < s.length() && isAlnum(s.charAt(j))) return -1;
        if (j + 1 < s.length() && s.charAt(j) == ':' && Character.isDigit(s.charAt(j + 1))) {
            j++;
            while (j < s.length() && Character.isDigit(s.charAt(j))) j++;
        }
        return j;
    }

    /** yyyy-MM-dd HH:mm(:ss(.SSS)) → 結尾位置，否則 -1。 */
    static int tsEnd(String s, int i) {
        Matcher m = TS.matcher(s).region(i, Math.min(s.length(), i + 16));
        if (!m.lookingAt()) return -1;
        int j = m.end();
        while (j < s.length() && (Character.isDigit(s.charAt(j)) || s.charAt(j) == ':' || s.charAt(j) == '.' || s.charAt(j) == ',')) j++;
        return j;
    }

    static final class Tpl {
        long count;
        final String level, file, text;
        final long line;

        Tpl(String level, String file, long line, String text) {
            this.level = level;
            this.file = file;
            this.line = line;
            this.text = text;
        }
    }

    /**
     * 訊息排行：把每一行有 log level 的 log（stack trace 不算）正規化成「訊息樣板」後計數。
     * level=ALL/ERROR/WARN/INFO/DEBUG；有搜尋字串時只統計命中的行。
     */
    static Map<String, Object> messages(SearchRequest req, String level) {
        long t0 = System.currentTimeMillis();
        Path path = resolve(req.path());
        String lv = level.toUpperCase(java.util.Locale.ROOT);
        if (!lv.matches("ALL|TRACE|DEBUG|INFO|WARN|ERROR|FATAL")) throw new IllegalArgumentException("level 錯誤: " + level);

        Rg rg = new Rg().files(req).arg("-H", "-n", "--null", "--max-columns", "400", "--max-columns-preview");
        RgCmd cmd;
        if (req.hasPattern()) {
            cmd = rg.match(req).build(req.pattern(), path);
        } else if (lv.equals("ALL")) {
            cmd = rg.build(LEVEL_RE, path);
        } else {
            cmd = rg.build("^.{0,120}?\\b" + (lv.equals("WARN") ? "(WARN|WARNING)" : lv) + "\\b", path);
        }

        Map<String, Tpl> map = new HashMap<>();
        long[] total = {0}, overflow = {0};
        String warn;
        try {
            warn = runLines(cmd, l -> {
                int z = l.indexOf('\0');
                if (z < 0) return;
                int c = l.indexOf(':', z + 1);
                if (c < 0) return;
                String text = l.substring(c + 1);
                if (text.endsWith("[... omitted end of long line]")) {
                    text = text.substring(0, text.length() - "[... omitted end of long line]".length()).stripTrailing() + " …";
                }
                Matcher lm = LEVEL_P.matcher(text);
                if (!lm.find()) return;                       // stack trace 等沒有 level 的行
                String found = lm.group(1).equals("WARNING") ? "WARN" : lm.group(1);
                if (!lv.equals("ALL") && !lv.equals(found)) return;
                total[0]++;
                String key = normalize(found, text);
                Tpl t = map.get(key);
                if (t == null) {
                    if (map.size() >= MAX_TEMPLATES) {
                        overflow[0]++;
                        return;
                    }
                    long lineNo;
                    try {
                        lineNo = Long.parseLong(l.substring(z + 1, c));
                    } catch (NumberFormatException e) {
                        lineNo = 0;
                    }
                    t = new Tpl(found, l.substring(0, z), lineNo, text);
                    map.put(key, t);
                }
                t.count++;
            });
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }

        List<Map<String, Object>> top = map.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().count, a.getValue().count))
                .limit(200)
                .map(e -> {
                    Tpl t = e.getValue();
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("template", e.getKey().length() > 600 ? e.getKey().substring(0, 600) + " …" : e.getKey());
                    row.put("count", t.count);
                    row.put("level", t.level);
                    row.put("file", t.file);
                    row.put("line", t.line);
                    row.put("example", t.text.length() > 600 ? t.text.substring(0, 600) + " …" : t.text);
                    return row;
                })
                .toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("level", lv);
        out.put("total", total[0]);
        out.put("distinct", map.size());
        out.put("overflow", overflow[0]);
        out.put("top", top);
        Set<String> warns = new java.util.LinkedHashSet<>();
        addWarnings(warns, warn);
        out.put("warnings", List.copyOf(warns));
        out.put("elapsedMs", System.currentTimeMillis() - t0);
        return out;
    }

    // ======================== 前後文 ========================

    /** 讀檔案中某行的前後 radius 行（.gz 自動解壓）。 */
    static Map<String, Object> context(Map<String, String> q) throws IOException {
        Path p = resolve(q.get("file"));
        if (!Files.isRegularFile(p)) throw new IllegalArgumentException("不是檔案: " + q.get("file"));
        long target = Math.max(1, numParam(q, "line", 1));
        int radius = (int) Math.max(1, Math.min(500, numParam(q, "radius", 50)));
        long from = Math.max(1, target - radius), to = target + radius;

        List<Map<String, Object>> lines = new ArrayList<>();
        // 外層 try 確保檔案一定會關閉（GZIPInputStream 建立失敗時也一樣），Windows 上才不會鎖住檔案
        try (InputStream raw = Files.newInputStream(p)) {
            InputStream in = raw;
            if (p.getFileName().toString().endsWith(".gz")) {
                try {
                    in = new GZIPInputStream(raw, 1 << 16);
                } catch (IOException e) {
                    throw new IllegalArgumentException("「" + p.getFileName() + "」不是有效的 gzip 壓縮檔");
                }
            }
            readRange(in, from, to, lines);
        }
        return Map.of("file", p.toString(), "target", target, "lines", lines);
    }

    static void readRange(InputStream in, long from, long to, List<Map<String, Object>> lines) throws IOException {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16)) {
            String l;
            long n = 0;
            while ((l = br.readLine()) != null) {
                if (++n < from) continue;
                if (n > to) break;
                if (l.length() > maxLineChars) l = l.substring(0, maxLineChars) + " …";
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("n", n);
                row.put("text", l);
                lines.add(row);
            }
        }
    }

    // ======================== HTTP 工具 ========================

    static final class Sse {
        final OutputStream out;

        Sse(OutputStream out) {
            this.out = out;
        }

        void send(String event, Object data) throws IOException {
            String msg = "event: " + event + "\ndata: " + Json.write(data) + "\n\n";
            out.write(msg.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        /** SSE 註解行，瀏覽器會忽略；用來偵測連線是否還在。 */
        void ping() throws IOException {
            out.write(": ping\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        void trySend(String event, Object data) {
            try {
                send(event, data);
            } catch (IOException ignored) {
            }
        }
    }

    static Map<String, String> parseQuery(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null || raw.isEmpty()) return m;
        for (String part : raw.split("&")) {
            String[] kv = part.split("=", 2);
            m.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                    kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        return m;
    }

    static void sendJson(HttpExchange ex, int status, Object body) throws IOException {
        byte[] b = Json.write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        if ("HEAD".equals(ex.getRequestMethod())) {   // HEAD 回應不能有 body，否則 JDK HttpServer 會丟例外
            ex.sendResponseHeaders(status, -1);
            return;
        }
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
    }

    static void serveFile(HttpExchange ex, Path file, String type) throws IOException {
        if (!Files.isRegularFile(file)) {
            sendJson(ex, 404, Map.of("message", "找不到 UI 檔案: " + file.toAbsolutePath()));
            return;
        }
        byte[] b = Files.readAllBytes(file);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(200, b.length);
        ex.getResponseBody().write(b);
    }

    // ======================== 自我測試（java src/LogViewer.java --selftest） ========================

    /**
     * 不需要 JUnit、不需要啟動伺服器的自我測試。修改程式後執行一次，確認核心邏輯沒被改壞：
     * Host 檢查、訊息正規化（含 UUID / IP / hex id）、中文位元組位置換算、JSON 解析、錯誤訊息。
     * 前端的 ID 偵測（追蹤 traceId / sessionId）請開 http://127.0.0.1:8099/?selftest 檢查。
     */
    static final class SelfTest {
        static int pass = 0, fail = 0;

        static void check(String name, Object actual, Object expected) {
            if (java.util.Objects.equals(actual, expected)) {
                pass++;
            } else {
                fail++;
                System.out.println("✗ " + name + "\n    預期: " + expected + "\n    實際: " + actual);
            }
        }

        static void throwsIae(String name, Runnable r, String msgPart) {
            try {
                r.run();
                fail++;
                System.out.println("✗ " + name + "：應該丟出 IllegalArgumentException");
            } catch (IllegalArgumentException e) {
                check(name, e.getMessage().contains(msgPart), true);
            }
        }

        static int run() {
            // Host 檢查（防 DNS rebinding）
            check("Host 127.0.0.1", isAllowedHost("127.0.0.1:8099", 8099), true);
            check("Host localhost", isAllowedHost("localhost:8099", 8099), true);
            check("Host 大小寫", isAllowedHost("LocalHost:8099", 8099), true);
            check("Host 外部網域", isAllowedHost("evil.example.com:8099", 8099), false);
            check("Host 不同 port", isAllowedHost("127.0.0.1:8100", 8099), false);
            check("Host 沒有 port", isAllowedHost("127.0.0.1", 8099), false);
            check("Host 空值", isAllowedHost(null, 8099), false);
            Locale prev = Locale.getDefault();
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));       // 土耳其語系下大寫 I 的轉換
            check("Host 土耳其語系", isAllowedHost("LOCALHOST:8099", 8099), true);
            Locale.setDefault(prev);

            // 訊息正規化：gate_web 格式（時間戳前的 IP / 帳號 / session 不列入）
            check("正規化 gate_web addSession",
                    normalize("INFO", "INFO ||||https-jsse-nio-443-exec-89|2026-11-10 00:00:06|com.example.ws.SessionManager|SessionManager.java|addSession|185|addSession accepted |123e4567-e89b-12d3-a456-426614174000 session=00112233-4455-6677-8899-aabbccddeeff@192.0.2.10"),
                    "INFO |com.example.ws.SessionManager|SessionManager.java|addSession|<N>|addSession accepted |<UUID> session=<UUID>@<IP>");
            check("正規化 不同使用者歸成同一類",
                    normalize("ERROR", "ERROR|192.0.2.1|demo_user_a|3F2A9C7D1E4B5A6C8D0E1F2A3B4C5D6E|exec-1|2026-11-10 00:02:52|C|C.java|m|349|Wrong Key!!dce"),
                    normalize("ERROR", "ERROR|198.51.100.7|demo_user_b|9B8A7C6D5E4F3A2B1C0D9E8F7A6B5C4D|exec-9|2026-11-10 10:11:12|C|C.java|m|349|Wrong Key!!dce"));
            // Logback 格式：level 在時間戳之後，不重複加；traceId 的 hex 變成 <HEX>
            check("正規化 Logback + traceId",
                    normalize("ERROR", "2026-11-17 16:29:01.123 ERROR [http-nio-8080-exec-5] [traceId=f2ee4e4519f9919c] c.r.Foo - 處理訂單 orderId=123 失敗"),
                    "ERROR [http-nio-<N>-exec-<N>] [traceId=<HEX>] c.r.Foo - 處理訂單 orderId=<N> 失敗");

            // rg 回傳的是 UTF-8 位元組位置，中文要換算成字元位置
            check("中文位元組位置換算", charIndex("中文abc".getBytes(StandardCharsets.UTF_8), 6), 2);

            // 迷你 JSON 解析
            Object j = Json.parse("{\"a\":[1,2.5,\"x\\u4e2d\"],\"b\":null,\"c\":true}");
            check("JSON 解析", Json.write(j), "{\"a\":[1,2.5,\"x中\"],\"b\":null,\"c\":true}");

            // 錯誤訊息為中文
            check("regex 錯誤訊息", rgError("regex parse error: unclosed group").contains("regex 語法錯誤"), true);
            throwsIae("數字參數格式錯誤", () -> numParam(Map.of("line", "abc"), "line", 1), "必須是數字");

            System.out.println((fail == 0 ? "✓ 全部通過" : "✗ 有失敗") + "：通過 " + pass + " 項，失敗 " + fail + " 項");
            return fail == 0 ? 0 : 1;
        }
    }

    // ======================== 迷你 JSON（避免外部依賴） ========================

    static final class Json {
        private final String s;
        private int i;

        private Json(String s) {
            this.s = s;
        }

        static Object parse(String s) {
            Json j = new Json(s);
            j.ws();
            return j.value();
        }

        private Object value() {
            char c = s.charAt(i);
            switch (c) {
                case '{': return object();
                case '[': return array();
                case '"': return string();
                case 't': i += 4; return Boolean.TRUE;
                case 'f': i += 5; return Boolean.FALSE;
                case 'n': i += 4; return null;
                default: return number();
            }
        }

        private Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            ws();
            if (s.charAt(i) == '}') {
                i++;
                return m;
            }
            while (true) {
                ws();
                String k = string();
                ws();
                i++; // :
                ws();
                m.put(k, value());
                ws();
                char c = s.charAt(i++);
                if (c == '}') return m;
            }
        }

        private List<Object> array() {
            List<Object> l = new ArrayList<>();
            i++;
            ws();
            if (s.charAt(i) == ']') {
                i++;
                return l;
            }
            while (true) {
                ws();
                l.add(value());
                ws();
                char c = s.charAt(i++);
                if (c == ']') return l;
            }
        }

        private String string() {
            StringBuilder sb = new StringBuilder();
            i++; // opening quote
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                char e = s.charAt(i++);
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> sb.append(e); // \" \\ \/
                }
            }
        }

        private Number number() {
            int st = i;
            boolean dbl = false;
            while (i < s.length()) {
                char c = s.charAt(i);
                if ((c >= '0' && c <= '9') || c == '-' || c == '+') i++;
                else if (c == '.' || c == 'e' || c == 'E') {
                    dbl = true;
                    i++;
                } else break;
            }
            String n = s.substring(st, i);
            return dbl ? (Number) Double.parseDouble(n) : (Number) Long.parseLong(n);
        }

        private void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        static String write(Object o) {
            StringBuilder sb = new StringBuilder();
            write(o, sb);
            return sb.toString();
        }

        private static void write(Object o, StringBuilder sb) {
            if (o == null) sb.append("null");
            else if (o instanceof String str) quote(str, sb);
            else if (o instanceof Number || o instanceof Boolean) sb.append(o);
            else if (o instanceof Map<?, ?> m) {
                sb.append('{');
                boolean first = true;
                for (var e : m.entrySet()) {
                    if (!first) sb.append(',');
                    first = false;
                    quote(String.valueOf(e.getKey()), sb);
                    sb.append(':');
                    write(e.getValue(), sb);
                }
                sb.append('}');
            } else if (o instanceof Iterable<?> it) {
                sb.append('[');
                boolean first = true;
                for (Object x : it) {
                    if (!first) sb.append(',');
                    first = false;
                    write(x, sb);
                }
                sb.append(']');
            } else quote(o.toString(), sb);
        }

        private static void quote(String str, StringBuilder sb) {
            sb.append('"');
            for (int k = 0; k < str.length(); k++) {
                char c = str.charAt(k);
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> {
                        if (c < 0x20 || c == 0x2028 || c == 0x2029) sb.append(String.format("\\u%04x", (int) c));
                        else sb.append(c);
                    }
                }
            }
            sb.append('"');
        }
    }
}
