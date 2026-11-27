package hcmute.edu.vn.backend.repository;

import hcmute.edu.vn.backend.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Truy vấn riêng cho việc cấp / thu hồi quyền kiểm duyệt viên. Tách khỏi UserRepository để không
 * phải sửa file đó; Spring Data cho phép nhiều repository cùng quản lý một entity.
 */
@Repository
public interface ModeratorUserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    List<User> findByRoleOrderByFullNameAsc(String role);

    /** Khóa dòng để hai quản trị viên bấm cùng lúc không ghi đè nhau. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);

    /** Tài khoản người dùng thường đang hoạt động khớp email / username / họ tên. */
    @Query("""
        SELECT u
        FROM User u
        WHERE u.role = 'ROLE_TRAVELLER'
          AND u.status = 'ACTIVE'
          AND (
                LOWER(u.email) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(u.username) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(u.fullName) LIKE LOWER(CONCAT('%', :keyword, '%'))
          )
        ORDER BY u.fullName ASC
        """)
    List<User> searchCandidates(@Param("keyword") String keyword, Pageable pageable);
}
