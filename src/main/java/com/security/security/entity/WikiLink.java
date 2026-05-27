package com.security.security.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "wiki_links", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"from_page_id", "to_slug"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WikiLink {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "from_page_id", nullable = false)
    private Long fromPageId;

    @Column(name = "to_slug", nullable = false, length = 300)
    private String toSlug;
}
