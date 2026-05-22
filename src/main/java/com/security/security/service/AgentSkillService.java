package com.security.security.service;

import com.security.security.entity.AgentSkill;
import com.security.security.repository.AgentSkillRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AgentSkillService {

    private final AgentSkillRepository agentSkillRepository;

    public AgentSkill createSkill(AgentSkill skill, String userId) {
        skill.setCreatedBy(userId);
        return agentSkillRepository.save(skill);
    }

    public List<AgentSkill> getAllSkills() {
        return agentSkillRepository.findAll();
    }

    public List<AgentSkill> getSkillsByWorkspace(String workspaceId) {
        return agentSkillRepository.findByWorkspaceId(workspaceId);
    }

    public Optional<AgentSkill> getSkillById(Long id) {
        return agentSkillRepository.findById(id);
    }

    public AgentSkill updateSkill(Long id, AgentSkill updatedData) {
        return agentSkillRepository.findById(id).map(skill -> {
            skill.setName(updatedData.getName());
            skill.setDescription(updatedData.getDescription());
            skill.setSystemPrompt(updatedData.getSystemPrompt());
            skill.setDefaultProvider(updatedData.getDefaultProvider());
            return agentSkillRepository.save(skill);
        }).orElseThrow(() -> new RuntimeException("Skill not found"));
    }

    public void deleteSkill(Long id) {
        agentSkillRepository.deleteById(id);
    }
}
