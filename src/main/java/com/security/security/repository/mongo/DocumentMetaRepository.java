package com.security.security.repository.mongo;

import com.security.security.model.mongo.DocumentMeta;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DocumentMetaRepository extends MongoRepository<DocumentMeta, String> {
    List<DocumentMeta> findByWorkspaceId(String workspaceId);
}
