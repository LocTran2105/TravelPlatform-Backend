package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.entity.Place;
import hcmute.edu.vn.backend.repository.PlaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@SuppressWarnings({"unchecked", "rawtypes"})
public class AccommodationService {

    private final PlaceRepository placeRepository;

    // Trả về List thuần tuý, không dùng dấu ngoặc nhọn để tránh lỗi giao diện
    public List searchAccommodations(int budgetMax, String location) {

        // Dùng var để Java tự hiểu là List của Place
        var allHotels = placeRepository.findByPlaceType("ACCOMMODATION");

        return allHotels.stream()
                .filter(place -> {
                    if (budgetMax > 0 && place.getEstimatedCostMin() != null) {
                        return place.getEstimatedCostMin().compareTo(new BigDecimal(budgetMax)) <= 0;
                    }
                    return true;
                })
                .filter(place -> {
                    if (location != null && !location.isEmpty() && place.getAddress() != null) {
                        return place.getAddress().toLowerCase().contains(location.toLowerCase());
                    }
                    return true;
                })
                .limit(5)
                .map(place -> Map.of(
                        "placeId", place.getId(),
                        "name", place.getName(),
                        "price", place.getEstimatedCostMin() != null ? place.getEstimatedCostMin() : 0,
                        "rating", place.getRating() != null ? place.getRating() : 0.0,
                        "address", place.getAddress() != null ? place.getAddress() : ""
                ))
                .collect(Collectors.toList());
    }
}