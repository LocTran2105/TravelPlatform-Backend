package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.dto.ExpenseRequest;
import hcmute.edu.vn.backend.entity.Expense;
import hcmute.edu.vn.backend.entity.ExpenseSplit;
import hcmute.edu.vn.backend.entity.Trip;
import hcmute.edu.vn.backend.entity.User;
import hcmute.edu.vn.backend.repository.ExpenseRepository;
import hcmute.edu.vn.backend.repository.ExpenseSplitRepository;
import hcmute.edu.vn.backend.repository.TripRepository;
import hcmute.edu.vn.backend.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

@Service
public class ExpenseService {

    @Autowired
    private ExpenseRepository expenseRepository;
    @Autowired
    private ExpenseSplitRepository expenseSplitRepository;
    @Autowired
    private TripRepository tripRepository;
    @Autowired
    private UserRepository userRepository;

    // 1. Logic Thêm một khoản chi tiêu mới
    @Transactional(rollbackFor = Exception.class)
    public Expense addExpense(Long tripId, ExpenseRequest request, String payerEmail) throws Exception {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new Exception("Không tìm thấy chuyến đi!"));

        User payer = userRepository.findByEmail(payerEmail)
                .orElseThrow(() -> new Exception("Người trả tiền không hợp lệ!"));

        // Tạo bản ghi Expense (Tổng khoản chi)
        Expense expense = new Expense();
        expense.setTrip(trip);
        expense.setPayer(payer); // Người ứng tiền
        expense.setTitle(request.getTitle());
        expense.setAmount(request.getAmount());

        Expense savedExpense = expenseRepository.save(expense);

        // Tạo các bản ghi ExpenseSplit (Chia đều cho những người tham gia)
        int numberOfPeople = request.getSplitUserIds().size();
        if (numberOfPeople == 0) throw new Exception("Phải có ít nhất 1 người chia sẻ khoản tiền!");

        // Tính tiền mỗi người phải chịu (chia đều, làm tròn 2 chữ số)
        BigDecimal amountPerPerson = request.getAmount().divide(new BigDecimal(numberOfPeople), 2, RoundingMode.HALF_UP);

        List<ExpenseSplit> splits = new ArrayList<>();
        for (Long userId : request.getSplitUserIds()) {
            User debtor = userRepository.findById(userId)
                    .orElseThrow(() -> new Exception("Không tìm thấy user id: " + userId));

            ExpenseSplit split = new ExpenseSplit();
            split.setExpense(savedExpense);
            split.setUser(debtor);
            split.setAmountOwed(amountPerPerson);
            splits.add(split);
        }

        expenseSplitRepository.saveAll(splits);
        savedExpense.setSplits(splits);
        return savedExpense;
    }

    // ==========================================
    // 2. THUẬT TOÁN TÍNH TOÁN BÁO CÁO TRẢ NỢ CUỐI CÙNG
    // ==========================================
    public List<String> calculateDebts(Long tripId, String requestUserEmail) throws Exception {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new Exception("Không tìm thấy chuyến đi!"));

        List<Expense> allExpenses = expenseRepository.findByTripId(tripId);

        // Map lưu Số dư của mỗi người (Balance).
        // Số dư = Tiền mình ứng ra trả giúp người khác - Tiền mình nợ người khác
        Map<String, BigDecimal> balances = new HashMap<>();

        for (Expense exp : allExpenses) {
            String payerName = exp.getPayer().getFullName();

            // Người trả tiền được CỘNG thêm tổng số tiền
            balances.put(payerName, balances.getOrDefault(payerName, BigDecimal.ZERO).add(exp.getAmount()));

            // Những người thụ hưởng bị TRỪ đi phần tiền phải chịu
            for (ExpenseSplit split : exp.getSplits()) {
                String debtorName = split.getUser().getFullName();
                balances.put(debtorName, balances.getOrDefault(debtorName, BigDecimal.ZERO).subtract(split.getAmountOwed()));
            }
        }

        // Tách ra 2 danh sách: Những người nợ tiền (Balance < 0) và Những người cho nợ (Balance > 0)
        PriorityQueue<BalanceNode> debtors = new PriorityQueue<>((a, b) -> b.amount.compareTo(a.amount));
        PriorityQueue<BalanceNode> creditors = new PriorityQueue<>((a, b) -> b.amount.compareTo(a.amount));

        for (Map.Entry<String, BigDecimal> entry : balances.entrySet()) {
            if (entry.getValue().compareTo(BigDecimal.ZERO) < 0) {
                debtors.add(new BalanceNode(entry.getKey(), entry.getValue().abs()));
            } else if (entry.getValue().compareTo(BigDecimal.ZERO) > 0) {
                creditors.add(new BalanceNode(entry.getKey(), entry.getValue()));
            }
        }

        // Bắt đầu ghép cặp trả tiền
        List<String> settlementReport = new ArrayList<>();

        while (!debtors.isEmpty() && !creditors.isEmpty()) {
            BalanceNode debtor = debtors.poll(); // Người nợ nhiều nhất
            BalanceNode creditor = creditors.poll(); // Người được nợ nhiều nhất

            // Tính số tiền có thể chuyển ngay lập tức
            BigDecimal amountToSettle = debtor.amount.min(creditor.amount);

            settlementReport.add(debtor.name + " cần chuyển cho " + creditor.name + ": " + amountToSettle + " VNĐ");

            // Cập nhật lại số dư sau khi chuyển khoản
            debtor.amount = debtor.amount.subtract(amountToSettle);
            creditor.amount = creditor.amount.subtract(amountToSettle);

            // Nếu người này chưa trả/nhận hết tiền, đẩy lại vào hàng đợi
            if (debtor.amount.compareTo(BigDecimal.ZERO) > 0) debtors.add(debtor);
            if (creditor.amount.compareTo(BigDecimal.ZERO) > 0) creditors.add(creditor);
        }

        if (settlementReport.isEmpty()) {
            settlementReport.add("Tuyệt vời! Không ai nợ ai, mọi khoản tiền đã được chia đều.");
        }

        return settlementReport;
    }

    // Helper class phục vụ thuật toán (lưu tên và số dư)
    private static class BalanceNode {
        String name;
        BigDecimal amount;

        BalanceNode(String name, BigDecimal amount) {
            this.name = name;
            this.amount = amount;
        }
    }
}