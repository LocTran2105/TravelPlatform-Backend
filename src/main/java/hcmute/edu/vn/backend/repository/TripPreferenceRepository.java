package hcmute.edu.vn.backend.repository;

import hcmute.edu.vn.backend.entity.TripPreference;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TripPreferenceRepository extends JpaRepository<TripPreference, Long> {
}