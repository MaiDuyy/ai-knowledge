package com.security.security.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("WorkspaceServiceClient Tests")
class WorkspaceServiceClientTest {

    private WorkspaceServiceClient client;

    @Mock
    private HttpClient httpClient;

    @Mock
    private HttpResponse<String> httpResponse;

    @BeforeEach
    void setUp() {
        client = new WorkspaceServiceClient("http://localhost:3020", "http://localhost:3010", "localhost", 50051);
        ReflectionTestUtils.setField(client, "httpClient", httpClient);
    }

    @Test
    @DisplayName("Should parse departmentId correctly from workspace service response")
    void getWorkspace_Success_ParsesDepartmentId() throws IOException, InterruptedException {
        String jsonResponse = """
                {
                  "success": true,
                  "workspace": {
                    "id": "ws-123",
                    "name": "Eng Workspace",
                    "slug": "eng-workspace",
                    "isPublic": false,
                    "departmentId": "dept-456"
                  }
                }
                """;

        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(jsonResponse);
        when(httpClient.send(any(), any())).thenReturn((HttpResponse) httpResponse);

        Map<String, Object> result = client.getWorkspace("ws-123", "user-1");

        assertThat(result).isNotEmpty();
        assertThat(result.get("id")).isEqualTo("ws-123");
        assertThat(result.get("departmentId")).isEqualTo("dept-456");
        assertThat(result.get("isPublic")).isEqualTo(false);
    }

    @Test
    @DisplayName("Should degrade gracefully on connection failure or HTTP error")
    void getWorkspace_Error_ReturnsEmptyMap() throws IOException, InterruptedException {
        when(httpClient.send(any(), any())).thenThrow(new IOException("Connection reset"));

        Map<String, Object> result = client.getWorkspace("ws-123", "user-1");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Should parse department name correctly from identity service response")
    void getDepartment_Success_ParsesDepartmentName() throws IOException, InterruptedException {
        String jsonResponse = """
                {
                  "success": true,
                  "data": {
                    "id": "dept-456",
                    "name": "Kỹ thuật",
                    "description": "Phòng kỹ thuật"
                  }
                }
                """;

        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(jsonResponse);
        when(httpClient.send(any(), any())).thenReturn((HttpResponse) httpResponse);

        Map<String, Object> result = client.getDepartment("dept-456", "user-1");

        assertThat(result).isNotEmpty();
        assertThat(result.get("id")).isEqualTo("dept-456");
        assertThat(result.get("name")).isEqualTo("Kỹ thuật");
    }

    @Test
    @DisplayName("Should degrade gracefully on department query HTTP error")
    void getDepartment_Error_ReturnsEmptyMap() throws IOException, InterruptedException {
        when(httpClient.send(any(), any())).thenThrow(new IOException("Timeout"));

        Map<String, Object> result = client.getDepartment("dept-456", "user-1");

        assertThat(result).isEmpty();
    }
}
