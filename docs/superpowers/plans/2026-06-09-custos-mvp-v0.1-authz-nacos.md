# Custos MVP v0.1 — 策略层（jCasbin + Nacos 秒级吊销）Implementation Plan（计划 4/5）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 以 TDD 实现 MVP 策略层——jCasbin RBAC + 工具级 scope、默认拒绝/deny 优先、可解释决策（命中策略 + 原因），以及「策略变更 → Watcher 重载 → 决策即时翻转」的**秒级吊销**机制。

**Architecture:** 独立 `authz` 模块。jCasbin 承载 PERM 模型；`Pdp` 包一层产出可解释 `Decision`。策略源抽象为 `ControlPlane`（发布/订阅）：**吊销逻辑用内存 `ControlPlane` 做确定性单测**（快、无容器），真实 `NacosControlPlane`（nacos-client）单独实现并用环境变量门控的冒烟测试（在计划 5 的 docker-compose 环境跑）。

**Tech Stack:** Java 21 · org.casbin:jcasbin 1.55.0 · com.alibaba.nacos:nacos-client 2.3.2 · JUnit 5

> 前置：计划 1/5（父 POM）。对应 spec §3.8、§5(秒级吊销)、详设 `docs/design/04-authz-design.md`、`docs/design/05-nacos-integration.md` §2。

---

## File Structure

| 文件 | 职责 |
|---|---|
| `pom.xml` | 加 `authz` 到 modules |
| `authz/pom.xml` | jcasbin + nacos-client + JUnit |
| `authz/src/main/resources/custos-rbac-model.conf` | jCasbin PERM 模型 |
| `authz/src/main/java/io/custos/authz/DecisionRequest.java` | 决策请求 |
| `authz/src/main/java/io/custos/authz/Decision.java` | 决策结果（可解释）|
| `authz/src/main/java/io/custos/authz/Pdp.java` | PDP 接口 |
| `authz/src/main/java/io/custos/authz/CasbinPdp.java` | jCasbin 实现 + 策略重载 |
| `authz/src/main/java/io/custos/authz/ControlPlane.java` | 策略发布/订阅抽象 |
| `authz/src/main/java/io/custos/authz/InMemoryControlPlane.java` | 测试用内存实现 |
| `authz/src/main/java/io/custos/authz/PolicyWatcher.java` | 订阅变更 → 重载 |
| `authz/src/main/java/io/custos/authz/NacosControlPlane.java` | nacos-client 实现 |
| `authz/src/test/java/io/custos/authz/**` | 测试 |

---

## Task 1: authz 模块 + jCasbin RBAC 模型 + 可解释 PDP

**Files:**
- Modify: `pom.xml`（modules 加 `authz`）
- Create: `authz/pom.xml`
- Create: `authz/src/main/resources/custos-rbac-model.conf`
- Create: `authz/src/main/java/io/custos/authz/DecisionRequest.java`
- Create: `authz/src/main/java/io/custos/authz/Decision.java`
- Create: `authz/src/main/java/io/custos/authz/Pdp.java`
- Create: `authz/src/main/java/io/custos/authz/CasbinPdp.java`
- Test: `authz/src/test/java/io/custos/authz/CasbinPdpTest.java`

- [ ] **Step 1: modules 加 authz**

`pom.xml` 的 `<modules>`：
```xml
  <modules>
    <module>engine</module>
    <module>identity</module>
    <module>authz</module>
  </modules>
```

- [ ] **Step 2: authz POM**

`authz/pom.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>io.custos</groupId>
    <artifactId>custos-parent</artifactId>
    <version>0.1.0-SNAPSHOT</version>
  </parent>
  <artifactId>custos-authz</artifactId>
  <dependencies>
    <dependency>
      <groupId>org.casbin</groupId>
      <artifactId>jcasbin</artifactId>
      <version>1.55.0</version>
    </dependency>
    <dependency>
      <groupId>com.alibaba.nacos</groupId>
      <artifactId>nacos-client</artifactId>
      <version>2.3.2</version>
    </dependency>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>
</project>
```

- [ ] **Step 3: jCasbin 模型（RBAC + 工具级 scope + deny 优先 + 默认拒绝）**

`authz/src/main/resources/custos-rbac-model.conf`:
```ini
[request_definition]
r = sub, obj, act

[policy_definition]
p = sub, obj, act, eft

[role_definition]
g = _, _

[policy_effect]
e = some(where (p.eft == allow)) && !some(where (p.eft == deny))

[matchers]
m = g(r.sub, p.sub) && keyMatch2(r.obj, p.obj) && (r.act == p.act || p.act == "*")
```

