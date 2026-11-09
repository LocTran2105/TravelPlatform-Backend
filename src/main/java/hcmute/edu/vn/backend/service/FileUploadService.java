package hcmute.edu.vn.backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class FileUploadService {

    private final CloudinaryService cloudinaryService;

    /**
     * Upload ảnh.
     *
     * Giữ nguyên method uploadImage để các Controller cũ
     * không phải thay đổi cách gọi.
     */
    public String uploadImage(
            MultipartFile file
    ) throws IOException {

        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException(
                    "Vui lòng chọn ảnh cần tải lên."
            );
        }

        String contentType = file.getContentType();

        if (contentType == null
                || !contentType.toLowerCase(Locale.ROOT)
                .startsWith("image/")) {

            throw new IllegalArgumentException(
                    "File tải lên phải là hình ảnh."
            );
        }

        return cloudinaryService.uploadFile(
                file,
                "travel-platform/images"
        );
    }
}