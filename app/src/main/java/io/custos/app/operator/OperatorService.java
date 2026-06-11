package io.custos.app.operator;

import io.custos.app.engine.EngineBootstrap;
import io.custos.app.engine.UnsealedContext;
import io.custos.authz.Pdp;
import io.custos.broker.BrokerService;
import io.custos.broker.SecretlessQueryExecutor;
import io.custos.engine.approval.JimmerApprovalStore;
import io.custos.engine.audit.HashChainAuditLog;
import io.custos.engine.barrier.DefaultBarrier;
import io.custos.engine.crypto.Keyring;
import io.custos.engine.lease.DefaultLeaseManager;
import io.custos.engine.resource.ResourceManager;
import io.custos.engine.resource.ResourceStore;
import io.custos.engine.seal.DefaultSealManager;
import io.custos.engine.seal.SealManager;
import io.custos.engine.seal.SealStatus;
import io.custos.engine.secrets.SecretsEngineRegistry;
import io.custos.engine.storage.JimmerStorage;
import io.custos.engine.storage.Storage;
import io.custos.identity.TokenService;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** 持有 SealManager 内存解封态；解封成功后装配依赖 keyring 的运营组件。 */
public final class OperatorService {

    private final EngineBootstrap engine;
    private final TokenService tokens;
    private final Pdp pdp;
    private final AtomicReference<UnsealedContext> ctx = new AtomicReference<>();

    public OperatorService(EngineBootstrap engine, TokenService tokens, Pdp pdp) {
        this.engine = engine; this.tokens = tokens; this.pdp = pdp;
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

    /**
     * 解封后装配：Barrier(keyring) → Storage、AuditLog、资源接入栈、BrokerService。
     * 不再持有任何硬编码目标库 admin 凭证——目标库经 {@link ResourceManager} 在运行期注册，
     * 高权限凭证整条经 Barrier 加密落盘。
     */
    private void assemble() {
        SealManager sm = engine.sealManager();
        Keyring keyring = ((DefaultSealManager) sm).keyring();
        DefaultBarrier barrier = new DefaultBarrier(engine.suite(), keyring);
        Storage storage = new JimmerStorage(engine.sql(), barrier);
        // 审计密钥从 keyring 活动密钥确定性派生（同密钥→同 auditKey，审计链跨重启可验）
        byte[] activeKey = keyring.key(keyring.activeVersion());
        byte[] auditKey = engine.suite().hmac(activeKey, "custos-audit-key".getBytes(StandardCharsets.UTF_8));
        HashChainAuditLog audit = new HashChainAuditLog(engine.sql(), engine.suite(), auditKey);

        DefaultLeaseManager leases = new DefaultLeaseManager(engine.sql());
        SecretsEngineRegistry registry = new SecretsEngineRegistry();
        ResourceStore resourceStore = new ResourceStore(storage);
        ResourceManager resourceManager = new ResourceManager(resourceStore, registry, leases, audit);
        resourceManager.mountAll();   // 载入已持久化资源，挂回 registry

        // 审批单非密钥，明文直连 DB（与 storage 共用 JSqlClient，但不经 Barrier）
        JimmerApprovalStore approvals = new JimmerApprovalStore(engine.sql());
        BrokerService broker = new BrokerService(tokens, pdp, resourceManager, new SecretlessQueryExecutor(), audit, approvals);
        ctx.set(new UnsealedContext(storage, audit, broker, resourceManager, approvals, leases));
    }
}
