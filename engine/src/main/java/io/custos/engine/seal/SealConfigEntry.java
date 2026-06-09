package io.custos.engine.seal;

import org.babyfish.jimmer.sql.Column;
import org.babyfish.jimmer.sql.Entity;
import org.babyfish.jimmer.sql.Id;
import org.babyfish.jimmer.sql.Table;

/** 解封配置行（custos_seal_config）。value 视键而定：wrapped_barrier 为密文，threshold/shares 为 4 字节元数据。 */
@Entity
@Table(name = "custos_seal_config")
public interface SealConfigEntry {

    @Id
    @Column(name = "ckey")
    String key();

    @Column(name = "cval")
    byte[] value();
}
