package io.custos.engine.resource;

import com.mysql.cj.jdbc.MysqlDataSource;
import io.custos.engine.barrier.DefaultBarrier;
import io.custos.engine.crypto.IntlSuite;
import io.custos.engine.crypto.Keyring;
import io.custos.engine.persistence.JimmerClients;
import io.custos.engine.storage.JimmerStorage;
import org.babyfish.jimmer.sql.JSqlClient;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class ResourceStoreIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    private MysqlDataSource ds;
    private ResourceStore store;

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
        store = new ResourceStore(new JimmerStorage(sql, barrier()));
    }

    @Test
    void adminPasswordCiphertextOnDiskRoundTrips() throws Exception {
        String pwd = "SuperSecretAdminPwd123";
        ResourceRecord rec = new ResourceRecord(
                "appdb", "db.relational", "mysql", "jdbc:mysql://h/appdb", "admin", pwd,
                List.of(new RoleDef("read-only", RoleKind.BUILTIN_READONLY, List.of(), List.of(), 3600, "appdb")));
        store.put(rec);

        // 往返：高权限密码能原样读回，证明序列化/解密无损。
        assertEquals(pwd, store.get("appdb").orElseThrow().adminPassword());

        // 直读落盘字节的 hex，断言不含明文密码的 hex —— 密文落盘、无明文。
        String pwdHex = HexFormat.of().formatHex(pwd.getBytes(UTF_8)).toUpperCase();
        try (Connection c = ds.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT HEX(svalue) FROM custos_storage WHERE skey='resource/appdb'")) {
            assertTrue(rs.next(), "expected a row for key resource/appdb");
            String onDiskHex = rs.getString(1).toUpperCase();
            assertFalse(onDiskHex.contains(pwdHex),
                    "admin password plaintext must not appear on disk; ciphertext expected");
        }
    }
}
