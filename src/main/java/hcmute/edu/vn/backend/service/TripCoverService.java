package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.entity.CoverImage;
import hcmute.edu.vn.backend.entity.Trip;
import hcmute.edu.vn.backend.repository.CoverImageRepository;
import hcmute.edu.vn.backend.repository.TripRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Đổi / gỡ ảnh bìa của chuyến đi. Chỉ OWNER và EDITOR được phép. */
@Service
public class TripCoverService {

    public static final long MAX_UPLOAD_BYTES = 5L * 1024 * 1024;
    private static final Set<String> ALLOWED_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    private final TripAccessService access;
    private final TripRepository tripRepository;
    private final CoverImageRepository coverImageRepository;
    private final CoverImageService coverImageService;
    private final CloudinaryService cloudinaryService;

    public TripCoverService(TripAccessService access,
                            TripRepository tripRepository,
                            CoverImageRepository coverImageRepository,
                            CoverImageService coverImageService,
                            CloudinaryService cloudinaryService) {
        this.access = access;
        this.tripRepository = tripRepository;
        this.coverImageRepository = coverImageRepository;
        this.coverImageService = coverImageService;
        this.cloudinaryService = cloudinaryService;
    }

    /** Chọn ảnh từ thư viện. Client chỉ gửi id, không gửi URL tuỳ ý. */
    @Transactional
    public Map<String, Object> setFromLibrary(Long tripId, Long coverImageId, String email) {
        Trip trip = access.requireEdit(tripId, email);
        if (coverImageId == null) {
            throw new TripAccessException(HttpStatus.BAD_REQUEST, "Hãy chọn một ảnh trong thư viện.");
        }
        CoverImage image = coverImageRepository.findByIdAndActiveTrue(coverImageId)
                .orElseThrow(() -> new TripAccessException(HttpStatus.NOT_FOUND, "Ảnh này không còn trong thư viện."));
        trip.setCoverImageUrl(image.getImageUrl());
        tripRepository.save(trip);
        return result(trip);
    }

    /** Tải ảnh của người dùng lên Cloudinary rồi dùng làm ảnh bìa. */
    @Transactional
    public Map<String, Object> upload(Long tripId, MultipartFile file, String email) throws Exception {
        Trip trip = access.requireEdit(tripId, email);
        validateImage(file);
        String url = cloudinaryService.uploadFile(file, "trip_covers");
        if (url == null || url.isBlank()) {
            throw new TripAccessException(HttpStatus.BAD_GATEWAY, "Không tải được ảnh lên. Vui lòng thử lại!");
        }
        trip.setCoverImageUrl(url);
        tripRepository.save(trip);
        return result(trip);
    }

    /** Gỡ ảnh bìa riêng; chuyến đi quay về ảnh gợi ý theo điểm đến (hoặc nền gradient). */
    @Transactional
    public Map<String, Object> remove(Long tripId, String email) {
        Trip trip = access.requireEdit(tripId, email);
        trip.setCoverImageUrl(null);
        tripRepository.save(trip);
        return result(trip);
    }

    private Map<String, Object> result(Trip trip) {
        coverImageService.applySuggestion(trip);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("coverImageUrl", trip.getCoverImageUrl());
        body.put("suggestedCoverUrl", trip.getSuggestedCoverUrl());
        return body;
    }

    /** Kiểm tra theo nội dung thật của file (không tin hoàn toàn vào Content-Type do client gửi). */
    void validateImage(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new TripAccessException(HttpStatus.BAD_REQUEST, "Chưa chọn ảnh để tải lên.");
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw new TripAccessException(HttpStatus.PAYLOAD_TOO_LARGE, "Ảnh quá lớn. Tối đa 5MB.");
        }
        String type = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        if (!ALLOWED_TYPES.contains(type)) {
            throw new TripAccessException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Chỉ hỗ trợ ảnh JPG, PNG hoặc WEBP.");
        }
        byte[] head = new byte[12];
        int read;
        try (InputStream in = file.getInputStream()) {
            read = in.readNBytes(head, 0, head.length);
        }
        if (!looksLikeImage(head, read)) {
            throw new TripAccessException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "File không phải ảnh JPG, PNG hoặc WEBP hợp lệ.");
        }
    }

    static boolean looksLikeImage(byte[] h, int n) {
        if (n >= 3 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF) return true; // JPEG
        if (n >= 8 && (h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G') return true;      // PNG
        return n >= 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F'
                && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P';                              // WEBP
    }
}
