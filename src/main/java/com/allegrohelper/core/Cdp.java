package com.allegrohelper.core;

import com.allegrohelper.util.Json;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Minimal Chrome DevTools Protocol client, built on the JDK's own HTTP and
 * WebSocket clients — no libraries, in keeping with the zero-dependency rule
 * (Chrome itself is an external program, like tesseract).
 *
 * <p>Chrome only exposes the protocol when started with
 * {@code --remote-debugging-port}, and ignores that flag when an instance is
 * already running on the same profile. So the app drives a Chrome instance on
 * its own dedicated profile directory: with {@code --remote-debugging-port=0}
 * Chrome picks a free port and writes it to {@code DevToolsActivePort} inside
 * the profile, which is also how a still-running instance is found and reused.
 * The file survives Chrome's exit, so it is only trusted after a successful
 * probe of {@code /json/version}.
 */
public final class Cdp implements AutoCloseable {

    /** Chrome binaries tried on the PATH when {@code CHROME_BIN} is not set. */
    private static final String[] CHROME_CANDIDATES = {
            "google-chrome", "google-chrome-stable", "chromium", "chromium-browser"};

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(30);
    private static final int LAUNCH_WAIT_SECONDS = 20;

    private final java.net.http.WebSocket ws;
    private final AtomicLong nextId = new AtomicLong();
    private final ConcurrentHashMap<Long, CompletableFuture<Map<String, Object>>> pending =
            new ConcurrentHashMap<>();

    private Cdp(java.net.http.WebSocket ws) {
        this.ws = ws;
    }

    // --------------------------------------------------------- Chrome lifecycle

    /**
     * Returns the debug port of a Chrome instance on the given profile,
     * reusing a running one or launching a new one.
     */
    public static int ensureChrome(Path profileDir, String chromeBin, Reporter reporter)
            throws IOException {
        Path portFile = profileDir.resolve("DevToolsActivePort");
        Integer port = readPortFile(portFile);
        if (port != null && probe(port)) {
            return port;
        }

        String binary = findChrome(chromeBin);
        Files.createDirectories(profileDir);
        Files.deleteIfExists(portFile);
        reporter.log("Starting " + binary + " (profile: " + profileDir + ")…");
        new ProcessBuilder(binary,
                "--user-data-dir=" + profileDir,
                "--remote-debugging-port=0",
                "--no-first-run",
                "--no-default-browser-check")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectErrorStream(true)
                .start();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(LAUNCH_WAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            port = readPortFile(portFile);
            if (port != null && probe(port)) {
                return port;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for Chrome to start.");
            }
        }
        throw new IOException("Chrome did not open its DevTools port within "
                + LAUNCH_WAIT_SECONDS + "s (profile: " + profileDir + ").");
    }

    private static String findChrome(String chromeBin) throws IOException {
        if (chromeBin != null && !chromeBin.isBlank()) {
            return chromeBin;
        }
        String pathVar = System.getenv().getOrDefault("PATH", "");
        for (String candidate : CHROME_CANDIDATES) {
            for (String dir : pathVar.split(":")) {
                if (!dir.isBlank() && Files.isExecutable(Path.of(dir, candidate))) {
                    return candidate;
                }
            }
        }
        throw new IOException("Could not find Google Chrome or Chromium on the PATH. "
                + "Install it or set CHROME_BIN=/path/to/chrome.");
    }

    private static Integer readPortFile(Path portFile) {
        try {
            String first = Files.readAllLines(portFile, StandardCharsets.UTF_8).get(0).strip();
            return Integer.parseInt(first);
        } catch (Exception e) {
            return null; // missing or malformed: treat as "no Chrome running"
        }
    }

