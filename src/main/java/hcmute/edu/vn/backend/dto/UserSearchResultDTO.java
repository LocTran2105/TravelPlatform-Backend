package hcmute.edu.vn.backend.dto;

/**
 * Kết quả tìm kiếm người dùng.
 * Chỉ chứa thông tin công khai; KHÔNG có email, số điện thoại, role.
 */
public record UserSearchResultDTO(
        Long id,
        String username,
        String fullName,
        String avatar
) {
}