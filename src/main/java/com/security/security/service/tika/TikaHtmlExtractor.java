package com.security.security.service.tika;

import lombok.extern.slf4j.Slf4j;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.ToHTMLContentHandler;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Component;
import org.xml.sax.ContentHandler;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * TikaHtmlExtractor — Docling "Backend" equivalent for Java.
 *
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │  Docling Architecture Mapping                                           │
 * ├────────────────────────┬────────────────────────────────────────────────┤
 * │  Docling (Python)      │  This class (Java)                             │
 * ├────────────────────────┼────────────────────────────────────────────────┤
 * │  AbstractBackend       │  TikaHtmlExtractor                             │
 * │  DoclingParseBackend   │  AutoDetectParser + ToHTMLContentHandler       │
 * │  MsWordDocumentBackend │  (handled by tika-parsers-standard-package)    │
 * │  HTMLDocumentBackend   │  (handled by tika-parsers-standard-package)    │
 * │  InputDocument         │  TikaHtmlResult (record)                       │
 * └────────────────────────┴────────────────────────────────────────────────┘
 *
 * Key decisions vs previous versions:
 *
 *  1. ToHTMLContentHandler (NOT ToXMLContentHandler)
 *     - ToXML produces XHTML with namespace decls → Jsoup misses tags
 *     - ToHTML produces clean HTML5 → Jsoup parses correctly
 *
 *  2. PDFParserConfig.setSortByPosition(true)
 *     - Without this: multi-column PDFs read in DOM order (column 1+2 mixed)
 *     - With this: Tika reads text in visual reading order (left→right, top→down)
 *     - Critical for any academic paper, report, or newspaper-style layout
 *
 *  3. PDFParserConfig.setExtractInlineImages(false)
 *     - We are not doing OCR on images; skip to avoid noise in HTML output
 *
 *  4. UrlResource support
 *     - Documents arriving via NATS from file-service have a URL, not a path
 *     - Spring's UrlResource handles both http:// and https:// transparently
 */
@Component
@Slf4j
public class TikaHtmlExtractor {

    /**
     * Extract HTML from any supported resource (file on disk or URL).
     *
     * @param resource Spring Resource — FileSystemResource or UrlResource
     * @return TikaHtmlResult with HTML string + metadata map
     */
    public TikaHtmlResult extract(Resource resource) throws Exception {
        AutoDetectParser parser   = new AutoDetectParser();
        Metadata         metadata = new Metadata();
        ParseContext     context  = new ParseContext();
        ContentHandler   handler  = new ToHTMLContentHandler();

        // ── PDF-specific: reading-order fix ───────────────────────────────────
        // sortByPosition = true → critical for multi-column documents.
        // Without this, Tika reads PDF content in stream order which for
        // 2-column layouts means mixing text from both columns.
        PDFParserConfig pdfConfig = new PDFParserConfig();
        pdfConfig.setSortByPosition(true);          // reading-order fix
        pdfConfig.setExtractInlineImages(false);    // skip image OCR
        pdfConfig.setDetectAngles(false);           // we don't do rotation correction
        context.set(PDFParserConfig.class, pdfConfig);

        try (InputStream stream = resource.getInputStream()) {
            parser.parse(stream, handler, metadata, context);
        }

        // Collect all Tika metadata (author, title, pages, content-type, etc.)
        Map<String, String> metaMap = new HashMap<>();
        for (String name : metadata.names()) {
            String value = metadata.get(name);
            if (value != null && !value.isBlank()) {
                metaMap.put(name, value);
            }
        }

        log.debug("[Tika] title='{}', pages={}, type={}",
                metaMap.get("dc:title"),
                metaMap.get("xmpTPg:NPages"),
                metaMap.get("Content-Type"));

        String html = handler.toString();
        log.info("[Tika] Extracted {} HTML chars from '{}'",
                html.length(), resource.getFilename());

        return new TikaHtmlResult(html, metaMap);
    }
}