package io.custos.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ScopeType;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** Custos 运维 CLI：operator/policy/audit 打 REST admin。--server/--token 跨子命令继承。 */
@Command(name = "custos", mixinStandardHelpOptions = true,
        subcommands = {CustosCli.Operator.class, CustosCli.PolicyCmd.class, CustosCli.AuditCmd.class})
public class CustosCli {

    @Option(names = "--server", scope = ScopeType.INHERIT, defaultValue = "http://127.0.0.1:8080")
    static String server;
    @Option(names = "--token", scope = ScopeType.INHERIT, defaultValue = "")
    static String token;

    static String post(String path, String json) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(server + path))
                .header("Content-Type", "application/json").header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(json == null ? "{}" : json)).build();
        return HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString()).body();
    }
    static String get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(server + path))
                .header("Authorization", "Bearer " + token).GET().build();
        return HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString()).body();
    }
    static String jsonStr(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    @Command(name = "operator", subcommands = {Operator.Init.class, Operator.Unseal.class, Operator.Status.class})
    static class Operator {
        @Command(name = "init") static class Init implements Runnable {
            @Option(names = "--shares", defaultValue = "5") int shares;
            @Option(names = "--threshold", defaultValue = "3") int threshold;
            public void run() { try { System.out.println(post("/operator/init", "{\"shares\":" + shares + ",\"threshold\":" + threshold + "}")); } catch (Exception e) { throw new RuntimeException(e); } }
        }
        @Command(name = "unseal") static class Unseal implements Runnable {
            @Parameters(index = "0") String share;
            public void run() { try { System.out.println(post("/operator/unseal", "{\"share\":" + jsonStr(share) + "}")); } catch (Exception e) { throw new RuntimeException(e); } }
        }
        @Command(name = "status") static class Status implements Runnable {
            public void run() { try { System.out.println(get("/operator/status")); } catch (Exception e) { throw new RuntimeException(e); } }
        }
    }

    @Command(name = "policy") static class PolicyCmd implements Runnable {
        @Option(names = "--content", required = true) String content;
        public void run() { try { System.out.println(post("/policy", "{\"content\":" + jsonStr(content) + "}")); } catch (Exception e) { throw new RuntimeException(e); } }
    }

    @Command(name = "audit", subcommands = {AuditCmd.Verify.class}) static class AuditCmd {
        @Command(name = "verify") static class Verify implements Runnable {
            public void run() { try { System.out.println(get("/audit/verify")); } catch (Exception e) { throw new RuntimeException(e); } }
        }
    }

    public static void main(String[] args) { System.exit(new CommandLine(new CustosCli()).execute(args)); }
}
