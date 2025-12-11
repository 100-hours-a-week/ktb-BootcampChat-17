package com.ktb.chatapp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoomsResponse implements Serializable {
    private static final long serialVersionUID = 1L;

    private boolean success = true;
    private List<RoomResponse> data;
    private PageMetadata metadata;
}