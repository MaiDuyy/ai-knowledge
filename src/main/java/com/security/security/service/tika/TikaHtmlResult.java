package com.security.security.service.tika;

import java.util.Map;

/**
 * TikaHtmlResult — output of TikaHtmlExtractor.
 *
 * Docling equivalent: InputDocument / ConversionResult before pipeline.
 *
 * @param html     HTML5 string produced by Tika's ToHTMLContentHandler.
 *                 Contains structural tags: h1-h6, table, p, ul, ol, pre.
 * @param metadata Tika metadata map: dc:title, xmpTPg:NPages, Content-Type,
 *                 dc:creator, dcterms:created, etc.
 */
public record TikaHtmlResult(
        String html,
        Map<String, String> metadata
) {
    /** Convenience accessor — page count from Tika metadata, 0 if unknown. */
    public int pageCount() {
        try {
            String v = metadata.get("xmpTPg:NPages");
            return (v != null) ? Integer.parseInt(v.trim()) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Convenience accessor — document title or empty string. */
    public String title() {
        String t = metadata.get("dc:title");
        return (t != null) ? t.trim() : "";
    }

    /** Content-Type reported by Tika. */
    public String contentType() {
        String ct = metadata.get("Content-Type");
        return (ct != null) ? ct.trim() : "";
    }
}