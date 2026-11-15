package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.dto.UserSearchResultDTO;
import hcmute.edu.vn.backend.entity.User;
import hcmute.edu.vn.backend.repository.UserSearchRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class UserSearchService {

    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 20;
    private static final int MAX_QUERY_LENGTH = 30;

    private final UserSearchRepository userSearchRepository;

    public UserSearchService(UserSearchRepository userSearchRepository) {
        this.userSearchRepository = userSearchRepository;
    }

    /**
     * Tìm người dùng theo username, không phân biệt hoa thường.
     * Thứ tự: username bắt đầu bằng từ khóa trước (kết quả khớp chính xác tự nằm đầu
     * vì sắp xếp theo bảng chữ cái), sau đó đến username chỉ chứa từ khóa.
     * Chỉ trả tài khoản ACTIVE.
     */
    @Transactional(readOnly = true)
    public List<UserSearchResultDTO> searchByUsername(String rawQuery, int limit) {
        String keyword = normalize(rawQuery);
        if (keyword.isEmpty()) {
            return List.of();
        }

        int size = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        String escaped = escapeLike(keyword);
        String prefixPattern = escaped + "%";
        String containsPattern = "%" + escaped + "%";

        List<User> users = new ArrayList<>(
                userSearchRepository.findActiveByUsernamePrefix(prefixPattern, PageRequest.of(0, size))
        );

        if (users.size() < size) {
            users.addAll(userSearchRepository.findActiveByUsernameContainingNotPrefix(
                    containsPattern, prefixPattern, PageRequest.of(0, size - users.size())
            ));
        }

        return users.stream()
                .map(u -> new UserSearchResultDTO(
                        u.getId(),
                        u.getUsername(),
                        u.getFullName(),
                        u.getAvatar() != null ? u.getAvatar() : ""
                ))
                .toList();
    }

    // Bỏ khoảng trắng và "@" đầu, chuyển chữ thường, chỉ giữ ký tự hợp lệ của username.
    private String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        while (s.startsWith("@")) {
            s = s.substring(1);
        }
        if (s.length() > MAX_QUERY_LENGTH) {
            s = s.substring(0, MAX_QUERY_LENGTH);
        }
        return s.replaceAll("[^a-z0-9._-]", "");
    }

    // '_' là ký tự đại diện của LIKE nhưng cũng hợp lệ trong username nên phải escape.
    private String escapeLike(String s) {
        return s.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
}