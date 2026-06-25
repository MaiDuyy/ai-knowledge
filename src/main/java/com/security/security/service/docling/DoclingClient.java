package com.security.security.service.docling;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.security.security.service.GeminiMultimodalService;
import com.security.security.service.tika.HtmlToMarkdownConverter;
import com.security.security.service.tika.TikaHtmlExtractor;
import com.security.security.service.tika.TikaHtmlResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.*;

/**
 * DoclingClient — Unified Document Converter Orchestrator.
 *
 * Orchestrates between Method A (Google Gemini) and Method C (Apache Tika).
 * Now delegating all Gemini multimodal and OCR parsing to GeminiMultimodalService.
 */
@Component
@Slf4j
public class DoclingClient {

    private final WebClient webClient;
    private final GeminiMultimodalService geminiMultimodalService;
    private final TikaHtmlExtractor tikaHtmlExtractor;
    private final HtmlToMarkdownConverter htmlToMarkdownConverter;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${spring.ai.google.genai.api-key:}")
    private String geminiApiKey;

    public DoclingClient(
            @Value("${docling.base-url:http://127.0.0.1:5001}") String baseUrl,
            WebClient.Builder builder,
            GeminiMultimodalService geminiMultimodalService,
            TikaHtmlExtractor tikaHtmlExtractor,
            HtmlToMarkdownConverter htmlToMarkdownConverter) {
        this.webClient = builder
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.geminiMultimodalService = geminiMultimodalService;
        this.tikaHtmlExtractor = tikaHtmlExtractor;
        this.htmlToMarkdownConverter = htmlToMarkdownConverter;
        log.info("[DocumentConverter] Refactored DoclingClient initialized with GeminiMultimodalService.");
    }

    // =========================================================================
    //  PUBLIC API
    // =========================================================================

    /**
     * Convert any document resource to Markdown.
     * Orchestrates between Method A (Google Gemini) and Method C (Apache Tika).
     *
     * @param resource Spring Resource (FileSystemResource or UrlResource)
     * @param filename Original filename
     * @return DoclingResult with markdown string or error details
     */
    public DoclingResult convertToMarkdown(Resource resource, String filename) {
        return convertToMarkdown(resource, filename, "gemini", null);
    }

    /**
     * Convert any document resource to Markdown.
     * Orchestrates between Method A (Google Gemini) and Method C (Apache Tika).
     *
     * @param resource Spring Resource (FileSystemResource or UrlResource)
     * @param filename Original filename
     * @param parserMethod The chosen parser: "gemini" or "tika"
     * @return DoclingResult with markdown string or error details
     */
    public DoclingResult convertToMarkdown(Resource resource, String filename, String parserMethod) {
        return convertToMarkdown(resource, filename, parserMethod, null);
    }

