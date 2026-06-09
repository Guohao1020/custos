package io.custos.engine.secrets;

import io.custos.engine.lease.Lease;
import io.custos.engine.lease.LeaseManager;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.HexFormat;

/**
 * 动态 DB 只读凭证：现场 CREATE USER + GRANT SELECT（裸 JDBC，因 ORM 不做账号 DDL），
 * 登记 Jimmer 租约；撤销时 DROP USER。用户名/密码用十六进制（仅 [0-9a-f]）避免标识符注入。
 */
public final class DynamicDbCredentials implements SecretsEngine {

    private final Connection admin;
    private final LeaseManager leases;
    private final String jdbcUrl;
    private final SecureRandom random = new SecureRandom();

    public DynamicDbCredentials(Connection admin, LeaseManager leases, String jdbcUrl) {
        this.admin = admin;
        this.leases = leases;
        this.jdbcUrl = jdbcUrl;
    }

    @Override
    public String type() { return "db-readonly"; }

    @Override
    public IssuedCred issue(String path, Duration ttl) { return issueReadonly(path, ttl); }

    public IssuedCred issueReadonly(String schema, Duration ttl) {
        String user = "v_ro_" + randomHex(6);
        String pwd = randomHex(16);
        try (Statement st = admin.createStatement()) {
            st.execute("CREATE USER '" + user + "'@'%' IDENTIFIED BY '" + pwd + "'");
            st.execute("GRANT SELECT ON `" + schema + "`.* TO '" + user + "'@'%'");
            st.execute("FLUSH PRIVILEGES");
        } catch (Exception e) {
            throw new IllegalStateException("issue readonly cred failed", e);
        }
        Lease lease = leases.register("db/creds/" + schema + "-ro", ttl, l -> dropUser(user));
        return new IssuedCred(user, pwd, lease.leaseId(), lease.expireAt());
    }

    public void revoke(String leaseId) {
        leases.revoke(leaseId);   // 触发 Revoker → dropUser
    }

    private void dropUser(String user) {
        try (Statement st = admin.createStatement()) {
            st.execute("DROP USER IF EXISTS '" + user + "'@'%'");
            st.execute("FLUSH PRIVILEGES");
        } catch (Exception e) {
            throw new IllegalStateException("drop user failed: " + user, e);
        }
    }

    private String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        random.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }
}
