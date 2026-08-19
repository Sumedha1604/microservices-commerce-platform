package com.sumedha.commerce.checkout.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;

final class DownstreamClientTestServer implements AutoCloseable {

    private final HttpServer server;
    private final Deque<Response> responses = new ArrayDeque<>();
    private Request lastRequest;

    DownstreamClientTestServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    void respond(int status, String body) {
        responses.add(new Response(status, body));
    }

    Request lastRequest() {
        return lastRequest;
    }

    private void handle(HttpExchange exchange) throws IOException {
        lastRequest = new Request(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        Response response = responses.removeFirst();
        byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(response.status(), body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    record Request(String method, String path, String body) {
    }

    private record Response(int status, String body) {
    }
}
