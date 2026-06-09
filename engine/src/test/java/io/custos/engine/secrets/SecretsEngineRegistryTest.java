package io.custos.engine.secrets;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class SecretsEngineRegistryTest {

    private SecretsEngine stub(String type) {
        return new SecretsEngine() {
            public String type() { return type; }
            public IssuedCred issue(String path, Duration ttl) { return new IssuedCred("u", "p", "l", 0L); }
            public void revoke(String leaseId) { }
        };
    }

    @Test
    void mountAndResolveByName() {
        SecretsEngineRegistry reg = new SecretsEngineRegistry();
        reg.mount("db", stub("db-readonly"));
        assertEquals("db-readonly", reg.require("db").type());
    }

    @Test
    void unknownMountThrows() {
        SecretsEngineRegistry reg = new SecretsEngineRegistry();
        assertThrows(IllegalArgumentException.class, () -> reg.require("nope"));
    }
}
