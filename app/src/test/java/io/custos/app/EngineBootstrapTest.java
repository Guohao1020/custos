package io.custos.app;

import io.custos.app.config.CustosProperties;
import io.custos.app.engine.EngineBootstrap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EngineBootstrapTest {
    /** 构建对象图不应触发数据库连接（连接惰性发生在 status()/读写时）；sealed 启动语义由 OperatorServiceTest 用真库验证。 */
    @Test
    void buildsComponentsWithoutConnecting() {
        CustosProperties props = new CustosProperties();
        EngineBootstrap b = new EngineBootstrap(props);
        assertNotNull(b.suite());
        assertNotNull(b.sql());
        assertNotNull(b.dataSource());
        assertNotNull(b.sealManager(), "SealManager 应被构建（尚未连库）");
    }
}
