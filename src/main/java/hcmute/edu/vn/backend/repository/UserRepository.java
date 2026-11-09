package hcmute.edu.vn.backend.repository;

import hcmute.edu.vn.backend.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    Optional<User> findByPhoneNumber(String phoneNumber);

    Optional<User> findByResetPasswordToken(String token);

    boolean existsByEmail(String email);

    boolean existsByPhoneNumber(String phoneNumber);

    List<User> findByRole(String role);

    List<User> findByStatus(String status);

    long countByRole(String role);

    long countByStatus(String status);

    /**
     * Admin tìm kiếm người dùng theo email, tên hoặc số điện thoại.
     */
    @Query("""
        SELECT u
        FROM User u
        WHERE (:role IS NULL OR u.role = :role)
          AND (:status IS NULL OR u.status = :status)
          AND (
                :keyword IS NULL
                OR LOWER(u.email) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(u.fullName) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR u.phoneNumber LIKE CONCAT('%', :keyword, '%')
          )
        """)
    Page<User> searchUsers(
            @Param("role") String role,
            @Param("status") String status,
            @Param("keyword") String keyword,
            Pageable pageable
    );
}