package hcmute.edu.vn.backend.security;

import hcmute.edu.vn.backend.entity.User;
import hcmute.edu.vn.backend.repository.UserRepository;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class AuthTokenFilter extends OncePerRequestFilter {

    private final JwtUtils jwtUtils;
    private final UserRepository userRepository;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String jwt = parseJwt(request);

        // Không có token: để Spring Security quyết định
        // API nào public và API nào cần đăng nhập.
        if (jwt == null) {
            filterChain.doFilter(request, response);
            return;
        }

        String email;

        // 1. Xác thực JWT.
        try {

            if (!jwtUtils.validateJwtToken(jwt)) {
                writeError(
                        response,
                        HttpServletResponse.SC_UNAUTHORIZED,
                        "Token không hợp lệ hoặc đã hết hạn."
                );
                return;
            }

            email = jwtUtils.getEmailFromToken(jwt);

            if (email == null || email.isBlank()) {
                writeError(
                        response,
                        HttpServletResponse.SC_UNAUTHORIZED,
                        "Token không chứa thông tin tài khoản."
                );
                return;
            }

        } catch (JwtException | IllegalArgumentException e) {

            SecurityContextHolder.clearContext();

            writeError(
                    response,
                    HttpServletResponse.SC_UNAUTHORIZED,
                    "Không thể xác thực token."
            );
            return;
        }

        // 2. Truy vấn tài khoản trực tiếp từ database.
        // Nhờ vậy, quyền mới sẽ có hiệu lực ngay sau khi Admin cập nhật.
        Optional<User> userOptional =
                userRepository.findByEmail(email);

        if (userOptional.isEmpty()) {

            writeError(
                    response,
                    HttpServletResponse.SC_UNAUTHORIZED,
                    "Tài khoản không còn tồn tại."
            );
            return;
        }

        User user = userOptional.get();

        // 3. Kiểm tra trạng thái tài khoản.
        if (!"ACTIVE".equalsIgnoreCase(user.getStatus())) {

            writeError(
                    response,
                    HttpServletResponse.SC_FORBIDDEN,
                    "Tài khoản của bạn đã bị khóa hoặc ngừng hoạt động."
            );
            return;
        }

        // 4. Kiểm tra role.
        if (user.getRole() == null || user.getRole().isBlank()) {

            writeError(
                    response,
                    HttpServletResponse.SC_FORBIDDEN,
                    "Tài khoản chưa được cấp quyền truy cập."
            );
            return;
        }

        // 5. Đưa role vào Spring Security.
        List<SimpleGrantedAuthority> authorities = List.of(
                new SimpleGrantedAuthority(user.getRole())
        );

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        email,
                        null,
                        authorities
                );

        authentication.setDetails(
                new WebAuthenticationDetailsSource()
                        .buildDetails(request)
        );

        SecurityContextHolder.getContext()
                .setAuthentication(authentication);

        filterChain.doFilter(request, response);
    }

    /**
     * Lấy JWT từ Authorization Header.
     */
    private String parseJwt(HttpServletRequest request) {

        String headerAuth = request.getHeader("Authorization");

        if (headerAuth != null
                && headerAuth.startsWith("Bearer ")) {

            return headerAuth.substring(7).trim();
        }

        return null;
    }

    /**
     * Trả về JSON lỗi xác thực.
     */
    private void writeError(
            HttpServletResponse response,
            int status,
            String message
    ) throws IOException {

        SecurityContextHolder.clearContext();

        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");

        response.getWriter().write(
                "{\"message\":\"" + message + "\"}"
        );
    }
}