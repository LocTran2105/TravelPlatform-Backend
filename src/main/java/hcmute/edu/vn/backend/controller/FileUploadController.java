package hcmute.edu.vn.backend.controller;

import hcmute.edu.vn.backend.service.FileUploadService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/upload")
public class FileUploadController {

    @Autowired
    private FileUploadService fileUploadService;

    @PostMapping("/image")
    public ResponseEntity<?> uploadImage(@RequestParam("file") MultipartFile file) {
        try {
            String imageUrl = fileUploadService.uploadImage(file);
            return ResponseEntity.ok(imageUrl); // Trả về link URL của ảnh
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Lỗi khi tải ảnh lên: " + e.getMessage());
        }
    }
}