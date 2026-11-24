package hcmute.edu.vn.backend.service;

import jakarta.annotation.PreDestroy;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.io.UnsupportedEncodingException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Gửi email của hệ thống. Việc gửi chạy trên luồng riêng nên không làm chậm request,
 * và lỗi gửi mail (Gmail từ chối, mất mạng...) chỉ được ghi log, không làm hỏng
 * thao tác chính (vd: tạo chuyến đi).
 */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy");

    /** Dữ liệu một email mời. token là token gốc (chỉ tồn tại trong email này). */
    public record TripInvitationMail(
            String toEmail,
            String inviterName,
            String tripTitle,
            String destination,
            LocalDate startDate,
            LocalDate endDate,
            String role,
            String token,
            LocalDateTime expiresAt) {}

    private final JavaMailSender mailSender;
    private final String fromAddress;
    private final String frontendUrl;

    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "mail-sender");
        t.setDaemon(true);
        return t;
    });

    public MailService(JavaMailSender mailSender,
                       @Value("${spring.mail.username:}") String fromAddress,
                       @Value("${app.frontend-url:http://localhost:5173}") String frontendUrl) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress == null ? "" : fromAddress.trim();
        this.frontendUrl = frontendUrl == null ? "" : frontendUrl.trim();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }

    public String buildInviteLink(String token) {
        String base = frontendUrl;
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "/invite/" + token;
    }

    /** Gửi bất đồng bộ; không ném lỗi ra ngoài. */
    public void sendTripInvitation(TripInvitationMail mail) {
        executor.execute(() -> {
            try {
                send(mail);
            } catch (Exception e) {
                log.error("Không gửi được email mời tham gia chuyến đi (người nhận: {}): {}",
                        maskForLog(mail.toEmail()), e.getMessage());
            }
        });
    }

    private void send(TripInvitationMail mail) throws MessagingException, UnsupportedEncodingException {
        String link = buildInviteLink(mail.token());

        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
        if (!fromAddress.isEmpty()) {
            helper.setFrom(fromAddress, "TravelPlatform");
        }
        helper.setTo(mail.toEmail());
        helper.setSubject(buildSubject(mail));
        helper.setText(buildPlainText(mail, link), buildHtml(mail, link));
        mailSender.send(message);
    }

    // ------------------------------------------------------------------
    // Nội dung email (static + package-private để kiểm thử độc lập)
    // ------------------------------------------------------------------

    static String buildSubject(TripInvitationMail m) {
        String s = clean(m.inviterName()) + " mời bạn tham gia chuyến đi " + clean(m.tripTitle());
        return s.length() > 150 ? s.substring(0, 147) + "..." : s;
    }

    static String buildPlainText(TripInvitationMail m, String link) {
        StringBuilder sb = new StringBuilder();
        sb.append(clean(m.inviterName())).append(" mời bạn tham gia chuyến đi \"")
                .append(clean(m.tripTitle())).append("\" trên TravelPlatform.\n\n");
        if (notBlank(m.destination())) sb.append("Điểm đến: ").append(clean(m.destination())).append('\n');
        String dates = dateRange(m.startDate(), m.endDate());
        if (!dates.isEmpty()) sb.append("Thời gian: ").append(dates).append('\n');
        sb.append("Quyền của bạn: ").append(roleText(m.role())).append("\n\n");
        sb.append("Mở liên kết để xem lời mời và bấm Tham gia:\n").append(link).append("\n\n");
        if (m.expiresAt() != null) {
            sb.append("Lời mời có hiệu lực đến ").append(m.expiresAt().format(DATE_TIME)).append(".\n");
        }
        sb.append("Nếu bạn chưa có tài khoản, hãy đăng ký bằng chính địa chỉ email này.\n");
        sb.append("Nếu bạn không quen người mời, hãy bỏ qua email này.");
        return sb.toString();
    }

    static String buildHtml(TripInvitationMail m, String link) {
        String inviter = esc(clean(m.inviterName()));
        String title = esc(clean(m.tripTitle()));
        String href = esc(link);

        StringBuilder rows = new StringBuilder();
        if (notBlank(m.destination())) {
            rows.append(row("Điểm đến", esc(clean(m.destination()))));
        }
        String dates = dateRange(m.startDate(), m.endDate());
        if (!dates.isEmpty()) {
            rows.append(row("Thời gian", esc(dates)));
        }
        rows.append(row("Quyền của bạn", esc(roleText(m.role()))));

        String expiry = m.expiresAt() == null ? "" :
                "<p style=\"margin:0 0 6px;font-size:13px;color:#64748b;\">Lời mời có hiệu lực đến "
                        + esc(m.expiresAt().format(DATE_TIME)) + ".</p>";

        return "<!DOCTYPE html><html lang=\"vi\"><head><meta charset=\"UTF-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"></head>"
                + "<body style=\"margin:0;padding:0;background:#f1f5f9;font-family:Segoe UI,Arial,sans-serif;\">"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:#f1f5f9;padding:32px 12px;\"><tr><td align=\"center\">"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"max-width:520px;background:#ffffff;border-radius:16px;overflow:hidden;\">"
                + "<tr><td style=\"padding:22px 32px;border-bottom:1px solid #f1f5f9;font-size:18px;font-weight:800;color:#0f172a;\">"
                + "<span style=\"color:#FF5645;\">&#9679;</span> TravelPlatform</td></tr>"
                + "<tr><td style=\"padding:28px 32px 8px;\">"
                + "<h1 style=\"margin:0 0 10px;font-size:22px;line-height:1.3;color:#0f172a;\">" + inviter + " mời bạn cùng lên kế hoạch chuyến đi</h1>"
                + "<p style=\"margin:0 0 18px;font-size:15px;line-height:1.6;color:#475569;\">Bạn được mời tham gia chuyến đi <strong style=\"color:#0f172a;\">" + title + "</strong>.</p>"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:#f8fafc;border:1px solid #e2e8f0;border-radius:12px;padding:6px 16px;margin-bottom:24px;\">"
                + rows + "</table>"
                + "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin:0 auto 22px;\"><tr><td style=\"background:#FF5645;border-radius:999px;\">"
                + "<a href=\"" + href + "\" style=\"display:inline-block;padding:13px 34px;font-size:15px;font-weight:700;color:#ffffff;text-decoration:none;\">Xem lời mời</a>"
                + "</td></tr></table>"
                + expiry
                + "<p style=\"margin:0 0 6px;font-size:13px;line-height:1.6;color:#64748b;\">Nếu bạn chưa có tài khoản, hãy đăng ký bằng chính địa chỉ email này rồi bấm <strong>Tham gia</strong>.</p>"
                + "<p style=\"margin:0 0 20px;font-size:13px;line-height:1.6;color:#64748b;\">Nút không bấm được? Dán liên kết này vào trình duyệt:<br>"
                + "<a href=\"" + href + "\" style=\"color:#FF5645;word-break:break-all;\">" + href + "</a></p>"
                + "</td></tr>"
                + "<tr><td style=\"padding:16px 32px 24px;font-size:12px;color:#94a3b8;border-top:1px solid #f1f5f9;\">Nếu bạn không quen người mời, hãy bỏ qua email này. Bạn sẽ không bị thêm vào chuyến đi nếu không bấm Tham gia.</td></tr>"
                + "</table></td></tr></table></body></html>";
    }

    private static String row(String label, String value) {
        return "<tr><td style=\"padding:9px 0;font-size:13px;color:#64748b;width:120px;vertical-align:top;\">" + label + "</td>"
                + "<td style=\"padding:9px 0;font-size:14px;font-weight:600;color:#0f172a;\">" + value + "</td></tr>";
    }

    private static String roleText(String role) {
        return "VIEWER".equalsIgnoreCase(role) ? "Chỉ xem" : "Xem và chỉnh sửa";
    }

    private static String dateRange(LocalDate start, LocalDate end) {
        if (start == null) return "";
        if (end == null || end.equals(start)) return start.format(DATE);
        return start.format(DATE) + " - " + end.format(DATE);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /** Bỏ xuống dòng/ký tự điều khiển (chống chèn header trong subject). */
    private static String clean(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\p{Cntrl}]+", " ").trim();
    }

    static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&#39;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String maskForLog(String email) {
        if (email == null) return "";
        int at = email.indexOf('@');
        return at <= 1 ? "***" + (at < 0 ? "" : email.substring(at)) : email.charAt(0) + "***" + email.substring(at);
    }
}
