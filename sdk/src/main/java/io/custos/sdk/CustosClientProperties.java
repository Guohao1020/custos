package io.custos.sdk;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** custos.client.* 配置。 */
@ConfigurationProperties(prefix = "custos.client")
public class CustosClientProperties {
    private String baseUrl = "http://127.0.0.1:8080";
    private String adminToken = "";
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String v) { baseUrl = v; }
    public String getAdminToken() { return adminToken; }
    public void setAdminToken(String v) { adminToken = v; }
}
