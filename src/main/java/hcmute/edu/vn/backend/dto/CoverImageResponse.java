package hcmute.edu.vn.backend.dto;

/** Một ảnh trong thư viện ảnh bìa. keywords đã chuẩn hoá (không dấu, chữ thường) để frontend tìm kiếm. */
public record CoverImageResponse(Long id, String title, String imageUrl, String keywords) {
}
