package com.security.security.resource;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/dashboard")
public class DashboardController {

    @GetMapping("/daily-brief")
    public ResponseEntity<Map<String, Object>> getDailyBrief(
            @RequestHeader(value = "x-user-id", defaultValue = "system-user") String userId) {

        // Trong thực tế, AI Service sẽ dùng LLM gộp lịch sử tin nhắn hoặc notification
        // từ Messaging/Notification service qua gRPC/NATS để summarize.
        // Ở đây ta trả về mock data format phù hợp với DashboardApi của frontend.

        return ResponseEntity.ok(Map.of(
                "summary", "Hôm nay có 2 thông báo mới từ team Backend và HR. Dự án đang đi đúng tiến độ.",
                "urgentMentions", List.of(
                        Map.of(
                                "id", "m1",
                                "from", "#backend-team",
                                "message", "Đã deploy xong bản cập nhật hiệu năng lên staging.",
                                "time", Instant.now().toString()
                        ),
                        Map.of(
                                "id", "m2",
                                "from", "@HR",
                                "message", "Lưu ý submit timesheet trước 5h chiều nay nhé.",
                                "time", Instant.now().toString()
                        )
                ),
                "meetings", List.of()
        ));
    }
}
