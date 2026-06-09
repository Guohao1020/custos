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