    /**
     * Convert any document resource to Markdown.
     * Orchestrates between Method A (Google Gemini) and Method C (Apache Tika).
     *
     * @param resource Spring Resource (FileSystemResource or UrlResource)
     * @param filename Original filename
     * @param parserMethod The chosen parser: "gemini" or "tika"
     * @param documentId Optional document ID for database caching of page-by-page OCR results
     * @return DoclingResult with markdown string or error details
     */
    public DoclingResult convertToMarkdown(Resource resource, String filename, String parserMethod, Long documentId) {
        long start = System.currentTimeMillis();
        log.info("[DocumentConverter] ▶ Converting: '{}' with parser: '{}', docId: {}", filename, parserMethod, documentId);

        String mimeType = getMimeType(filename);
        
        // ── LOCAL DIRECT PARSERS FOR SIMPLE FORMATS (WeKnora design) ──
        if ("text/csv".equals(mimeType) || "application/json".equals(mimeType) || "text/markdown".equals(mimeType) || "text/plain".equals(mimeType)) {
            try {
                byte[] fileBytes;
                try (var in = resource.getInputStream()) {
                    fileBytes = in.readAllBytes();
                }
                String markdown;
                if ("text/csv".equals(mimeType)) {
                    log.info("[DocumentConverter] CSV detected. Converting locally to Markdown table.");
                    markdown = csvToMarkdown(fileBytes);
                } else if ("application/json".equals(mimeType)) {
                    log.info("[DocumentConverter] JSON detected. Converting locally using WeKnora recursive splitter.");
                    markdown = jsonToMarkdown(fileBytes);
                } else {
                    log.info("[DocumentConverter] Text/Markdown detected. Reading directly.");
                    markdown = new String(fileBytes, java.nio.charset.StandardCharsets.UTF_8);
                }
                long elapsed = System.currentTimeMillis() - start;
                log.info("[DocumentConverter] ✓ Local parsing successful for '{}' in {}ms", filename, elapsed);
                return DoclingResult.success(markdown, "LOCAL_PARSER_SUCCESS", elapsed);
            } catch (Exception e) {
                log.warn("[DocumentConverter] Local parsing failed for '{}': {}. Falling back to standard pipeline.", filename, e.getMessage(), e);
            }
        }

        boolean hasGemini = geminiMultimodalService != null && isGeminiConfigured();
        boolean forceTika = "tika".equalsIgnoreCase(parserMethod);

        // ── METHOD A: Google Gemini Multimodal / Hybrid PDF ──────────────────
        if (hasGemini && !forceTika) {
            try {
                String markdown = null;
                if (mimeType.equals("application/pdf")) {
                    log.info("[DocumentConverter] PDF detected. Running Gemini page-by-page hybrid parsing.");
                    byte[] fileBytes;
                    try (var in = resource.getInputStream()) {
                        fileBytes = in.readAllBytes();
                    }
                    markdown = geminiMultimodalService.parsePdf(fileBytes, documentId);
                } else if (isMultimodalSupported(mimeType)) {
                    log.info("[DocumentConverter] Method A1 (Non-PDF): Sending '{}' directly to Gemini", filename);
                    byte[] fileBytes;
                    try (var in = resource.getInputStream()) {
                        fileBytes = in.readAllBytes();
                    }
                    markdown = geminiMultimodalService.parseImage(fileBytes, mimeType, documentId, 1);
                } else {
                    log.info("[DocumentConverter] Method A2: Office document '{}' - converting to HTML via Tika, then beautifying with Gemini", filename);
                    TikaHtmlResult htmlResult = tikaHtmlExtractor.extract(resource);
                    String rawHtml = htmlResult.html();
                    markdown = geminiMultimodalService.parseHtml(rawHtml, documentId, 1);
                }

                if (markdown != null && !markdown.isBlank()) {
                    long elapsed = System.currentTimeMillis() - start;
                    log.info("[DocumentConverter] ✓ Gemini successfully parsed '{}' in {}ms", filename, elapsed);
                    markdown = ensureOriginalImageRef(filename, markdown);
                    return DoclingResult.success(markdown, "GEMINI_SUCCESS", elapsed);
                }
            } catch (Exception e) {
                log.warn("[DocumentConverter] Gemini conversion failed for '{}': {}. Falling back to Method C (Tika local).", filename, e.getMessage(), e);
            }
        } else {
            if (forceTika) {
                log.info("[DocumentConverter] Forced Apache Tika parser method for '{}'", filename);
            } else {
                log.info("[DocumentConverter] Gemini not configured or offline. Using Method C (Tika local) for '{}'", filename);
            }
        }

        // ── METHOD C: Pure Java Tika Local Fallback ──────────────────────────
        try {
            log.info("[DocumentConverter] Method C: Running Apache Tika local fallback extraction for '{}'", filename);
            TikaHtmlResult htmlResult = tikaHtmlExtractor.extract(resource);
            String rawHtml = htmlResult.html();
            if (rawHtml == null || rawHtml.isBlank()) {
                throw new IllegalStateException("Tika returned empty HTML");
            }
            String markdown = htmlToMarkdownConverter.convert(rawHtml);
            long elapsed = System.currentTimeMillis() - start;
            log.info("[DocumentConverter] ✓ Tika successfully converted '{}' in {}ms", filename, elapsed);
            markdown = ensureOriginalImageRef(filename, markdown);
            return DoclingResult.success(markdown, "TIKA_SUCCESS", elapsed);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("[DocumentConverter] Tika fallback conversion failed for '{}': {}", filename, e.getMessage(), e);
            return DoclingResult.failed("Both Gemini and Tika methods failed: " + e.getMessage(), elapsed);
        }
    }

    public boolean isHealthy() {
        return true;
    }

    private boolean isGeminiConfigured() {
        return geminiApiKey != null
                && !geminiApiKey.isBlank()
                && !geminiApiKey.contains("API_KEY"); // avoid placeholder literal ${API_KEY}
    }

    private boolean isMultimodalSupported(String mimeType) {
        return mimeType.equals("application/pdf")
                || mimeType.startsWith("image/")
                || mimeType.equals("text/plain")
                || mimeType.equals("text/csv")
                || mimeType.equals("text/html");
    }

