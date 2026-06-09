package io.custos.engine.storage;

import org.babyfish.jimmer.sql.Column;
import org.babyfish.jimmer.sql.Entity;
import org.babyfish.jimmer.sql.Id;
import org.babyfish.jimmer.sql.Table;

/** 通用密文 KV 行；value 为 Barrier 密文（明文加解密在 service 层）。列名避开 KEY/VALUE 保留字。 */
@Entity
@Table(name = "custos_storage")
public interface StorageEntry {

    @Id
    @Column(name = "skey")
    String key();

    @Column(name = "svalue")
    byte[] value();

    @Column(name = "updated_at")
    long updatedAt();
}
