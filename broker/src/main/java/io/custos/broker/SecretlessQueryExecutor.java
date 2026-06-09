package io.custos.broker;

import io.custos.engine.secrets.IssuedCred;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 用临时凭证执行只读查询，只回行数据。凭证仅在本方法内使用，绝不外泄到返回值。 */
public final class SecretlessQueryExecutor {

    public List<Map<String, Object>> runReadonly(String jdbcUrl, IssuedCred cred, String sql) {
        requireReadonly(sql);
        try (Connection conn = DriverManager.getConnection(jdbcUrl, cred.username(), cred.password());
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            ResultSetMetaData md = rs.getMetaData();
            int cols = md.getColumnCount();
            List<Map<String, Object>> rows = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= cols; i++) row.put(md.getColumnLabel(i), rs.getObject(i));
                rows.add(row);
            }
            return rows;
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("secretless query failed", e);
        }
    }

    /** 只允许单条 SELECT，拒绝写语句与多语句注入。 */
    private static void requireReadonly(String sql) {
        String t = sql.strip();
        if (t.endsWith(";")) t = t.substring(0, t.length() - 1).strip();
        if (t.contains(";")) throw new IllegalArgumentException("multiple statements not allowed");
        String upper = t.toUpperCase();
        if (!(upper.startsWith("SELECT ") || upper.startsWith("SELECT\n") || upper.startsWith("WITH "))) {
            throw new IllegalArgumentException("only read-only SELECT is allowed");
        }
    }
}
