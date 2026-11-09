package hcmute.edu.vn.backend.controller;

import hcmute.edu.vn.backend.dto.MenuCategoryRequest;
import hcmute.edu.vn.backend.dto.MenuItemRequest;
import hcmute.edu.vn.backend.service.PartnerMenuService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

@RestController
@RequestMapping("/api/partner/businesses/{businessId}/menu")
@RequiredArgsConstructor
public class PartnerMenuController {

    private final PartnerMenuService service;

    @GetMapping("/categories")
    public ResponseEntity<?> categories(
            @PathVariable Long businessId,
            Principal principal
    ) {
        try {
            return ResponseEntity.ok(
                    service.getCategories(businessId, principal.getName())
            );
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return ResponseEntity.badRequest().body(exception.getMessage());
        }
    }

    @PostMapping("/categories")
    public ResponseEntity<?> createCategory(
            @PathVariable Long businessId,
            @Valid @RequestBody MenuCategoryRequest request,
            Principal principal
    ) {
        try {
            return ResponseEntity.ok(
                    service.createCategory(businessId, request, principal.getName())
            );
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return ResponseEntity.badRequest().body(exception.getMessage());
        }
    }

    @PutMapping("/categories/{categoryId}")
    public ResponseEntity<?> updateCategory(
            @PathVariable Long businessId,
            @PathVariable Long categoryId,
            @Valid @RequestBody MenuCategoryRequest request,
            Principal principal
    ) {
        try {
            return ResponseEntity.ok(
                    service.updateCategory(
                            businessId,
                            categoryId,
                            request,
                            principal.getName()
                    )
            );
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return ResponseEntity.badRequest().body(exception.getMessage());
        }
    }

    @DeleteMapping("/categories/{categoryId}")
    public ResponseEntity<?> deleteCategory(
            @PathVariable Long businessId,
            @PathVariable Long categoryId,
            Principal principal
    ) {
        try {
            service.deleteCategory(
                    businessId,
                    categoryId,
                    principal.getName()
            );
            return ResponseEntity.ok("Đã ẩn danh mục menu.");
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return ResponseEntity.badRequest().body(exception.getMessage());
        }
    }

    @GetMapping("/items")
    public ResponseEntity<?> items(
            @PathVariable Long businessId,
            Principal principal
    ) {
        try {
            return ResponseEntity.ok(
                    service.getItems(businessId, principal.getName())
            );
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return ResponseEntity.badRequest().body(exception.getMessage());
        }
    }

    @PostMapping("/items")
    public ResponseEntity<?> createItem(
            @PathVariable Long businessId,
            @Valid @RequestBody MenuItemRequest request,
            Principal principal
    ) {
        try {
            return ResponseEntity.ok(
                    service.createItem(businessId, request, principal.getName())
            );
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return ResponseEntity.badRequest().body(exception.getMessage());
        }
    }

    @PutMapping("/items/{itemId}")
    public ResponseEntity<?> updateItem(
            @PathVariable Long businessId,
            @PathVariable Long itemId,
            @Valid @RequestBody MenuItemRequest request,
            Principal principal
    ) {
        try {
            return ResponseEntity.ok(
                    service.updateItem(
                            businessId,
                            itemId,
                            request,
                            principal.getName()
                    )
            );
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return ResponseEntity.badRequest().body(exception.getMessage());
        }
    }

    @DeleteMapping("/items/{itemId}")
    public ResponseEntity<?> deleteItem(
            @PathVariable Long businessId,
            @PathVariable Long itemId,
            Principal principal
    ) {
        try {
            service.deactivateItem(
                    businessId,
                    itemId,
                    principal.getName()
            );
            return ResponseEntity.ok("Đã ngừng kinh doanh món/dịch vụ.");
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return ResponseEntity.badRequest().body(exception.getMessage());
        }
    }

    @GetMapping("/items/{itemId}/price-history")
    public ResponseEntity<?> priceHistory(
            @PathVariable Long businessId,
            @PathVariable Long itemId,
            Principal principal
    ) {
        try {
            return ResponseEntity.ok(
                    service.getPriceHistory(
                            businessId,
                            itemId,
                            principal.getName()
                    )
            );
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return ResponseEntity.badRequest().body(exception.getMessage());
        }
    }
}
