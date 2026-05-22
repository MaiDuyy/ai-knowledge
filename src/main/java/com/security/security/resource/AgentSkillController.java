package com.security.security.resource;

import com.security.security.entity.AgentSkill;
import com.security.security.service.AgentSkillService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/skills")
@RequiredArgsConstructor
public class AgentSkillController {

    private final AgentSkillService agentSkillService;

    @PostMapping
    public ResponseEntity<AgentSkill> createSkill(
            @RequestBody AgentSkill skill,
            @RequestHeader(value = "x-user-id", defaultValue = "system-user") String userId) {
        return ResponseEntity.ok(agentSkillService.createSkill(skill, userId));
    }

    @GetMapping
    public ResponseEntity<List<AgentSkill>> getSkills(
            @RequestParam(required = false) String workspaceId) {
        if (workspaceId != null && !workspaceId.isBlank()) {
            return ResponseEntity.ok(agentSkillService.getSkillsByWorkspace(workspaceId));
        }
        return ResponseEntity.ok(agentSkillService.getAllSkills());
    }

    @GetMapping("/{id}")
    public ResponseEntity<AgentSkill> getSkill(@PathVariable Long id) {
        return agentSkillService.getSkillById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<AgentSkill> updateSkill(
            @PathVariable Long id,
            @RequestBody AgentSkill skill) {
        return ResponseEntity.ok(agentSkillService.updateSkill(id, skill));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteSkill(@PathVariable Long id) {
        agentSkillService.deleteSkill(id);
        return ResponseEntity.noContent().build();
    }
}
