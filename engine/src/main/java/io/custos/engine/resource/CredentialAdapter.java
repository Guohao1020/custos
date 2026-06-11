package io.custos.engine.resource;
import java.sql.Connection;
import java.time.Duration;
/** 动态凭证型方言适配 SPI：用 admin 连接现场签发/撤销一个短时账号。 */
public interface CredentialAdapter {
    MintedCred issue(Connection admin, RoleDef role, Duration ttl);
    void revoke(Connection admin, String username, RoleDef role);
}
