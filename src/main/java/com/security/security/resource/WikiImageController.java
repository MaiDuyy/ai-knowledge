package com.security.security.resource;

import com.security.security.entity.SourceImage;
import com.security.security.repository.SourceImageRepository;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@RestController
@RequestMapping("/api/wiki/images")
@RequiredArgsConstructor
@Slf4j
public class WikiImageController {

    private final SourceImageRepository sourceImageRepository;

    @Value("${app.upload.dir:uploads}")
    private String uploadDir;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ResolveRequest {
        private List<UUID> ids = new ArrayList<>();
    }

    @Data
    @AllArgsConstructor
    public static class ResolveResponse {
        private Map<String, String> resolved;
        private List<String> denied;
    }

    /**
     * POST /api/wiki/images/resolve
     * Resolves a batch of image UUIDs to direct raw image fetch paths.
     */
    @PostMapping("/resolve")
    public ResponseEntity<ResolveResponse> resolveWikiImages(@RequestBody ResolveRequest body) {
        log.info("[WikiImageController] Resolving {} image IDs", body.getIds().size());
        if (body.getIds() == null || body.getIds().isEmpty()) {
            return ResponseEntity.ok(new ResolveResponse(new HashMap<>(), new ArrayList<>()));
        }

        List<SourceImage> images = sourceImageRepository.findByIdIn(body.getIds());
        Map<String, String> resolved = new HashMap<>();
        List<String> denied = new ArrayList<>(); // Stub for RBAC access control if needed in the future

        for (SourceImage img : images) {
            // Relative URL that will be proxied beautifully by the API Gateway
            String relativeUrl = "/api/wiki/images/raw/" + img.getId().toString();
            resolved.put(img.getId().toString(), relativeUrl);
        }

        return ResponseEntity.ok(new ResolveResponse(resolved, denied));
    }

    /**
     * GET /api/wiki/images/raw/{id}
     * Serves the raw image file directly with high-performance cache headers.
     */
    @GetMapping("/raw/{id}")
    public ResponseEntity<Resource> getRawImage(@PathVariable UUID id) {
        log.info("[WikiImageController] Serving raw image: {}", id);
        SourceImage img = sourceImageRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Image not found: " + id));

        try {
            Path imagePath = Paths.get(uploadDir).resolve(img.getMinioKey()).normalize();
            Resource resource = new UrlResource(imagePath.toUri());

            if (!resource.exists() || !resource.isReadable()) {
                log.warn("[WikiImageController] Image file not found on disk at: {}", imagePath);
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }

            MediaType mediaType = MediaType.parseMediaType(img.getContentType());

            return ResponseEntity.ok()
                    .contentType(mediaType)
                    .header(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable")
                    .body(resource);

        } catch (Exception e) {
            log.error("[WikiImageController] Error reading image file: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
