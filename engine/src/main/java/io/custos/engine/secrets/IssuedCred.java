package io.custos.engine.secrets;

/** 现场签发的临时凭证（仅经纪层持有，绝不返回 LLM）。 */
public record IssuedCred(String username, String password, String leaseId, long expireAt) {}
