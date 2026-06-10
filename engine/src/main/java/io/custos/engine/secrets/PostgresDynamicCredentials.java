package io.custos.engine.secrets;

import io.custos.engine.lease.Lease;
import io.custos.engine.lease.LeaseManager;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.HexFormat;

/** PostgreSQL 动态只读凭证（SecretsEngine 第三实现）：CREATE ROLE + GRANT SELECT；撤销 DROP ROLE。标识符仅 [0-9a-f] 防注入。 */
public final class PostgresDynamicCredentials implements SecretsEngine {

    private final Connection admin;
    private final LeaseManager leases;
    private final SecureRandom random = new SecureRandom();

    public PostgresDynamicCredentials(Connection admin, LeaseManager leases) {
        this.admin = admin;
        this.leases = leases;
    }

    @Override
    public String type() { return "db-readonly-postgres"; }

    @Override
    public IssuedCred issue(String schema, Duration ttl) {
        String user = "v_ro_" + hex(6);
        String pwd = hex(16);
        try (Statement st = admin.createStatement()) {
            st.execute("CREATE ROLE " + user + " LOGIN PASSWORD '" + pwd + "'");
            st.execute("GRANT USAGE ON SCHEMA " + schema + " TO " + user);
            st.execute("GRANT SELECT ON ALL TABLES IN SCHEMA " + schema + " TO " + user);
        } catch (Exception e) {
            throw new IllegalStateException("issue pg readonly cred failed", e);
        }
        Lease lease = leases.register("db/creds/pg/" + schema + "-ro", ttl, l -> dropRole(user));
        return new IssuedCred(user, pwd, lease.leaseId(), lease.expireAt());
    }

    @Override
    public void revoke(String leaseId) { leases.revoke(leaseId); }

    private void dropRole(String user) {
        try (Statement st = admin.createStatement()) {
            st.execute("REASSIGN OWNED BY " + user + " TO CURRENT_USER");
            st.execute("DROP OWNED BY " + user);
            st.execute("DROP ROLE IF EXISTS " + user);
        } catch (Exception e) {
            throw new IllegalStateException("drop pg role failed: " + user, e);
        }
    }

    private String hex(int bytes) {
        byte[] b = new byte[bytes];
        random.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }
}
