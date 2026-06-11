package io.custos.engine.lease;

import com.mysql.cj.jdbc.MysqlDataSource;
import io.custos.engine.persistence.JimmerClients;
import org.babyfish.jimmer.sql.JSqlClient;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class DefaultLeaseManagerIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    private DefaultLeaseManager leases;

    @BeforeEach
    void setUp() throws Exception {
        MysqlDataSource ds = new MysqlDataSource();
        ds.setUrl(MYSQL.getJdbcUrl()); ds.setUser(MYSQL.getUsername()); ds.setPassword(MYSQL.getPassword());
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("""
              CREATE TABLE IF NOT EXISTS custos_lease (
                lease_id VARCHAR(160) PRIMARY KEY, resource_path VARCHAR(512) NOT NULL,
                issued_at BIGINT NOT NULL, expire_at BIGINT NOT NULL, revoked TINYINT NOT NULL DEFAULT 0)""");
        }
        JSqlClient sql = JimmerClients.of(ds);
        leases = new DefaultLeaseManager(sql);
    }

    @Test
    void revokeCallsRevoker() {
        AtomicInteger revoked = new AtomicInteger();
        Lease lease = leases.register("db/creds/orders-ro/a", Duration.ofHours(1), l -> revoked.incrementAndGet());
        leases.revoke(lease.leaseId());
        assertEquals(1, revoked.get());
    }

    @Test
    void revokePrefixRevokesSubtree() {
        AtomicInteger n = new AtomicInteger();
        Revoker r = l -> n.incrementAndGet();
        leases.register("db/creds/orders-ro/a", Duration.ofHours(1), r);
        leases.register("db/creds/orders-ro/b", Duration.ofHours(1), r);
        leases.register("db/creds/other/c", Duration.ofHours(1), r);
        assertEquals(2, leases.revokePrefix("db/creds/orders-ro/"));
        assertEquals(2, n.get());
    }

    @Test
    void renewExtendsExpiry() {
        Lease lease = leases.register("db/creds/x", Duration.ofMinutes(10), l -> {});
        assertTrue(leases.renew(lease.leaseId(), Duration.ofHours(2)).expireAt() > lease.expireAt());
    }

    @Test
    void listActiveExcludesRevokedAndExpired() {
        Lease live = leases.register("db/appdb", Duration.ofHours(1), id -> {});
        Lease toRevoke = leases.register("db/appdb", Duration.ofHours(1), id -> {});
        leases.revoke(toRevoke.leaseId());
        var active = leases.listActive();
        assertTrue(active.stream().anyMatch(l -> l.leaseId().equals(live.leaseId())));
        assertTrue(active.stream().noneMatch(l -> l.leaseId().equals(toRevoke.leaseId())));
    }
}
