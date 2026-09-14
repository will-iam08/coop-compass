package com.coopcompass;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

public final class ApplicationServer {
    private final ApplicationRepository repository;
    private final Path publicDirectory;

    private ApplicationServer(ApplicationRepository repository, Path publicDirectory) {
        this.repository = repository;
        this.publicDirectory = publicDirectory;
    }

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        ApplicationRepository repository = new ApplicationRepository(Path.of("data", "applications.tsv"));
        ApplicationServer app = new ApplicationServer(repository, Path.of("src", "main", "resources", "public").toAbsolutePath());
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", port), 0);
        server.createContext("/api/applications", app::applications);
        server.createContext("/api/recently-deleted", app::recentlyDeleted);
        server.createContext("/api/dashboard", app::dashboard);
        server.createContext("/", app::staticFile);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        System.out.println("My Internship Notebook is running at http://localhost:" + port);
    }

    private void applications(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            if ("GET".equals(method) && "/api/applications".equals(path)) {
                respondJson(exchange, 200, "[" + repository.list().stream().map(this::applicationJson).reduce((left, right) -> left + "," + right).orElse("") + "]");
                return;
            }
            if ("POST".equals(method) && "/api/applications".equals(path)) {
                Application created = repository.create(payload(readBody(exchange)));
                respondJson(exchange, 201, applicationJson(created));
                return;
            }
            long id = idFrom(path);
            if ("PATCH".equals(method) && id > 0) {
                String status = Json.string(readBody(exchange), "status").orElseThrow(() -> new IllegalArgumentException("status is required."));
                Application application = repository.updateStatus(id, status).orElseThrow(() -> new NotFoundException("Application not found."));
                respondJson(exchange, 200, applicationJson(application));
                return;
            }
            if ("DELETE".equals(method) && id > 0) {
                if (!repository.delete(id)) throw new NotFoundException("Application not found.");
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            respondJson(exchange, 405, error("Method or endpoint not supported."));
        } catch (NotFoundException exception) {
            respondJson(exchange, 404, error(exception.getMessage()));
        } catch (IllegalArgumentException exception) {
            respondJson(exchange, 400, error(exception.getMessage()));
        } catch (Exception exception) {
            exception.printStackTrace();
            respondJson(exchange, 500, error("Something went wrong. Try again."));
        }
    }

    private void dashboard(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            respondJson(exchange, 405, error("Method not supported."));
            return;
        }
        ApplicationRepository.Dashboard dashboard = repository.dashboard();
        String statuses = dashboard.byStatus().entrySet().stream()
                .map(entry -> "\"" + entry.getKey().name() + "\":" + entry.getValue())
                .reduce((left, right) -> left + "," + right).orElse("");
        respondJson(exchange, 200, "{\"total\":" + dashboard.total() + ",\"responseRate\":" + dashboard.responseRate() + ",\"byStatus\":{" + statuses + "}}");
    }

    private void recentlyDeleted(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            if ("GET".equals(method) && "/api/recently-deleted".equals(path)) {
                String applications = repository.recentlyDeleted().stream()
                        .map(this::deletedApplicationJson)
                        .reduce((left, right) -> left + "," + right)
                        .orElse("");
                respondJson(exchange, 200, "[" + applications + "]");
                return;
            }

            String restoreSuffix = "/restore";
            if ("POST".equals(method) && path.endsWith(restoreSuffix)) {
                long id = idFrom(path.substring(0, path.length() - restoreSuffix.length()), "/api/recently-deleted/");
                if (id <= 0) {
                    respondJson(exchange, 405, error("Method or endpoint not supported."));
                    return;
                }
                Application restored = repository.restore(id).orElseThrow(() -> new NotFoundException("Recently deleted application not found."));
                respondJson(exchange, 200, applicationJson(restored));
                return;
            }

            long id = idFrom(path, "/api/recently-deleted/");
            if ("DELETE".equals(method) && id > 0) {
                if (!repository.permanentlyDelete(id)) throw new NotFoundException("Recently deleted application not found.");
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            respondJson(exchange, 405, error("Method or endpoint not supported."));
        } catch (NotFoundException exception) {
            respondJson(exchange, 404, error(exception.getMessage()));
        } catch (IllegalArgumentException exception) {
            respondJson(exchange, 400, error(exception.getMessage()));
        } catch (Exception exception) {
            exception.printStackTrace();
            respondJson(exchange, 500, error("Something went wrong. Try again."));
        }
    }

    private void staticFile(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        String requested = exchange.getRequestURI().getPath();
        String relative = requested.equals("/") ? "index.html" : requested.substring(1);
        Path file = publicDirectory.resolve(relative).normalize();
        if (!file.startsWith(publicDirectory) || !Files.isRegularFile(file)) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }
        byte[] content = Files.readAllBytes(file);
        exchange.getResponseHeaders().set("Content-Type", contentType(file));
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, content.length);
        exchange.getResponseBody().write(content);
        exchange.close();
    }

    private Map<String, String> payload(String body) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String field : new String[]{"company", "role", "location", "source", "status", "deadline", "notes", "skills"}) {
            Json.string(body, field).ifPresent(value -> values.put(field, value));
        }
        return values;
    }

    private long idFrom(String path) {
        return idFrom(path, "/api/applications/");
    }

    private long idFrom(String path, String prefix) {
        if (!path.startsWith(prefix)) return -1;
        try { return Long.parseLong(path.substring(prefix.length())); }
        catch (NumberFormatException exception) { return -1; }
    }

    private String applicationJson(Application application) {
        return applicationJson(application, null);
    }

    private String deletedApplicationJson(ApplicationRepository.DeletedApplication deleted) {
        return applicationJson(deleted.application(), deleted.deletedAt());
    }

    private String applicationJson(Application application, java.time.Instant deletedAt) {
        return "{\"id\":" + application.id()
                + ",\"company\":\"" + Json.escape(application.company()) + "\""
                + ",\"role\":\"" + Json.escape(application.role()) + "\""
                + ",\"location\":\"" + Json.escape(application.location()) + "\""
                + ",\"source\":\"" + Json.escape(application.source()) + "\""
                + ",\"status\":\"" + application.status().name() + "\""
                + ",\"deadline\":\"" + application.deadline() + "\""
                + ",\"notes\":\"" + Json.escape(application.notes()) + "\""
                + ",\"skills\":[" + application.skills().stream().map(skill -> "\"" + Json.escape(skill) + "\"").reduce((left, right) -> left + "," + right).orElse("") + "]"
                + ",\"createdAt\":\"" + DateTimeFormatter.ISO_INSTANT.format(application.createdAt()) + "\""
                + (deletedAt == null ? "" : ",\"deletedAt\":\"" + DateTimeFormatter.ISO_INSTANT.format(deletedAt) + "\"")
                + "}";
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void respondJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] content = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, content.length);
        exchange.getResponseBody().write(content);
        exchange.close();
    }

    private static String error(String message) { return "{\"error\":\"" + Json.escape(message) + "\"}"; }

    private static String contentType(Path file) {
        return switch (file.getFileName().toString().substring(file.getFileName().toString().lastIndexOf('.') + 1)) {
            case "css" -> "text/css; charset=utf-8";
            case "js" -> "application/javascript; charset=utf-8";
            case "svg" -> "image/svg+xml";
            default -> "text/html; charset=utf-8";
        };
    }

    private static final class NotFoundException extends RuntimeException {
        private NotFoundException(String message) { super(message); }
    }
}
