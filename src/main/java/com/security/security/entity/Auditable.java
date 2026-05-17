package com.security.security.entity;


import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import org.springframework.util.AlternativeJdkIdGenerator;

import java.time.LocalDateTime;

import static java.time.LocalDateTime.now;

@Getter
@Setter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
@JsonIgnoreProperties(value= {"createAt", "updateAt"} , allowGetters = true)
public abstract class Auditable {
    @Id
    @SequenceGenerator(name =  "primary_key_seq" , sequenceName = "primary_key_seq" , allocationSize = 1)
    @GeneratedValue(strategy =  GenerationType.SEQUENCE, generator = "primary_key_seq")
    @Column(name = "id" , updatable = false)
    private Long id;
    private String referenceId = new AlternativeJdkIdGenerator().generateId().toString();
    @NotNull
    private String createBy;
    @NotNull
    private String updateBy;

    @NotNull
    @CreatedDate
    @Column(name = "create_at" ,nullable = false ,updatable = false)
    private LocalDateTime createAt;
    @CreatedDate
    @Column(name = "update_at" ,nullable = false )
    private LocalDateTime updateAt;


    @PrePersist
    public void beforePersist() {
        var userId  = com.security.security.domain.RequestContext.getUserId();
        if(userId == null) {
            userId = "SYSTEM";
        }
        setCreateAt(now());
        setCreateBy(userId);
        setUpdateBy(userId);
        setUpdateAt(now());
    }

    @PreUpdate
    public void beforeUpdate() {
        var userId  = com.security.security.domain.RequestContext.getUserId();
        if(userId == null) {
            userId = "SYSTEM";
        }
        setUpdateAt(now());
        setUpdateBy(userId);
    }


}
