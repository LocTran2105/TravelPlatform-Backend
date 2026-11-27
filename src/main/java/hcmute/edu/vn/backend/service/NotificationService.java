package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.dto.NotificationDto;
import hcmute.edu.vn.backend.entity.Notification;
import hcmute.edu.vn.backend.entity.User;
import hcmute.edu.vn.backend.repository.NotificationRepository;
import hcmute.edu.vn.backend.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Collectors;

/** API thông báo của người dùng hiện tại. Mọi thao tác chỉ tác động lên thông báo của chính người đó. */
@Service
public class NotificationService {

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 50;

    private final NotificationRepository repository;
    private final UserRepository userRepository;

    public NotificationService(NotificationRepository repository, UserRepository userRepository) {
        this.repository = repository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public NotificationDto.PageResult list(String email, boolean unreadOnly, Integer page, Integer size) {
        User me = requireUser(email);
        int p = page == null || page < 0 ? 0 : page;
        int s = size == null || size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);

        PageRequest request = PageRequest.of(p, s);
        Page<Notification> result = unreadOnly
                ? repository.findByRecipientIdAndSeenFalseOrderByEventAtDescIdDesc(me.getId(), request)
                : repository.findByRecipientIdOrderByEventAtDescIdDesc(me.getId(), request);

        List<Notification> rows = result.getContent();
        Set<Long> actorIds = rows.stream()
                .map(Notification::getActorId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, User> actors = new HashMap<>();
        if (!actorIds.isEmpty()) {
            for (User u : userRepository.findAllById(actorIds)) {
                actors.put(u.getId(), u);
            }
        }

        List<NotificationDto.Item> items = rows.stream().map(n -> toItem(n, actors.get(n.getActorId()))).toList();
        return new NotificationDto.PageResult(
                items, p, s, result.getTotalElements(), result.hasNext(),
                repository.countByRecipientIdAndSeenFalse(me.getId()));
    }

    @Transactional(readOnly = true)
    public long unreadCount(String email) {
        return repository.countByRecipientIdAndSeenFalse(requireUser(email).getId());
    }

    @Transactional
    public void markRead(String email, Long id) {
        User me = requireUser(email);
        Notification n = repository.findByIdAndRecipientId(id, me.getId())
                .orElseThrow(() -> new NoSuchElementException("Không tìm thấy thông báo."));
        if (!n.isSeen()) {
            n.setSeen(true);
            repository.save(n);
        }
    }

    @Transactional
    public int markAllRead(String email) {
        return repository.markAllRead(requireUser(email).getId());
    }

    @Transactional
    public void delete(String email, Long id) {
        User me = requireUser(email);
        if (repository.deleteOwned(id, me.getId()) == 0) {
            throw new NoSuchElementException("Không tìm thấy thông báo.");
        }
    }

    private User requireUser(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new AccessDeniedException("Không tìm thấy người dùng."));
        if (!"ACTIVE".equalsIgnoreCase(user.getStatus())) {
            throw new AccessDeniedException("Tài khoản hiện không hoạt động.");
        }
        return user;
    }

    private NotificationDto.Item toItem(Notification n, User actor) {
        NotificationDto.Actor dto = actor == null ? null
                : new NotificationDto.Actor(actor.getId(), actor.getUsername(), actor.getFullName(), actor.getAvatar());
        return new NotificationDto.Item(
                n.getId(), n.getType(), n.getTargetType(), n.getTargetId(), n.getLink(), n.getMessage(),
                n.getActorCount(), n.isSeen(), n.getCreatedAt(), n.getEventAt(), dto);
    }
}
