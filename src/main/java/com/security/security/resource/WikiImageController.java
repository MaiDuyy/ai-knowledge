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
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
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

    private static final Set<String> ALLOWED_IMAGE_TYPES = Set.of(
            "image/png", "image/jpeg", "image/gif", "image/webp", "image/svg+xml"
    );

    /**
     * POST /api/wiki/images/upload
     * Upload a standalone wiki image (not from a compiled document).
     * Returns the image UUID and serving URL for use as image://UUID in markdown.
     */
    @PostMapping("/upload")
    public ResponseEntity<?> uploadWikiImage(
            @RequestParam("file") MultipartFile file,
            @RequestHeader(value = "x-user-id", defaultValue = "anonymous") String userId) {

        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "File is required"));
        }

        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_IMAGE_TYPES.contains(contentType.toLowerCase())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Only image files are allowed (png, jpg, gif, webp, svg)"));
        }

        long maxBytes = 10L * 1024 * 1024; // 10 MB
        if (file.getSize() > maxBytes) {
            return ResponseEntity.badRequest().body(Map.of("error", "File too large. Maximum 10 MB"));
        }

        try {
            String ext = switch (contentType.toLowerCase()) {
                case "image/png"     -> "png";
                case "image/jpeg"    -> "jpg";
                case "image/gif"     -> "gif";
                case "image/webp"    -> "webp";
                case "image/svg+xml" -> "svg";
                default              -> "bin";
            };

            UUID imgId = UUID.randomUUID();
            String imgFilename = imgId + "." + ext;
            Path imagesDir = Paths.get(uploadDir).resolve("images");
            if (!Files.exists(imagesDir)) {
                Files.createDirectories(imagesDir);
            }
            Path targetPath = imagesDir.resolve(imgFilename);
            Files.write(targetPath, file.getBytes());

            SourceImage sourceImg = SourceImage.builder()
                    .id(imgId)
                    .source(null)                   // standalone — not from a document
                    .minioKey("images/" + imgFilename)
                    .pageNumber(null)
                    .imageIndex(0)
                    .caption(file.getOriginalFilename())
                    .contentType(contentType)
                    .sizeBytes((int) file.getSize())
                    .build();

            sourceImageRepository.save(sourceImg);

            log.info("[WikiImageController] Uploaded standalone wiki image: {} by user {}", imgId, userId);
            return ResponseEntity.ok(Map.of(
                    "id",  imgId.toString(),
                    "url", "/api/wiki/images/raw/" + imgId
            ));

        } catch (Exception e) {
            log.error("[WikiImageController] Failed to upload wiki image: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Upload failed: " + e.getMessage()));
        }
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
