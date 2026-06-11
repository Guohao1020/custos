package io.custos.engine.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ResourceRecordTest {
    @Test
    void jacksonRoundTrip() throws Exception {
        ObjectMapper om = new ObjectMapper();
        ResourceRecord r = new ResourceRecord("appdb", "db.relational", "mysql",
                "jdbc:mysql://localhost:3306/appdb", "custos", "custospwd",
                List.of(new RoleDef("read-only", RoleKind.BUILTIN_READONLY, List.of(), List.of(), 3600, "appdb")));
        byte[] json = om.writeValueAsBytes(r);
        ResourceRecord back = om.readValue(json, ResourceRecord.class);
        assertEquals(r, back);
        assertEquals("custospwd", back.adminPassword());
        assertEquals(RoleKind.BUILTIN_READONLY, back.roles().get(0).kind());
    }
}
