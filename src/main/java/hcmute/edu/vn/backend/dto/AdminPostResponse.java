package hcmute.edu.vn.backend.dto;

import hcmute.edu.vn.backend.entity.Image;
import hcmute.edu.vn.backend.entity.Post;

import java.time.LocalDateTime;
import java.util.List;

public record AdminPostResponse(
        Long id,
        Long userId,
        String authorName,
        String authorEmail,
        String caption,
        String locationName,
        Double latitude,
        Double longitude,
        Integer rating,
        String moderationStatus,
        String moderationNote,
        String moderatedByEmail,
        LocalDateTime moderatedAt,
        LocalDateTime createdAt,
        List<String> imageUrls,
        List<String> videoUrls
) {

    public static AdminPostResponse from(
            Post post,
            List<Image> media
    ) {

        List<String> images = media.stream()
                .filter(image ->
                        "IMAGE".equalsIgnoreCase(
                                image.getMediaType()
                        )
                )
                .map(Image::getImageUrl)
                .toList();

        List<String> videos = media.stream()
                .filter(image ->
                        "VIDEO".equalsIgnoreCase(
                                image.getMediaType()
                        )
                )
                .map(Image::getImageUrl)
                .toList();

        return new AdminPostResponse(
                post.getId(),
                post.getUser().getId(),
                post.getUser().getFullName(),
                post.getUser().getEmail(),
                post.getCaption(),
                post.getLocationName(),
                post.getLatitude() != null
                        ? post.getLatitude().doubleValue()
                        : null,

                post.getLongitude() != null
                        ? post.getLongitude().doubleValue()
                        : null,
                post.getRating(),
                post.getModerationStatus(),
                post.getModerationNote(),
                post.getModeratedBy() == null
                        ? null
                        : post.getModeratedBy().getEmail(),
                post.getModeratedAt(),
                post.getCreatedAt(),
                images,
                videos
        );
    }
}