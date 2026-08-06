package com.security.security.repository.mongo;

import com.security.security.entity.mongo.MongoDocument;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MongoDocumentRepository extends MongoRepository<MongoDocument, String> {

    Optional<MongoDocument> findByPostgresDocumentId(Long postgresDocumentId);

    List<MongoDocument> findByWorkspaceId(String workspaceId);

    List<MongoDocument> findByWorkspaceIdAndDepartmentId(String workspaceId, String departmentId);
}
