package io.custos.sdk;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.junit.jupiter.api.Assertions.*;

class CustosClientAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CustosClientAutoConfiguration.class));

    @Test
    void registersClientByDefault() {
        runner.run(ctx -> assertNotNull(ctx.getBean(CustosClient.class)));
    }

    @Test
    void bindsProperties() {
        runner.withPropertyValues("custos.client.base-url=http://h:9999", "custos.client.admin-token=t0")
                .run(ctx -> {
                    CustosClientProperties p = ctx.getBean(CustosClientProperties.class);
                    assertEquals("http://h:9999", p.getBaseUrl());
                    assertEquals("t0", p.getAdminToken());
                });
    }

    @Test
    void backsOffWhenUserDefinesOwnClient() {
        runner.withUserConfiguration(Custom.class)
                .run(ctx -> assertSame(Custom.MARKER, ctx.getBean(CustosClient.class)));
    }

    @Configuration
    static class Custom {
        static final CustosClient MARKER = new CustosClient("http://marker", "");
        @Bean
        CustosClient custosClient() { return MARKER; }
    }
}
