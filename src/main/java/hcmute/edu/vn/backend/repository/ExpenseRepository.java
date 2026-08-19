package hcmute.edu.vn.backend.repository;

import hcmute.edu.vn.backend.entity.Expense;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface ExpenseRepository extends JpaRepository<Expense, Long> {
    List<Expense> findByTripId(Long tripId);
}