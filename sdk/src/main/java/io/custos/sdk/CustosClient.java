package io.custos.sdk;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** Custos host 客户端：secretless——只透传 userToken 与查询，结果即行数据 JSON。 */
public final class CustosClient {

    private final String baseUrl;
    private final String adminToken;
    private final HttpClient http = HttpClient.newHttpClient();

    public CustosClient(String baseUrl, String adminToken) {
        this.baseUrl = baseUrl;
        this.adminToken = adminToken;
    }

    /** POST /query_db；返回响应体 JSON 文本（allowed/rows/denyReason）。 */
    public String queryDb(String tool, String schema, String sql, String userToken) {
        String body = "{\"tool\":" + q(tool) + ",\"schema\":" + q(schema)
                + ",\"sql\":" + q(sql) + ",\"userToken\":" + q(userToken) + "}";
        return send(HttpRequest.newBuilder(URI.create(baseUrl + "/query_db"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    /** GET /operator/status（admin，带 Bearer）。 */
    public String operatorStatus() {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + "/operator/status"))
                .header("Authorization", "Bearer " + adminToken).GET().build());
    }

    private String send(HttpRequest req) {
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString()).body();
        } catch (Exception e) {
            throw new IllegalStateException("custos host unreachable: " + baseUrl, e);
        }
    }

    private static String q(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}
