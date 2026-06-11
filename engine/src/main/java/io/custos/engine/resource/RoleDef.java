package io.custos.engine.resource;
import java.util.List;
/**
 * 一个资源下的具名签发角色。
 * BUILTIN_READONLY：按资源 dialect 选内置适配器，creationStatements/revocationStatements 留空。
 * TEMPLATE：管理员自填 SQL，占位符 {{name}}/{{password}}/{{expiration}}。
 */
public record RoleDef(String name, RoleKind kind,
                      List<String> creationStatements, List<String> revocationStatements,
                      long defaultTtlSeconds, String schema) {}