- [ ] **Step 4: 写失败测试（准/拒 + 可解释 + 默认拒绝）**

`authz/src/test/java/io/custos/authz/CasbinPdpTest.java`:
```java
package io.custos.authz;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CasbinPdpTest {

    // CSV 策略文本：reader 角色可只读 db 工具；claude-prod 属于 reader
    private static final String POLICY = """
            p, role:reader, tool:db/*, read, allow
            p, role:reader, tool:db/*, write, deny
            g, agent:claude-prod, role:reader
            """;

    private CasbinPdp pdp() {
        CasbinPdp pdp = new CasbinPdp();
        pdp.reload(POLICY);
        return pdp;
    }

    @Test
    void allowsReadForGrantedAgent() {
        Decision d = pdp().decide(new DecisionRequest("agent:claude-prod", "tool:db/query_orders", "read"));
        assertTrue(d.allowed());
        assertFalse(d.matchedPolicies().isEmpty(), "应给出命中策略");
    }

    @Test
    void deniesWriteEvenForGrantedAgent() {
        Decision d = pdp().decide(new DecisionRequest("agent:claude-prod", "tool:db/query_orders", "write"));
        assertFalse(d.allowed());
        assertNotNull(d.reason());
    }

    @Test
    void defaultDeniesUnknownAgent() {
        Decision d = pdp().decide(new DecisionRequest("agent:unknown", "tool:db/query_orders", "read"));
        assertFalse(d.allowed(), "无匹配 → 默认拒绝");
    }
}
```

- [ ] **Step 5: 运行测试，确认失败**

Run: `mvn -q -pl authz test -Dtest=CasbinPdpTest`
Expected: 编译失败（Pdp 类未定义）。

- [ ] **Step 6: 写 DecisionRequest / Decision / Pdp**

`authz/src/main/java/io/custos/authz/DecisionRequest.java`:
```java
package io.custos.authz;

/** 决策请求（MVP）：sub=主体(agent:xxx 或 role:xxx)，obj=工具(tool:server/tool)，act=动作。 */
public record DecisionRequest(String sub, String obj, String act) {}
```

`authz/src/main/java/io/custos/authz/Decision.java`:
```java
package io.custos.authz;

import java.util.List;

/** 可解释决策：是否放行 + 命中策略 + 人读原因。 */
public record Decision(boolean allowed, List<String> matchedPolicies, String reason) {}
```

`authz/src/main/java/io/custos/authz/Pdp.java`:
```java
package io.custos.authz;

public interface Pdp {
    Decision decide(DecisionRequest req);
    /** 用新的策略 CSV 文本热重载（来自 ControlPlane）。 */
    void reload(String policyCsv);
}
```

- [ ] **Step 7: 实现 CasbinPdp**

`authz/src/main/java/io/custos/authz/CasbinPdp.java`:
```java
package io.custos.authz;

import org.casbin.jcasbin.main.EnforceResult;
import org.casbin.jcasbin.main.Enforcer;
import org.casbin.jcasbin.model.Model;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** jCasbin RBAC PDP：默认拒绝 + deny 优先；enforceEx 给出命中策略（可解释）。reload 线程安全替换 enforcer。 */
public final class CasbinPdp implements Pdp {

    private static final String MODEL_TEXT = """
            [request_definition]
            r = sub, obj, act
            [policy_definition]
            p = sub, obj, act, eft
            [role_definition]
            g = _, _
            [policy_effect]
            e = some(where (p.eft == allow)) && !some(where (p.eft == deny))
            [matchers]
            m = g(r.sub, p.sub) && keyMatch2(r.obj, p.obj) && (r.act == p.act || p.act == "*")
            """;

    private final AtomicReference<Enforcer> enforcerRef = new AtomicReference<>(buildEnforcer(""));

    @Override
    public Decision decide(DecisionRequest req) {
        Enforcer e = enforcerRef.get();
        EnforceResult res = e.enforceEx(req.sub(), req.obj(), req.act());
        boolean allow = res.isAllow();          // jcasbin 1.55.0: EnforceResult.isAllow()（非 getResult）
        List<String> matched = res.getExplain();
        String reason = allow
                ? "命中允许策略: " + matched
                : "无匹配允许策略或命中拒绝策略（默认拒绝）: " + req;
        return new Decision(allow, matched, reason);
    }

    @Override
    public void reload(String policyCsv) {
        enforcerRef.set(buildEnforcer(policyCsv == null ? "" : policyCsv));
    }

    private static Enforcer buildEnforcer(String policyCsv) {
        Model model = new Model();
        model.loadModelFromText(MODEL_TEXT);
        Enforcer e = new Enforcer(model);
        for (String line : policyCsv.split("\\R")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            String[] parts = splitCsv(t);
            if (parts.length == 0) continue;
            String ptype = parts[0];
            String[] rule = new String[parts.length - 1];
            System.arraycopy(parts, 1, rule, 0, rule.length);
            if ("p".equals(ptype)) {
                e.addNamedPolicy("p", rule);
            } else if ("g".equals(ptype)) {
                e.addNamedGroupingPolicy("g", rule);
            }
        }
        return e;
    }

    private static String[] splitCsv(String line) {
        String[] raw = line.split(",");
        for (int i = 0; i < raw.length; i++) raw[i] = raw[i].trim();
        return raw;
    }
}
```

