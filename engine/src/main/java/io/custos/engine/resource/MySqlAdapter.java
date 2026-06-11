package io.custos.engine.resource;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.HexFormat;

/** MySQL 内置只读适配器：CREATE USER + GRANT SELECT；撤销 DROP USER。标识符仅 [0-9a-f] 防注入。 */
public final class MySqlAdapter implements CredentialAdapter {
    private final SecureRandom random = new SecureRandom();
    @Override
    public MintedCred issue(Connection admin, RoleDef role, Duration ttl) {
        String user = "v_ro_" + hex(6);
        String pwd = hex(16);
        try (Statement st = admin.createStatement()) {
            st.execute("CREATE USER '" + user + "'@'%' IDENTIFIED BY '" + pwd + "'");
            st.execute("GRANT SELECT ON `" + role.schema() + "`.* TO '" + user + "'@'%'");
            st.execute("FLUSH PRIVILEGES");
        } catch (Exception e) { throw new IllegalStateException("mysql issue failed", e); }
        return new MintedCred(user, pwd);
    }
    @Override
    public void revoke(Connection admin, String username, RoleDef role) {
        try (Statement st = admin.createStatement()) {
            st.execute("DROP USER IF EXISTS '" + username + "'@'%'");
            st.execute("FLUSH PRIVILEGES");
        } catch (Exception e) { throw new IllegalStateException("mysql revoke failed: " + username, e); }
    }
    private String hex(int bytes) { byte[] b = new byte[bytes]; random.nextBytes(b); return HexFormat.of().formatHex(b); }
}
