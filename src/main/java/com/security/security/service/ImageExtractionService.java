package com.security.security.service;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
@Slf4j
public class ImageExtractionService {

    @Value("${app.upload.dir:uploads}")
    private String uploadDir;

    private static final int MIN_IMAGE_BYTES = 2048;

    @Data
    @AllArgsConstructor
    public static class ExtractedImage {
        private byte[] bytes;
        private String contentType;
        private String extension;
        private Integer pageNumber;
        private int imageIndex;
    }

    /**
     * Auto-detect file type and extract images.
     */
    public List<ExtractedImage> extractImages(byte[] fileData, String fileName) {
        String lower = fileName.toLowerCase();
        try {
            if (lower.endsWith(".pdf")) {
                return extractImagesFromPdf(fileData);
            } else if (lower.endsWith(".docx")) {
                return extractImagesFromDocx(fileData);
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
        int imageIndex = 0;

        try (PDDocument document = PDDocument.load(fileData)) {
            for (int pageNum = 0; pageNum < document.getNumberOfPages(); pageNum++) {
                PDPage page = document.getPage(pageNum);
                PDResources resources = page.getResources();
                if (resources == null) continue;

                for (COSName name : resources.getXObjectNames()) {
                    try {
                        if (resources.isImageXObject(name)) {
                            PDImageXObject image = (PDImageXObject) resources.getXObject(name);
                            if (image == null || image.getImage() == null) continue;

                            String format = "png";
                            String ext = image.getSuffix();
                            if (ext != null && !ext.isEmpty()) {
                                format = ext.toLowerCase();
                            }

                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                            ImageIO.write(image.getImage(), format, baos);
                            byte[] imgBytes = baos.toByteArray();

                            if (imgBytes.length < MIN_IMAGE_BYTES) {
                                continue;
                            }

                            String contentType = mimeFromExt(format);

                            images.add(new ExtractedImage(
                                    imgBytes,
                                    contentType,
                                    format,
                                    pageNum + 1,
                                    imageIndex++
                            ));
                        }
                    } catch (Exception ex) {
                        log.warn("Failed to extract specific PDF image reference on page {}: {}", pageNum + 1, ex.getMessage());
                    }
                }
            }
        }

        log.info("Extracted {} images from PDF", images.size());
        return images;
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

                        images.add(new ExtractedImage(
                                imgBytes,
                                contentType,
                                ext,
                                null,
                                imageIndex++
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
     * Helper to map extensions to mime type.
     */
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