    private String getMimeType(String filename) {
        if (filename == null) return "application/octet-stream";
        String lower = filename.toLowerCase();
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".doc")) return "application/msword";
        if (lower.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (lower.endsWith(".xls")) return "application/vnd.ms-excel";
        if (lower.endsWith(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if (lower.endsWith(".ppt")) return "application/vnd.ms-powerpoint";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".txt")) return "text/plain";
        if (lower.endsWith(".csv")) return "text/csv";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) return "text/markdown";
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html";
        return "application/octet-stream";
    }

    // =========================================================================
    //  RESULT TYPE
    // =========================================================================

    public record DoclingResult(
            boolean success,
            String  markdown,
            String  doclingStatus,
            String  errorMessage,
            long    elapsedMs
    ) {
        public static DoclingResult success(String markdown, String status, long ms) {
            return new DoclingResult(true, markdown, status, null, ms);
        }

        public static DoclingResult failed(String error, long ms) {
            return new DoclingResult(false, null, "FAILURE", error, ms);
        }

        public boolean hasMarkdown() {
            return success && markdown != null && !markdown.isBlank();
        }
    }

    // =========================================================================
    //  LOCAL CONVERSION HELPERS (CSV, JSON & Image References)
    // =========================================================================

    private String csvToMarkdown(byte[] bytes) throws Exception {
        String csvText = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        List<List<String>> records = parseCsv(csvText);
        if (records.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        List<String> header = records.get(0);
        
        // Header
        sb.append("| ");
        sb.append(String.join(" | ", header));
        sb.append(" |\n");
        
        // Separator
        sb.append("|");
        for (int i = 0; i < header.size(); i++) {
            sb.append(" --- |");
        }
        sb.append("\n");
        
        // Data rows
        for (int r = 1; r < records.size(); r++) {
            List<String> row = records.get(r);
            sb.append("| ");
            List<String> cells = new ArrayList<>();
            for (int i = 0; i < header.size(); i++) {
                if (i < row.size()) {
                    cells.add(row.get(i));
                } else {
                    cells.add("");
                }
            }
            sb.append(String.join(" | ", cells));
            sb.append(" |\n");
        }
        return sb.toString();
    }

    private List<List<String>> parseCsv(String csvText) {
        List<List<String>> records = new ArrayList<>();
        List<String> currentRow = new ArrayList<>();
        StringBuilder currentCell = new StringBuilder();
        boolean inQuotes = false;
        int len = csvText.length();
        for (int i = 0; i < len; i++) {
            char c = csvText.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < len && csvText.charAt(i + 1) == '"') {
                        currentCell.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    currentCell.append(c);
                }
            } else {
                if (c == '"') {
                    inQuotes = true;
                } else if (c == ',') {
                    currentRow.add(currentCell.toString().trim());
                    currentCell.setLength(0);
                } else if (c == '\n') {
                    currentRow.add(currentCell.toString().trim());
                    currentCell.setLength(0);
                    records.add(new ArrayList<>(currentRow));
                    currentRow.clear();
                } else if (c == '\r') {
                    if (i + 1 < len && csvText.charAt(i + 1) == '\n') {
                        i++;
                    }
                    currentRow.add(currentCell.toString().trim());
                    currentCell.setLength(0);
                    records.add(new ArrayList<>(currentRow));
                    currentRow.clear();
                } else {
                    currentCell.append(c);
                }
            }
        }
        if (currentCell.length() > 0 || !currentRow.isEmpty()) {
            currentRow.add(currentCell.toString().trim());
            records.add(currentRow);
        }
        return records;
    }

    private String jsonToMarkdown(byte[] bytes) throws Exception {
        byte[] cleanedBytes = trimBOM(bytes);
        if (cleanedBytes.length == 0) {
            throw new IllegalArgumentException("Empty JSON content");
        }
        
        Object parsed = objectMapper.readValue(cleanedBytes, Object.class);
        Object normalized = listToDictPreprocess(parsed);
        
        int defaultJSONChunkSize = 1536;
        int minJSONChunkSize = defaultJSONChunkSize - 200;
        
        byte[] normalizedBytes = objectMapper.writeValueAsBytes(normalized);
        if (normalizedBytes.length <= defaultJSONChunkSize) {
            String formatted = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(normalized);
            return wrapCodeBlock(formatted);
        }
        
        List<Map<String, Object>> chunks = new ArrayList<>();
        chunks.add(new LinkedHashMap<>());
        
        recursiveJSONSplit(normalized, new ArrayList<>(), chunks, defaultJSONChunkSize, minJSONChunkSize);
        
        List<String> blocks = new ArrayList<>();
        for (Map<String, Object> chunk : chunks) {
            if (chunk.isEmpty()) {
                continue;
            }
            String formatted = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(chunk);
            blocks.add(wrapCodeBlock(formatted));
          }
        
        if (blocks.isEmpty()) {
            String formatted = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(normalized);
            return wrapCodeBlock(formatted);
        }
        
        return String.join("\n\n", blocks);
    }

    private Object listToDictPreprocess(Object data) {
        if (data instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) data;
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()), listToDictPreprocess(entry.getValue()));
            }
            return result;
        } else if (data instanceof List) {
            List<?> list = (List<?>) data;
            Map<String, Object> result = new LinkedHashMap<>();
            for (int i = 0; i < list.size(); i++) {
                result.put(String.valueOf(i), listToDictPreprocess(list.get(i)));
            }
            return result;
        } else {
            return data;
        }
    }

    @SuppressWarnings("unchecked")
    private void recursiveJSONSplit(
            Object data,
            List<String> currentPath,
            List<Map<String, Object>> chunks,
            int defaultChunkSize,
            int minChunkSize) {
        
        if (!(data instanceof Map)) {
            if (!currentPath.isEmpty() && !chunks.isEmpty()) {
                setNestedMap(chunks.get(chunks.size() - 1), currentPath, data);
            }
            return;
        }
        
        Map<String, Object> map = (Map<String, Object>) data;
        List<String> keys = sortedKeys(map.keySet());
        
        for (String key : keys) {
            Object value = map.get(key);
            List<String> newPath = new ArrayList<>(currentPath);
            newPath.add(key);
            
            Map<String, Object> lastChunk = chunks.get(chunks.size() - 1);
            int chunkSize = jsonSize(lastChunk);
            
            Map<String, Object> singleItemMap = new LinkedHashMap<>();
            singleItemMap.put(key, value);
            int itemSize = jsonSize(singleItemMap);
            int remaining = defaultChunkSize - chunkSize;
            
            if (itemSize <= remaining) {
                setNestedMap(lastChunk, newPath, value);
            } else {
                if (chunkSize >= minChunkSize) {
                    chunks.add(new LinkedHashMap<>());
                    lastChunk = chunks.get(chunks.size() - 1);
                }
                
                Object normalizedVal = listToDictPreprocess(value);
                if (normalizedVal instanceof Map && canSplitMap((Map<String, Object>) normalizedVal)) {
                    recursiveJSONSplit(normalizedVal, newPath, chunks, defaultChunkSize, minChunkSize);
                } else {
                    setNestedMap(lastChunk, newPath, value);
                }
            }
        }
    }

    private void setNestedMap(Map<String, Object> map, List<String> path, Object value) {
        if (path.isEmpty()) return;
        Map<String, Object> current = map;
        for (int i = 0; i < path.size() - 1; i++) {
            String key = path.get(i);
            Object next = current.get(key);
            if (!(next instanceof Map)) {
                next = new LinkedHashMap<String, Object>();
                current.put(key, next);
            }
            current = (Map<String, Object>) next;
        }
        current.put(path.get(path.size() - 1), value);
    }

    private boolean canSplitMap(Map<String, Object> map) {
        if (map.size() > 1) {
            return true;
        }
        if (map.size() == 1) {
            for (Object val : map.values()) {
                if (val instanceof Map && ((Map<?, ?>) val).size() > 1) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<String> sortedKeys(Set<String> keySet) {
        List<String> keys = new ArrayList<>(keySet);
        boolean allNumeric = true;
        for (String k : keys) {
            try {
                Integer.parseInt(k);
            } catch (NumberFormatException e) {
                allNumeric = false;
                break;
            }
        }
        if (allNumeric) {
            keys.sort((a, b) -> Integer.compare(Integer.parseInt(a), Integer.parseInt(b)));
        } else {
            Collections.sort(keys);
        }
        return keys;
    }

    private int jsonSize(Object obj) {
        try {
            return objectMapper.writeValueAsBytes(obj).length;
        } catch (Exception e) {
            return 0;
        }
    }

    private String wrapCodeBlock(String content) {
        return "```json\n" + content + "\n```";
    }

    private byte[] trimBOM(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            byte[] dest = new byte[bytes.length - 3];
            System.arraycopy(bytes, 3, dest, 0, dest.length);
            return dest;
        }
        return bytes;
    }

    private String ensureOriginalImageRef(String filename, String markdown) {
        if (filename == null) return markdown;
        String lower = filename.toLowerCase();
        boolean isImg = lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".gif") || lower.endsWith(".webp");
        if (!isImg) {
            return markdown;
        }
        if (markdown == null) {
            markdown = "";
        }
        if (markdown.contains("![") && markdown.contains("]")) {
            return markdown;
        }
        String imgLine = "![" + filename + "](image://original)";
        if (markdown.trim().isEmpty()) {
            return imgLine;
        } else {
            return imgLine + "\n\n" + markdown;
        }
    }
}