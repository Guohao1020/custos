package io.custos.engine.approval;

import com.mysql.cj.jdbc.MysqlDataSource;
import io.custos.engine.persistence.JimmerClients;
import org.babyfish.jimmer.sql.JSqlClient;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.Statement;

@Testcontainers
class JimmerApprovalStoreIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    private JSqlClient sql;

    @BeforeEach
    void setUp() throws Exception {
        MysqlDataSource ds = new MysqlDataSource();
        ds.setUrl(MYSQL.getJdbcUrl()); ds.setUser(MYSQL.getUsername()); ds.setPassword(MYSQL.getPassword());
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("""
              CREATE TABLE IF NOT EXISTS custos_approval (
                id          VARCHAR(160) PRIMARY KEY,
                agent       VARCHAR(512) NOT NULL,
                tool        VARCHAR(256) NOT NULL,
                resource    VARCHAR(256) NOT NULL,
                role        VARCHAR(128) NOT NULL,
                risk        INT NOT NULL,
                reason      VARCHAR(512),
                status      VARCHAR(32) NOT NULL,
                created_at  BIGINT NOT NULL,
                decided_at  BIGINT NOT NULL DEFAULT 0,
                expire_at   BIGINT NOT NULL DEFAULT 0)""");
        }
        sql = JimmerClients.of(ds);
    }

    @Test
    void createListApproveConsume() {
        ApprovalStore store = new JimmerApprovalStore(sql);
        String id = store.create("agent:claude-prod", "db/query_orders", "appdb", "read-only", 55, "需审批");
        Assertions.assertEquals(1, store.listPending().size());
        Assertions.assertEquals(ApprovalStatus.PENDING, store.get(id).orElseThrow().status());
        long exp = 9999999999999L;
        store.approve(id, exp);
        Assertions.assertEquals(ApprovalStatus.APPROVED, store.get(id).orElseThrow().status());
        Assertions.assertEquals(exp, store.get(id).orElseThrow().expireAt());
        Assertions.assertTrue(store.listPending().isEmpty());
        store.markConsumed(id);
        Assertions.assertEquals(ApprovalStatus.CONSUMED, store.get(id).orElseThrow().status());
    }

    @Test
    void denyMovesOutOfPending() {
        ApprovalStore store = new JimmerApprovalStore(sql);
        String id = store.create("agent:x", "db/q", "appdb", "read-only", 60, "r");
        store.deny(id);
        Assertions.assertEquals(ApprovalStatus.DENIED, store.get(id).orElseThrow().status());
        Assertions.assertTrue(store.listPending().isEmpty());
    }
}
