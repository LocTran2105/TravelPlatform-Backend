package hcmute.edu.vn.backend.service;

import com.cloudinary.Cloudinary;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CloudinaryService {

    private final Cloudinary cloudinary;

    /**
     * Kết quả upload bao gồm URL và thông tin cần thiết
     * để có thể xóa file khỏi Cloudinary sau này.
     */
    public record UploadResult(
            String secureUrl,
            String publicId,
            String resourceType
    ) {
    }

    /**
     * Upload ảnh hoặc video lên Cloudinary.
     */
    public String uploadFile(
            MultipartFile file,
            String folderName
    ) throws IOException {

        return uploadFileWithDetails(file, folderName).secureUrl();
    }

    /**
     * Upload và lấy đầy đủ metadata.
     */
    public UploadResult uploadFileWithDetails(
            MultipartFile file,
            String folderName
    ) throws IOException {

        validateFile(file);

        String folder = normalizeFolder(folderName);

        Map<String, Object> params = new HashMap<>();

        params.put("folder", folder);
        params.put("public_id", UUID.randomUUID().toString());
        params.put("resource_type", "auto");
        params.put("overwrite", false);

        Map<?, ?> result = cloudinary.uploader().upload(
                file.getBytes(),
                params
        );

        Object secureUrl = result.get("secure_url");
        Object publicId = result.get("public_id");
        Object resourceType = result.get("resource_type");

        if (secureUrl == null || publicId == null) {
            throw new IOException(
                    "Cloudinary không trả về đầy đủ thông tin file."
            );
        }

        return new UploadResult(
                secureUrl.toString(),
                publicId.toString(),
                resourceType == null
                        ? "image"
                        : resourceType.toString()
        );
    }

    /**
     * Giữ lại method cũ để không gây lỗi
     * cho các service đang sử dụng.
     *
     * Mặc định xóa ảnh.
     */
    public void deleteFile(String publicId) throws IOException {

        deleteFile(publicId, "image");
    }

    /**
     * Xóa file theo publicId và resourceType.
     *
     * resourceType: image, video, raw.
     */
    public void deleteFile(
            String publicId,
            String resourceType
    ) throws IOException {

        if (publicId == null || publicId.isBlank()) {
            throw new IllegalArgumentException(
                    "Public ID không được để trống."
            );
        }

        String type = resourceType == null
                ? "image"
                : resourceType.trim().toLowerCase(Locale.ROOT);

        if (!type.equals("image")
                && !type.equals("video")
                && !type.equals("raw")) {

            throw new IllegalArgumentException(
                    "Resource type không hợp lệ."
            );
        }

        Map<String, Object> params = new HashMap<>();

        params.put("resource_type", type);
        params.put("invalidate", true);

        Map<?, ?> result = cloudinary.uploader().destroy(
                publicId,
                params
        );

        Object status = result.get("result");

        if (status != null
                && !"ok".equalsIgnoreCase(status.toString())
                && !"not found".equalsIgnoreCase(status.toString())) {

            throw new IOException(
                    "Không thể xóa file khỏi Cloudinary: " + status
            );
        }
    }

    private void validateFile(MultipartFile file) {

        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException(
                    "File tải lên không hợp lệ."
            );
        }

        String contentType = file.getContentType();

        if (contentType == null
                || (!contentType.startsWith("image/")
                && !contentType.startsWith("video/"))) {

            throw new IllegalArgumentException(
                    "Chỉ hỗ trợ upload ảnh và video."
            );
        }
    }

    private String normalizeFolder(String folderName) {

        if (folderName == null || folderName.isBlank()) {
            return "travel-platform";
        }

        String folder = folderName.trim()
                .replace('\\', '/')
                .replaceAll("^/+|/+$", "");

        if (!folder.matches("[a-zA-Z0-9/_-]+")) {
            throw new IllegalArgumentException(
                    "Tên folder không hợp lệ."
            );
        }

        return folder;
    }
}