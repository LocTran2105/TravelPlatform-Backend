package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.dto.CommentRequest;
import hcmute.edu.vn.backend.entity.Trip;
import hcmute.edu.vn.backend.entity.TripComment;
import hcmute.edu.vn.backend.entity.User;
import hcmute.edu.vn.backend.repository.TripCommentRepository;
import hcmute.edu.vn.backend.repository.TripRepository;
import hcmute.edu.vn.backend.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TripCommentService {

    @Autowired
    private TripCommentRepository tripCommentRepository;
    @Autowired
    private TripRepository tripRepository;
    @Autowired
    private UserRepository userRepository;

    public TripComment addComment(Long tripId, CommentRequest request, String userEmail) throws Exception {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new Exception("Không tìm thấy chuyến đi!"));

        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new Exception("Không tìm thấy người dùng!"));

        TripComment comment = new TripComment();
        comment.setTrip(trip);
        comment.setUser(user);
        comment.setContent(request.getContent());

        // Nếu request có parentId, tức là user đang Reply (Trả lời) một bình luận khác
        if (request.getParentId() != null) {
            TripComment parent = tripCommentRepository.findById(request.getParentId())
                    .orElseThrow(() -> new Exception("Bình luận gốc không tồn tại!"));
            comment.setParentComment(parent);
        }

        return tripCommentRepository.save(comment);
    }

    public List<TripComment> getCommentsByTrip(Long tripId) {
        // Chỉ lấy các comment gốc, các comment reply sẽ được đính kèm bên trong nhờ quan hệ @OneToMany
        return tripCommentRepository.findByTripIdAndParentCommentIsNullOrderByCreatedAtDesc(tripId);
    }
}