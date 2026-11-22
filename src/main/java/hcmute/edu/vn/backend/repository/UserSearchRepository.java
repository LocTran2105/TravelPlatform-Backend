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

    /**
     * Tìm tài khoản ACTIVE theo USERNAME hoặc HỌ TÊN, và xếp hạng ngay trong SQL:
     *   0  username trùng khớp hoàn toàn
     *   1  username bắt đầu bằng từ khóa
     *   2  họ tên bắt đầu bằng từ khóa
     *   3  một TỪ trong họ tên bắt đầu bằng từ khóa  (vd "an" -> "Nguyễn Văn An")
     *   4  username chứa từ khóa
     *   5  họ tên chứa từ khóa
     * Cùng hạng thì xếp theo username.
     *
     * Các tham số pattern do UserSearchService dựng sẵn (đã escape bằng '!').
     * Khi từ khóa KHÔNG thể là username (có chữ có dấu, ký tự lạ...), service truyền
     * pattern không bao giờ khớp ('!' / '!!%' / '%!!%') để bỏ qua điều kiện username.
     *
     * Không phân biệt hoa thường; có phân biệt dấu hay không do collation của cột
     * (DB đang dùng utf8mb4_unicode_ci nên "nguyen" khớp "Nguyễn").
     */
    @Query("""
            SELECT u FROM User u
            WHERE u.status = 'ACTIVE'
              AND (
                    LOWER(u.username) LIKE :usernameContains ESCAPE '!'
                 OR LOWER(u.fullName) LIKE :nameContains ESCAPE '!'
              )
            ORDER BY
              CASE
                WHEN LOWER(u.username) = :usernameExact THEN 0
                WHEN LOWER(u.username) LIKE :usernamePrefix ESCAPE '!' THEN 1
                WHEN LOWER(u.fullName) LIKE :namePrefix ESCAPE '!' THEN 2
                WHEN LOWER(u.fullName) LIKE :nameWordPrefix ESCAPE '!' THEN 3
                WHEN LOWER(u.username) LIKE :usernameContains ESCAPE '!' THEN 4
                ELSE 5
              END,
              u.username ASC
            """)
    List<User> searchActiveByUsernameOrName(
            @Param("usernameExact") String usernameExact,
            @Param("usernamePrefix") String usernamePrefix,
            @Param("usernameContains") String usernameContains,
            @Param("namePrefix") String namePrefix,
            @Param("nameWordPrefix") String nameWordPrefix,
            @Param("nameContains") String nameContains,
            Pageable pageable
    );
}