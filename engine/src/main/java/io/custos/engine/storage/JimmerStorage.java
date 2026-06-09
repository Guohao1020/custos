package io.custos.engine.storage;

import io.custos.engine.barrier.Barrier;
import org.babyfish.jimmer.sql.JSqlClient;

import java.util.List;
import java.util.Optional;

/** Jimmer 全密文存储：put 时 barrier.seal，get 时 barrier.open；save 按 @Id upsert。 */
public final class JimmerStorage implements Storage {

    private final JSqlClient sql;
    private final Barrier barrier;

    public JimmerStorage(JSqlClient sql, Barrier barrier) {
        this.sql = sql;
        this.barrier = barrier;
    }

    @Override
    public Optional<byte[]> get(String key) {
        StorageEntry e = sql.getEntities().findById(StorageEntry.class, key);
        return e == null ? Optional.empty() : Optional.of(barrier.open(e.value()));
    }

    @Override
    public void put(String key, byte[] value) {
        byte[] sealed = barrier.seal(value);
        sql.getEntities().save(
                StorageEntryDraft.$.produce(d -> {
                    d.setKey(key);
                    d.setValue(sealed);
                    d.setUpdatedAt(System.currentTimeMillis());
                })
        );
    }

    @Override
    public void delete(String key) {
        sql.getEntities().delete(StorageEntry.class, key);
    }

    @Override
    public List<String> list(String prefix) {
        StorageEntryTable t = StorageEntryTable.$;
        return sql.createQuery(t)
                .where(t.key().like(prefix + "%"))
                .select(t.key())
                .execute();
    }
}
