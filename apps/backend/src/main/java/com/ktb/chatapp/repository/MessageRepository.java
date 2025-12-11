package com.ktb.chatapp.repository;

import com.ktb.chatapp.model.Message;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.Aggregation;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface MessageRepository extends MongoRepository<Message, String> {
    Page<Message> findByRoomIdAndIsDeletedAndTimestampBefore(
            String roomId, Boolean isDeleted, LocalDateTime timestamp, Pageable pageable);

    /**
     * 특정 시간 이후의 메시지 수 카운트 (삭제되지 않은 메시지만)
     * 최근 N분간 메시지 수를 조회할 때 사용
     */
    @Query(value = "{ 'room': ?0, 'isDeleted': false, 'timestamp': { $gte: ?1 } }", count = true)
    long countRecentMessagesByRoomId(String roomId, LocalDateTime since);

    /**
     * fileId로 메시지 조회 (파일 권한 검증용)
     */
    Optional<Message> findByFileId(String fileId);

    // ✅ Bulk 메시지 수 조회 추가
    /**
     * 여러 방의 최근 메시지 수를 한 번에 조회
     * Aggregation을 사용하여 효율적으로 처리
     *
     * @param roomIds 조회할 방 ID 리스트
     * @param since 기준 시간 (이 시간 이후의 메시지만 카운트)
     * @return 방별 메시지 수 리스트
     */
    @Aggregation(pipeline = {
            "{ $match: { 'room': { $in: ?0 }, 'isDeleted': false, 'timestamp': { $gte: ?1 } } }",
            "{ $group: { _id: '$room', count: { $sum: 1 } } }"
    })
    List<MessageCountProjection> countRecentMessagesByRoomIds(List<String> roomIds, LocalDateTime since);

    /**
     * Aggregation 결과를 받기 위한 Projection 인터페이스
     */
    interface MessageCountProjection {
        String getId();      // room ID (_id 필드)
        Long getCount();     // message count
    }
}