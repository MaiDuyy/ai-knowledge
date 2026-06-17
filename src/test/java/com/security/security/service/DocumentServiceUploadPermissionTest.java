package com.security.security.service;

import com.security.security.client.WorkspaceServiceClient;
import com.security.security.dto.DocumentUploadResponse;
import com.security.security.entity.Document;
import com.security.security.entity.enumeration.DocStatus;
import com.security.security.entity.enumeration.DocType;
import com.security.security.event.DocumentUploadedEvent;
import com.security.security.event.NatsEventPublisher;
import com.security.security.exception.ApiException;
import com.security.security.repository.DocumentRepository;
import com.security.security.service.docling.DoclingClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DocumentService Upload & Approval Permission Tests")
class DocumentServiceUploadPermissionTest {

    @Mock private DocumentRepository documentRepository;
    @Mock private NatsEventPublisher natsEventPublisher;
    @Mock private WorkspaceServiceClient workspaceServiceClient;
    @Mock private DoclingClient doclingClient;

    @InjectMocks
    private DocumentService documentService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(documentService, "uploadDir", tempDir.toString());
    }

    @Test
    @DisplayName("MEMBER uploading department-level document (no workspace) throws AccessDeniedException")
    void member_uploadDeptLevel_throwsAccessDenied() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "doc.pdf", "application/pdf", "dummy content".getBytes());

        assertThatThrownBy(() -> documentService.uploadDocument(
                file, "user-1", false, "gemini",
                null, "dept-1", "ALL", "INTERNAL",
                "WORKSPACE_MEMBER", "[{\"departmentId\":\"dept-1\",\"role\":\"MEMBER\"}]"
        )).isInstanceOf(AccessDeniedException.class)
          .hasMessageContaining("Chỉ Trưởng phòng, Phó phòng và Quản trị viên");
    }

    @Test
    @DisplayName("MEMBER uploading workspace-level document gets PENDING status and does not trigger ETL")
    void member_uploadWorkspaceLevel_getsPending() throws IOException {
        MockMultipartFile file = new MockMultipartFile(
                "file", "doc.pdf", "application/pdf", "dummy content".getBytes());

        when(workspaceServiceClient.getWorkspace("ws-1", "user-1")).thenReturn(Map.of(
                "id", "ws-1",
                "departmentId", "dept-1"
        ));

        when(documentRepository.save(any(Document.class))).thenAnswer(inv -> {
            Document doc = inv.getArgument(0);
            doc.setId(100L);
            return doc;
        });

        DocumentUploadResponse response = documentService.uploadDocument(
                file, "user-1", false, "gemini",
                "ws-1", "dept-1", "ALL", "INTERNAL",
                "WORKSPACE_MEMBER", "[{\"departmentId\":\"dept-1\",\"role\":\"MEMBER\"}]"
        );

        assertThat(response.getStatus()).isEqualTo(DocStatus.PENDING.name());
        verify(natsEventPublisher, never()).publishDocumentIngestRequested(anyLong(), anyString());
    }

    @Test
    @DisplayName("HEAD uploading department-level document gets PROCESSING status and triggers ETL immediately")
    void head_uploadDeptLevel_getsProcessingAndTriggersETL() throws IOException {
        MockMultipartFile file = new MockMultipartFile(
                "file", "doc.pdf", "application/pdf", "dummy content".getBytes());

        when(documentRepository.save(any(Document.class))).thenAnswer(inv -> {
            Document doc = inv.getArgument(0);
            doc.setId(200L);
            return doc;
        });

        DocumentUploadResponse response = documentService.uploadDocument(
                file, "user-head", false, "gemini",
                null, "dept-1", "ALL", "INTERNAL",
                "WORKSPACE_MEMBER", "[{\"departmentId\":\"dept-1\",\"role\":\"HEAD\"}]"
        );

        assertThat(response.getStatus()).isEqualTo(DocStatus.PROCESSING.name());
        verify(natsEventPublisher).publishDocumentIngestRequested(200L, "user-head");
    }

    @Test
    @DisplayName("HEAD approving a PENDING document updates status to PROCESSING and triggers ETL")
    void head_approvePendingDocument_success() {
        Document pendingDoc = Document.builder()
                .id(300L)
                .userId("user-1")
                .workspaceId("ws-1")
                .departmentId("dept-1")
                .status(DocStatus.PENDING)
                .fileName("doc.pdf")
                .build();

        when(documentRepository.findById(300L)).thenReturn(Optional.of(pendingDoc));
        when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

        Document approved = documentService.approveDocument(
                300L, "user-head",
                "WORKSPACE_MEMBER", "[{\"departmentId\":\"dept-1\",\"role\":\"HEAD\"}]"
        );

        assertThat(approved.getStatus()).isEqualTo(DocStatus.PROCESSING);
        verify(natsEventPublisher).publishDocumentIngestRequested(300L, "user-1");
    }

    @Test
    @DisplayName("Non-authorized user approving a PENDING document throws AccessDeniedException")
    void otherUser_approvePendingDocument_throwsAccessDenied() {
        Document pendingDoc = Document.builder()
                .id(400L)
                .userId("user-1")
                .workspaceId("ws-1")
                .departmentId("dept-1")
                .status(DocStatus.PENDING)
                .build();

        when(documentRepository.findById(400L)).thenReturn(Optional.of(pendingDoc));

        assertThatThrownBy(() -> documentService.approveDocument(
                400L, "user-other",
                "WORKSPACE_MEMBER", "[{\"departmentId\":\"dept-1\",\"role\":\"MEMBER\"}]"
        )).isInstanceOf(AccessDeniedException.class)
          .hasMessageContaining("Chỉ Trưởng phòng, Phó phòng và Quản trị viên");
    }

    @Test
    @DisplayName("uploadDocument calculates SHA-256 hash and saves it to Document entity along with folderPath")
    void uploadDocument_calculatesHashAndSavesFolderPath() throws IOException {
        MockMultipartFile file = new MockMultipartFile(
                "file", "doc.pdf", "application/pdf", "dummy content".getBytes());

        when(workspaceServiceClient.getWorkspace("ws-1", "user-1")).thenReturn(Map.of(
                "id", "ws-1",
                "departmentId", "dept-1"
        ));

        org.mockito.ArgumentCaptor<Document> docCaptor = org.mockito.ArgumentCaptor.forClass(Document.class);
        when(documentRepository.save(docCaptor.capture())).thenAnswer(inv -> {
            Document doc = inv.getArgument(0);
            doc.setId(500L);
            return doc;
        });

        DocumentUploadResponse response = documentService.uploadDocument(
                file, "user-1", false, "gemini",
                "ws-1", "dept-1", "ALL", "INTERNAL",
                "WORKSPACE_MEMBER", "[{\"departmentId\":\"dept-1\",\"role\":\"HEAD\"}]",
                "HR/Policies"
        );

        assertThat(response.getDocumentId()).isEqualTo(500L);
        Document savedDoc = docCaptor.getValue();
        assertThat(savedDoc.getFileHash()).isEqualTo("bf0ecbdb9b814248d086c9b69cf26182d9d4138f2ad3d0637c4555fc8cbf68e5");
        assertThat(savedDoc.getFolderPath()).isEqualTo("HR/Policies");
    }
}

