package com.security.security.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class InternalMeetingAiAuthenticationFilterTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsMissingCredentialAndDoesNotInvokeTheEndpoint() throws Exception {
        InternalMeetingAiAuthenticationFilter filter = new InternalMeetingAiAuthenticationFilter("service-secret");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", InternalMeetingAiAuthenticationFilter.PATH);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter.doFilter(request, response, (req, res) -> chainCalled.set(true));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chainCalled).isFalse();
    }

    @Test
    void acceptsOnlyTheDedicatedServiceCredential() throws Exception {
        InternalMeetingAiAuthenticationFilter filter = new InternalMeetingAiAuthenticationFilter("service-secret");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", InternalMeetingAiAuthenticationFilter.PATH);
        request.addHeader(InternalMeetingAiAuthenticationFilter.SERVICE_KEY_HEADER, "service-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter.doFilter(request, response, (req, res) -> {
            chainCalled.set(true);
            assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo("voice-service");
            assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                    .extracting(authority -> authority.getAuthority())
                    .containsExactly("ROLE_INTERNAL_MEETING_AI");
        });

        assertThat(chainCalled).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void rejectsAllRequestsWhenNoRuntimeCredentialIsConfigured() throws Exception {
        InternalMeetingAiAuthenticationFilter filter = new InternalMeetingAiAuthenticationFilter("");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", InternalMeetingAiAuthenticationFilter.PATH);
        request.addHeader(InternalMeetingAiAuthenticationFilter.SERVICE_KEY_HEADER, "anything");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            throw new AssertionError("request must not pass without configured credential");
        });

        assertThat(response.getStatus()).isEqualTo(401);
    }
}
