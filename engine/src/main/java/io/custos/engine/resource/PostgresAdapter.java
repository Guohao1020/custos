package io.custos.engine.resource;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.HexFormat;

/** PostgreSQL 内置只读适配器：CREATE ROLE LOGIN + GRANT USAGE/SELECT；撤销 REASSIGN/DROP OWNED/DROP ROLE。标识符仅 [0-9a-f] 防注入。 */
public final class PostgresAdapter implements CredentialAdapter {
    private final SecureRandom random = new SecureRandom();
    @Override
    public MintedCred issue(Connection admin, RoleDef role, Duration ttl) {
        String user = "v_ro_" + hex(6);
        String pwd = hex(16);
        try (Statement st = admin.createStatement()) {
            st.execute("CREATE ROLE " + user + " LOGIN PASSWORD '" + pwd + "'");
            st.execute("GRANT USAGE ON SCHEMA " + role.schema() + " TO " + user);
            st.execute("GRANT SELECT ON ALL TABLES IN SCHEMA " + role.schema() + " TO " + user);
        } catch (Exception e) { throw new IllegalStateException("postgres issue failed", e); }
        return new MintedCred(user, pwd);
    }
    @Override
    public void revoke(Connection admin, String username, RoleDef role) {
        try (Statement st = admin.createStatement()) {
            st.execute("REASSIGN OWNED BY " + username + " TO CURRENT_USER");
            st.execute("DROP OWNED BY " + username);
            st.execute("DROP ROLE IF EXISTS " + username);
        } catch (Exception e) { throw new IllegalStateException("postgres revoke failed: " + username, e); }
    }
    private String hex(int bytes) { byte[] b = new byte[bytes]; random.nextBytes(b); return HexFormat.of().formatHex(b); }
}
