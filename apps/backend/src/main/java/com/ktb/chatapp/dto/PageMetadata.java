package com.ktb.chatapp.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PageMetadata implements Serializable {
    private static final long serialVersionUID = 1L;

    private long total;
    private int page;
    private int pageSize;
    private long totalPages;
    private boolean hasMore;
    private int currentCount;
    private SortInfo sort;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SortInfo implements Serializable {
        private static final long serialVersionUID = 1L;

        private String field;
        private String order;
    }
}