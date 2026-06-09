package io.custos.identity;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** SPIFFE 风格身份命名：custos://<trust-domain>/agent/<agent>/session/<session>。 */
public record AgentId(String trustDomain, String agent, String session) {

    private static final Pattern P =
            Pattern.compile("^custos://([^/]+)/agent/([^/]+)/session/([^/]+)$");

    public String toUri() {
        return "custos://" + trustDomain + "/agent/" + agent + "/session/" + session;
    }

    public static AgentId parse(String uri) {
        Matcher m = P.matcher(uri);
        if (!m.matches()) throw new IllegalArgumentException("invalid custos id: " + uri);
        return new AgentId(m.group(1), m.group(2), m.group(3));
    }
}
