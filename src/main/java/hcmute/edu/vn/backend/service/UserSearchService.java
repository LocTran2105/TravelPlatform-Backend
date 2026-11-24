package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.dto.UserSearchResultDTO;
import hcmute.edu.vn.backend.entity.User;
import hcmute.edu.vn.backend.repository.UserSearchRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

@Service
public class UserSearchService {

    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 20;
    // Họ tên dài hơn username nên cho phép từ khóa dài hơn (trước đây 30).
    private static final int MAX_QUERY_LENGTH = 50;

    // Pattern KHÔNG BAO GIỜ khớp username (username không chứa '!'), dùng để tắt điều kiện
    // username khi từ khóa không thể là username (vd "nguyễn văn").
    private static final String NO_MATCH_EXACT = "!";
    private static final String NO_MATCH_PREFIX = "!!%";
    private static final String NO_MATCH_CONTAINS = "%!!%";

    private final UserSearchRepository userSearchRepository;

    public UserSearchService(UserSearchRepository userSearchRepository) {
        this.userSearchRepository = userSearchRepository;
    }

    /**
     * Tìm người dùng theo username HOẶC họ tên, không phân biệt hoa thường.
     * Thứ tự kết quả xem UserSearchRepository.searchActiveByUsernameOrName.
     * Chỉ trả tài khoản ACTIVE.
     */
    @Transactional(readOnly = true)
    public List<UserSearchResultDTO> searchByUsername(String rawQuery, int limit) {
        String keyword = normalize(rawQuery);
        if (keyword.isEmpty()) {
            return List.of();
        }

        int size = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);

        // --- Điều kiện họ tên: dùng nguyên từ khóa (giữ dấu cách giữa các từ) ---
        String name = escapeLike(keyword);
        String namePrefix = name + "%";
        String nameWordPrefix = "% " + name + "%";
        String nameContains = "%" + name + "%";

        // --- Điều kiện username: bỏ dấu cách ("nguyen van" -> "nguyenvan");
        //     nếu còn ký tự ngoài [a-z0-9._-] thì không thể là username -> tắt. ---
        String compact = keyword.replace(" ", "");
        boolean usernameCapable = compact.matches("[a-z0-9._-]+");
        String usernameExact = usernameCapable ? compact : NO_MATCH_EXACT;
        String usernamePrefix = usernameCapable ? escapeLike(compact) + "%" : NO_MATCH_PREFIX;
        String usernameContains = usernameCapable ? "%" + escapeLike(compact) + "%" : NO_MATCH_CONTAINS;

        List<User> users = userSearchRepository.searchActiveByUsernameOrName(
                usernameExact, usernamePrefix, usernameContains,
                namePrefix, nameWordPrefix, nameContains,
                PageRequest.of(0, size)
        );

        return users.stream()
                .map(u -> new UserSearchResultDTO(
                        u.getId(),
                        u.getUsername(),
                        u.getFullName(),
                        u.getAvatar() != null ? u.getAvatar() : ""
                ))
                .toList();
    }

    // Bỏ "@" đầu, ký tự điều khiển, gộp khoảng trắng, chuyển chữ thường, cắt độ dài.
    // KHÔNG lọc chữ có dấu vì còn dùng để tìm theo họ tên.
    private String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.replaceAll("\\p{Cntrl}", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
        while (s.startsWith("@")) {
            s = s.substring(1).trim();
        }
        s = s.replaceAll("\\s+", " ");
        if (s.length() > MAX_QUERY_LENGTH) {
            s = s.substring(0, MAX_QUERY_LENGTH).trim();
        }
        return s;
    }

    // '_' và '%' là ký tự đại diện của LIKE ('_' còn hợp lệ trong username) nên phải escape.
    private String escapeLike(String s) {
        return s.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
}