package io.custos.engine.secrets;

import com.mysql.cj.jdbc.MysqlDataSource;
import io.custos.engine.lease.DefaultLeaseManager;
import io.custos.engine.persistence.JimmerClients;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class DynamicDbCredentialsIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0").withUsername("root").withPassword("root");

    private Connection admin;
    private DynamicDbCredentials creds;

    /** 只读用户仅被授予 appdb，连接默认库须指向 appdb（保留 getJdbcUrl 的连接参数，仅替换库名段）。 */
    private String appdbUrl() {
        return MYSQL.getJdbcUrl().replaceFirst("/" + MYSQL.getDatabaseName() + "(\\?|$)", "/appdb$1");
    }

    @BeforeEach
    void setUp() throws Exception {
        admin = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", "root");
        try (Statement st = admin.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS custos_lease (lease_id VARCHAR(160) PRIMARY KEY, resource_path VARCHAR(512), issued_at BIGINT, expire_at BIGINT, revoked TINYINT DEFAULT 0)");
            st.execute("CREATE DATABASE IF NOT EXISTS appdb");
            st.execute("CREATE TABLE IF NOT EXISTS appdb.orders (id INT)");
            st.execute("INSERT INTO appdb.orders VALUES (1)");
        }
        MysqlDataSource ds = new MysqlDataSource();
        ds.setUrl(MYSQL.getJdbcUrl()); ds.setUser("root"); ds.setPassword("root");
        creds = new DynamicDbCredentials(admin, new DefaultLeaseManager(JimmerClients.of(ds)), MYSQL.getJdbcUrl());
    }

    @Test
    void issuedCredReadsButCannotWrite() throws Exception {
        IssuedCred c = creds.issueReadonly("appdb", Duration.ofHours(1));
        try (Connection user = DriverManager.getConnection(appdbUrl(), c.username(), c.password());
             Statement st = user.createStatement()) {
            assertTrue(st.executeQuery("SELECT COUNT(*) FROM appdb.orders").next());
            assertThrows(SQLException.class, () -> st.executeUpdate("INSERT INTO appdb.orders VALUES (2)"));
        }
    }

    @Test
    void revokingDropsUser() throws Exception {
        IssuedCred c = creds.issueReadonly("appdb", Duration.ofHours(1));
        creds.revoke(c.leaseId());
        assertThrows(SQLException.class, () ->
                DriverManager.getConnection(appdbUrl(), c.username(), c.password()));
    }
}
