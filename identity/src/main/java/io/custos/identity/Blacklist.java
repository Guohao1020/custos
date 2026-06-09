package io.custos.identity;

/** 吊销黑名单：按 subject(=SPIFFE id) 判定是否已吊销。计划 4 由 Nacos 配置驱动热更新。 */
public interface Blacklist {
    boolean isRevoked(String subject);
}
