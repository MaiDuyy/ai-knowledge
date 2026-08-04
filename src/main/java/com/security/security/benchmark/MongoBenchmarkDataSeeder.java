package com.security.security.benchmark;

import com.security.security.model.mongo.DocumentChunk;
import com.security.security.model.mongo.DocumentMeta;
import com.security.security.model.mongo.Workspace;
import com.security.security.repository.mongo.DocumentChunkRepository;
import com.security.security.repository.mongo.DocumentMetaRepository;
import com.security.security.repository.mongo.WorkspaceRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;

@Component
public class MongoBenchmarkDataSeeder implements CommandLineRunner {

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private DocumentMetaRepository documentMetaRepository;

    @Autowired
    private DocumentChunkRepository documentChunkRepository;

    @Override
    public void run(String... args) throws Exception {
        System.out.println("Checking MongoDB data seeding...");
        if (workspaceRepository.count() > 0) {
            System.out.println("Data already seeded. Skipping.");
            return;
        }

        System.out.println("Seeding Mock Benchmark Data for Multi-hop Reasoning...");

        // 1. Create Workspace
        Workspace workspace = new Workspace();
        workspace.setName("KTMP Nexus Benchmark Workspace");
        workspace.setOwnerId("admin_system");
        workspace.setCreatedAt(Instant.now());
        workspace.setStatus("ACTIVE");
        workspace = workspaceRepository.save(workspace);

        // 2. Create Documents
        String wsId = workspace.getId();
        
        DocumentMeta doc1 = createDoc(wsId, "doc1-project-nexus.md", "Project Overview");
        DocumentMeta doc2 = createDoc(wsId, "doc2-hr-directory.md", "HR Directory");
        DocumentMeta doc3 = createDoc(wsId, "doc3-architecture-specs.md", "Architecture Specs");
        DocumentMeta doc4 = createDoc(wsId, "doc4-financial-policy.md", "Financial Policy");

        documentMetaRepository.saveAll(Arrays.asList(doc1, doc2, doc3, doc4));

        // 3. Create Chunks (with granular access control)
        
        // Doc 1: Project Overview (Anyone can read)
        DocumentChunk chunk1 = createChunk(wsId, doc1.getId(), 1, "KTMP Nexus là nền tảng OTT Chat dành riêng cho doanh nghiệp...", Arrays.asList("ROLE_EMPLOYEE", "ROLE_ARCHITECT", "ROLE_FINANCE_MANAGER"));
        
        // Doc 2: HR Directory (Employee can read basic HR info)
        DocumentChunk chunk2 = createChunk(wsId, doc2.getId(), 1, "Trưởng phòng Core_Tech là Trần Thị B. Nguyễn Văn A (Mã NV: EMP-101) - Chức danh: ROLE_ARCHITECT", Arrays.asList("ROLE_EMPLOYEE", "ROLE_ARCHITECT", "ROLE_FINANCE_MANAGER"));
        
        // Doc 3: Architecture Specs (Only IT and Architect can read specs)
        DocumentChunk chunk3 = createChunk(wsId, doc3.getId(), 1, "Kiến trúc Dual-DB gồm MongoDB 7.0 và PgVector. Phí duy trì hạ tầng này là $500/tháng.", Arrays.asList("ROLE_ARCHITECT", "ROLE_FINANCE_MANAGER"));
        
        // Doc 4: Financial Policy (Only Finance Manager can read financial approval rules)
        DocumentChunk chunk4 = createChunk(wsId, doc4.getId(), 1, "Phòng Core_Tech có ngân sách Q3 là $50,000. Trên $400/tháng bắt buộc phải có chữ ký xác nhận của Trưởng phòng.", Arrays.asList("ROLE_FINANCE_MANAGER"));

        documentChunkRepository.saveAll(Arrays.asList(chunk1, chunk2, chunk3, chunk4));

        System.out.println("Seeding complete! Workspace ID: " + wsId);
    }

    private DocumentMeta createDoc(String wsId, String path, String title) {
        DocumentMeta doc = new DocumentMeta();
        doc.setWorkspaceId(wsId);
        doc.setFilePath("/benchmark-docs/" + path);
        doc.setTitle(title);
        doc.setCreatedAt(Instant.now());
        doc.setStatus("COMPLETED");
        return doc;
    }

    private DocumentChunk createChunk(String wsId, String docId, int index, String summary, java.util.List<String> roles) {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setWorkspaceId(wsId);
        chunk.setDocumentId(docId);
        chunk.setChunkIndex(index);
        chunk.setContentSummary(summary);
        chunk.setAllowedRoles(roles);
        chunk.setCreatedAt(Instant.now());
        chunk.setMetadata(new HashMap<>());
        return chunk;
    }
}
