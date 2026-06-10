package io.custos.cli;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import picocli.CommandLine;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CustosCliHttpTest {

    static HttpServer server;
    static AtomicReference<String> queryBody = new AtomicReference<>();
    static AtomicReference<String> sealAuth = new AtomicReference<>();

    @BeforeAll
    static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/query_db", ex -> {
            queryBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = "{\"allowed\":true}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.createContext("/operator/seal", ex -> {
            sealAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            byte[] out = "{\"sealed\":true}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
    }

    @AfterAll
    static void stop() { server.stop(0); }

    private static String base() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    @Test
    void queryHitsQueryDbWithJsonShape() {
        int exit = new CommandLine(new CustosCli()).execute(
                "--server", base(), "query", "--tool", "db/q", "--schema", "appdb",
                "--sql", "SELECT 1", "--user-token", "jwt");
        assertEquals(0, exit);
        assertTrue(queryBody.get().contains("\"tool\":\"db/q\""));
        assertTrue(queryBody.get().contains("\"userToken\":\"jwt\""));
    }

    @Test
    void operatorSealSendsBearer() {
        int exit = new CommandLine(new CustosCli()).execute(
                "--server", base(), "--token", "tk", "operator", "seal");
        assertEquals(0, exit);
        assertEquals("Bearer tk", sealAuth.get());
    }
}
