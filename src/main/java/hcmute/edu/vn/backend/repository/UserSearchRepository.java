package hcmute.edu.vn.backend.repository;

import hcmute.edu.vn.backend.entity.User;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Repository riêng cho tìm kiếm người dùng (không cần sửa UserRepository).
 * Ký tự escape là '!' vì username không bao giờ chứa '!'.
 */
public interface UserSearchRepository extends Repository<User, Long> {

    // Username BẮT ĐẦU bằng từ khóa (có thể tận dụng index của cột username).
    @Query("""
            SELECT u FROM User u
            WHERE u.status = 'ACTIVE'
              AND u.username LIKE :prefix ESCAPE '!'
            ORDER BY u.username ASC
            """)
    List<User> findActiveByUsernamePrefix(
            @Param("prefix") String prefix,
            Pageable pageable
    );

    // Username CHỨA từ khóa nhưng không bắt đầu bằng từ khóa (để không trùng kết quả ở trên).
    @Query("""
            SELECT u FROM User u
            WHERE u.status = 'ACTIVE'
              AND u.username LIKE :contains ESCAPE '!'
              AND u.username NOT LIKE :prefix ESCAPE '!'
            ORDER BY u.username ASC
            """)
    List<User> findActiveByUsernameContainingNotPrefix(
            @Param("contains") String contains,
            @Param("prefix") String prefix,
            Pageable pageable
    );
}