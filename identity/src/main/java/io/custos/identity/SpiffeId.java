package io.custos.identity;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** SPIFFE ID：spiffe://<trust-domain>/<path>。 */
public record SpiffeId(String trustDomain, String path) {
    private static final Pattern P = Pattern.compile("^spiffe://([^/]+)/(.+)$");
    public String toUri() { return "spiffe://" + trustDomain + "/" + path; }
    public static SpiffeId parse(String uri) {
        Matcher m = P.matcher(uri);
        if (!m.matches()) throw new IllegalArgumentException("invalid spiffe id: " + uri);
        return new SpiffeId(m.group(1), m.group(2));
    }
}
