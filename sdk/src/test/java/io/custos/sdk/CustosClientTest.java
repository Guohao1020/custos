package io.custos.sdk;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CustosClientTest {

    static HttpServer server;
    static AtomicReference<String> lastBody = new AtomicReference<>();
    static AtomicReference<String> lastAuth = new AtomicReference<>();

    @BeforeAll
    static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/query_db", ex -> {
            lastBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = "{\"allowed\":true}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.createContext("/operator/status", ex -> {
            lastAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            byte[] out = "{\"sealed\":true}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
    }

    @AfterAll
    static void stop() { server.stop(0); }

    private CustosClient client() {
        return new CustosClient("http://127.0.0.1:" + server.getAddress().getPort(), "tok");
    }

    @Test
    void queryDbPostsJsonShape() {
        assertEquals("{\"allowed\":true}", client().queryDb("db/q", "appdb", "SELECT 1", "jwt"));
        assertTrue(lastBody.get().contains("\"tool\":\"db/q\""));
        assertTrue(lastBody.get().contains("\"userToken\":\"jwt\""));
    }

    @Test
    void statusSendsBearer() {
        assertEquals("{\"sealed\":true}", client().operatorStatus());
        assertEquals("Bearer tok", lastAuth.get());
    }
}
