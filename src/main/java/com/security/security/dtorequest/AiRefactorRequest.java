package com.security.security.dtorequest;

import lombok.Data;

@Data
public class AiRefactorRequest {
    private String mode; // "FULL_DOCUMENT" or "PARTIAL"
    private String targetText; 
    private String instruction; 
}
