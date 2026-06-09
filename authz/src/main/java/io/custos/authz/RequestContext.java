package io.custos.authz;

import java.util.Map;

/** ABAC 决策上下文属性包。键约定：clearance/resourceLevel(int)、hour(0-23)、ipTrusted(true/false)、intentSuspicious(true/false)。 */
public record RequestContext(Map<String, String> attributes) {
    public static RequestContext empty() { return new RequestContext(Map.of()); }
    public String attr(String k) { return attributes.get(k); }
    public int intAttr(String k, int dflt) {
        String v = attributes.get(k);
        if (v == null) return dflt;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return dflt; }
    }
    public boolean boolAttr(String k) { return "true".equals(attributes.get(k)); }
}
