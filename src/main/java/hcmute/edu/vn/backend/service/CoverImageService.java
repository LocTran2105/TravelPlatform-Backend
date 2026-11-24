package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.dto.CoverImageResponse;
import hcmute.edu.vn.backend.dto.TripSummaryResponse;
import hcmute.edu.vn.backend.entity.CoverImage;
import hcmute.edu.vn.backend.entity.Trip;
import hcmute.edu.vn.backend.repository.CoverImageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Thư viện ảnh bìa + gợi ý ảnh bìa theo điểm đến.
 * Gợi ý KHÔNG ghi vào DB: chỉ điền trường tạm trip.suggestedCoverUrl khi chuyến đi chưa có ảnh bìa riêng.
 */
@Service
public class CoverImageService {

    private final CoverImageRepository repository;

    public CoverImageService(CoverImageRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<CoverImageResponse> listActive() {
        return repository.findByActiveTrueOrderBySortOrderAscIdAsc().stream()
                .map(c -> new CoverImageResponse(c.getId(), c.getTitle(), c.getImageUrl(), searchText(c)))
                .toList();
    }

    /** Điền suggestedCoverUrl cho các chuyến đi chưa có ảnh bìa. Chỉ truy vấn thư viện một lần. */
    @Transactional(readOnly = true)
    public void applySuggestions(Collection<Trip> trips) {
        if (trips == null || trips.isEmpty()) return;
        List<CoverImage> library = null;
        for (Trip trip : trips) {
            if (trip == null || hasText(trip.getCoverImageUrl())) continue;
            if (library == null) library = repository.findByActiveTrueOrderBySortOrderAscIdAsc();
            trip.setSuggestedCoverUrl(suggestFor(trip, library));
        }
    }

    public void applySuggestion(Trip trip) {
        if (trip != null) applySuggestions(List.of(trip));
    }

    /** Điền suggestedCoverUrl cho danh sách tóm tắt (trang cá nhân). Truy vấn thư viện tối đa 1 lần. */
    @Transactional(readOnly = true)
    public void applySuggestionsToSummaries(Collection<TripSummaryResponse> summaries) {
        if (summaries == null || summaries.isEmpty()) return;
        List<CoverImage> library = null;
        for (TripSummaryResponse s : summaries) {
            if (s == null || hasText(s.getCoverImageUrl())) continue;
            if (library == null) library = repository.findByActiveTrueOrderBySortOrderAscIdAsc();
            s.setSuggestedCoverUrl(suggest(s.getId(), s.getDestination(), library));
        }
    }

    /** URL ảnh gợi ý cho một chuyến đi, hoặc null nếu không có ảnh nào khớp điểm đến. */
    String suggestFor(Trip trip, List<CoverImage> library) {
        return suggest(trip.getId(), trip.getDestination(), library);
    }

    private String suggest(Long tripId, String destination, List<CoverImage> library) {
        String dest = normalize(destination);
        if (dest.isEmpty() || library == null) return null;

        List<CoverImage> matches = new ArrayList<>();
        for (CoverImage image : library) {
            if (matches(dest, image)) matches.add(image);
        }
        if (matches.isEmpty()) return null;
        // Nhiều ảnh cùng khớp thì xoay vòng theo id chuyến đi để các chuyến không giống hệt nhau (vẫn ổn định giữa các lần tải).
        long seed = tripId == null ? 0 : tripId;
        return matches.get((int) (Math.abs(seed % matches.size()))).getImageUrl();
    }

    private boolean matches(String normalizedDest, CoverImage image) {
        if (image.getKeywords() == null) return false;
        String paddedDest = " " + normalizedDest + " ";
        for (String raw : image.getKeywords().split(",")) {
            String keyword = normalize(raw);
            if (keyword.length() < 2) continue;
            // "da lat" khớp "thanh pho da lat"; cũng cho phép điểm đến ngắn hơn từ khoá ("lat" thì không vì quá ngắn)
            if (paddedDest.contains(" " + keyword + " ")) return true;
            if (normalizedDest.length() >= 4 && (" " + keyword + " ").contains(paddedDest)) return true;
        }
        return false;
    }

    /** Chuỗi để frontend tìm kiếm: từ khoá + tiêu đề, đã bỏ dấu (tiêu đề luôn có mặt dù chưa nhập từ khoá). */
    private static String searchText(CoverImage c) {
        String kw = c.getKeywords() == null ? "" : c.getKeywords().replace(',', ' ');
        return normalize(kw + " " + (c.getTitle() == null ? "" : c.getTitle()));
    }

    /** Bỏ dấu tiếng Việt, chữ thường, chỉ giữ chữ-số và khoảng trắng đơn. */
    static String normalize(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replace('đ', 'd').replace('Đ', 'D')
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
        return n;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
