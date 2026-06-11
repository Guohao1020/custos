package io.custos.engine.resource;

import com.mysql.cj.jdbc.MysqlDataSource;
import io.custos.engine.barrier.DefaultBarrier;
import io.custos.engine.crypto.IntlSuite;
import io.custos.engine.crypto.Keyring;
import io.custos.engine.lease.DefaultLeaseManager;
import io.custos.engine.persistence.JimmerClients;
import io.custos.engine.secrets.IssuedCred;
import io.custos.engine.storage.JimmerStorage;
import org.babyfish.jimmer.sql.JSqlClient;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class DbDynamicEngineIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0").withUsername("root").withPassword("root");

    private Connection adminConn;
    private ResourceStore store;
    private DefaultLeaseManager leases;

    private DefaultBarrier barrier() {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        Keyring kr = new Keyring();
        kr.add(1, k);
        return new DefaultBarrier(new IntlSuite(), kr);
    }

    /** 只读用户仅被授予 appdb，连接默认库须指向 appdb（保留连接参数，仅替换库名段）。 */
    private String appdbUrl() {
        return MYSQL.getJdbcUrl().replaceFirst("/\\w+(\\?|$)", "/appdb$1");
    }

    @BeforeEach
    void setUp() throws Exception {
        adminConn = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", "root");
        try (Statement st = adminConn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS custos_storage (skey VARCHAR(255) PRIMARY KEY, svalue LONGBLOB NOT NULL, updated_at BIGINT NOT NULL)");
            st.execute("""
              CREATE TABLE IF NOT EXISTS custos_lease (
                lease_id VARCHAR(160) PRIMARY KEY, resource_path VARCHAR(512) NOT NULL,
                issued_at BIGINT NOT NULL, expire_at BIGINT NOT NULL, revoked TINYINT NOT NULL DEFAULT 0)""");
            st.execute("CREATE DATABASE IF NOT EXISTS appdb");
            st.execute("CREATE TABLE IF NOT EXISTS appdb.orders (id INT)");
            st.execute("INSERT INTO appdb.orders VALUES (1),(2)");
        }
        MysqlDataSource ds = new MysqlDataSource();
        ds.setUrl(MYSQL.getJdbcUrl());
        ds.setUser("root");
        ds.setPassword("root");
        JSqlClient sql = JimmerClients.of(ds);
        store = new ResourceStore(new JimmerStorage(sql, barrier()));
        leases = new DefaultLeaseManager(sql);
    }

    @Test
    void issuesReadonlyCredThenRevokesUser() throws Exception {
        String appdbUrl = appdbUrl();
        store.put(new ResourceRecord("appdb", "db.relational", "mysql", appdbUrl, "root", "root",
                List.of(new RoleDef("read-only", RoleKind.BUILTIN_READONLY, List.of(), List.of(), 3600, "appdb"))));

        DbDynamicEngine engine = new DbDynamicEngine("appdb", store, leases);
        assertEquals("db.relational", engine.type());
        assertEquals(appdbUrl, engine.jdbcUrl());

        IssuedCred c = engine.issue("read-only", Duration.ofMinutes(5));

        // 用签出的只读凭证连 appdb 查 orders 得 2 行。
        try (Connection u = DriverManager.getConnection(engine.jdbcUrl(), c.username(), c.password());
             ResultSet rs = u.createStatement().executeQuery("SELECT COUNT(*) FROM appdb.orders")) {
            assertTrue(rs.next());
            assertEquals(2, rs.getInt(1));
        }

        // IssuedCred.toString() 不得泄漏 admin 密码 root（签出的 password 是随机 hex，非 root）。
        assertFalse(c.toString().contains("password='root'"));

        engine.revoke(c.leaseId());

        // 撤销后账号没了。
        try (ResultSet rs = adminConn.createStatement().executeQuery(
                "SELECT COUNT(*) FROM mysql.user WHERE user='" + c.username() + "'")) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1));
        }
    }
}
