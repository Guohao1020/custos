package io.custos.engine.resource;

import io.custos.engine.audit.AuditLog;
import io.custos.engine.audit.AuditRecord;
import io.custos.engine.lease.LeaseManager;
import io.custos.engine.secrets.SecretsEngineRegistry;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

/** 资源生命周期:注册(试连校验→存→挂 registry→审计)、列表、轮换高权限钥、注销。 */
public final class ResourceManager {
    private final ResourceStore store;
    private final SecretsEngineRegistry registry;
    private final LeaseManager leases;
    private final AuditLog audit;

    public ResourceManager(ResourceStore store, SecretsEngineRegistry registry, LeaseManager leases, AuditLog audit) {
        this.store = store;
        this.registry = registry;
        this.leases = leases;
        this.audit = audit;
    }

    /** 解封后调用:把已持久化的资源全部挂回 registry。 */
    public void mountAll() {
        for (String name : store.listNames()) mount(name);
    }

    private void mount(String name) {
        registry.mount(name, new DbDynamicEngine(name, store, leases));
    }

    public DbDynamicEngine require(String name) {
        return (DbDynamicEngine) registry.require(name);
    }

    public List<String> list() {
        return store.listNames();
    }

    public void register(ResourceRecord r) {
        validate(r);
        store.put(r);
        mount(r.name());
        record("register", r.name());
    }

    public void rotateAdminKey(String name, String newPassword) {
        ResourceRecord r = store.get(name).orElseThrow(() -> new IllegalArgumentException("no resource: " + name));
        ResourceRecord updated = new ResourceRecord(r.name(), r.type(), r.dialect(), r.jdbcUrl(),
                r.adminUsername(), newPassword, r.roles());
        validate(updated);
        store.put(updated);
        record("rotate-admin", name);
    }

    public void unregister(String name) {
        store.delete(name);
        registry.unmount(name);
        record("unregister", name);
    }

    /** 用提供的 admin 凭证开一次连接,验证连通 + 凭证有效。 */
    private void validate(ResourceRecord r) {
        try (Connection c = DriverManager.getConnection(r.jdbcUrl(), r.adminUsername(), r.adminPassword())) {
            if (!c.isValid(5)) throw new IllegalStateException("admin connection invalid");
        } catch (Exception e) {
            throw new IllegalArgumentException("resource validation failed for " + r.name() + ": " + e.getMessage(), e);
        }
    }

    private void record(String action, String resourceName) {
        if (audit == null) return;
        audit.append(new AuditRecord(System.currentTimeMillis(), "admin", "resource", "resource:" + resourceName,
                action, "ok", "", resourceName));
    }
}
