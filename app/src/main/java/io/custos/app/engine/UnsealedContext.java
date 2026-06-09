package io.custos.app.engine;

import io.custos.broker.BrokerService;
import io.custos.engine.audit.AuditLog;
import io.custos.engine.storage.Storage;

/** 解封成功后装配的运营组件集合（依赖 keyring）。 */
public record UnsealedContext(Storage storage, AuditLog audit, BrokerService broker) {}
