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
    static AtomicReference<String> resourcesMethod = new AtomicReference<>();
    static AtomicReference<String> resourcesBody = new AtomicReference<>();
    static AtomicReference<String> resourcesAuth = new AtomicReference<>();

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
        server.createContext("/resources", ex -> {
            resourcesMethod.set(ex.getRequestMethod());
            resourcesAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            resourcesBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = "[{\"name\":\"appdb\"}]".getBytes(StandardCharsets.UTF_8);
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

    @Test
    void resourceRegisterPostsRecordWithoutLeakingPassword() {
        java.io.PrintStream original = System.out;
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        int exit;
        try {
            System.setOut(new java.io.PrintStream(captured, true, StandardCharsets.UTF_8));
            exit = new CommandLine(new CustosCli()).execute(
                    "--server", base(), "--token", "demo-token", "resource", "register",
                    "--name", "appdb", "--type", "db.relational", "--dialect", "mysql",
                    "--jdbc-url", "jdbc:mysql://h/appdb", "--admin-user", "custos",
                    "--admin-password", "custospwd", "--role", "read-only");
        } finally {
            System.setOut(original);
        }
        assertEquals(0, exit);
        assertEquals("POST", resourcesMethod.get());
        assertEquals("Bearer demo-token", resourcesAuth.get());
        String body = resourcesBody.get();
        assertTrue(body.contains("\"name\":\"appdb\""), body);
        assertTrue(body.contains("\"adminUsername\":\"custos\""), body);
        assertTrue(body.contains("\"adminPassword\":\"custospwd\""), body);
        assertTrue(body.contains("\"dialect\":\"mysql\""), body);
        assertFalse(captured.toString(StandardCharsets.UTF_8).contains("custospwd"),
                "adminPassword must not be printed to stdout");
    }
}
