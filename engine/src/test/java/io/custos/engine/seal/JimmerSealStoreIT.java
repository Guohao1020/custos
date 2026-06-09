package io.custos.engine.seal;

import com.mysql.cj.jdbc.MysqlDataSource;
import io.custos.engine.barrier.DefaultBarrier;
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

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class JimmerSealStoreIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    private JSqlClient sql;

    @BeforeEach
    void setUp() throws Exception {
        MysqlDataSource ds = new MysqlDataSource();
        ds.setUrl(MYSQL.getJdbcUrl());
        ds.setUser(MYSQL.getUsername());
        ds.setPassword(MYSQL.getPassword());
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS custos_seal_config (ckey VARCHAR(64) PRIMARY KEY, cval LONGBLOB NOT NULL)");
        }
        sql = JimmerClients.of(ds);
    }

    @Test
    void unsealRecoversFromJimmerStoreAcrossInstances() {
        List<byte[]> shares = new DefaultSealManager(new IntlSuite(), new JimmerSealStore(sql)).init(5, 3);

        DefaultSealManager mgr = new DefaultSealManager(new IntlSuite(), new JimmerSealStore(sql));
        assertTrue(mgr.status().sealed());
        mgr.submitUnsealKey(shares.get(0));
        mgr.submitUnsealKey(shares.get(2));
        assertFalse(mgr.submitUnsealKey(shares.get(4)).sealed());

        DefaultBarrier barrier = new DefaultBarrier(new IntlSuite(), mgr.keyring());
        assertArrayEquals("ok".getBytes(), barrier.open(barrier.seal("ok".getBytes())));
    }
}
