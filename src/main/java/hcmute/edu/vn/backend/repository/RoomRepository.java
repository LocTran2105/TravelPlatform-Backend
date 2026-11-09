package hcmute.edu.vn.backend.repository;

import hcmute.edu.vn.backend.entity.Room;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RoomRepository extends JpaRepository<Room, Long> {

    // Lấy danh sách phòng thuộc một cơ sở kinh doanh.
    List<Room> findByBusiness_IdOrderByIdAsc(Long businessId);

    // Lấy phòng theo trạng thái, sắp xếp theo giá.
    List<Room> findByBusiness_IdAndStatusOrderByPricePerNightAsc(
            Long businessId,
            String status
    );

    // Kiểm tra phòng có thuộc cơ sở kinh doanh không.
    Optional<Room> findByIdAndBusiness_Id(
            Long roomId,
            Long businessId
    );

    // Đếm tổng số phòng.
    long countByBusiness_Id(Long businessId);

    // Đếm phòng theo trạng thái.
    long countByBusiness_IdAndStatus(
            Long businessId,
            String status
    );

    /**
     * Khóa bản ghi phòng trong transaction đặt phòng
     * để tránh nhiều request cùng đặt một phòng cuối cùng.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Room r WHERE r.id = :roomId")
    Optional<Room> findByIdForUpdate(
            @Param("roomId") Long roomId
    );
}