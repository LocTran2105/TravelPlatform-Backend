package hcmute.edu.vn.backend.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Autowired
    private AuthTokenFilter authTokenFilter;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/auth/**",
                                "/api/public/**",
                                "/api/map/alerts/approved",
                                "/error"
                        ).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/posts").permitAll()
                        // Khách xem được bình luận của bài PUBLIC (quyền xem vẫn
                        // được CommentService kiểm tra theo từng bài).
                        .requestMatchers(HttpMethod.GET, "/api/posts/*/comments").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/trips/*/reviews").permitAll()
                        // Người cầm link mời được xem trước lời mời khi chưa đăng nhập
                        // (token là chuỗi ngẫu nhiên 256 bit nên không đoán được).
                        .requestMatchers(HttpMethod.GET, "/api/invitations/preview/*").permitAll()
                        // Tương tự cho link chia sẻ chuyến đi (token ngẫu nhiên 256 bit)
                        .requestMatchers(HttpMethod.GET, "/api/share/preview/*").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/trips/user/*").permitAll()
                        .requestMatchers(HttpMethod.GET,
                                "/api/social/users/*/followers",
                                "/api/social/users/*/following"
                        ).permitAll()
                        .requestMatchers(HttpMethod.GET,
                                "/api/social/users/*/followers",
                                "/api/social/users/*/following",
                                "/api/social/users/*/follow-info"
                        ).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/posts/user/*").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/users/profile").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/users/*").permitAll()
                        .requestMatchers("/api/admin/**").hasAuthority("ROLE_ADMIN")
                        .requestMatchers("/api/partner/**").hasAuthority("ROLE_PARTNER")
                        // /api/partner-applications/** không nằm trong matcher ROLE_PARTNER:
                        // traveller đã đăng nhập được phép gửi đơn đăng ký đối tác.
                        .anyRequest().authenticated()
                );

        http.addFilterBefore(authTokenFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}