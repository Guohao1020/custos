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
    private java.util.List<Tenant> tenants = new java.util.ArrayList<>();
    private Cluster cluster = new Cluster();

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
    /** 一个策略租户：name=RBAC domain（=请求里的 tenant），namespace=该租户策略所在的 Nacos namespace。
     *  tenants 为空时退化为单租户 default（namespace=nacos.namespace），严格向后兼容。 */
    public static class Tenant {
        private String name; private String namespace;
        public Tenant() {}
        public Tenant(String name, String namespace) { this.name = name; this.namespace = namespace; }
        public String getName() { return name; } public void setName(String v) { name = v; }
        public String getNamespace() { return namespace; } public void setNamespace(String v) { namespace = v; }
    }
    /** 集群注册配置：serviceName=注册到 Nacos NamingService 的服务名；register=是否在启动时注册自身。 */
    public static class Cluster {
        private String serviceName = "custos-host"; private boolean register = true;
        public String getServiceName() { return serviceName; } public void setServiceName(String v) { serviceName = v; }
        public boolean isRegister() { return register; } public void setRegister(boolean v) { register = v; }
    }
    public Engine getEngine() { return engine; } public void setEngine(Engine v) { engine = v; }
    public Nacos getNacos() { return nacos; } public void setNacos(Nacos v) { nacos = v; }
    public Identity getIdentity() { return identity; } public void setIdentity(Identity v) { identity = v; }
    public Broker getBroker() { return broker; } public void setBroker(Broker v) { broker = v; }
    public Console getConsole() { return console; } public void setConsole(Console v) { console = v; }
    public java.util.List<Tenant> getTenants() { return tenants; } public void setTenants(java.util.List<Tenant> v) { tenants = v; }
    public Cluster getCluster() { return cluster; } public void setCluster(Cluster v) { cluster = v; }
}
