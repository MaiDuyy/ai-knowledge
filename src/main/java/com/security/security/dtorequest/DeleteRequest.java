package com.security.security.dtorequest;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeleteRequest {
    private String documentId;

    public DeleteRequest(Long documentId) {
        this.documentId = documentId != null ? String.valueOf(documentId) : null;
    }
}
