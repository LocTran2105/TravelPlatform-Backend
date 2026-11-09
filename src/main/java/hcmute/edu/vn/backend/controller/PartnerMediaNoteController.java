package hcmute.edu.vn.backend.controller;

import hcmute.edu.vn.backend.dto.BusinessMediaRequest;
import hcmute.edu.vn.backend.dto.BusinessNoteRequest;
import hcmute.edu.vn.backend.service.PartnerMediaNoteService;
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
@RequestMapping("/api/partner/businesses/{businessId}")
@RequiredArgsConstructor
public class PartnerMediaNoteController {

    private final PartnerMediaNoteService service;

    @GetMapping("/media")
    public ResponseEntity<?> media(
            @PathVariable Long businessId,
            Principal principal
    ) {
        try {
            return ResponseEntity.ok(
                    service.listMedia(businessId, principal.getName())
            );
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return ResponseEntity.badRequest().body(exception.getMessage());
        }
    }

    @PostMapping("/media")
    public ResponseEntity<?> addMedia(
            @PathVariable Long businessId,
            @Valid @RequestBody BusinessMediaRequest request,
            Principal principal
    ) {
        try {
            return ResponseEntity.ok(
                    service.addMedia(businessId, request, principal.getName())
            );
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return ResponseEntity.badRequest().body(exception.getMessage());
        }
    }

    @DeleteMapping("/media/{mediaId}")
    public ResponseEntity<?> deleteMedia(
            @PathVariable Long businessId,
            @PathVariable Long mediaId,
            Principal principal
    ) {
        try {
            service.deleteMedia(businessId, mediaId, principal.getName());
            return ResponseEntity.ok("Đã xóa media.");
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return ResponseEntity.badRequest().body(exception.getMessage());
        }
    }

    @GetMapping("/notes")
    public ResponseEntity<?> notes(
            @PathVariable Long businessId,
            Principal principal
    ) {
        try {
            return ResponseEntity.ok(
                    service.listNotes(businessId, principal.getName())
            );
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return ResponseEntity.badRequest().body(exception.getMessage());
        }
    }

    @PostMapping("/notes")
    public ResponseEntity<?> createNote(
            @PathVariable Long businessId,
            @Valid @RequestBody BusinessNoteRequest request,
            Principal principal
    ) {
        try {
            return ResponseEntity.ok(
                    service.saveNote(
                            businessId,
                            null,
                            request,
                            principal.getName()
                    )
            );
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return ResponseEntity.badRequest().body(exception.getMessage());
        }
    }

    @PutMapping("/notes/{noteId}")
    public ResponseEntity<?> updateNote(
            @PathVariable Long businessId,
            @PathVariable Long noteId,
            @Valid @RequestBody BusinessNoteRequest request,
            Principal principal
    ) {
        try {
            return ResponseEntity.ok(
                    service.saveNote(
                            businessId,
                            noteId,
                            request,
                            principal.getName()
                    )
            );
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return ResponseEntity.badRequest().body(exception.getMessage());
        }
    }

    @DeleteMapping("/notes/{noteId}")
    public ResponseEntity<?> deleteNote(
            @PathVariable Long businessId,
            @PathVariable Long noteId,
            Principal principal
    ) {
        try {
            service.deleteNote(businessId, noteId, principal.getName());
            return ResponseEntity.ok("Đã ẩn ghi chú.");
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return ResponseEntity.badRequest().body(exception.getMessage());
        }
    }
}
