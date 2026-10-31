package hcmute.edu.vn.backend.repository;

import hcmute.edu.vn.backend.entity.Expense;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface ExpenseRepository extends JpaRepository<Expense, Long> {
    List<Expense> findByTripId(Long tripId);

    // Lấy luôn người trả + các khoản chia + người chịu trong 1 query (tránh N+1)
    @Query("select distinct e from Expense e " +
            "left join fetch e.payer " +
            "left join fetch e.splits s " +
            "left join fetch s.user " +
            "where e.trip.id = :tripId")
    List<Expense> findAllWithDetailsByTripId(@Param("tripId") Long tripId);
}