- [ ] **Step 8: 运行测试，确认通过**

Run: `mvn -q -pl authz test -Dtest=CasbinPdpTest`
Expected: PASS（3 个用例）。

- [ ] **Step 9: 提交**
```bash
git add pom.xml authz/pom.xml authz/src/main/resources authz/src/main/java/io/custos/authz/DecisionRequest.java authz/src/main/java/io/custos/authz/Decision.java authz/src/main/java/io/custos/authz/Pdp.java authz/src/main/java/io/custos/authz/CasbinPdp.java authz/src/test/java/io/custos/authz/CasbinPdpTest.java
git commit -m "feat(authz): jCasbin RBAC PDP with tool-level scope and explainable decisions"
```

---

## Task 2: ControlPlane 抽象 + PolicyWatcher + 秒级吊销（确定性单测）

**Files:**
- Create: `authz/src/main/java/io/custos/authz/ControlPlane.java`
- Create: `authz/src/main/java/io/custos/authz/InMemoryControlPlane.java`
- Create: `authz/src/main/java/io/custos/authz/PolicyWatcher.java`
- Test: `authz/src/test/java/io/custos/authz/RevocationViaWatcherTest.java`

- [ ] **Step 1: 写失败测试（改策略 → Watcher 重载 → 决策从准翻成拒）**

`authz/src/test/java/io/custos/authz/RevocationViaWatcherTest.java`:
```java
package io.custos.authz;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.awaitility.Awaitility.await;   // 见下方注：若不引 awaitility，可用轮询

class RevocationViaWatcherTest {

    private static final String ALLOW = """
            p, role:reader, tool:db/*, read, allow
            g, agent:claude-prod, role:reader
            """;
    private static final String REVOKED = """
            p, role:reader, tool:db/*, read, deny
            g, agent:claude-prod, role:reader
            """;

    @Test
    void changingPolicyRevokesWithinMillis() {
        InMemoryControlPlane cp = new InMemoryControlPlane();
        cp.publish("custos-policy", ALLOW);

        CasbinPdp pdp = new CasbinPdp();
        PolicyWatcher watcher = new PolicyWatcher(cp, "custos-policy", pdp);
        watcher.start();   // 初次加载 + 订阅

        DecisionRequest req = new DecisionRequest("agent:claude-prod", "tool:db/query_orders", "read");
        assertTrue(pdp.decide(req).allowed(), "吊销前应放行");

        long t0 = System.currentTimeMillis();
        cp.publish("custos-policy", REVOKED);     // 管理员改策略

        // 等待 watcher 重载（确定性：InMemoryControlPlane 同步回调）
        await().atMost(Duration.ofSeconds(2)).until(() -> !pdp.decide(req).allowed());
        long elapsed = System.currentTimeMillis() - t0;
        assertFalse(pdp.decide(req).allowed(), "吊销后应拒绝");
        assertTrue(elapsed < 2000, "应秒级生效，实测 " + elapsed + "ms");
    }
}
```

> 注：`awaitility` 仅测试用。在 `authz/pom.xml` 加：
> ```xml
> <dependency><groupId>org.awaitility</groupId><artifactId>awaitility</artifactId><version>4.2.1</version><scope>test</scope></dependency>
> ```
> （若不想引入，可改用 `for` 轮询 + `Thread.sleep(20)`，最多 100 次。）

- [ ] **Step 2: 运行测试，确认失败**

Run: `mvn -q -pl authz test -Dtest=RevocationViaWatcherTest`
Expected: 编译失败（ControlPlane/PolicyWatcher 未定义）。

