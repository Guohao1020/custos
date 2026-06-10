-- demo 初始化：建库 + Custos 自身元数据表 + 目标只读库 + 授权
CREATE DATABASE IF NOT EXISTS custos;
CREATE DATABASE IF NOT EXISTS appdb;

-- Custos 元数据表（与 engine/src/main/resources/db/schema.sql 一致；host 不自动建表）
USE custos;
CREATE TABLE IF NOT EXISTS custos_storage (
  skey       VARCHAR(255) PRIMARY KEY,
  svalue     LONGBLOB NOT NULL,
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

-- 目标受治理只读库
USE appdb;
CREATE TABLE IF NOT EXISTS orders (id INT, amount INT);
INSERT INTO orders VALUES (1, 100), (2, 200), (3, 300);

-- demo 授权：custos 账号需能 CREATE USER/GRANT 以现场签发动态只读凭证。
-- 生产应改用专用最小权限 admin 角色（见 docs/audit/AUDIT-PREP.md G3 思路），此处为 demo 简化。
GRANT ALL PRIVILEGES ON *.* TO 'custos'@'%' WITH GRANT OPTION;
FLUSH PRIVILEGES;
