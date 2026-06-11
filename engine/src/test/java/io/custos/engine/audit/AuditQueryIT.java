package io.custos.engine.audit;

import com.mysql.cj.jdbc.MysqlDataSource;
import io.custos.engine.crypto.IntlSuite;
import io.custos.engine.persistence.JimmerClients;
import org.babyfish.jimmer.sql.JSqlClient;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class AuditQueryIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    private HashChainAuditLog log;

    @BeforeEach
    void setUp() throws Exception {
        MysqlDataSource ds = new MysqlDataSource();
        ds.setUrl(MYSQL.getJdbcUrl());
        ds.setUser(MYSQL.getUsername());
        ds.setPassword(MYSQL.getPassword());
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS custos_audit");
            st.execute("""
              CREATE TABLE custos_audit (
                seq BIGINT AUTO_INCREMENT PRIMARY KEY, ts BIGINT NOT NULL, actor VARCHAR(512) NOT NULL,
                task VARCHAR(512), resource VARCHAR(512), action VARCHAR(64), decision VARCHAR(32),
                result_digest VARCHAR(128), sensitive_hmac VARCHAR(128),
                prev_hash VARCHAR(128) NOT NULL, chain_hash VARCHAR(128) NOT NULL)""");
        }
        JSqlClient sql = JimmerClients.of(ds);
        log = new HashChainAuditLog(sql, new IntlSuite(), new byte[32]);

        // 依次 append 6 条：actor 交替 agent:a / agent:b；decision 轮替 allow/deny/require-approval。
        String[] actors = {"agent:a", "agent:b"};
        String[] decisions = {"allow", "deny", "require-approval"};
        long base = System.currentTimeMillis();
        for (int i = 0; i < 6; i++) {
            log.append(new AuditRecord(base + i, actors[i % 2], "task", "db:orders",
                    "read", decisions[i % 3], "digest" + i, "pwd=xxx"));
        }
    }

    @Test
    void pagedNewestFirst() {
        List<AuditEntry> page0 = log.query(new AuditQuery(null, null, null, null, 0, 3));
        assertEquals(3, page0.size());
        assertTrue(page0.get(0).seq() > page0.get(1).seq()); // 降序

        List<AuditEntry> page1 = log.query(new AuditQuery(null, null, null, null, 1, 3));
        assertEquals(3, page1.size());
        assertTrue(page0.get(2).seq() > page1.get(0).seq()); // 翻页不重叠
    }

    @Test
    void filterByActorAndDecision() {
        List<AuditEntry> onlyA = log.query(new AuditQuery("agent:a", null, null, null, 0, 100));
        assertFalse(onlyA.isEmpty());
        assertTrue(onlyA.stream().allMatch(e -> e.actor().equals("agent:a")));

        List<AuditEntry> onlyDeny = log.query(new AuditQuery(null, "deny", null, null, 0, 100));
        assertFalse(onlyDeny.isEmpty());
        assertTrue(onlyDeny.stream().allMatch(e -> e.decision().equals("deny")));
        assertEquals(onlyDeny.size(), log.count(new AuditQuery(null, "deny", null, null, 0, 100)));
    }

    @Test
    void decisionCountsAllAndRecent() {
        Map<String, Long> all = log.decisionCounts(0); // 0=全量
        assertTrue(all.getOrDefault("allow", 0L) >= 1);

        Map<String, Long> recent = log.decisionCounts(2); // 仅最近 2 行
        assertTrue(recent.values().stream().mapToLong(Long::longValue).sum() <= 2);
    }
}