- [ ] **Step 3: 写 ControlPlane 接口 + InMemoryControlPlane**

`authz/src/main/java/io/custos/authz/ControlPlane.java`:
```java
package io.custos.authz;

import java.util.function.Consumer;

/** 控制面：发布/订阅策略配置。NacosControlPlane 为生产实现，InMemoryControlPlane 为测试实现。 */
public interface ControlPlane {
    void publish(String dataId, String content);
    String get(String dataId);
    /** 订阅变更；内容变化时回调 onChange。 */
    void subscribe(String dataId, Consumer<String> onChange);
}
```

`authz/src/main/java/io/custos/authz/InMemoryControlPlane.java`:
```java
package io.custos.authz;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** 内存控制面：publish 同步回调所有订阅者（确定性测试用）。 */
public final class InMemoryControlPlane implements ControlPlane {

    private final Map<String, String> store = new ConcurrentHashMap<>();
    private final Map<String, List<Consumer<String>>> listeners = new ConcurrentHashMap<>();

    @Override
    public void publish(String dataId, String content) {
        store.put(dataId, content);
        listeners.getOrDefault(dataId, List.of()).forEach(l -> l.accept(content));
    }

    @Override
    public String get(String dataId) {
        return store.get(dataId);
    }

    @Override
    public void subscribe(String dataId, Consumer<String> onChange) {
        listeners.computeIfAbsent(dataId, k -> new CopyOnWriteArrayList<>()).add(onChange);
    }
}
```

- [ ] **Step 4: 写 PolicyWatcher**

`authz/src/main/java/io/custos/authz/PolicyWatcher.java`:
```java
package io.custos.authz;

/** 监听 ControlPlane 上的策略 dataId，变更即重载 PDP（秒级吊销）。 */
public final class PolicyWatcher {

    private final ControlPlane controlPlane;
    private final String dataId;
    private final Pdp pdp;

    public PolicyWatcher(ControlPlane controlPlane, String dataId, Pdp pdp) {
        this.controlPlane = controlPlane;
        this.dataId = dataId;
        this.pdp = pdp;
    }

    /** 初次加载当前策略，并订阅后续变更。 */
    public void start() {
        String current = controlPlane.get(dataId);
        pdp.reload(current == null ? "" : current);
        controlPlane.subscribe(dataId, pdp::reload);
    }
}
```

- [ ] **Step 5: 运行测试，确认通过**

Run: `mvn -q -pl authz test -Dtest=RevocationViaWatcherTest`
Expected: PASS。

- [ ] **Step 6: 提交**
```bash
git add authz/pom.xml authz/src/main/java/io/custos/authz/ControlPlane.java authz/src/main/java/io/custos/authz/InMemoryControlPlane.java authz/src/main/java/io/custos/authz/PolicyWatcher.java authz/src/test/java/io/custos/authz/RevocationViaWatcherTest.java
git commit -m "feat(authz): control-plane watcher with second-level policy revocation"
```

---

## Task 3: NacosControlPlane（nacos-client 真实实现，环境门控冒烟测试）

**Files:**
- Create: `authz/src/main/java/io/custos/authz/NacosControlPlane.java`
- Test: `authz/src/test/java/io/custos/authz/NacosControlPlaneSmokeIT.java`

- [ ] **Step 1: 写环境门控冒烟测试（仅当提供 NACOS_ADDR 时运行）**

`authz/src/test/java/io/custos/authz/NacosControlPlaneSmokeIT.java`:
```java
package io.custos.authz;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** 仅当环境变量 NACOS_ADDR 存在时运行（计划 5 的 docker-compose 或本地 Nacos）。 */
@EnabledIfEnvironmentVariable(named = "NACOS_ADDR", matches = ".+")
class NacosControlPlaneSmokeIT {

    @Test
    void publishGetAndSubscribeRoundTrips() throws Exception {
        String addr = System.getenv("NACOS_ADDR");
        NacosControlPlane cp = new NacosControlPlane(addr, "public", "DEFAULT_GROUP");

        cp.publish("custos-policy-it", "p, role:reader, tool:db/*, read, allow");
        Thread.sleep(300);
        assertNotNull(cp.get("custos-policy-it"));

        AtomicReference<String> got = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        cp.subscribe("custos-policy-it", v -> { got.set(v); latch.countDown(); });

        cp.publish("custos-policy-it", "p, role:reader, tool:db/*, read, deny");
        assertTrue(latch.await(5, TimeUnit.SECONDS), "应收到秒级变更推送");
        assertTrue(got.get().contains("deny"));
    }
}
```

