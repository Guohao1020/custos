package io.custos.engine.resource;

import com.mysql.cj.jdbc.MysqlDataSource;
import io.custos.engine.audit.HashChainAuditLog;
import io.custos.engine.barrier.DefaultBarrier;
import io.custos.engine.crypto.IntlSuite;
import io.custos.engine.crypto.Keyring;
import io.custos.engine.lease.DefaultLeaseManager;
import io.custos.engine.persistence.JimmerClients;
import io.custos.engine.secrets.IssuedCred;
import io.custos.engine.secrets.SecretsEngineRegistry;
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

/**
 * 编排层 IT:真跑 MySQL 容器,验证 register(试连校验→存→挂 registry→审计)、list、
 * rotateAdminKey、unregister 全生命周期,以及坏 admin 凭证注册失败。
 */
@Testcontainers
class ResourceManagerIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0").withUsername("root").withPassword("root");

    private MysqlDataSource ds;
    private ResourceStore store;
    private DefaultLeaseManager leases;
    private SecretsEngineRegistry registry;
    private HashChainAuditLog audit;

    private DefaultBarrier barrier() {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        Keyring kr = new Keyring();
        kr.add(1, k);
        return new DefaultBarrier(new IntlSuite(), kr);
    }

    /** 只读用户仅被授予 appdb,连接默认库须指向 appdb(保留连接参数,仅替换库名段)。 */
    private String appdbUrl() {
        return MYSQL.getJdbcUrl().replaceFirst("/\\w+(\\?|$)", "/appdb$1");
    }

    private ResourceRecord appdbRecord() {
        return new ResourceRecord("appdb", "db.relational", "mysql", appdbUrl(), "root", "root",
                List.of(new RoleDef("read-only", RoleKind.BUILTIN_READONLY, List.of(), List.of(), 3600, "appdb")));
    }

    private long auditCount(String action) throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM custos_audit WHERE action='" + action + "'")) {
            assertTrue(rs.next());
            return rs.getLong(1);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        try (Connection admin = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", "root");
             Statement st = admin.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS custos_storage (skey VARCHAR(255) PRIMARY KEY, svalue LONGBLOB NOT NULL, updated_at BIGINT NOT NULL)");
            st.execute("""
              CREATE TABLE IF NOT EXISTS custos_lease (
                lease_id VARCHAR(160) PRIMARY KEY, resource_path VARCHAR(512) NOT NULL,
                issued_at BIGINT NOT NULL, expire_at BIGINT NOT NULL, revoked TINYINT NOT NULL DEFAULT 0)""");
            st.execute("""
              CREATE TABLE IF NOT EXISTS custos_audit (
                seq BIGINT AUTO_INCREMENT PRIMARY KEY, ts BIGINT NOT NULL, actor VARCHAR(512) NOT NULL,
                task VARCHAR(512), resource VARCHAR(512), action VARCHAR(64), decision VARCHAR(32),
                result_digest VARCHAR(128), sensitive_hmac VARCHAR(128),
                prev_hash VARCHAR(128) NOT NULL, chain_hash VARCHAR(128) NOT NULL)""");
            // 容器在同类多个测试方法间复用,清空状态表使每个方法从干净起点开始。
            st.execute("TRUNCATE TABLE custos_storage");
            st.execute("TRUNCATE TABLE custos_lease");
            st.execute("TRUNCATE TABLE custos_audit");
            st.execute("CREATE DATABASE IF NOT EXISTS appdb");
            st.execute("CREATE TABLE IF NOT EXISTS appdb.orders (id INT)");
            // 容器在同类多个测试方法间复用,每次重置为恰好 2 行,使行数断言确定。
            st.execute("TRUNCATE TABLE appdb.orders");
            st.execute("INSERT INTO appdb.orders VALUES (1),(2)");
        }
        ds = new MysqlDataSource();
        ds.setUrl(MYSQL.getJdbcUrl());
        ds.setUser("root");
        ds.setPassword("root");
        JSqlClient sql = JimmerClients.of(ds);
        store = new ResourceStore(new JimmerStorage(sql, barrier()));
        leases = new DefaultLeaseManager(sql);
        registry = new SecretsEngineRegistry();
        audit = new HashChainAuditLog(sql, new IntlSuite(), new byte[32]);
    }

    @Test
    void fullLifecycle() throws Exception {
        ResourceManager mgr = new ResourceManager(store, registry, leases, audit);

        // register:试连校验通过 → 挂 registry → 审计 append 一条 register。
        mgr.register(appdbRecord());
        assertTrue(mgr.list().contains("appdb"));
        assertEquals(1L, auditCount("register"));
        assertTrue(audit.verify().ok());

        // require 返回 DbDynamicEngine,可现场签发只读凭证。
        IssuedCred c = mgr.require("appdb").issue("read-only", Duration.ofMinutes(5));
        try (Connection u = DriverManager.getConnection(mgr.require("appdb").jdbcUrl(), c.username(), c.password());
             ResultSet rs = u.createStatement().executeQuery("SELECT COUNT(*) FROM appdb.orders")) {
            assertTrue(rs.next());
            assertEquals(2, rs.getInt(1));
        }
        mgr.require("appdb").revoke(c.leaseId());

        // rotateAdminKey:用同样有效的口令验流程,轮换后存储更新且仍可签发。
        mgr.rotateAdminKey("appdb", "root");
        assertEquals("root", store.get("appdb").orElseThrow().adminPassword());
        assertEquals(1L, auditCount("rotate-admin"));
        IssuedCred c2 = mgr.require("appdb").issue("read-only", Duration.ofMinutes(5));
        mgr.require("appdb").revoke(c2.leaseId());

        // unregister:删存储 + registry.unmount;之后 require 抛异常。
        mgr.unregister("appdb");
        assertFalse(mgr.list().contains("appdb"));
        assertEquals(1L, auditCount("unregister"));
        assertThrows(RuntimeException.class, () -> mgr.require("appdb"));

        // 审计链注册/轮换/注销三条事件后仍完整未断链。
        assertTrue(audit.verify().ok());
    }

    @Test
    void mountAllRemountsPersistedResources() {
        // 先直接落盘(模拟解封前已持久化),再 mountAll 把资源挂回 registry。
        store.put(appdbRecord());
        ResourceManager mgr = new ResourceManager(store, registry, leases, audit);
        mgr.mountAll();
        assertEquals("db.relational", mgr.require("appdb").type());
    }

    @Test
    void registerWithBadAdminCredsThrows() {
        ResourceManager mgr = new ResourceManager(store, registry, leases, audit);
        ResourceRecord bad = new ResourceRecord("bad", "db.relational", "mysql", appdbUrl(), "nope", "wrongpwd",
                appdbRecord().roles());
        assertThrows(IllegalArgumentException.class, () -> mgr.register(bad));
        // 校验失败:既不入存储也不挂 registry。
        assertFalse(mgr.list().contains("bad"));
        assertThrows(RuntimeException.class, () -> mgr.require("bad"));
    }

    @Test
    void rotateMissingResourceThrows() {
        ResourceManager mgr = new ResourceManager(store, registry, leases, audit);
        assertThrows(IllegalArgumentException.class, () -> mgr.rotateAdminKey("ghost", "x"));
    }
}
