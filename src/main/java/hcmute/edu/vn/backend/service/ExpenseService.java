package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.dto.ExpenseOverviewResponse;
import hcmute.edu.vn.backend.dto.ExpenseRequest;
import hcmute.edu.vn.backend.dto.ExpenseResponse;
import hcmute.edu.vn.backend.dto.SettlementResponse;
import hcmute.edu.vn.backend.entity.*;
import hcmute.edu.vn.backend.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

@Service
public class ExpenseService {

    private static final BigDecimal MAX_MONEY = new BigDecimal("1000000000000");
    private static final Set<String> CATEGORIES = Set.of(
            "FLIGHTS", "LODGING", "CAR_RENTAL", "TRANSIT", "FOOD", "DRINKS",
            "SIGHTSEEING", "ACTIVITIES", "SHOPPING", "GAS", "GROCERIES", "OTHER");

    @Autowired
    private ExpenseRepository expenseRepository;
    @Autowired
    private TripRepository tripRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private TripMemberRepository tripMemberRepository;
    @Autowired
    private TripDestinationRepository tripDestinationRepository;

    // ==========================================
    // HÀM HỖ TRỢ
    // ==========================================

    // Chỉ chủ chuyến đi hoặc thành viên mới được xem/sửa chi phí
    private Trip loadTripForMember(Long tripId, String email) throws Exception {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new Exception("Không tìm thấy chuyến đi!"));
        boolean isOwner = trip.getUser().getEmail().equalsIgnoreCase(email);
        boolean isMember = tripMemberRepository.existsByTripIdAndUserEmail(tripId, email);
        if (!isOwner && !isMember) {
            throw new Exception("Bạn không có quyền truy cập chi phí của chuyến đi này!");
        }
        return trip;
    }

    // Thành viên chuyến đi, luôn gồm cả chủ chuyến đi (chuyến đi nhân bản chưa có bản ghi trip_members cho chủ)
    private Map<Long, User> getMembers(Trip trip) {
        Map<Long, User> members = new LinkedHashMap<>();
        members.put(trip.getUser().getId(), trip.getUser());
        for (TripMember m : tripMemberRepository.findByTripId(trip.getId())) {
            members.putIfAbsent(m.getUser().getId(), m.getUser());
        }
        return members;
    }

    private LocalDate effectiveDate(Expense e) {
        if (e.getExpenseDate() != null) return e.getExpenseDate();
        if (e.getCreatedAt() != null) return e.getCreatedAt().toLocalDate();
        return LocalDate.MIN;
    }

    private ExpenseResponse toResponse(Expense e) {
        List<ExpenseResponse.SplitInfo> splits = e.getSplits().stream()
                .map(s -> new ExpenseResponse.SplitInfo(s.getUser().getId(), s.getUser().getFullName(), s.getAmountOwed()))
                .toList();
        LocalDate date = e.getExpenseDate() != null ? e.getExpenseDate()
                : (e.getCreatedAt() != null ? e.getCreatedAt().toLocalDate() : null);
        return new ExpenseResponse(
                e.getId(), e.getTitle(), e.getCategory(), e.getDescription(), e.getAmount(), date,
                e.getTripDestination() != null ? e.getTripDestination().getId() : null,
                e.getPayer().getId(), e.getPayer().getFullName(), e.getPayer().getAvatar(),
                splits, e.getCreatedAt());
    }

    // ==========================================
    // 1. THÊM MỘT KHOẢN CHI
    // ==========================================
    @Transactional(rollbackFor = Exception.class)
    public ExpenseResponse addExpense(Long tripId, ExpenseRequest request, String email) throws Exception {
        Trip trip = loadTripForMember(tripId, email);
        User currentUser = userRepository.findByEmail(email)
                .orElseThrow(() -> new Exception("Không tìm thấy người dùng!"));
        Map<Long, User> members = getMembers(trip);

        // Số tiền: VNĐ nguyên đồng, lớn hơn 0
        if (request.getAmount() == null) throw new Exception("Vui lòng nhập số tiền!");
        BigDecimal amount = request.getAmount().setScale(0, RoundingMode.HALF_UP);
        if (amount.signum() <= 0) throw new Exception("Số tiền phải lớn hơn 0!");
        if (amount.compareTo(MAX_MONEY) > 0) throw new Exception("Số tiền quá lớn!");

        // Người trả tiền: mặc định là người đang đăng nhập, nếu chọn người khác thì phải là thành viên
        User payer = currentUser;
        if (request.getPayerId() != null) {
            payer = members.get(request.getPayerId());
            if (payer == null) throw new Exception("Người trả tiền phải là thành viên của chuyến đi!");
        }

        // Địa điểm trong lịch trình (nếu có) phải thuộc chuyến đi này
        TripDestination destination = null;
        if (request.getTripDestinationId() != null) {
            destination = tripDestinationRepository.findById(request.getTripDestinationId())
                    .orElseThrow(() -> new Exception("Không tìm thấy địa điểm trong lịch trình!"));
            if (!destination.getTripDay().getTrip().getId().equals(tripId)) {
                throw new Exception("Địa điểm không thuộc chuyến đi này!");
            }
        }

        // Danh mục
        String category = (request.getCategory() == null || request.getCategory().isBlank())
                ? "OTHER" : request.getCategory().trim().toUpperCase();
        if (!CATEGORIES.contains(category)) throw new Exception("Danh mục chi phí không hợp lệ!");

        // Tên hiển thị
        String title = (request.getTitle() != null && !request.getTitle().isBlank())
                ? request.getTitle().trim()
                : (destination != null ? destination.getPlaceName() : category);
        if (title.length() > 255) title = title.substring(0, 255);

        // Những người cùng chịu chi phí: trống = "Không chia" (người trả tự chịu toàn bộ)
        List<Long> splitIds = request.getSplitUserIds() == null ? List.of()
                : request.getSplitUserIds().stream().filter(Objects::nonNull).distinct().toList();
        List<User> debtors = new ArrayList<>();
        if (splitIds.isEmpty()) {
            debtors.add(payer);
        } else {
            for (Long id : splitIds) {
                User u = members.get(id);
                if (u == null) throw new Exception("Người được chia tiền phải là thành viên của chuyến đi!");
                debtors.add(u);
            }
        }

        Expense expense = new Expense();
        expense.setTrip(trip);
        expense.setTripDestination(destination);
        expense.setPayer(payer);
        expense.setTitle(title);
        expense.setCategory(category);
        expense.setDescription(request.getDescription() != null && !request.getDescription().isBlank()
                ? request.getDescription().trim() : null);
        expense.setAmount(amount);
        expense.setExpenseDate(request.getExpenseDate() != null ? request.getExpenseDate() : LocalDate.now());

        // Chia đều theo đồng; phần dư (mỗi người 1đ) dồn cho những người đứng đầu danh sách
        // => tổng các phần chia luôn đúng bằng số tiền của khoản chi
        BigDecimal count = BigDecimal.valueOf(debtors.size());
        BigDecimal base = amount.divide(count, 0, RoundingMode.DOWN);
        int remainder = amount.subtract(base.multiply(count)).intValueExact();
        for (int i = 0; i < debtors.size(); i++) {
            ExpenseSplit split = new ExpenseSplit();
            split.setExpense(expense);
            split.setUser(debtors.get(i));
            split.setAmountOwed(i < remainder ? base.add(BigDecimal.ONE) : base);
            expense.getSplits().add(split);
        }

        return toResponse(expenseRepository.save(expense));
    }

    // ==========================================
    // 2. DỮ LIỆU TAB NGÂN SÁCH
    // ==========================================
    @Transactional(readOnly = true)
    public ExpenseOverviewResponse getOverview(Long tripId, String email) throws Exception {
        Trip trip = loadTripForMember(tripId, email);
        User current = userRepository.findByEmail(email)
                .orElseThrow(() -> new Exception("Không tìm thấy người dùng!"));

        List<Expense> expenses = new ArrayList<>(expenseRepository.findAllWithDetailsByTripId(tripId));
        Comparator<Expense> byDate = Comparator.comparing(this::effectiveDate);
        expenses.sort(byDate.thenComparing(Expense::getId).reversed()); // mới nhất trước

        BigDecimal total = expenses.stream().map(Expense::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<ExpenseOverviewResponse.MemberInfo> members = getMembers(trip).values().stream()
                .map(u -> new ExpenseOverviewResponse.MemberInfo(u.getId(), u.getFullName(), u.getAvatar()))
                .toList();

        return new ExpenseOverviewResponse(current.getId(), total, members,
                expenses.stream().map(this::toResponse).toList());
    }

    // ==========================================
    // 3. XOÁ KHOẢN CHI (người trả tiền hoặc chủ chuyến đi)
    // ==========================================
    @Transactional(rollbackFor = Exception.class)
    public void deleteExpense(Long tripId, Long expenseId, String email) throws Exception {
        Trip trip = loadTripForMember(tripId, email);
        Expense expense = expenseRepository.findById(expenseId)
                .orElseThrow(() -> new Exception("Không tìm thấy khoản chi!"));
        if (!expense.getTrip().getId().equals(tripId)) {
            throw new Exception("Khoản chi không thuộc chuyến đi này!");
        }
        boolean isOwner = trip.getUser().getEmail().equalsIgnoreCase(email);
        boolean isPayer = expense.getPayer().getEmail().equalsIgnoreCase(email);
        if (!isOwner && !isPayer) {
            throw new Exception("Chỉ người trả tiền hoặc chủ chuyến đi mới được xóa khoản chi này!");
        }
        expenseRepository.delete(expense);
    }

    // ==========================================
    // 4. CÂN ĐỐI NHÓM (ai nợ ai bao nhiêu, ít giao dịch nhất)
    // ==========================================
    @Transactional(readOnly = true)
    public SettlementResponse calculateSettlement(Long tripId, String email) throws Exception {
        Trip trip = loadTripForMember(tripId, email);

        // Tính theo id người dùng (không theo tên, vì 2 người có thể trùng họ tên)
        Map<Long, User> users = new LinkedHashMap<>(getMembers(trip));
        Map<Long, BigDecimal> balances = new LinkedHashMap<>();
        users.keySet().forEach(id -> balances.put(id, BigDecimal.ZERO));

        // Số dư = tiền mình ứng ra - phần mình phải chịu
        for (Expense exp : expenseRepository.findAllWithDetailsByTripId(tripId)) {
            User payer = exp.getPayer();
            users.putIfAbsent(payer.getId(), payer);
            balances.merge(payer.getId(), exp.getAmount(), BigDecimal::add);
            for (ExpenseSplit split : exp.getSplits()) {
                User u = split.getUser();
                users.putIfAbsent(u.getId(), u);
                balances.merge(u.getId(), split.getAmountOwed().negate(), BigDecimal::add);
            }
        }

        PriorityQueue<BalanceNode> debtors = new PriorityQueue<>((a, b) -> b.amount.compareTo(a.amount));
        PriorityQueue<BalanceNode> creditors = new PriorityQueue<>((a, b) -> b.amount.compareTo(a.amount));
        for (Map.Entry<Long, BigDecimal> entry : balances.entrySet()) {
            int sign = entry.getValue().signum();
            if (sign < 0) debtors.add(new BalanceNode(entry.getKey(), entry.getValue().negate()));
            else if (sign > 0) creditors.add(new BalanceNode(entry.getKey(), entry.getValue()));
        }

        // Ghép người nợ nhiều nhất với người được nợ nhiều nhất
        List<SettlementResponse.TransferInfo> transfers = new ArrayList<>();
        while (!debtors.isEmpty() && !creditors.isEmpty()) {
            BalanceNode debtor = debtors.poll();
            BalanceNode creditor = creditors.poll();
            BigDecimal pay = debtor.amount.min(creditor.amount);

            User from = users.get(debtor.userId);
            User to = users.get(creditor.userId);
            transfers.add(new SettlementResponse.TransferInfo(
                    from.getId(), from.getFullName(), to.getId(), to.getFullName(), pay));

            debtor.amount = debtor.amount.subtract(pay);
            creditor.amount = creditor.amount.subtract(pay);
            if (debtor.amount.signum() > 0) debtors.add(debtor);
            if (creditor.amount.signum() > 0) creditors.add(creditor);
        }

        List<SettlementResponse.BalanceInfo> balanceList = balances.entrySet().stream()
                .map(en -> {
                    User u = users.get(en.getKey());
                    return new SettlementResponse.BalanceInfo(u.getId(), u.getFullName(), u.getAvatar(), en.getValue());
                })
                .sorted((a, b) -> b.getBalance().compareTo(a.getBalance()))
                .toList();

        return new SettlementResponse(balanceList, transfers);
    }

    // Lưu id và số dư (phục vụ thuật toán ghép cặp)
    private static class BalanceNode {
        Long userId;
        BigDecimal amount;

        BalanceNode(Long userId, BigDecimal amount) {
            this.userId = userId;
            this.amount = amount;
        }
    }
}