package io.custos.engine.resource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TemplateAdapterTest {
    @Test void substitutesPlaceholders() {
        String out = TemplateAdapter.render(
                "CREATE USER '{{name}}'@'%' IDENTIFIED BY '{{password}}'",
                "v_ro_abc", "deadbeef", "2026-01-01 00:00:00");
        assertEquals("CREATE USER 'v_ro_abc'@'%' IDENTIFIED BY 'deadbeef'", out);
    }
}
