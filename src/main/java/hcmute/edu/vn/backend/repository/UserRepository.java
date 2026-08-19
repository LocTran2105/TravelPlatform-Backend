package hcmute.edu.vn.backend.repository;

import hcmute.edu.vn.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    // Spring Boot tự động hiểu hàm này tương đương: SELECT * FROM users WHERE email = ?
    Optional<User> findByEmail(String email);

    Optional<User> findByPhoneNumber(String phoneNumber);

    // Kiểm tra xem email hoặc số điện thoại đã tồn tại chưa (dùng khi Đăng ký)
    boolean existsByEmail(String email);
    boolean existsByPhoneNumber(String phoneNumber);
}