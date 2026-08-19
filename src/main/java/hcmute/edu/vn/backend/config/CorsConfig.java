package hcmute.edu.vn.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig implements WebMvcConfigurer {
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**") // Cho phép tất cả API
                .allowedOrigins("http://localhost:5173") // Cho phép React truy cập
                .allowedMethods("GET", "POST", "PUT", "DELETE");
    }
}