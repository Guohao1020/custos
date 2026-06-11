package io.custos.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 绑定 application.yml 的 custos.* 配置。 */
@ConfigurationProperties(prefix = "custos")
public class CustosProperties {
    private Engine engine = new Engine();
    private Nacos nacos = new Nacos();
    private Identity identity = new Identity();
    private Broker broker = new Broker();
    private Console console = new Console();

    public static class Engine {
        private String storageUrl = "jdbc:mysql://localhost:3306/custos";
        private String storageUsername = "custos";
        private String storagePassword = "custos";
        private int shares = 5;
        private int threshold = 3;
        public String getStorageUrl() { return storageUrl; } public void setStorageUrl(String v) { storageUrl = v; }
        public String getStorageUsername() { return storageUsername; } public void setStorageUsername(String v) { storageUsername = v; }
        public String getStoragePassword() { return storagePassword; } public void setStoragePassword(String v) { storagePassword = v; }
        public int getShares() { return shares; } public void setShares(int v) { shares = v; }
        public int getThreshold() { return threshold; } public void setThreshold(int v) { threshold = v; }
    }
    public static class Nacos {
        private String serverAddr = ""; private String namespace = "public"; private String policyDataId = "custos-policy"; private String group = "DEFAULT_GROUP";
        private String username = ""; private String password = "";
        public String getServerAddr() { return serverAddr; } public void setServerAddr(String v) { serverAddr = v; }
        public String getNamespace() { return namespace; } public void setNamespace(String v) { namespace = v; }
        public String getPolicyDataId() { return policyDataId; } public void setPolicyDataId(String v) { policyDataId = v; }
        public String getGroup() { return group; } public void setGroup(String v) { group = v; }
        public String getUsername() { return username; } public void setUsername(String v) { username = v; }
        public String getPassword() { return password; } public void setPassword(String v) { password = v; }
    }
    public static class Identity {
        private String issuer = "custos";
        public String getIssuer() { return issuer; } public void setIssuer(String v) { issuer = v; }
    }
    public static class Broker {
        private String dbReadonlySchema = "appdb"; private String targetJdbcUrl = "jdbc:mysql://localhost:3306/appdb";
        public String getDbReadonlySchema() { return dbReadonlySchema; } public void setDbReadonlySchema(String v) { dbReadonlySchema = v; }
        public String getTargetJdbcUrl() { return targetJdbcUrl; } public void setTargetJdbcUrl(String v) { targetJdbcUrl = v; }
    }
    public static class Console {
        /** 允许跨域的 console 源（逗号分隔多个；默认 dev 的 Vite 5173 + compose 的 3000）。 */
        private String origin = "http://localhost:5173,http://localhost:3000";
        public String getOrigin() { return origin; } public void setOrigin(String v) { origin = v; }
    }
    public Engine getEngine() { return engine; } public void setEngine(Engine v) { engine = v; }
    public Nacos getNacos() { return nacos; } public void setNacos(Nacos v) { nacos = v; }
    public Identity getIdentity() { return identity; } public void setIdentity(Identity v) { identity = v; }
    public Broker getBroker() { return broker; } public void setBroker(Broker v) { broker = v; }
    public Console getConsole() { return console; } public void setConsole(Console v) { console = v; }
}
