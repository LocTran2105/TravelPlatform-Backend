package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.entity.Alert;
import hcmute.edu.vn.backend.entity.Report;
import hcmute.edu.vn.backend.repository.AlertRepository;
import hcmute.edu.vn.backend.repository.ReportRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

@Service
public class AdminService {

    @Autowired
    private AlertRepository alertRepository;
    @Autowired
    private ReportRepository reportRepository;

    // 1. Duyệt / Từ chối Cảnh báo Bản đồ
    public String processAlert(Long alertId, boolean isApproved) throws Exception {
        Alert alert = alertRepository.findById(alertId)
                .orElseThrow(() -> new Exception("Không tìm thấy cảnh báo!"));

        alert.setStatus(isApproved ? "APPROVED" : "REJECTED");
        alertRepository.save(alert);

        return isApproved ? "Đã duyệt cảnh báo thành công!" : "Đã từ chối cảnh báo!";
    }

    // 2. Xem danh sách Báo cáo đang chờ xử lý
    public Page<Report> getPendingReports(int page, int size) {
        // Tạo đối tượng Pageable: Trang số 'page', hiển thị 'size' phần tử, sắp xếp theo createdAt giảm dần
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());

        return reportRepository.findByStatus("PENDING", pageable);
    }

    // 3. Xử lý Báo cáo vi phạm
    public String resolveReport(Long reportId, String action) throws Exception {
        Report report = reportRepository.findById(reportId)
                .orElseThrow(() -> new Exception("Không tìm thấy báo cáo!"));

        if (action.equalsIgnoreCase("RESOLVED")) {
            report.setStatus("RESOLVED");
            // Thực tế ở đây có thể viết thêm code tự động xóa bài viết/comment bị report
        } else {
            report.setStatus("REJECTED"); // Báo cáo sai sự thật
        }

        reportRepository.save(report);
        return "Đã xử lý báo cáo với trạng thái: " + report.getStatus();
    }
}