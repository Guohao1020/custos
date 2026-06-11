package io.custos.engine.resource;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

/** 模板逃生口：跑 RoleDef 的 creation/revocation 语句，占位符 {{name}}/{{password}}/{{expiration}}。 */
public final class TemplateAdapter implements CredentialAdapter {
    private static final DateTimeFormatter EXP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);
    private final SecureRandom random = new SecureRandom();

    static String render(String tpl, String name, String password, String expiration) {
        return tpl.replace("{{name}}", name).replace("{{password}}", password).replace("{{expiration}}", expiration);
    }
    @Override
    public MintedCred issue(Connection admin, RoleDef role, Duration ttl) {
        String user = "v_ro_" + hex(6);
        String pwd = hex(16);
        String exp = EXP.format(Instant.now().plus(ttl));
        try (Statement st = admin.createStatement()) {
            for (String s : role.creationStatements()) st.execute(render(s, user, pwd, exp));
        } catch (Exception e) { throw new IllegalStateException("template issue failed", e); }
        return new MintedCred(user, pwd);
    }
    @Override
    public void revoke(Connection admin, String username, RoleDef role) {
        try (Statement st = admin.createStatement()) {
            for (String s : role.revocationStatements()) st.execute(render(s, username, "", ""));
        } catch (Exception e) { throw new IllegalStateException("template revoke failed: " + username, e); }
    }
    private String hex(int bytes) { byte[] b = new byte[bytes]; random.nextBytes(b); return HexFormat.of().formatHex(b); }
}
