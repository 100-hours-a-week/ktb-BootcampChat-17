package com.ktb.chatapp.service;

import com.ktb.chatapp.dto.*;
import com.ktb.chatapp.event.RoomCreatedEvent;
import com.ktb.chatapp.event.RoomUpdatedEvent;
import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class RoomService {

    private final RoomRepository roomRepository;
    private final UserRepository userRepository;
    private final MessageRepository messageRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;

    public RoomsResponse getAllRoomsWithPagination(
            com.ktb.chatapp.dto.PageRequest pageRequest, String name) {

        try {
            long startTime = System.currentTimeMillis();

            // 정렬 설정 검증
            if (!pageRequest.isValidSortField()) {
                pageRequest.setSortField("createdAt");
            }
            if (!pageRequest.isValidSortOrder()) {
                pageRequest.setSortOrder("desc");
            }

            // 정렬 방향 설정
            Sort.Direction direction = "desc".equals(pageRequest.getSortOrder())
                    ? Sort.Direction.DESC
                    : Sort.Direction.ASC;

            // 정렬 필드 매핑
            String sortField = pageRequest.getSortField();
            if ("participantsCount".equals(sortField)) {
                sortField = "participantIds";
            }

            // Pageable 객체 생성
            PageRequest springPageRequest = PageRequest.of(
                    pageRequest.getPage(),
                    pageRequest.getPageSize(),
                    Sort.by(direction, sortField)
            );

            // 검색어가 있는 경우와 없는 경우 분리
            Page<Room> roomPage;
            if (pageRequest.getSearch() != null && !pageRequest.getSearch().trim().isEmpty()) {
                roomPage = roomRepository.findByNameContainingIgnoreCase(
                        pageRequest.getSearch().trim(), springPageRequest);
            } else {
                roomPage = roomRepository.findAll(springPageRequest);
            }

            List<Room> rooms = roomPage.getContent();

            // ✅ Step 1: 모든 userId 수집 (중복 제거)
            Set<String> allUserIds = new HashSet<>();
            for (Room room : rooms) {
                if (room.getCreator() != null) {
                    allUserIds.add(room.getCreator());
                }
                if (room.getParticipantIds() != null) {
                    allUserIds.addAll(room.getParticipantIds());
                }
            }

            // ✅ Step 2: User Bulk 조회 (1 query)
            Map<String, User> userMap = getUserMapBulk(allUserIds);
            log.debug("User Bulk GET: {}/{} users loaded", userMap.size(), allUserIds.size());

            // ✅ Step 3: 메시지 수 Bulk 조회 (1 query)
            List<String> roomIds = rooms.stream()
                    .map(Room::getId)
                    .collect(Collectors.toList());

            Map<String, Long> messageCountMap = getMessageCountMapBulk(roomIds);
            log.debug("Message Count Bulk GET: {}/{} rooms loaded",
                    messageCountMap.size(), roomIds.size());

            // ✅ Step 4: Room을 RoomResponse로 변환 (추가 쿼리 없음)
            List<RoomResponse> roomResponses = rooms.stream()
                    .map(room -> mapToRoomResponseOptimized(room, name, userMap, messageCountMap))
                    .collect(Collectors.toList());

            long endTime = System.currentTimeMillis();
            log.info("Rooms loaded in {}ms (total: {}, users: {}, message counts: {})",
                    endTime - startTime, rooms.size(), userMap.size(), messageCountMap.size());

            // 메타데이터 생성
            PageMetadata metadata = PageMetadata.builder()
                    .total(roomPage.getTotalElements())
                    .page(pageRequest.getPage())
                    .pageSize(pageRequest.getPageSize())
                    .totalPages(roomPage.getTotalPages())
                    .hasMore(roomPage.hasNext())
                    .currentCount(roomResponses.size())
                    .sort(PageMetadata.SortInfo.builder()
                            .field(pageRequest.getSortField())
                            .order(pageRequest.getSortOrder())
                            .build())
                    .build();

            return RoomsResponse.builder()
                    .success(true)
                    .data(roomResponses)
                    .metadata(metadata)
                    .build();

        } catch (Exception e) {
            log.error("방 목록 조회 에러", e);
            return RoomsResponse.builder()
                    .success(false)
                    .data(List.of())
                    .build();
        }
    }

    /**
     * ✅ User Bulk 조회 헬퍼 메서드
     * N번의 findById() 대신 1번의 findAllById()로 조회
     */
    private Map<String, User> getUserMapBulk(Set<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return new HashMap<>();
        }

        try {
            List<User> users = userRepository.findAllById(userIds);
            return users.stream()
                    .collect(Collectors.toMap(User::getId, Function.identity()));
        } catch (Exception e) {
            log.error("User bulk query failed", e);
            return new HashMap<>();
        }
    }

    /**
     * ✅ 메시지 수 Bulk 조회 헬퍼 메서드
     * N번의 countRecentMessagesByRoomId() 대신 1번의 Aggregation으로 조회
     */
    private Map<String, Long> getMessageCountMapBulk(List<String> roomIds) {
        if (roomIds == null || roomIds.isEmpty()) {
            return new HashMap<>();
        }

        try {
            LocalDateTime tenMinutesAgo = LocalDateTime.now().minusMinutes(10);
            List<MessageRepository.MessageCountProjection> results =
                    messageRepository.countRecentMessagesByRoomIds(roomIds, tenMinutesAgo);

            return results.stream()
                    .collect(Collectors.toMap(
                            MessageRepository.MessageCountProjection::getId,
                            MessageRepository.MessageCountProjection::getCount
                    ));
        } catch (Exception e) {
            log.error("Message count bulk query failed", e);
            return new HashMap<>();
        }
    }

    /**
     * ✅ 최적화된 RoomResponse 매핑 (추가 쿼리 없음)
     * userMap과 messageCountMap을 사용하여 O(1) 조회
     */
    private RoomResponse mapToRoomResponseOptimized(
            Room room,
            String currentUserEmail,
            Map<String, User> userMap,
            Map<String, Long> messageCountMap) {

        if (room == null) return null;

        // Creator 조회 (Map에서 O(1))
        User creator = null;
        if (room.getCreator() != null) {
            creator = userMap.get(room.getCreator());
        }

        // Participants 조회 (Map에서 O(1) × N)
        List<User> participants = room.getParticipantIds().stream()
                .map(userMap::get)
                .filter(Objects::nonNull)
                .toList();

        // 메시지 수 조회 (Map에서 O(1))
        long recentMessageCount = messageCountMap.getOrDefault(room.getId(), 0L);

        return RoomResponse.builder()
                .id(room.getId())
                .name(room.getName() != null ? room.getName() : "제목 없음")
                .hasPassword(room.isHasPassword())
                .creator(creator != null ? UserResponse.builder()
                        .id(creator.getId())
                        .name(creator.getName() != null ? creator.getName() : "알 수 없음")
                        .email(creator.getEmail() != null ? creator.getEmail() : "")
                        .build() : null)
                .participants(participants.stream()
                        .filter(p -> p != null && p.getId() != null)
                        .map(p -> UserResponse.builder()
                                .id(p.getId())
                                .name(p.getName() != null ? p.getName() : "알 수 없음")
                                .email(p.getEmail() != null ? p.getEmail() : "")
                                .build())
                        .collect(Collectors.toList()))
                .createdAtDateTime(room.getCreatedAt())
                .isCreator(creator != null && creator.getEmail() != null
                        && creator.getEmail().equals(currentUserEmail))
                .recentMessageCount((int) recentMessageCount)
                .build();
    }

    public HealthResponse getHealthStatus() {
        try {
            long startTime = System.currentTimeMillis();

            boolean isMongoConnected = false;
            long latency = 0;

            try {
                roomRepository.findOneForHealthCheck();
                long endTime = System.currentTimeMillis();
                latency = endTime - startTime;
                isMongoConnected = true;
            } catch (Exception e) {
                log.warn("MongoDB 연결 확인 실패", e);
                isMongoConnected = false;
            }

            LocalDateTime lastActivity = roomRepository.findMostRecentRoom()
                    .map(Room::getCreatedAt)
                    .orElse(null);

            Map<String, HealthResponse.ServiceHealth> services = new HashMap<>();
            services.put("database", HealthResponse.ServiceHealth.builder()
                    .connected(isMongoConnected)
                    .latency(latency)
                    .build());

            return HealthResponse.builder()
                    .success(true)
                    .services(services)
                    .lastActivity(lastActivity)
                    .build();

        } catch (Exception e) {
            log.error("Health check 실행 중 에러 발생", e);
            return HealthResponse.builder()
                    .success(false)
                    .services(new HashMap<>())
                    .build();
        }
    }

    public Room createRoom(CreateRoomRequest createRoomRequest, String name) {
        User creator = userRepository.findByEmail(name)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다: " + name));

        Room room = new Room();
        room.setName(createRoomRequest.getName().trim());
        room.setCreator(creator.getId());
        room.getParticipantIds().add(creator.getId());

        if (createRoomRequest.getPassword() != null && !createRoomRequest.getPassword().isEmpty()) {
            room.setHasPassword(true);
            room.setPassword(passwordEncoder.encode(createRoomRequest.getPassword()));
        }

        Room savedRoom = roomRepository.save(room);

        try {
            // ✅ 이벤트 발행 시에도 최적화된 매핑 사용
            Map<String, User> userMap = getUserMapBulk(Set.of(creator.getId()));
            Map<String, Long> messageCountMap = new HashMap<>();
            RoomResponse roomResponse = mapToRoomResponseOptimized(
                    savedRoom, name, userMap, messageCountMap);
            eventPublisher.publishEvent(new RoomCreatedEvent(this, roomResponse));
        } catch (Exception e) {
            log.error("roomCreated 이벤트 발행 실패", e);
        }

        return savedRoom;
    }

    public Optional<Room> findRoomById(String roomId) {
        return roomRepository.findById(roomId);
    }

    public Room joinRoom(String roomId, String password, String name) {
        Optional<Room> roomOpt = roomRepository.findById(roomId);
        if (roomOpt.isEmpty()) {
            return null;
        }

        Room room = roomOpt.get();
        User user = userRepository.findByEmail(name)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다: " + name));

        if (room.isHasPassword()) {
            if (password == null || !passwordEncoder.matches(password, room.getPassword())) {
                throw new RuntimeException("비밀번호가 일치하지 않습니다.");
            }
        }

        if (!room.getParticipantIds().contains(user.getId())) {
            room.getParticipantIds().add(user.getId());
            room = roomRepository.save(room);
        }

        try {
            // ✅ 이벤트 발행 시에도 최적화된 매핑 사용
            Set<String> allUserIds = new HashSet<>(room.getParticipantIds());
            if (room.getCreator() != null) {
                allUserIds.add(room.getCreator());
            }
            Map<String, User> userMap = getUserMapBulk(allUserIds);
            Map<String, Long> messageCountMap = getMessageCountMapBulk(List.of(roomId));

            RoomResponse roomResponse = mapToRoomResponseOptimized(
                    room, name, userMap, messageCountMap);
            eventPublisher.publishEvent(new RoomUpdatedEvent(this, roomId, roomResponse));
        } catch (Exception e) {
            log.error("roomUpdate 이벤트 발행 실패", e);
        }

        return room;
    }
}