- [ ] **Step 2: 运行测试，确认（无 NACOS_ADDR 时跳过）**

Run: `mvn -q -pl authz test -Dtest=NacosControlPlaneSmokeIT`
Expected: 编译失败（NacosControlPlane 未定义）。

- [ ] **Step 3: 实现 NacosControlPlane**

`authz/src/main/java/io/custos/authz/NacosControlPlane.java`:
```java
package io.custos.authz;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;

import java.util.Properties;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** 真实控制面：策略存 Nacos 配置（Raft CP），gRPC 长连接秒级推送。 */
public final class NacosControlPlane implements ControlPlane {

    private final ConfigService configService;
    private final String group;

    public NacosControlPlane(String serverAddr, String namespace, String group) {
        this.group = group;
        try {
            Properties props = new Properties();
            props.put("serverAddr", serverAddr);
            props.put("namespace", namespace);
            this.configService = NacosFactory.createConfigService(props);
        } catch (Exception e) {
            throw new IllegalStateException("create nacos config service failed", e);
        }
    }

    @Override
    public void publish(String dataId, String content) {
        try {
            configService.publishConfig(dataId, group, content);
        } catch (Exception e) {
            throw new IllegalStateException("nacos publish failed", e);
        }
    }

    @Override
    public String get(String dataId) {
        try {
            return configService.getConfig(dataId, group, 3000);
        } catch (Exception e) {
            throw new IllegalStateException("nacos get failed", e);
        }
    }

    @Override
    public void subscribe(String dataId, Consumer<String> onChange) {
        try {
            configService.addListener(dataId, group, new Listener() {
                @Override
                public Executor getExecutor() { return null; }
                @Override
                public void receiveConfigInfo(String configInfo) { onChange.accept(configInfo); }
            });
        } catch (Exception e) {
            throw new IllegalStateException("nacos subscribe failed", e);
        }
    }
}
```

- [ ] **Step 4: 运行测试**

Run: `mvn -q -pl authz test -Dtest=NacosControlPlaneSmokeIT`
Expected: 无 `NACOS_ADDR` → SKIPPED（计划 5 在 compose 中设 `NACOS_ADDR` 后运行并验证秒级推送）。编译应通过。

- [ ] **Step 5: 运行全部 authz 测试**

Run: `mvn -q -pl authz test`
Expected: CasbinPdpTest + RevocationViaWatcherTest PASS；Nacos 冒烟 SKIPPED。

- [ ] **Step 6: 提交**
```bash
git add authz/src/main/java/io/custos/authz/NacosControlPlane.java authz/src/test/java/io/custos/authz/NacosControlPlaneSmokeIT.java
git commit -m "feat(authz): Nacos control-plane adapter with env-gated smoke test"
```

---

## Self-Review（对照 spec §3.8、§5、详设 04/05）

- **Spec 覆盖**：jCasbin RBAC + 工具级 scope（§3.8）→ Task 1；默认拒绝 + deny 优先 → 模型 effect；可解释（命中策略 + 原因，详设 04 §4）→ `Decision` + enforceEx；Nacos Adapter/Watcher 秒级吊销（§5、详设 05 §2）→ Task 2（逻辑确定性单测）+ Task 3（真实 Nacos）。
- **类型一致性**：`DecisionRequest(sub,obj,act)`、`Decision(allowed,matchedPolicies,reason)`、`Pdp.decide/reload`、`ControlPlane.publish/get/subscribe`、`PolicyWatcher.start` 跨任务一致；`NacosControlPlane` 与 `InMemoryControlPlane` 实现同一接口。
- **占位扫描**：无 TODO/TBD。jCasbin API 已**源码核准**（1.55.0）：`new Enforcer(Model)`、`Model.loadModelFromText(text)`、`enforceEx(...) → EnforceResult.isAllow()/getExplain()`（**是 `isAllow()`，非 `getResult()`**）、`addNamedPolicy/addNamedGroupingPolicy(String ptype, String... rule)`。
- **测试策略**：秒级吊销**逻辑**用内存控制面做确定性快测（不依赖容器）；真实 Nacos 推送在计划 5 compose 环境用环境门控 IT 验证——避免 Testcontainers 对 Nacos gRPC 端口映射的脆弱性。
- **可独立交付**：authz 模块可单独编译/测试，产出可解释 PDP + 秒级热更新吊销。

> **下一计划**：5/5 经纪层 + MCP + docker-compose demo（端到端串起 identity→PDP→动态凭证→secretless→审计 + AC1–AC8）。
