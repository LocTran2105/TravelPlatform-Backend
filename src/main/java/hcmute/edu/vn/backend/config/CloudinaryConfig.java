package hcmute.edu.vn.backend.config;

import com.cloudinary.Cloudinary;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class CloudinaryConfig {

    @Bean
    public Cloudinary cloudinary() {
        Map<String, String> config = new HashMap<>();
        // BẠN HÃY THAY 3 THÔNG SỐ NÀY BẰNG TÀI KHOẢN CỦA BẠN NHÉ
        config.put("cloud_name", "dcke9stsl");
        config.put("api_key", "194535266651688");
        config.put("api_secret", "YUvGLNAspYQ9GrT_GHpwBh72mZw");

        return new Cloudinary(config);
    }
}