package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.dto.BusinessHoursRequest;
import hcmute.edu.vn.backend.dto.SpecialHoursRequest;
import hcmute.edu.vn.backend.entity.PartnerBusiness;
import hcmute.edu.vn.backend.entity.PartnerBusinessHours;
import hcmute.edu.vn.backend.entity.PartnerBusinessSpecialHour;
import hcmute.edu.vn.backend.repository.PartnerBusinessHoursRepository;
import hcmute.edu.vn.backend.repository.PartnerBusinessRepository;
import hcmute.edu.vn.backend.repository.PartnerBusinessSpecialHourRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class PartnerBusinessScheduleService {

    private final PartnerBusinessRepository businessRepository;
    private final PartnerBusinessHoursRepository hoursRepository;
    private final PartnerBusinessSpecialHourRepository specialHourRepository;
    private final PartnerAccessService accessService;
    private final PartnerAuditService auditService;

    /**
     * Lấy giờ hoạt động hàng tuần.
     */
    @Transactional(readOnly = true)
    public List<PartnerBusinessHours> getHours(
            Long businessId,
            String email
    ) {
        requireOwnedBusiness(businessId, email);

        return hoursRepository
                .findByBusiness_IdOrderByDayOfWeekAsc(businessId);
    }

    /**
     * Thay thế toàn bộ giờ hoạt động hàng tuần.
     */
    @Transactional
    public List<PartnerBusinessHours> replaceHours(
            Long businessId,
            List<BusinessHoursRequest> requests,
            String email
    ) {
        PartnerBusiness business =
                requireOwnedBusiness(businessId, email);

        if (requests == null || requests.size() > 7) {
            throw new IllegalArgumentException(
                    "Danh sách giờ hoạt động không hợp lệ."
            );
        }

        Set<Integer> submittedDays = new HashSet<>();
        List<PartnerBusinessHours> entities = new ArrayList<>();

        for (BusinessHoursRequest request : requests) {

            validateHoursRequest(request);

            if (!submittedDays.add(request.getDayOfWeek())) {
                throw new IllegalArgumentException(
                        "Không được khai báo trùng ngày trong tuần."
                );
            }

            boolean closed = Boolean.TRUE.equals(
                    request.getClosed()
            );

            PartnerBusinessHours hours =
                    new PartnerBusinessHours();

            hours.setBusiness(business);
            hours.setDayOfWeek(request.getDayOfWeek());
            hours.setClosed(closed);

            hours.setOpenTime(
                    closed ? null : request.getOpenTime()
            );

            hours.setCloseTime(
                    closed ? null : request.getCloseTime()
            );

            entities.add(hours);
        }

        hoursRepository.deleteByBusiness_Id(businessId);

        List<PartnerBusinessHours> saved =
                hoursRepository.saveAll(entities);

        auditService.log(
                email,
                businessId,
                "REPLACE_HOURS",
                "BUSINESS_HOURS",
                null,
                null,
                saved.size()
        );

        return saved;
    }

    /**
     * Lấy giờ hoạt động đặc biệt trong một khoảng ngày.
     */
    @Transactional(readOnly = true)
    public List<PartnerBusinessSpecialHour> getSpecialHours(
            Long businessId,
            LocalDate from,
            LocalDate to,
            String email
    ) {
        requireOwnedBusiness(businessId, email);

        if (from == null || to == null || to.isBefore(from)) {
            throw new IllegalArgumentException(
                    "Khoảng ngày không hợp lệ."
            );
        }

        return specialHourRepository
                .findByBusiness_IdAndSpecialDateBetweenOrderBySpecialDateAsc(
                        businessId,
                        from,
                        to
                );
    }

    /**
     * Thêm hoặc cập nhật giờ đặc biệt.
     */
    @Transactional
    public PartnerBusinessSpecialHour saveSpecialHour(
            Long businessId,
            SpecialHoursRequest request,
            String email
    ) {
        PartnerBusiness business =
                requireOwnedBusiness(businessId, email);

        validateSpecialHoursRequest(request);

        PartnerBusinessSpecialHour specialHour =
                specialHourRepository
                        .findByBusiness_IdAndSpecialDate(
                                businessId,
                                request.getSpecialDate()
                        )
                        .orElseGet(
                                PartnerBusinessSpecialHour::new
                        );

        boolean closed = Boolean.TRUE.equals(
                request.getClosed()
        );

        specialHour.setBusiness(business);
        specialHour.setSpecialDate(request.getSpecialDate());
        specialHour.setClosed(closed);

        specialHour.setOpenTime(
                closed ? null : request.getOpenTime()
        );

        specialHour.setCloseTime(
                closed ? null : request.getCloseTime()
        );

        specialHour.setNote(request.getNote());

        PartnerBusinessSpecialHour saved =
                specialHourRepository.save(specialHour);

        auditService.log(
                email,
                businessId,
                "UPSERT_SPECIAL_HOURS",
                "SPECIAL_HOURS",
                saved.getId(),
                null,
                saved.getSpecialDate()
        );

        return saved;
    }

    /**
     * Xóa giờ đặc biệt.
     */
    @Transactional
    public void deleteSpecialHour(
            Long businessId,
            Long specialHourId,
            String email
    ) {
        requireOwnedBusiness(businessId, email);

        PartnerBusinessSpecialHour specialHour =
                specialHourRepository
                        .findByIdAndBusiness_Id(
                                specialHourId,
                                businessId
                        )
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Không tìm thấy giờ hoạt động đặc biệt."
                                )
                        );

        LocalDate specialDate = specialHour.getSpecialDate();

        specialHourRepository.delete(specialHour);

        auditService.log(
                email,
                businessId,
                "DELETE",
                "SPECIAL_HOURS",
                specialHourId,
                specialDate,
                null
        );
    }

    /**
     * Xác minh quyền sở hữu business.
     */
    private PartnerBusiness requireOwnedBusiness(
            Long businessId,
            String email
    ) {
        accessService.requireApprovedPartner(email);

        return businessRepository
                .findByIdAndPartner_User_EmailAndDeletedAtIsNull(
                        businessId,
                        email
                )
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Không tìm thấy cơ sở kinh doanh "
                                        + "hoặc bạn không có quyền quản lý."
                        )
                );
    }

    private void validateHoursRequest(
            BusinessHoursRequest request
    ) {
        if (request == null
                || request.getDayOfWeek() == null
                || request.getDayOfWeek() < 1
                || request.getDayOfWeek() > 7) {

            throw new IllegalArgumentException(
                    "dayOfWeek phải từ 1 đến 7."
            );
        }

        if (!Boolean.TRUE.equals(request.getClosed())
                && (request.getOpenTime() == null
                || request.getCloseTime() == null
                || !request.getCloseTime().isAfter(
                request.getOpenTime()
        ))) {

            throw new IllegalArgumentException(
                    "Giờ mở cửa phải nhỏ hơn giờ đóng cửa."
            );
        }
    }

    private void validateSpecialHoursRequest(
            SpecialHoursRequest request
    ) {
        if (request == null || request.getSpecialDate() == null) {
            throw new IllegalArgumentException(
                    "Ngày đặc biệt không được để trống."
            );
        }

        if (!Boolean.TRUE.equals(request.getClosed())
                && (request.getOpenTime() == null
                || request.getCloseTime() == null
                || !request.getCloseTime().isAfter(
                request.getOpenTime()
        ))) {

            throw new IllegalArgumentException(
                    "Giờ mở cửa phải nhỏ hơn giờ đóng cửa."
            );
        }
    }
}