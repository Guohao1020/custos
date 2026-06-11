package io.custos.engine.resource;

import io.custos.engine.crypto.Zeroize;
import io.custos.engine.lease.Lease;
import io.custos.engine.lease.LeaseManager;
import io.custos.engine.secrets.IssuedCred;
import io.custos.engine.secrets.SecretsEngine;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;

/**
 * 动态凭证型引擎：包一条 ResourceRecord。issue 时临时开 admin 连接（高权限密码内存解出、用后 Zeroize、关连接），
 * 按 role.kind/dialect 选适配器现场签发，登记租约（到期 revoker 撤销）。
 */
public final class DbDynamicEngine implements SecretsEngine {
    private final ResourceStore store;
    private final LeaseManager leases;
    private final String resourceName;

    public DbDynamicEngine(String resourceName, ResourceStore store, LeaseManager leases) {
        this.resourceName = resourceName;
        this.store = store;
        this.leases = leases;
    }

    @Override
    public String type() {
        return "db.relational";
    }

    /** 给 broker 用：本资源的连接串（执行器用签出的只读凭证连它）。 */
    public String jdbcUrl() {
        return record().jdbcUrl();
    }

    private ResourceRecord record() {
        return store.get(resourceName).orElseThrow(() -> new IllegalStateException("resource gone: " + resourceName));
    }

    private CredentialAdapter adapterFor(ResourceRecord r, RoleDef role) {
        if (role.kind() == RoleKind.TEMPLATE) {
            return new TemplateAdapter();
        }
        return switch (r.dialect()) {
            case "mysql" -> new MySqlAdapter();
            case "postgresql" -> new PostgresAdapter();
            default -> throw new IllegalArgumentException("no builtin adapter for dialect: " + r.dialect());
        };
    }

    @Override
    public IssuedCred issue(String roleName, Duration ttl) {
        ResourceRecord r = record();
        RoleDef role = r.role(roleName);
        byte[] pwd = r.adminPassword().getBytes(StandardCharsets.UTF_8);
        MintedCred minted;
        try (Connection admin = DriverManager.getConnection(r.jdbcUrl(), r.adminUsername(), new String(pwd, StandardCharsets.UTF_8))) {
            minted = adapterFor(r, role).issue(admin, role, ttl);
        } catch (Exception e) {
            throw new IllegalStateException("issue on resource " + resourceName + " failed", e);
        } finally {
            Zeroize.wipe(pwd);
        }
        Lease lease = leases.register("resource/" + resourceName + "/" + roleName, ttl, l -> dropQuietly(minted.username(), role));
        return new IssuedCred(minted.username(), minted.password(), lease.leaseId(), lease.expireAt());
    }

    @Override
    public void revoke(String leaseId) {
        leases.revoke(leaseId);
    }

    private void dropQuietly(String username, RoleDef role) {
        ResourceRecord r = record();
        byte[] pwd = r.adminPassword().getBytes(StandardCharsets.UTF_8);
        try (Connection admin = DriverManager.getConnection(r.jdbcUrl(), r.adminUsername(), new String(pwd, StandardCharsets.UTF_8))) {
            adapterFor(r, role).revoke(admin, username, role);
        } catch (Exception e) {
            throw new IllegalStateException("revoke on resource " + resourceName + " failed", e);
        } finally {
            Zeroize.wipe(pwd);
        }
    }
}
