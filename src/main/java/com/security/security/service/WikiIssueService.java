package com.security.security.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.security.security.dto.WikiIssueDTO;
import com.security.security.dtorequest.CreateWikiIssueRequest;
import com.security.security.dtorequest.UpdateWikiIssueRequest;
import com.security.security.entity.WikiIssue;
import com.security.security.entity.WikiPage;
import com.security.security.entity.enumeration.WikiIssueStatus;
import com.security.security.entity.enumeration.WikiIssueType;
import com.security.security.repository.WikiIssueRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class WikiIssueService {

    private final WikiIssueRepository wikiIssueRepository;
    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    // ─── Mapping helpers ─────────────────────────────────────────────────────

    private WikiIssueDTO toDTO(WikiIssue issue) {
        return WikiIssueDTO.builder()
                .id(issue.getId())
                .wikiPageSlug(issue.getWikiPageSlug())
                .issueType(issue.getIssueType() != null ? issue.getIssueType().name() : null)
                .status(issue.getStatus() != null ? issue.getStatus().name() : null)
                .workspaceId(issue.getWorkspaceId())
                .description(issue.getDescription())
                .evidence(issue.getEvidence())
                .suggestedFix(issue.getSuggestedFix())
                .detectedBy(issue.getDetectedBy())
                .resolvedBy(issue.getResolvedBy())
                .resolvedNote(issue.getResolvedNote())
                .resolvedAt(issue.getResolvedAt() != null ? issue.getResolvedAt().format(FMT) : null)
                .createdAt(issue.getCreatedAt() != null ? issue.getCreatedAt().format(FMT) : null)
                .updatedAt(issue.getUpdatedAt() != null ? issue.getUpdatedAt().format(FMT) : null)
                .build();
    }

    // ─── CRUD ────────────────────────────────────────────────────────────────

    public List<WikiIssueDTO> getIssuesByPage(String slug, String workspaceId) {
        return wikiIssueRepository.findByWikiPageSlugAndWorkspaceId(slug, workspaceId)
                .stream().map(this::toDTO).collect(Collectors.toList());
    }

    public List<WikiIssueDTO> getAllIssues(String workspaceId, String statusFilter) {
        List<WikiIssue> issues;
        if (statusFilter != null && !statusFilter.isBlank()) {
            try {
                WikiIssueStatus status = WikiIssueStatus.valueOf(statusFilter.toUpperCase());
                issues = wikiIssueRepository.findByWorkspaceIdAndStatus(workspaceId, status);
            } catch (IllegalArgumentException e) {
                issues = wikiIssueRepository.findByWorkspaceIdOrderByCreatedAtDesc(workspaceId);
            }
        } else {
            issues = wikiIssueRepository.findByWorkspaceIdOrderByCreatedAtDesc(workspaceId);
        }
        return issues.stream().map(this::toDTO).collect(Collectors.toList());
    }

    public WikiIssueDTO createIssue(CreateWikiIssueRequest req, String detectedBy) {
        WikiIssueType issueType;
        try {
            issueType = WikiIssueType.valueOf(req.getIssueType().toUpperCase());
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid issueType: " + req.getIssueType());
        }

        WikiIssue issue = WikiIssue.builder()
                .wikiPageSlug(req.getWikiPageSlug())
                .issueType(issueType)
                .status(WikiIssueStatus.OPEN)
                .workspaceId(req.getWorkspaceId())
                .description(req.getDescription())
                .evidence(req.getEvidence())
                .suggestedFix(req.getSuggestedFix())
                .detectedBy(detectedBy != null ? detectedBy : "manual")
                .build();

        return toDTO(wikiIssueRepository.save(issue));
    }

    public WikiIssueDTO updateIssue(Long id, UpdateWikiIssueRequest req, String userId) {
        WikiIssue issue = wikiIssueRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Issue not found with ID: " + id));

        if (req.getStatus() != null) {
            try {
                WikiIssueStatus newStatus = WikiIssueStatus.valueOf(req.getStatus().toUpperCase());
                issue.setStatus(newStatus);
                if (newStatus == WikiIssueStatus.FIXED || newStatus == WikiIssueStatus.IGNORED) {
                    issue.setResolvedBy(userId);
                    issue.setResolvedAt(LocalDateTime.now());
                }
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid status: " + req.getStatus());
            }
        }

        if (req.getResolvedNote() != null) {
            issue.setResolvedNote(req.getResolvedNote());
        }

        return toDTO(wikiIssueRepository.save(issue));
    }

    public void deleteIssue(Long id) {
        wikiIssueRepository.deleteById(id);
    }

    public long countOpenIssues(String slug) {
        return wikiIssueRepository.countByWikiPageSlugAndStatusNot(slug, WikiIssueStatus.IGNORED);
    }

    @Transactional
    public void deleteIssuesByPage(String slug) {
        wikiIssueRepository.deleteByWikiPageSlug(slug);
    }

    // ─── Auto-detect issues for a wiki page using LLM ────────────────────────

    /**
     * Analyze a WikiPage for quality issues using LLM and persist detected issues.
     * Skips pages with content shorter than 200 characters.
     */
    public void detectIssuesForPage(WikiPage page) {
        if (page == null || page.getContent() == null || page.getContent().length() < 200) {
            log.debug("[WikiIssueService] Skipping issue detection for page '{}' — content too short",
                    page != null ? page.getSlug() : "null");
            return;
        }

        log.info("[WikiIssueService] Running auto issue detection for page: {}", page.getSlug());

        try {
            String prompt = buildDetectionPrompt(page);

//            var chatResponse = chatModel.call(new UserMessage(prompt));
//            String responseText = chatResponse.getResult().getOutput().getText();
            String responseText = chatModel.call(prompt);
            if (responseText == null || responseText.isBlank()) return;

            // Strip markdown fences
            responseText = responseText.replaceAll("```json", "").replaceAll("```", "").trim();

            List<Map<String, Object>> detectedIssues = objectMapper.readValue(
                    responseText, new TypeReference<List<Map<String, Object>>>() {});

            int created = 0;
            for (Map<String, Object> item : detectedIssues) {
                String typeName = (String) item.get("type");
                if (typeName == null) continue;

                WikiIssueType issueType;
                try {
                    issueType = WikiIssueType.valueOf(typeName.toUpperCase());
                } catch (IllegalArgumentException e) {
                    log.debug("[WikiIssueService] Unknown issue type '{}', skipping", typeName);
                    continue;
                }

                WikiIssue issue = WikiIssue.builder()
                        .wikiPageSlug(page.getSlug())
                        .issueType(issueType)
                        .status(WikiIssueStatus.OPEN)
                        .workspaceId(page.getWorkspaceId())
                        .description((String) item.get("description"))
                        .evidence((String) item.get("evidence"))
                        .suggestedFix((String) item.get("suggestedFix"))
                        .detectedBy("auto")
                        .build();

                wikiIssueRepository.save(issue);
                created++;
            }

            log.info("[WikiIssueService] Auto-detected {} issues for page '{}'", created, page.getSlug());

        } catch (Exception e) {
            // 429 rate limit — skip silently to preserve quota for content generation
            String msg = e.getMessage();
            if (msg != null && (msg.contains("429") || msg.contains("quota") || msg.contains("RESOURCE_EXHAUSTED"))) {
                log.warn("[WikiIssueService] Rate limit (429) hit for page '{}' — skipping issue detection to preserve API quota", page.getSlug());
            } else {
                log.warn("[WikiIssueService] Issue detection failed for page '{}': {}", page.getSlug(), msg);
            }
        }
    }

    private String buildDetectionPrompt(WikiPage page) {
        String content = page.getContent();
        if (content.length() > 3000) content = content.substring(0, 3000) + "...";

        return """
                You are a wiki quality analyst. Analyze the following wiki page for quality issues.

                Wiki Page Title: %s
                Wiki Page Slug: %s
                Content:
                %s

                Identify any of these issue types found in the content:
                - MIXED_ENTITIES: Page mixes multiple unrelated entities without clear structure
                - CONTRADICTORY_FACTS: Page contains contradictory statements about the same subject
                - OUT_OF_DATE: Content appears outdated based on context clues
                - MISSING_LINKS: Mentions entities that likely have wiki pages but uses no [[slug|link]] syntax
                - POOR_QUALITY: Content is incomplete, incoherent, or very low quality
                - HALLUCINATION: Contains fabricated or unsupported claims

                Return ONLY a JSON array. If no issues found, return [].
                Format: [{"type": "ISSUE_TYPE", "description": "clear explanation", "evidence": "specific text excerpt showing the issue", "suggestedFix": "how to fix it"}]

                Rules:
                - Only report real, clear issues — not stylistic preferences
                - evidence must be a direct quote from the content
                - suggestedFix must be actionable
                - Return [] if no significant issues exist
                """.formatted(page.getTitle(), page.getSlug(), content);
    }
}