    private static boolean probe(int port) {
        try {
            httpJson("GET", port, "/json/version");
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Opens {@code url} in a new tab and returns the tab's WebSocket debugger
     * URL. Newer Chrome requires PUT for {@code /json/new}; older accepted GET.
     */
    public static String openTab(int port, String url) throws IOException {
        String endpoint = "/json/new?" + url;
        Map<String, Object> tab;
        try {
            tab = httpJson("PUT", port, endpoint);
        } catch (IOException e) {
            tab = httpJson("GET", port, endpoint);
        }
        Object wsUrl = tab.get("webSocketDebuggerUrl");
        if (wsUrl == null) {
            throw new IOException("Chrome did not report a WebSocket debugger URL for the new tab.");
        }
        return wsUrl.toString();
    }

    private static Map<String, Object> httpJson(String method, int port, String path)
            throws IOException {
        HttpClient client = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .timeout(HTTP_TIMEOUT)
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
        try {
            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IOException("Chrome returned HTTP " + response.statusCode()
                        + " for " + method + " " + path);
            }
            return Json.parseObject(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while talking to Chrome.");
        }
    }

    // ------------------------------------------------------------------ session

    /** Connects to a tab's WebSocket debugger URL. */
    public static Cdp connect(String wsUrl) throws IOException {
        CompletableFuture<Cdp> ready = new CompletableFuture<>();
        java.net.http.WebSocket.Listener listener = new java.net.http.WebSocket.Listener() {
            private final StringBuilder buffer = new StringBuilder();

            @Override
            public CompletionStage<?> onText(java.net.http.WebSocket ws, CharSequence data,
                                             boolean last) {
                buffer.append(data);
                if (last) {
                    String message = buffer.toString();
                    buffer.setLength(0);
                    ready.join().handleMessage(message);
                }
                ws.request(1);
                return null;
            }
        };
        try {
            java.net.http.WebSocket ws = HttpClient.newHttpClient()
                    .newWebSocketBuilder()
                    .connectTimeout(HTTP_TIMEOUT)
                    .buildAsync(URI.create(wsUrl), listener)
                    .get(HTTP_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            Cdp cdp = new Cdp(ws);
            ready.complete(cdp);
            return cdp;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while connecting to Chrome.");
        } catch (ExecutionException | TimeoutException e) {
            throw new IOException("Could not connect to " + wsUrl + ": " + e.getMessage(), e);
        }
    }

    private void handleMessage(String message) {
        Map<String, Object> msg;
        try {
            msg = Json.parseObject(message);
        } catch (RuntimeException e) {
            return; // not for us; CDP events without ids are ignored anyway
        }
        Object id = msg.get("id");
        if (id instanceof Number n) {
            CompletableFuture<Map<String, Object>> future = pending.remove(n.longValue());
            if (future != null) {
                future.complete(msg);
            }
        }
    }

    /** Sends a CDP command and returns its {@code result}, or throws on error. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> send(String method, Map<String, Object> params) throws IOException {
        long id = nextId.incrementAndGet();
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        pending.put(id, future);
        String message = Json.write(Map.of("id", id, "method", method, "params", params), false);
        try {
            ws.sendText(message, true).get(COMMAND_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            Map<String, Object> response =
                    future.get(COMMAND_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            if (response.get("error") instanceof Map<?, ?> error) {
                throw new IOException(method + " failed: " + error.get("message"));
            }
            Object result = response.get("result");
            return result instanceof Map ? (Map<String, Object>) result : Map.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for " + method + ".");
        } catch (ExecutionException | TimeoutException e) {
            throw new IOException(method + " failed: " + e.getMessage(), e);
        } finally {
            pending.remove(id);
        }
    }

    /**
     * Evaluates a JavaScript expression in the page and returns its value
     * (a JSON-compatible type), or throws when the expression itself threw.
     */
    public Object eval(String expression) throws IOException {
        Map<String, Object> result = send("Runtime.evaluate",
                Map.of("expression", expression, "returnByValue", true));
        if (result.get("exceptionDetails") instanceof Map<?, ?> details) {
            throw new IOException("JavaScript failed: " + details.get("text"));
        }
        Object inner = result.get("result");
        return inner instanceof Map<?, ?> m ? m.get("value") : null;
    }

    /** Node id of the first match for the CSS selector, or 0 when absent. */
    public int querySelector(String selector) throws IOException {
        // Just after navigation the document node can still be swapped out
        // between getDocument and querySelector ("Could not find node with
        // given id"), so retry the pair on a fresh document.
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            Map<String, Object> doc = send("DOM.getDocument", Map.of("depth", 1));
            Object root = doc.get("root");
            if (!(root instanceof Map<?, ?> rootMap)
                    || !(rootMap.get("nodeId") instanceof Number rootId)) {
                throw new IOException("DOM.getDocument returned no root node.");
            }
            try {
                Map<String, Object> match = send("DOM.querySelector",
                        Map.of("nodeId", rootId.intValue(), "selector", selector));
                return match.get("nodeId") instanceof Number n ? n.intValue() : 0;
            } catch (IOException e) {
                last = e;
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while querying the DOM.");
                }
            }
        }
        throw last;
    }

    /** Sets the files of an {@code <input type="file">} — CDP's replacement for the file dialog. */
    public void setFileInputFiles(int nodeId, List<String> files) throws IOException {
        send("DOM.setFileInputFiles", Map.of("nodeId", nodeId, "files", files));
    }

    @Override
    public void close() {
        ws.sendClose(java.net.http.WebSocket.NORMAL_CLOSURE, "done");
        ws.abort();
    }
}
