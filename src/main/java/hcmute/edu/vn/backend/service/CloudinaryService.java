package hcmute.edu.vn.backend.service;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

@Service
public class CloudinaryService {

    @Autowired
    private Cloudinary cloudinary;

    // Upload file (Hỗ trợ cả ảnh và video)
    public String uploadFile(MultipartFile file, String folderName) throws IOException {
        // Tạo tên file ngẫu nhiên (UUID) để không bao giờ bị trùng lặp
        String publicId = folderName + "/" + UUID.randomUUID().toString();

        // resource_type: "auto" giúp Cloudinary tự nhận diện file tải lên là image hay video
        Map params = ObjectUtils.asMap(
                "public_id", publicId,
                "resource_type", "auto",
                "folder", folderName
        );

        Map uploadResult = cloudinary.uploader().upload(file.getBytes(), params);

        // Trả về chuỗi URL an toàn (HTTPS) để lưu vào Database MySQL
        return uploadResult.get("secure_url").toString();
    }

    // Xóa file (Dùng khi người dùng xóa bài viết / đổi avatar)
    public void deleteFile(String publicId) throws IOException {
        cloudinary.uploader().destroy(publicId, ObjectUtils.emptyMap());
    }
}