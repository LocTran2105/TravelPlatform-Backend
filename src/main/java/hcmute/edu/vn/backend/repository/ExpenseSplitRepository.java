package hcmute.edu.vn.backend.repository;

import hcmute.edu.vn.backend.entity.Expense;
import hcmute.edu.vn.backend.entity.ExpenseSplit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExpenseSplitRepository extends JpaRepository<ExpenseSplit, Long> {
}