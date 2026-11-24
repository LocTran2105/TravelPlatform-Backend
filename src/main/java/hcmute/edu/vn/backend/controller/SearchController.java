package hcmute.edu.vn.backend.controller;

import hcmute.edu.vn.backend.service.UserSearchService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final UserSearchService userSearchService;

    public SearchController(UserSearchService userSearchService) {
        this.userSearchService = userSearchService;
    }

    // GET /api/search/users?q=loc&limit=8  (yêu cầu đăng nhập theo SecurityConfig hiện tại)
    @GetMapping("/users")
    public ResponseEntity<?> searchUsers(
            @RequestParam("q") String query,
            @RequestParam(value = "limit", defaultValue = "10") int limit
    ) {
        return ResponseEntity.ok(userSearchService.searchByUsername(query, limit));
    }
}