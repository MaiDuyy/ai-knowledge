package com.security.security.repository;

import com.security.security.entity.AgentSkill;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AgentSkillRepository extends JpaRepository<AgentSkill, Long> {
    List<AgentSkill> findByWorkspaceId(String workspaceId);
}
