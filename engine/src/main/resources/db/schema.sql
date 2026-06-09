CREATE TABLE IF NOT EXISTS custos_storage (
  skey       VARCHAR(255) PRIMARY KEY,
  svalue     LONGBLOB NOT NULL,          -- Barrier 密文
  updated_at BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS custos_seal_config (
  ckey VARCHAR(64) PRIMARY KEY,
  cval LONGBLOB NOT NULL
);
CREATE TABLE IF NOT EXISTS custos_audit (
  seq            BIGINT AUTO_INCREMENT PRIMARY KEY,
  ts             BIGINT NOT NULL, actor VARCHAR(512) NOT NULL,
  task VARCHAR(512), resource VARCHAR(512), action VARCHAR(64),
  decision VARCHAR(32), result_digest VARCHAR(128), sensitive_hmac VARCHAR(128),
  prev_hash VARCHAR(128) NOT NULL, chain_hash VARCHAR(128) NOT NULL
);
CREATE TABLE IF NOT EXISTS custos_lease (
  lease_id      VARCHAR(160) PRIMARY KEY,
  resource_path VARCHAR(512) NOT NULL,
  issued_at     BIGINT NOT NULL, expire_at BIGINT NOT NULL,
  revoked       TINYINT NOT NULL DEFAULT 0
);
