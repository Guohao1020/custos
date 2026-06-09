package io.custos.engine.storage;

import com.mysql.cj.jdbc.MysqlDataSource;
import io.custos.engine.barrier.DefaultBarrier;
import io.custos.engine.crypto.IntlSuite;
import io.custos.engine.crypto.Keyring;
import io.custos.engine.persistence.JimmerClients;
import org.babyfish.jimmer.sql.JSqlClient;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class JimmerStorageIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    private MysqlDataSource ds;
    private JimmerStorage storage;

    private DefaultBarrier barrier() {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        Keyring kr = new Keyring();
        kr.add(1, k);
        return new DefaultBarrier(new IntlSuite(), kr);
    }

    @BeforeEach
    void setUp() throws Exception {
        ds = new MysqlDataSource();
        ds.setUrl(MYSQL.getJdbcUrl());
        ds.setUser(MYSQL.getUsername());
        ds.setPassword(MYSQL.getPassword());
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS custos_storage (skey VARCHAR(255) PRIMARY KEY, svalue LONGBLOB NOT NULL, updated_at BIGINT NOT NULL)");
        }
        JSqlClient sql = JimmerClients.of(ds);
        storage = new JimmerStorage(sql, barrier());
    }

    @Test
    void putThenGetRoundTrips() {
        storage.put("k1", "secret-value".getBytes(StandardCharsets.UTF_8));
        assertArrayEquals("secret-value".getBytes(StandardCharsets.UTF_8), storage.get("k1").orElseThrow());
    }

    @Test
    void storedBytesAreCiphertext() throws Exception {
        storage.put("k2", "plaintext-marker".getBytes(StandardCharsets.UTF_8));
        try (Connection c = ds.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT svalue FROM custos_storage WHERE skey='k2'")) {
            assertTrue(rs.next());
            assertFalse(new String(rs.getBytes(1), StandardCharsets.UTF_8).contains("plaintext-marker"));
        }
    }

    @Test
    void listByPrefix() {
        storage.put("p/a", "1".getBytes());
        storage.put("p/b", "2".getBytes());
        storage.put("q/c", "3".getBytes());
        assertEquals(2, storage.list("p/").size());
    }
}
