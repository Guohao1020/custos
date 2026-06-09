package io.custos.engine.secrets;

import java.time.Duration;

/** 密钥引擎 SPI：按挂载路径签发/撤销现场凭证。DB/AK·SK/KV 等各实现一份。 */
public interface SecretsEngine {
    /** 引擎类型标识，如 "db-readonly"。 */
    String type();

    /** 在给定 path（如 schema 名）上签发 TTL 凭证。 */
    IssuedCred issue(String path, Duration ttl);

    /** 按 leaseId 撤销（触发底层清理，如 DROP USER）。 */
    void revoke(String leaseId);
}
