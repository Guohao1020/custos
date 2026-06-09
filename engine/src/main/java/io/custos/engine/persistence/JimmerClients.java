package io.custos.engine.persistence;

import org.babyfish.jimmer.sql.JSqlClient;
import org.babyfish.jimmer.sql.dialect.MySqlDialect;
import org.babyfish.jimmer.sql.runtime.ConnectionManager;

import javax.sql.DataSource;

/** 由 DataSource 构建 JSqlClient（MySQL 方言）。app 模块用 Spring starter 装配，引擎/测试用此工厂。 */
public final class JimmerClients {
    private JimmerClients() {}

    public static JSqlClient of(DataSource dataSource) {
        return JSqlClient.newBuilder()
                .setConnectionManager(ConnectionManager.simpleConnectionManager(dataSource))
                .setDialect(new MySqlDialect())
                .build();
    }
}
