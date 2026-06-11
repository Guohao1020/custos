package io.custos.engine.resource;
/** 适配器现场签发的裸凭证（无 lease，由引擎登记租约）。 */
public record MintedCred(String username, String password) {}
