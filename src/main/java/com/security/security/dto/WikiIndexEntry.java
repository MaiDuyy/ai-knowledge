package com.security.security.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WikiIndexEntry {
    private String slug;
    private String title;
    private String summary;

    @JsonProperty("parent_slug")
    private String parentSlug;

    @JsonProperty("category_path")
    private List<String> categoryPath;

    @JsonProperty("wiki_path")
    private String wikiPath;

    private int depth;

    @JsonProperty("sort_order")
    private int sortOrder;
}
