package hcmute.edu.vn.backend.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Autowired
    private AuthTokenFilter authTokenFilter;

    // Bộ mã hóa mật khẩu để lưu vào Database một cách an toàn
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable) // Tắt CSRF vì ta dùng Token
                .cors(cors -> cors.configure(null)) // Dùng cấu hình CORS đã tạo ở bước khởi tạo dự án
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)) // REST API không dùng Session
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**").permitAll() // Cho phép tất cả truy cập API Đăng nhập/Đăng ký
                        .requestMatchers("/api/public/**").permitAll() // Các API public (như xem lịch trình public)
                        .anyRequest().authenticated() // Tất cả các API còn lại đều phải có Token hợp lệ
                );

        // Chèn bộ lọc Token vào trước bộ lọc xác thực mặc định
        http.addFilterBefore(authTokenFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}