package io.custos.app.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 放行可配 console 源 + Authorization 头（不用通配符；凭证不随 CORS 携带）。 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {
    private final String[] origins;

    public CorsConfig(CustosProperties props) {
        // 逗号分隔的多个 console 源，逐个精确放行（绝不用 "*" 通配符）。
        this.origins = props.getConsole().getOrigin().split("\\s*,\\s*");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "DELETE", "OPTIONS")
                .allowedHeaders("Authorization", "Content-Type")
                .allowCredentials(false)
                .maxAge(3600);
    }
}
