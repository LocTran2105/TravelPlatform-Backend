package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.dto.AlertRequest;
import hcmute.edu.vn.backend.dto.CustomPlaceRequest;
import hcmute.edu.vn.backend.entity.Alert;
import hcmute.edu.vn.backend.entity.CustomPlace;
import hcmute.edu.vn.backend.entity.User;
import hcmute.edu.vn.backend.repository.AlertRepository;
import hcmute.edu.vn.backend.repository.CustomPlaceRepository;
import hcmute.edu.vn.backend.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MapService {

    @Autowired
    private CustomPlaceRepository customPlaceRepository;
    @Autowired
    private AlertRepository alertRepository;
    @Autowired
    private UserRepository userRepository;

    // --- XỬ LÝ ĐỊA ĐIỂM TÙY CHỈNH ---
    public CustomPlace addCustomPlace(CustomPlaceRequest request, String userEmail) throws Exception {
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new Exception("Người dùng không tồn tại!"));

        CustomPlace place = new CustomPlace();
        place.setUser(user);
        place.setName(request.getName());
        place.setDescription(request.getDescription());
        place.setLatitude(request.getLatitude());
        place.setLongitude(request.getLongitude());
        place.setImages(request.getImages());

        return customPlaceRepository.save(place);
    }

    public List<CustomPlace> getMyPlaces(String userEmail) throws Exception {
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new Exception("Người dùng không tồn tại!"));
        return customPlaceRepository.findByUserId(user.getId());
    }

    // --- XỬ LÝ CẢNH BÁO (ALERTS) ---
    public Alert createAlert(AlertRequest request, String userEmail) throws Exception {
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new Exception("Người dùng không tồn tại!"));

        Alert alert = new Alert();
        alert.setUser(user);
        alert.setTitle(request.getTitle());
        alert.setDescription(request.getDescription());
        alert.setAlertType(request.getAlertType());
        alert.setLatitude(request.getLatitude());
        alert.setLongitude(request.getLongitude());
        alert.setStatus("PENDING"); // Người dùng tạo ra phải chờ duyệt

        return alertRepository.save(alert);
    }

    public List<Alert> getApprovedAlerts() {
        // Chỉ lấy những cảnh báo đã được Admin duyệt để hiển thị cho cộng đồng
        return alertRepository.findByStatus("APPROVED");
    }
}