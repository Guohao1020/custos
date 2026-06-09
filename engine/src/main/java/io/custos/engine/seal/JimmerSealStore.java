package io.custos.engine.seal;

import org.babyfish.jimmer.sql.JSqlClient;

import java.util.Optional;

/** SealStore 的 Jimmer 实现（custos_seal_config）。全列已设 + 按 @Id → save 走 UPSERT，可跨实例恢复解封状态。 */
public final class JimmerSealStore implements SealStore {

    private final JSqlClient sql;

    public JimmerSealStore(JSqlClient sql) {
        this.sql = sql;
    }

    @Override
    public Optional<byte[]> get(String key) {
        SealConfigEntry e = sql.getEntities().findById(SealConfigEntry.class, key);
        return e == null ? Optional.empty() : Optional.of(e.value());
    }

    @Override
    public void put(String key, byte[] value) {
        sql.getEntities().save(
                SealConfigEntryDraft.$.produce(d -> {
                    d.setKey(key);
                    d.setValue(value);
                })
        );
    }
}
