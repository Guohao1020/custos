package io.custos.sdk;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(CustosClientProperties.class)
public class CustosClientAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public CustosClient custosClient(CustosClientProperties p) {
        return new CustosClient(p.getBaseUrl(), p.getAdminToken());
    }
}
