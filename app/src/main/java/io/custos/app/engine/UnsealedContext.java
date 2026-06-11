package io.custos.app.engine;

import io.custos.broker.BrokerService;
import io.custos.engine.approval.ApprovalStore;
import io.custos.engine.audit.AuditLog;
import io.custos.engine.lease.LeaseManager;
import io.custos.engine.resource.ResourceManager;
import io.custos.engine.storage.Storage;

/** 解封成功后装配的运营组件集合（依赖 keyring）。审批单非密钥，明文直连 DB（不经 Barrier）。 */
public record UnsealedContext(Storage storage, AuditLog audit, BrokerService broker,
                              ResourceManager resourceManager, ApprovalStore approvals,
                              LeaseManager leases) {}
