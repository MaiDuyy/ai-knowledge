package com.security.security.service;

import com.security.security.entity.Document;
import com.security.security.entity.SourceImage;
import com.security.security.repository.SourceImageRepository;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
@Slf4j
public class ImageProcessingService {

    private final SourceImageRepository sourceImageRepository;

    @Value("${app.upload.dir:uploads}")
    private String uploadDir;

    private static final int MIN_IMAGE_BYTES = 2048;

    public ImageProcessingService(SourceImageRepository sourceImageRepository) {
        this.sourceImageRepository = sourceImageRepository;
    }

    @Data
    @AllArgsConstructor
    public static class ExtractedImage {
        private byte[] bytes;
        private String contentType;
        private String extension;
        private Integer pageNumber;
        private int imageIndex;
        private int width;
        private int height;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    private static class ProcessedImageResult {
        private int index;
        private boolean skipped;
        private SourceImage sourceImage;
    }

    public List<ExtractedImage> extractImages(byte[] fileData, String fileName) {
        String lower = fileName.toLowerCase();
        try {
            if (lower.endsWith(".pdf")) {
                return extractImagesFromPdf(fileData);
            } else if (lower.endsWith(".docx")) {
                return extractImagesFromDocx(fileData);
            } else if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".bmp") || lower.endsWith(".tiff")) {
                log.info("Extracting single standalone image: {}", fileName);
                String ext = getFileExtension(fileName);
                String contentType = mimeFromExt(ext);
                int width = 0;
                int height = 0;
                try (ByteArrayInputStream bais = new ByteArrayInputStream(fileData)) {
                    java.awt.image.BufferedImage bi = ImageIO.read(bais);
                    if (bi != null) {
                        width = bi.getWidth();
                        height = bi.getHeight();
                    }
                } catch (Exception e) {
                    log.warn("Failed to read standalone image dimensions: {}", e.getMessage());
                }
                List<ExtractedImage> list = new ArrayList<>();
                list.add(new ExtractedImage(
                        fileData,
                        contentType,
                        ext,
                        1, // Page number 1
                        0, // Image index 0
                        width,
                        height
                ));
                return list;
            } else {
                log.debug("No image extraction supported for file type: {}", fileName);
            }
        } catch (Exception e) {
            log.error("Failed to extract images from {}: {}", fileName, e.getMessage(), e);
        }
        return new ArrayList<>();
    }

    /**
     * Extract images from PDF using PDFBox.
     */
    public List<ExtractedImage> extractImagesFromPdf(byte[] fileData) throws Exception {
        List<ExtractedImage> images = new ArrayList<>();
        int[] imageIndex = new int[]{0};

        try (PDDocument document = PDDocument.load(fileData)) {
            for (int pageNum = 0; pageNum < document.getNumberOfPages(); pageNum++) {
                PDPage page = document.getPage(pageNum);
                PDResources resources = page.getResources();
                if (resources == null) continue;

                // 1. Get ordered names from content stream (visual/drawing order)
                List<COSName> orderedNames = new ArrayList<>();
                try {
                    PDFStreamParser parser = new PDFStreamParser(page);
                    parser.parse();
                    List<Object> tokens = parser.getTokens();
                    for (int i = 0; i < tokens.size(); i++) {
                        Object token = tokens.get(i);
                        if (token instanceof Operator op) {
                            if ("Do".equals(op.getName()) && i > 0) {
                                Object prev = tokens.get(i - 1);
                                if (prev instanceof COSName cosName) {
                                    if (resources.isImageXObject(cosName)) {
                                        orderedNames.add(cosName);
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse content stream for page {}: {}", pageNum + 1, e.getMessage());
                }

                // 2. Build set of remaining XObject names in resources
                Set<COSName> remainingNames = new LinkedHashSet<>();
                for (COSName name : resources.getXObjectNames()) {
                    if (resources.isImageXObject(name)) {
                        remainingNames.add(name);
                    }
                }

                // 3. Extract in ordered list first
                Set<String> processedNames = new HashSet<>();
                for (COSName name : orderedNames) {
                    if (processedNames.contains(name.getName())) continue;
                    processedNames.add(name.getName());
                    remainingNames.remove(name);
                    extractSingleImage(resources, name, images, pageNum + 1, imageIndex);
                }

                // 4. Extract remaining (fallback for nested forms/edge cases)
                for (COSName name : remainingNames) {
                    if (processedNames.contains(name.getName())) continue;
                    processedNames.add(name.getName());
                    extractSingleImage(resources, name, images, pageNum + 1, imageIndex);
                }
            }
        }

        log.info("Extracted {} images from PDF", images.size());
        return images;
    }

    private void extractSingleImage(
            PDResources resources,
            COSName name,
            List<ExtractedImage> images,
            int pageNumber,
            int[] imageIndex
    ) {
        try {
            if (resources.isImageXObject(name)) {
                PDImageXObject image = (PDImageXObject) resources.getXObject(name);
                if (image == null || image.getImage() == null) return;

                String format = "png";
                String ext = image.getSuffix();
                if (ext != null && !ext.isEmpty()) {
                    String lowerExt = ext.toLowerCase();
                    if (lowerExt.equals("jpg") || lowerExt.equals("jpeg") || lowerExt.equals("png") || lowerExt.equals("gif") || lowerExt.equals("webp")) {
                        format = lowerExt;
                    } else {
                        format = "png";
                    }
                }

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                boolean success = ImageIO.write(image.getImage(), format, baos);
                if (!success) {
                    baos.reset();
                    success = ImageIO.write(image.getImage(), "png", baos);
                    format = "png";
                }

                if (!success) {
                    return;
                }

                byte[] imgBytes = baos.toByteArray();
                if (imgBytes.length < MIN_IMAGE_BYTES) {
                    return;
                }

                String contentType = mimeFromExt(format);

                images.add(new ExtractedImage(
                        imgBytes,
                        contentType,
                        format,
                        pageNumber,
                        imageIndex[0]++,
                        image.getWidth(),
                        image.getHeight()
                ));
            } else {
                PDXObject xobject = resources.getXObject(name);
                if (xobject instanceof PDFormXObject) {
                    PDResources formResources = ((PDFormXObject) xobject).getResources();
                    if (formResources != null) {
                        for (COSName formName : formResources.getXObjectNames()) {
                            extractSingleImage(formResources, formName, images, pageNumber, imageIndex);
                        }
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("Failed to extract single image/form reference on page {}: {}", pageNumber, ex.getMessage());
        }
    }

    /**
     * Extract images from DOCX using ZipInputStream.
     */
    public List<ExtractedImage> extractImagesFromDocx(byte[] fileData) throws Exception {
        List<ExtractedImage> images = new ArrayList<>();
        int imageIndex = 0;

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(fileData))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.startsWith("word/media/")) {
                    try {
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        byte[] buffer = new byte[4096];
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            baos.write(buffer, 0, len);
                        }
                        byte[] imgBytes = baos.toByteArray();
                        if (imgBytes.length < MIN_IMAGE_BYTES) {
                            continue;
                        }

                        String ext = getFileExtension(name);
                        String contentType = mimeFromExt(ext);

                        int width = 0;
                        int height = 0;
                        try (ByteArrayInputStream bais = new ByteArrayInputStream(imgBytes)) {
                            java.awt.image.BufferedImage bi = ImageIO.read(bais);
                            if (bi != null) {
                                width = bi.getWidth();
                                height = bi.getHeight();
                            }
                        } catch (Exception e) {
                            log.warn("Failed to read image dimensions for DOCX media: {}", e.getMessage());
                        }

                        images.add(new ExtractedImage(
                                imgBytes,
                                contentType,
                                ext,
                                null,
                                imageIndex++,
                                width,
                                height
                        ));
                    } catch (Exception ex) {
                        log.warn("Failed to extract DOCX media entry {}: {}", name, ex.getMessage());
                    }
                }
            }
        }

        log.info("Extracted {} images from DOCX", images.size());
        return images;
    }

    /**
     * Process images for a preview session (synchronous, fast, placeholder captions).
     */
    @Transactional
    public String processPreviewImages(Document document, String markdown) {
        log.info("[ImageProcessingService] Processing preview images for docId={}", document.getId());
        try {
            Path filePath = Paths.get(document.getFilePath());
            if (!Files.exists(filePath)) {
                return markdown;
            }
            byte[] fileBytes = Files.readAllBytes(filePath);
            List<ExtractedImage> extractedImages = extractImages(fileBytes, document.getFileName());

            if (extractedImages == null || extractedImages.isEmpty()) {
                return markdown;
            }

            Path imagesDir = Paths.get(uploadDir).resolve("images");
            if (!Files.exists(imagesDir)) {
                Files.createDirectories(imagesDir);
            }

            // Purge old source images first
            sourceImageRepository.deleteBySourceId(document.getId());

            List<ProcessedImageResult> results = new ArrayList<>();
            List<SourceImage> imagesToSave = new ArrayList<>();

            for (int i = 0; i < extractedImages.size(); i++) {
                ExtractedImage extImg = extractedImages.get(i);
                UUID imgId = UUID.randomUUID();
                String imgFilename = imgId.toString() + "." + extImg.getExtension();
                Path targetPath = imagesDir.resolve(imgFilename);

                // Write to disk
                Files.write(targetPath, extImg.getBytes());

                SourceImage sourceImg = SourceImage.builder()
                        .id(imgId)
                        .source(document)
                        .minioKey("images/" + imgFilename)
                        .pageNumber(extImg.getPageNumber())
                        .imageIndex(extImg.getImageIndex())
                        .caption("Extracted Image") // Placeholder caption for preview
                        .contentType(extImg.getContentType())
                        .sizeBytes(extImg.getBytes().length)
                        .build();

                imagesToSave.add(sourceImg);

                ProcessedImageResult result = new ProcessedImageResult();
                result.setIndex(i);
                result.setSkipped(false);
                result.setSourceImage(sourceImg);
                results.add(result);
            }

            if (!imagesToSave.isEmpty()) {
                sourceImageRepository.saveAll(imagesToSave);
            }

            return replaceMarkdownImages(markdown, results);
        } catch (Exception e) {
            log.error("[ImageProcessingService] Failed to process preview images for docId={}: {}", document.getId(), e.getMessage(), e);
        }
        return markdown;
    }

    /**
     * Process images for ingestion — extracts, filters header/footer images, and indexes into markdown.
     * No AI caption generation; uses positional labels and fingerprint-based header/footer detection.
     */
    @Transactional
    public String processIngestImages(Document document, String markdown) {
        log.info("[ImageProcessingService] Processing ingest images for docId={}", document.getId());
        try {
            Path filePath = Paths.get(document.getFilePath());
            if (!Files.exists(filePath)) {
                return markdown;
            }
            byte[] fileBytes = Files.readAllBytes(filePath);
            List<ExtractedImage> extractedImages = extractImages(fileBytes, document.getFileName());

            if (extractedImages == null || extractedImages.isEmpty()) {
                // Still strip header/footer text from markdown even without images
                return stripRepeatedHeaderFooterText(markdown);
            }

            Path imagesDir = Paths.get(uploadDir).resolve("images");
            if (!Files.exists(imagesDir)) {
                Files.createDirectories(imagesDir);
            }

            // ── Phase 1: Fingerprint images to detect repeated header/footer images ──
            Set<String> headerFooterFingerprints = detectRepeatedImageFingerprints(extractedImages);
            log.info("[ImageProcessingService] Detected {} repeated header/footer image fingerprints", headerFooterFingerprints.size());

            ProcessedImageResult[] resultsArray = new ProcessedImageResult[extractedImages.size()];
            for (int idx = 0; idx < extractedImages.size(); idx++) {
                ProcessedImageResult def = new ProcessedImageResult();
                def.setIndex(idx);
                def.setSkipped(true);
                resultsArray[idx] = def;
            }

            // ── Phase 2: Process each image with header/footer filtering ──
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            ExecutorService imageExecutor = Executors.newVirtualThreadPerTaskExecutor();

            for (int idx = 0; idx < extractedImages.size(); idx++) {
                final int index = idx;
                final ExtractedImage extImg = extractedImages.get(idx);

                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try {
                        int w = extImg.getWidth();
                        int h = extImg.getHeight();

                        // Filter 1: Dimension-based — icons, logos, banners, lines
                        if (w > 0 && h > 0) {
                            if (w <= 150 && h <= 150) {
                                log.info("[ImageProcessingService] Skipping icon/logo index={} (w={}, h={})", index, w, h);
                                return;
                            }
                            double aspect = (double) w / h;
                            if (aspect > 4.0 || aspect < 0.25) {
                                log.info("[ImageProcessingService] Skipping banner/line index={} (w={}, h={}, aspect={:.1f})", index, w, h, aspect);
                                return;
                            }
                        }

                        // Filter 2: Fingerprint-based — repeated across ≥50% of pages = header/footer
                        String fingerprint = computeImageFingerprint(extImg);
                        if (headerFooterFingerprints.contains(fingerprint)) {
                            log.info("[ImageProcessingService] Skipping repeated header/footer image index={} (fingerprint={})", index, fingerprint.substring(0, 8));
                            return;
                        }

                        // Filter 3: Position-based — first or last image on a page with small height
                        if (isPositionalHeaderFooter(extImg, extractedImages)) {
                            log.info("[ImageProcessingService] Skipping positional header/footer image index={} (page={}, imgIdx={})", index, extImg.getPageNumber(), extImg.getImageIndex());
                            return;
                        }

                        UUID imgId = UUID.randomUUID();
                        String imgFilename = imgId.toString() + "." + extImg.getExtension();
                        Path targetPath = imagesDir.resolve(imgFilename);

                        int pageNum = extImg.getPageNumber() > 0 ? extImg.getPageNumber() : 1;
                        String caption = String.format("Image %d (page %d)", index + 1, pageNum);

                        Files.write(targetPath, extImg.getBytes());

                        SourceImage sourceImg = SourceImage.builder()
                                .id(imgId)
                                .source(document)
                                .minioKey("images/" + imgFilename)
                                .pageNumber(extImg.getPageNumber())
                                .imageIndex(extImg.getImageIndex())
                                .caption(caption)
                                .contentType(extImg.getContentType())
                                .sizeBytes(extImg.getBytes().length)
                                .build();

                        ProcessedImageResult result = new ProcessedImageResult();
                        result.setIndex(index);
                        result.setSkipped(false);
                        result.setSourceImage(sourceImg);
                        resultsArray[index] = result;
                    } catch (Exception ex) {
                        log.warn("[ImageProcessingService] Failed to process image idx={} for docId={}: {}", index, document.getId(), ex.getMessage());
                    }
                }, imageExecutor);
                futures.add(future);
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            imageExecutor.shutdown();

            try {
                sourceImageRepository.deleteBySourceId(document.getId());
            } catch (Exception e) {
                log.warn("[ImageProcessingService] Failed to delete old source images for docId={}: {}", document.getId(), e.getMessage());
            }

            List<ProcessedImageResult> sortedResults = Arrays.asList(resultsArray);

            List<SourceImage> imagesToSave = sortedResults.stream()
                    .filter(r -> !r.isSkipped() && r.getSourceImage() != null)
                    .map(ProcessedImageResult::getSourceImage)
                    .toList();
            if (!imagesToSave.isEmpty()) {
                sourceImageRepository.saveAll(imagesToSave);
            }

            // ── Phase 3: Replace image tags in markdown, then strip repeated header/footer text ──
            String replaced = replaceMarkdownImages(markdown, sortedResults);
            return stripRepeatedHeaderFooterText(replaced);
        } catch (Exception e) {
            log.error("[ImageProcessingService] Failed to process ingest images for docId={}: {}", document.getId(), e.getMessage(), e);
        }
        return markdown;
    }

    /**
     * Compute a lightweight fingerprint for an image (first 64 bytes hash + dimensions).
     * Identical fingerprints across pages indicate the same header/footer image.
     */
    private String computeImageFingerprint(ExtractedImage img) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] sample = img.getBytes();
            int sampleLen = Math.min(sample.length, 256);
            md.update(sample, 0, sampleLen);
            md.update((byte) (img.getWidth() >> 8));
            md.update((byte) img.getWidth());
            md.update((byte) (img.getHeight() >> 8));
            md.update((byte) img.getHeight());
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "unknown-" + img.getImageIndex();
        }
    }

    /**
     * Detect images that appear on ≥50% of pages — these are almost certainly headers/footers.
     * Groups by fingerprint, counts distinct pages, flags those appearing on majority of pages.
     */
    private Set<String> detectRepeatedImageFingerprints(List<ExtractedImage> images) {
        if (images == null || images.size() < 3) return Set.of();

        int totalPages = images.stream()
                .mapToInt(i -> i.getPageNumber() != null ? i.getPageNumber() : 1)
                .max().orElse(1);

        if (totalPages < 2) return Set.of();

        // fingerprint → set of page numbers
        Map<String, Set<Integer>> fpToPages = new HashMap<>();
        for (ExtractedImage img : images) {
            String fp = computeImageFingerprint(img);
            int page = img.getPageNumber() != null ? img.getPageNumber() : 1;
            fpToPages.computeIfAbsent(fp, k -> new HashSet<>()).add(page);
        }

        double threshold = totalPages * 0.5;
        Set<String> repeated = new HashSet<>();
        for (Map.Entry<String, Set<Integer>> entry : fpToPages.entrySet()) {
            if (entry.getValue().size() >= threshold) {
                repeated.add(entry.getKey());
            }
        }
        return repeated;
    }

    /**
     * Position-based header/footer detection:
     * If an image is the first or last on its page AND has a small height (≤ 120px),
     * it's likely a header or footer element (letterhead, page number bar, etc).
     */
    private boolean isPositionalHeaderFooter(ExtractedImage img, List<ExtractedImage> allImages) {
        int h = img.getHeight();
        if (h <= 0 || h > 120) return false;

        int page = img.getPageNumber() != null ? img.getPageNumber() : 1;
        List<ExtractedImage> pageImages = allImages.stream()
                .filter(i -> (i.getPageNumber() != null ? i.getPageNumber() : 1) == page)
                .toList();

        if (pageImages.size() <= 1) return false;

        int imgIdx = img.getImageIndex();
        int minIdx = pageImages.stream().mapToInt(ExtractedImage::getImageIndex).min().orElse(0);
        int maxIdx = pageImages.stream().mapToInt(ExtractedImage::getImageIndex).max().orElse(0);

        return imgIdx == minIdx || imgIdx == maxIdx;
    }

    /**
     * Strip repeated header/footer TEXT from markdown pages.
     * Detects lines that appear identically at the start or end of ≥50% of page blocks.
     */
    private String stripRepeatedHeaderFooterText(String markdown) {
        if (markdown == null || markdown.isBlank()) return markdown;

        java.util.regex.Pattern pagePattern = java.util.regex.Pattern.compile("<!-- PAGE_BREAK: \\d+ -->");
        String[] pages = pagePattern.split(markdown);

        if (pages.length < 3) return markdown;

        // Collect first 3 lines and last 3 lines of each page
        Map<String, Integer> headerLineCounts = new HashMap<>();
        Map<String, Integer> footerLineCounts = new HashMap<>();

        for (String page : pages) {
            String trimmed = page.strip();
            if (trimmed.isEmpty()) continue;
            String[] lines = trimmed.split("\\n");

            for (int i = 0; i < Math.min(3, lines.length); i++) {
                String line = lines[i].strip();
                if (!line.isEmpty() && line.length() < 200 && !line.startsWith("#")) {
                    headerLineCounts.merge(line, 1, Integer::sum);
                }
            }

            for (int i = Math.max(0, lines.length - 3); i < lines.length; i++) {
                String line = lines[i].strip();
                if (!line.isEmpty() && line.length() < 200 && !line.startsWith("#")) {
                    footerLineCounts.merge(line, 1, Integer::sum);
                }
            }
        }

        double threshold = pages.length * 0.5;
        Set<String> repeatedLines = new HashSet<>();

        headerLineCounts.forEach((line, count) -> {
            if (count >= threshold) repeatedLines.add(line);
        });
        footerLineCounts.forEach((line, count) -> {
            if (count >= threshold) repeatedLines.add(line);
        });

        if (repeatedLines.isEmpty()) return markdown;

        log.info("[ImageProcessingService] Stripping {} repeated header/footer text lines", repeatedLines.size());

        StringBuilder result = new StringBuilder();
        for (String line : markdown.split("\\n")) {
            if (!repeatedLines.contains(line.strip())) {
                result.append(line).append("\n");
            }
        }
        return result.toString().strip();
    }

    /**
     * Replace image placeholders in the markdown text using page-aware matching (semantic caption alignment + sequential fallback).
     */
    private String replaceMarkdownImages(String markdown, List<ProcessedImageResult> results) {
        if (markdown == null) return "";
        if (results == null || results.isEmpty()) return markdown;

        // Group results by pageNumber (1-indexed)
        Map<Integer, List<ProcessedImageResult>> resultsByPage = new HashMap<>();
        for (ProcessedImageResult r : results) {
            if (r.getSourceImage() == null) continue;
            Integer pageNum = r.getSourceImage().getPageNumber();
            if (pageNum == null) pageNum = 1;
            resultsByPage.computeIfAbsent(pageNum, k -> new ArrayList<>()).add(r);
        }

        // Split markdown by PAGE_BREAK
        java.util.regex.Pattern pagePattern = java.util.regex.Pattern.compile("<!-- PAGE_BREAK: (\\d+) -->");
        java.util.regex.Matcher pageMatcher = pagePattern.matcher(markdown);

        List<String> blocks = new ArrayList<>();
        List<String> separators = new ArrayList<>();
        int lastIdx = 0;
        while (pageMatcher.find()) {
            blocks.add(markdown.substring(lastIdx, pageMatcher.start()));
            separators.add(pageMatcher.group(0));
            lastIdx = pageMatcher.end();
        }
        blocks.add(markdown.substring(lastIdx));

        // If no PAGE_BREAK markers are found (e.g. parsed in one shot), match all images globally
        if (blocks.size() <= 1) {
            log.info("[ImageProcessingService] No PAGE_BREAK markers found. Matching all {} images globally.", results.size());
            return replaceBlockImages(markdown, results);
        }

        StringBuilder finalMarkdown = new StringBuilder();
        for (int i = 0; i < blocks.size(); i++) {
            if (i > 0) {
                finalMarkdown.append(separators.get(i - 1));
            }
            int pageNum = i + 1;
            String blockContent = blocks.get(i);
            List<ProcessedImageResult> pageResults = resultsByPage.getOrDefault(pageNum, Collections.emptyList());

            // Replace image tags in this block
            String replacedBlock = replaceBlockImages(blockContent, pageResults);
            finalMarkdown.append(replacedBlock);
        }

        return finalMarkdown.toString();
    }

    private static class MarkdownImageTag {
        String fullTag;
        String altText;
        String url;
        int start;
        int end;

        public MarkdownImageTag(String fullTag, String altText, String url, int start, int end) {
            this.fullTag = fullTag;
            this.altText = altText;
            this.url = url;
            this.start = start;
            this.end = end;
        }
    }

    private String replaceBlockImages(String blockContent, List<ProcessedImageResult> pageResults) {
        if (blockContent == null || blockContent.isEmpty()) return "";

        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("!\\[(.*?)\\]\\((.*?)\\)");
        java.util.regex.Matcher matcher = pattern.matcher(blockContent);

        List<MarkdownImageTag> tags = new ArrayList<>();
        while (matcher.find()) {
            tags.add(new MarkdownImageTag(
                matcher.group(0),
                matcher.group(1),
                matcher.group(2),
                matcher.start(),
                matcher.end()
            ));
        }

        if (tags.isEmpty()) {
            return blockContent;
        }

        boolean[] tagMatched = new boolean[tags.size()];
        boolean[] resMatched = new boolean[pageResults.size()];
        Map<Integer, ProcessedImageResult> tagToResult = new HashMap<>();

        // Stage 0: Direct index mapping for structured image://N format (Gemini OCR output)
        // Sort non-skipped results by imageIndex to get page-local sequential order
        List<ProcessedImageResult> indexedResults = pageResults.stream()
                .filter(r -> !r.isSkipped() && r.getSourceImage() != null)
                .sorted(java.util.Comparator.comparing(r -> r.getSourceImage().getImageIndex()))
                .collect(java.util.stream.Collectors.toList());

        java.util.regex.Pattern indexedUrlPattern = java.util.regex.Pattern.compile("^image://(\\d+)$");
        for (int tIdx = 0; tIdx < tags.size(); tIdx++) {
            MarkdownImageTag tag = tags.get(tIdx);
            java.util.regex.Matcher im = indexedUrlPattern.matcher(tag.url != null ? tag.url.trim() : "");
            if (im.matches()) {
                int localIdx = Integer.parseInt(im.group(1));
                if (localIdx < indexedResults.size()) {
                    ProcessedImageResult target = indexedResults.get(localIdx);
                    tagToResult.put(tIdx, target);
                    tagMatched[tIdx] = true;
                    int srcIdx = pageResults.indexOf(target);
                    if (srcIdx >= 0) resMatched[srcIdx] = true;
                }
            }
        }

        // Stage 1: Caption/AltText Match (for old-format tags without image://N)
        for (int tIdx = 0; tIdx < tags.size(); tIdx++) {
            MarkdownImageTag tag = tags.get(tIdx);
            String alt = tag.altText;
            if (alt == null || alt.isBlank()) continue;

            double bestScore = 0.0;
            int bestResIdx = -1;

            for (int rIdx = 0; rIdx < pageResults.size(); rIdx++) {
                if (resMatched[rIdx]) continue;
                ProcessedImageResult r = pageResults.get(rIdx);
                if (r.isSkipped() || r.getSourceImage() == null) continue;

                String caption = r.getSourceImage().getCaption();
                double score = getSimilarityScore(alt, caption);
                if (score > bestScore) {
                    bestScore = score;
                    bestResIdx = rIdx;
                }
            }

            if (bestResIdx != -1) {
                tagToResult.put(tIdx, pageResults.get(bestResIdx));
                tagMatched[tIdx] = true;
                resMatched[bestResIdx] = true;
            }
        }

        // Stage 2: Sequential Match (Only non-skipped unmatched results)
        int rIdx = 0;
        for (int tIdx = 0; tIdx < tags.size(); tIdx++) {
            if (tagMatched[tIdx]) continue;

            while (rIdx < pageResults.size()) {
                if (!resMatched[rIdx]) {
                    ProcessedImageResult r = pageResults.get(rIdx);
                    if (!r.isSkipped() && r.getSourceImage() != null) {
                        tagToResult.put(tIdx, r);
                        tagMatched[tIdx] = true;
                        resMatched[rIdx] = true;
                        rIdx++;
                        break;
                    }
                }
                rIdx++;
            }
        }

        // Assemble replaced block content manually by index (safer than matcher replacement)
        StringBuilder sb = new StringBuilder();
        int lastPos = 0;
        for (int tIdx = 0; tIdx < tags.size(); tIdx++) {
            MarkdownImageTag tag = tags.get(tIdx);
            sb.append(blockContent, lastPos, tag.start);

            ProcessedImageResult r = tagToResult.get(tIdx);
            if (r != null && !r.isSkipped() && r.getSourceImage() != null) {
                SourceImage img = r.getSourceImage();
                String alt = sanitizeCaptionForAlt(img.getCaption());
                sb.append(String.format("![%s](image://%s)", alt, img.getId().toString()));
            } else {
                sb.append(""); // remove tag if skipped/unmatched
            }
            lastPos = tag.end;
        }
        sb.append(blockContent.substring(lastPos));
        return sb.toString();
    }

    private double getSimilarityScore(String s1, String s2) {
        if (s1 == null || s2 == null) return 0.0;
        String a = s1.toLowerCase().trim();
        String c = s2.toLowerCase().trim();
        if (a.isEmpty() || c.isEmpty()) return 0.0;

        // Ignore generic labels
        if (a.equals("extracted image") || a.equals("ảnh trích xuất") || a.equals("image") || a.equals("ảnh")) return 0.0;
        if (c.equals("extracted image") || c.equals("ảnh trích xuất") || c.equals("image") || c.equals("ảnh")) return 0.0;

        double jaccard = calculateWordSimilarity(a, c);

        // Check if one contains the other (only if they have at least 2 words to prevent single word matching errors)
        String[] aWords = a.split("\\s+");
        String[] cWords = c.split("\\s+");
        boolean containsMatch = false;
        if (aWords.length >= 2 && cWords.length >= 2) {
            if (a.contains(c) || c.contains(a)) {
                containsMatch = true;
            }
        }

        if (jaccard >= 0.35) {
            return jaccard;
        } else if (containsMatch) {
            return 0.36; // Return a score slightly above the 0.35 threshold to consider it a valid match
        }

        return 0.0;
    }

    private double calculateWordSimilarity(String s1, String s2) {
        if (s1 == null || s2 == null) return 0.0;

        // Basic normalization for Vietnamese/English text
        String clean1 = s1.toLowerCase().replaceAll("[^a-zA-Z0-9\\sàáạảãâầấậẩẫăằắặẳẵèéẹẻẽêềếệểễìíịỉĩòóọỏõôồốộổỗơờớợởỡùúụủũưừứựửữỳýỵỷỹđ]", " ").trim();
        String clean2 = s2.toLowerCase().replaceAll("[^a-zA-Z0-9\\sàáạảãâầấậẩẫăằắặẳẵèéẹẻẽêềếệểễìíịỉĩòóọỏõôồốộổỗơờớợởỡùúụủũưừứựửữỳýỵỷỹđ]", " ").trim();

        Set<String> words1 = new HashSet<>(Arrays.asList(clean1.split("\\s+")));
        Set<String> words2 = new HashSet<>(Arrays.asList(clean2.split("\\s+")));

        words1.remove("");
        words2.remove("");

        if (words1.isEmpty() || words2.isEmpty()) return 0.0;

        int intersection = 0;
        for (String w : words1) {
            if (words2.contains(w)) {
                intersection++;
            }
        }

        int union = words1.size() + words2.size() - intersection;
        return (double) intersection / union;
    }

    private String sanitizeCaptionForAlt(String caption) {
        if (caption == null || caption.isBlank()) return "Extracted Image";
        String cleaned = caption.replace("\n", " ").replace("\r", " ");
        cleaned = cleaned.replace("\"", "'").replace("[", "(").replace("]", ")");
        return cleaned.trim();
    }

    private String mimeFromExt(String ext) {
        if (ext == null) return "image/png";
        return switch (ext.toLowerCase()) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            case "tiff", "tif" -> "image/tiff";
            case "webp" -> "image/webp";
            case "svg" -> "image/svg+xml";
            default -> "image/png";
        };
    }

    private String getFileExtension(String path) {
        if (path == null || path.lastIndexOf('.') == -1) return "png";
        return path.substring(path.lastIndexOf('.') + 1);
    }
}
