package io.custos.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Custos 装配入口（MVP）：读 application.yml，初始化引擎(解封)/身份/PDP+Nacos Watcher/经纪+MCP。
 * 具体 Bean 装配按 application.yml（见 docs/superpowers/specs/...-mvp-v0.1-design.md §7）。
 */
@SpringBootApplication
public class CustosApplication {
    public static void main(String[] args) {
        SpringApplication.run(CustosApplication.class, args);
    }
}
