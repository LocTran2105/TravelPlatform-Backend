package hcmute.edu.vn.backend.dto;

import lombok.Getter;
import lombok.Setter;

/** Body của PATCH /api/trips/{tripId}/visibility */
@Getter
@Setter
public class TripVisibilityRequest {
    private String visibility; // PUBLIC, FOLLOWERS, FRIENDS, PRIVATE
}