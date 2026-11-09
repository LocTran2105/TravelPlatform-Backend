package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.dto.BusinessMediaRequest;
import hcmute.edu.vn.backend.dto.BusinessNoteRequest;
import hcmute.edu.vn.backend.entity.PartnerBusiness;
import hcmute.edu.vn.backend.entity.PartnerBusinessMedia;
import hcmute.edu.vn.backend.entity.PartnerBusinessNote;
import hcmute.edu.vn.backend.repository.PartnerBusinessMediaRepository;
import hcmute.edu.vn.backend.repository.PartnerBusinessNoteRepository;
import hcmute.edu.vn.backend.repository.PartnerBusinessRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class PartnerMediaNoteService {

    private static final Set<String> MEDIA_TYPES = Set.of("IMAGE", "VIDEO");

    private final PartnerBusinessRepository businessRepository;
    private final PartnerBusinessMediaRepository mediaRepository;
    private final PartnerBusinessNoteRepository noteRepository;
    private final PartnerAccessService accessService;
    private final PartnerAuditService auditService;

    @Transactional(readOnly = true)
    public List<PartnerBusinessMedia> listMedia(Long businessId, String email) {
        requireOwnedBusiness(businessId, email);
        return mediaRepository.findByBusiness_IdOrderBySortOrderAscIdAsc(businessId);
    }

    @Transactional
    public PartnerBusinessMedia addMedia(
            Long businessId,
            BusinessMediaRequest request,
            String email
    ) {
        PartnerBusiness business = requireOwnedBusiness(businessId, email);
        validateMediaRequest(request);

        if (Boolean.TRUE.equals(request.getCover())) {
            List<PartnerBusinessMedia> currentMedia = mediaRepository
                    .findByBusiness_IdOrderBySortOrderAscIdAsc(businessId);

            currentMedia.forEach(media -> media.setCover(false));
            mediaRepository.saveAll(currentMedia);
        }

        PartnerBusinessMedia media = new PartnerBusinessMedia();
        media.setBusiness(business);
        media.setMediaType(normalizeMediaType(request.getMediaType()));
        media.setMediaUrl(request.getMediaUrl().trim());
        media.setThumbnailUrl(trimToNull(request.getThumbnailUrl()));
        media.setCaption(trimToNull(request.getCaption()));
        media.setCover(Boolean.TRUE.equals(request.getCover()));
        media.setSortOrder(request.getSortOrder() == null ? 0 : request.getSortOrder());

        PartnerBusinessMedia saved = mediaRepository.save(media);

        auditService.log(
                email,
                businessId,
                "CREATE",
                "MEDIA",
                saved.getId(),
                null,
                saved.getMediaUrl()
        );

        return saved;
    }

    @Transactional
    public void deleteMedia(Long businessId, Long mediaId, String email) {
        requireOwnedBusiness(businessId, email);

        PartnerBusinessMedia media = mediaRepository
                .findByIdAndBusiness_Id(mediaId, businessId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Không tìm thấy media."
                ));

        String mediaUrl = media.getMediaUrl();
        mediaRepository.delete(media);

        auditService.log(
                email,
                businessId,
                "DELETE",
                "MEDIA",
                mediaId,
                mediaUrl,
                null
        );
    }

    @Transactional(readOnly = true)
    public List<PartnerBusinessNote> listNotes(Long businessId, String email) {
        requireOwnedBusiness(businessId, email);
        return noteRepository.findByBusiness_IdOrderByCreatedAtDesc(businessId);
    }

    @Transactional
    public PartnerBusinessNote saveNote(
            Long businessId,
            Long noteId,
            BusinessNoteRequest request,
            String email
    ) {
        PartnerBusiness business = requireOwnedBusiness(businessId, email);
        validateNoteRequest(request);

        PartnerBusinessNote note;
        if (noteId == null) {
            note = new PartnerBusinessNote();
        } else {
            note = noteRepository.findByIdAndBusiness_Id(noteId, businessId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Không tìm thấy ghi chú."
                    ));
        }

        String oldTitle = note.getTitle();
        note.setBusiness(business);
        note.setTitle(request.getTitle().trim());
        note.setContent(request.getContent().trim());
        note.setNoteType(request.getNoteType() == null
                || request.getNoteType().isBlank()
                ? "TRAVEL_TIP"
                : request.getNoteType().trim().toUpperCase(Locale.ROOT));
        note.setActive(request.getActive() == null || request.getActive());

        PartnerBusinessNote saved = noteRepository.save(note);

        auditService.log(
                email,
                businessId,
                noteId == null ? "CREATE" : "UPDATE",
                "BUSINESS_NOTE",
                saved.getId(),
                oldTitle,
                saved.getTitle()
        );

        return saved;
    }

    @Transactional
    public void deleteNote(Long businessId, Long noteId, String email) {
        requireOwnedBusiness(businessId, email);

        PartnerBusinessNote note = noteRepository
                .findByIdAndBusiness_Id(noteId, businessId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Không tìm thấy ghi chú."
                ));

        note.setActive(false);
        noteRepository.save(note);

        auditService.log(
                email,
                businessId,
                "DEACTIVATE",
                "BUSINESS_NOTE",
                noteId,
                note.getTitle(),
                null
        );
    }

    private PartnerBusiness requireOwnedBusiness(Long businessId, String email) {
        accessService.requireApprovedPartner(email);

        return businessRepository
                .findByIdAndPartner_User_EmailAndDeletedAtIsNull(businessId, email)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Không tìm thấy cơ sở kinh doanh hoặc bạn không có quyền quản lý."
                ));
    }

    private void validateMediaRequest(BusinessMediaRequest request) {
        if (request == null || request.getMediaUrl() == null
                || request.getMediaUrl().isBlank()) {
            throw new IllegalArgumentException(
                    "Đường dẫn media không được để trống."
            );
        }

        normalizeMediaType(request.getMediaType());

        if (request.getSortOrder() != null && request.getSortOrder() < 0) {
            throw new IllegalArgumentException(
                    "Thứ tự media không được âm."
            );
        }
    }

    private String normalizeMediaType(String mediaType) {
        String normalized = mediaType == null || mediaType.isBlank()
                ? "IMAGE"
                : mediaType.trim().toUpperCase(Locale.ROOT);

        if (!MEDIA_TYPES.contains(normalized)) {
            throw new IllegalArgumentException(
                    "mediaType chỉ nhận IMAGE hoặc VIDEO."
            );
        }

        return normalized;
    }

    private void validateNoteRequest(BusinessNoteRequest request) {
        if (request == null
                || request.getTitle() == null
                || request.getTitle().isBlank()
                || request.getContent() == null
                || request.getContent().isBlank()) {
            throw new IllegalArgumentException(
                    "Tiêu đề và nội dung ghi chú không được để trống."
            );
        }
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
