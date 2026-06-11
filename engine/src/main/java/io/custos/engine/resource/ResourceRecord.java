package io.custos.engine.resource;
import java.util.List;
/**
 * 受治理后端的注册条目。adminPassword 为高权限凭证——整条记录经 Barrier 加密落盘，
 * 该字段从不出现在 REST 响应/日志/IssuedCred 中。
 * type 为开放分类（v0.5 仅 db.relational）；dialect ∈ {mysql, postgresql}（template 角色不依赖 dialect）。
 */
public record ResourceRecord(String name, String type, String dialect,
                             String jdbcUrl, String adminUsername, String adminPassword,
                             List<RoleDef> roles) {
    public RoleDef role(String roleName) {
        return roles.stream().filter(r -> r.name().equals(roleName)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no role '" + roleName + "' on resource '" + name + "'"));
    }
}
