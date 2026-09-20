package com.security.security.service;

import org.junit.jupiter.api.Test;
import java.io.File;
import java.nio.file.Files;
import java.util.List;

public class ImageExtractionTest {

    @Test
    public void testExtractImagesFromUploads() throws Exception {
        File uploadsDir = new File("uploads");
        if (!uploadsDir.exists() || !uploadsDir.isDirectory()) {
            System.out.println("Uploads directory does not exist!");
            return;
        }

        File[] files = uploadsDir.listFiles((dir, name) -> name.endsWith(".pdf"));
        if (files == null || files.length == 0) {
            System.out.println("No PDF files found in uploads directory!");
            return;
        }

        ImageProcessingService service = new ImageProcessingService(null);

        for (File file : files) {
            System.out.println("=================================================");
            System.out.println("Scanning file: " + file.getName());
            byte[] fileData = Files.readAllBytes(file.toPath());
            try {
                List<ImageProcessingService.ExtractedImage> images = service.extractImagesFromPdf(fileData);
                System.out.println("Extracted " + images.size() + " images.");
                for (int i = 0; i < images.size(); i++) {
                    ImageProcessingService.ExtractedImage img = images.get(i);
                    System.out.println("  - Image " + i + ": page=" + img.getPageNumber() + ", index=" + img.getImageIndex() + ", size=" + img.getBytes().length + " bytes, format=" + img.getExtension() + ", type=" + img.getContentType());
                }
            } catch (Exception e) {
                System.out.println("Error scanning file: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }
}
