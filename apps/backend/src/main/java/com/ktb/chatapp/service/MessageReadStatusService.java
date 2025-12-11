package com.ktb.chatapp.service;

import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.repository.MessageRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import com.mongodb.client.result.UpdateResult;

/**
 * 메시지 읽음 상태 관리 서비스
 * Bulk Update로 N+1 문제 해결
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageReadStatusService {

    private final MessageRepository messageRepository;
    private final MongoTemplate mongoTemplate;

    /**
     * 메시지 읽음 상태 업데이트 (Bulk Update)
     *
     * @param messageIds 읽음 상태를 업데이트할 메시지 리스트
     * @param userId 읽은 사용자 ID
     */
    public void updateReadStatus(List<String> messageIds, String userId) {
        if (messageIds == null || messageIds.isEmpty()) {
            return;
        }

        Message.MessageReader readerInfo = Message.MessageReader.builder()
                .userId(userId)
                .readAt(LocalDateTime.now())
                .build();

        try {
            // 🚀 Bulk Update: 이미 읽은 메시지는 제외하고 한 번에 업데이트
            Query query = new Query(
                    Criteria.where("_id").in(messageIds)
                            .and("readers.userId").ne(userId)  // 이미 읽은 건 제외
            );

            Update update = new Update().addToSet("readers", readerInfo);

            UpdateResult result = mongoTemplate.updateMulti(query, update, Message.class);

            log.debug("Read status updated: {} messages modified by user {}",
                    result.getModifiedCount(), userId);

        } catch (Exception e) {
            log.error("Read status update error for user {}", userId, e);
        }
    }
}
