package com.eventpulse;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class Main {
    public static void main(String[] args) throws Exception {
        int port = 8086;
        BoxOfficeStore store = new BoxOfficeStore("eventpulse_boxoffice.db");
        AtomicBoolean running = new AtomicBoolean(true);

        // Background Virtual Thread TTL Hold Reaper (releases abandoned seats every 1s)
        Thread reaper = Thread.ofVirtual().start(() -> {
            while (running.get()) {
                try {
                    store.reclaimExpiredHolds();
                    Thread.sleep(1000L);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } catch (Exception ignored) {
                }
            }
        });

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        server.createContext("/", exchange -> {
            String html = Files.readString(Path.of("web/index.html"), StandardCharsets.UTF_8);
            sendResponse(exchange, 200, "text/html; charset=UTF-8", html);
        });

        server.createContext("/api/state", exchange -> {
            try {
                sendResponse(exchange, 200, "application/json", store.getDashboardStateJson());
            } catch (Exception e) {
                sendResponse(exchange, 500, "application/json", "{\"ok\":false,\"error\":\"" + JsonUtil.escape(e.getMessage()) + "\"}");
            }
        });

        server.createContext("/api/hold", exchange -> {
            try {
                Map<String, String> body = parseBody(exchange);
                String seat = body.getOrDefault("seatCode", "");
                String holder = body.getOrDefault("holderName", "Live Buyer");
                String token = body.getOrDefault("sessionToken", "");
                String res = store.holdSeat(seat, holder, token, 90_000L);
                sendResponse(exchange, res.contains("\"ok\":true") ? 200 : 409, "application/json", res);
            } catch (Exception e) {
                sendResponse(exchange, 400, "application/json", "{\"ok\":false,\"error\":\"" + JsonUtil.escape(e.getMessage()) + "\"}");
            }
        });

        server.createContext("/api/release", exchange -> {
            try {
                Map<String, String> body = parseBody(exchange);
                String seat = body.getOrDefault("seatCode", "");
                String token = body.getOrDefault("sessionToken", "");
                String res = store.releaseSeat(seat, token);
                sendResponse(exchange, res.contains("\"ok\":true") ? 200 : 400, "application/json", res);
            } catch (Exception e) {
                sendResponse(exchange, 400, "application/json", "{\"ok\":false,\"error\":\"" + JsonUtil.escape(e.getMessage()) + "\"}");
            }
        });

        server.createContext("/api/confirm", exchange -> {
            try {
                Map<String, String> body = parseBody(exchange);
                String token = body.getOrDefault("sessionToken", "");
                String name = body.getOrDefault("buyerName", "Verified Fan");
                String email = body.getOrDefault("buyerEmail", "fan@eventpulse.io");
                String res = store.confirmBooking(token, name, email);
                sendResponse(exchange, res.contains("\"ok\":true") ? 200 : 409, "application/json", res);
            } catch (Exception e) {
                sendResponse(exchange, 400, "application/json", "{\"ok\":false,\"error\":\"" + JsonUtil.escape(e.getMessage()) + "\"}");
            }
        });

        server.createContext("/api/simulate-rush", exchange -> {
            try {
                String res = store.simulateFlashSaleRush();
                sendResponse(exchange, res.contains("\"ok\":true") ? 200 : 400, "application/json", res);
            } catch (Exception e) {
                sendResponse(exchange, 500, "application/json", "{\"ok\":false,\"error\":\"" + JsonUtil.escape(e.getMessage()) + "\"}");
            }
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            running.set(false);
            server.stop(0);
            try {
                reaper.join(300L);
                store.close();
            } catch (Exception ignored) {
            }
        }));

        server.start();
        System.out.println("EventPulse Flash-Sale Seat-Locking Box Office running at http://127.0.0.1:" + port);
    }

    private static Map<String, String> parseBody(HttpExchange exchange) throws IOException {
        String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        return JsonUtil.parseObject(raw);
    }

    private static void sendResponse(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
