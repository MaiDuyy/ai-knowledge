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
public class WikiIndexGroup {
    private String type;
    private long total;
    private List<WikiIndexEntry> items;

    @JsonProperty("next_cursor")
    private String nextCursor;
}
