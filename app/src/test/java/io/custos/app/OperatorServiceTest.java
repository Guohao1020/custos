package io.custos.app;

import io.custos.app.config.CustosProperties;
import io.custos.app.engine.EngineBootstrap;
import io.custos.app.operator.OperatorService;
import io.custos.authz.CasbinPdp;
import io.custos.identity.InMemoryBlacklist;
import io.custos.identity.JwtTokenService;
import io.custos.identity.TokenService;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class OperatorServiceTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0").withUsername("root").withPassword("root");

    private OperatorService op;

    @BeforeEach
    void setUp() throws Exception {
        try (Connection c = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", "root"); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS custos_storage (skey VARCHAR(255) PRIMARY KEY, svalue LONGBLOB NOT NULL, updated_at BIGINT NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS custos_seal_config (ckey VARCHAR(64) PRIMARY KEY, cval LONGBLOB NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS custos_audit (seq BIGINT AUTO_INCREMENT PRIMARY KEY, ts BIGINT NOT NULL, actor VARCHAR(512) NOT NULL, task VARCHAR(512), resource VARCHAR(512), action VARCHAR(64), decision VARCHAR(32), result_digest VARCHAR(128), sensitive_hmac VARCHAR(128), prev_hash VARCHAR(128) NOT NULL, chain_hash VARCHAR(128) NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS custos_lease (lease_id VARCHAR(160) PRIMARY KEY, resource_path VARCHAR(512) NOT NULL, issued_at BIGINT NOT NULL, expire_at BIGINT NOT NULL, revoked TINYINT NOT NULL DEFAULT 0)");
        }
        CustosProperties props = new CustosProperties();
        props.getEngine().setStorageUrl(MYSQL.getJdbcUrl());
        props.getEngine().setStorageUsername("root");
        props.getEngine().setStoragePassword("root");
        var g = KeyPairGenerator.getInstance("EC"); g.initialize(new ECGenParameterSpec("secp256r1"));
        TokenService tokens = new JwtTokenService(g.generateKeyPair(), "custos", new InMemoryBlacklist());
        CasbinPdp pdp = new CasbinPdp();
        op = new OperatorService(new EngineBootstrap(props), tokens, pdp, io.custos.broker.BrokerMetrics.NOOP);
    }

    @Test
    void initStaysSealedThenUnsealsAtThreshold() {
        assertTrue(op.status().sealed());
        List<String> shares = op.init(5, 3);
        assertEquals(5, shares.size());
        assertTrue(op.status().sealed(), "init 后仍 sealed");
        op.unseal(shares.get(0));
        op.unseal(shares.get(1));
        var s = op.unseal(shares.get(2));
        assertFalse(s.sealed(), "满 3 片应解封");
        assertNotNull(op.unsealed(), "解封后应装配运营组件");
    }
}
