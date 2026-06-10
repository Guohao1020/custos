package io.custos.app.operator;

import io.custos.app.config.CustosProperties;
import io.custos.app.engine.EngineBootstrap;
import io.custos.app.engine.UnsealedContext;
import io.custos.authz.Pdp;
import io.custos.broker.BrokerService;
import io.custos.broker.SecretlessQueryExecutor;
import io.custos.engine.audit.HashChainAuditLog;
import io.custos.engine.barrier.DefaultBarrier;
import io.custos.engine.crypto.Keyring;
import io.custos.engine.lease.DefaultLeaseManager;
import io.custos.engine.seal.DefaultSealManager;
import io.custos.engine.seal.SealManager;
import io.custos.engine.seal.SealStatus;
import io.custos.engine.secrets.DynamicDbCredentials;
import io.custos.engine.storage.JimmerStorage;
import io.custos.engine.storage.Storage;
import io.custos.identity.TokenService;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** 持有 SealManager 内存解封态；解封成功后装配依赖 keyring 的运营组件。 */
public final class OperatorService {

    private final EngineBootstrap engine;
    private final TokenService tokens;
    private final Pdp pdp;
    private final CustosProperties props;
    private final String adminJdbcUrl, adminUser, adminPwd;
    private final AtomicReference<UnsealedContext> ctx = new AtomicReference<>();

    public OperatorService(EngineBootstrap engine, TokenService tokens, Pdp pdp, CustosProperties props,
                           String adminJdbcUrl, String adminUser, String adminPwd) {
        this.engine = engine; this.tokens = tokens; this.pdp = pdp; this.props = props;
        this.adminJdbcUrl = adminJdbcUrl; this.adminUser = adminUser; this.adminPwd = adminPwd;
    }

    public List<String> init(int shares, int threshold) {
        List<byte[]> parts = engine.sealManager().init(shares, threshold);
        List<String> out = new ArrayList<>();
        for (byte[] p : parts) out.add(Base64.getEncoder().encodeToString(p));
        return out;
    }

    public SealStatus unseal(String shareB64) {
        SealStatus s = engine.sealManager().submitUnsealKey(Base64.getDecoder().decode(shareB64));
        if (!s.sealed() && ctx.get() == null) assemble();
        return s;
    }

    public void seal() {
        engine.sealManager().seal();
        ctx.set(null);
    }

    public SealStatus status() { return engine.sealManager().status(); }

    public UnsealedContext unsealed() {
        UnsealedContext c = ctx.get();
        if (c == null) throw new IllegalStateException("sealed");
        return c;
    }

    /** 解封后装配：Barrier(keyring) → Storage、AuditLog、BrokerService。 */
    private void assemble() {
        SealManager sm = engine.sealManager();
        Keyring keyring = ((DefaultSealManager) sm).keyring();
        DefaultBarrier barrier = new DefaultBarrier(engine.suite(), keyring);
        Storage storage = new JimmerStorage(engine.sql(), barrier);
        // 审计密钥从 keyring 活动密钥确定性派生（同密钥→同 auditKey，审计链跨重启可验）
        byte[] activeKey = keyring.key(keyring.activeVersion());
        byte[] auditKey = engine.suite().hmac(activeKey, "custos-audit-key".getBytes(StandardCharsets.UTF_8));
        HashChainAuditLog audit = new HashChainAuditLog(engine.sql(), engine.suite(), auditKey);
        try {
            Connection admin = DriverManager.getConnection(adminJdbcUrl, adminUser, adminPwd);
            DynamicDbCredentials creds = new DynamicDbCredentials(admin, new DefaultLeaseManager(engine.sql()), props.getBroker().getTargetJdbcUrl());
            BrokerService broker = new BrokerService(tokens, pdp, creds, new SecretlessQueryExecutor(), props.getBroker().getTargetJdbcUrl(), audit);
            ctx.set(new UnsealedContext(storage, audit, broker));
        } catch (Exception e) {
            throw new IllegalStateException("assemble after unseal failed", e);
        }
    }
}
