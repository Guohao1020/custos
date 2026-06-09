package io.custos.app.query;

import io.custos.app.operator.OperatorService;
import io.custos.broker.QueryIntent;
import io.custos.broker.QueryResult;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
public class QueryController {
    private final OperatorService op;
    public QueryController(OperatorService op) { this.op = op; }

    @PostMapping("/query_db")
    public QueryResult query(@RequestBody Map<String, String> body) {
        try {
            return op.unsealed().broker().queryDb(
                    new QueryIntent(body.get("tool"), body.get("schema"), body.get("sql")),
                    body.get("userToken"));
        } catch (IllegalStateException sealed) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "sealed");
        }
    }
}
