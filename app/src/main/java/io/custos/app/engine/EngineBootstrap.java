package io.custos.app.engine;

import com.mysql.cj.jdbc.MysqlDataSource;
import io.custos.app.config.CustosProperties;
import io.custos.engine.crypto.IntlSuite;
import io.custos.engine.persistence.JimmerClients;
import io.custos.engine.seal.DefaultSealManager;
import io.custos.engine.seal.JimmerSealStore;
import io.custos.engine.seal.SealManager;
import org.babyfish.jimmer.sql.JSqlClient;

import javax.sql.DataSource;

/** 启动期可建（无需密钥）：DataSource → JSqlClient → JimmerSealStore → SealManager（sealed）。 */
public final class EngineBootstrap {

    private final IntlSuite suite = new IntlSuite();
    private final DataSource dataSource;
    private final JSqlClient sql;
    private final SealManager sealManager;

    public EngineBootstrap(CustosProperties props) {
        MysqlDataSource ds = new MysqlDataSource();
        ds.setUrl(props.getEngine().getStorageUrl());
        ds.setUser(props.getEngine().getStorageUsername());
        ds.setPassword(props.getEngine().getStoragePassword());
        this.dataSource = ds;
        this.sql = JimmerClients.of(ds);
        this.sealManager = new DefaultSealManager(suite, new JimmerSealStore(sql));
    }

    public IntlSuite suite() { return suite; }
    public DataSource dataSource() { return dataSource; }
    public JSqlClient sql() { return sql; }
    public SealManager sealManager() { return sealManager; }
}
