package com.security.security.dto;

/**
 * Versioned event contract emitted by the internal Meeting AI SSE endpoint.
 * Only speech deltas are eligible for downstream TTS.
 */
public sealed interface MeetingAiStreamEvent permits MeetingAiStreamEvent.SpeechDelta,
        MeetingAiStreamEvent.DisplayDelta, MeetingAiStreamEvent.Source, MeetingAiStreamEvent.Done {

    String type();

    int version();

    String turnId();

    record SpeechDelta(String type, int version, String turnId, long sequence, String text) implements MeetingAiStreamEvent {
        public static SpeechDelta of(String turnId, long sequence, String text) {
            return new SpeechDelta("speech.delta", 1, turnId, sequence, text);
        }
    }

    record DisplayDelta(String type, int version, String turnId, long sequence, String text) implements MeetingAiStreamEvent {
        public static DisplayDelta of(String turnId, long sequence, String text) {
            return new DisplayDelta("display.delta", 1, turnId, sequence, text);
        }
    }

    record Source(String type, int version, String turnId, long sequence, String documentId, String title, String chunkId)
            implements MeetingAiStreamEvent {
        public static Source of(String turnId, long sequence, String documentId, String title, String chunkId) {
            return new Source("source", 1, turnId, sequence, documentId, title, chunkId);
        }
    }

    record Done(String type, int version, String turnId, boolean replayed, Usage usage, Latency latency)
            implements MeetingAiStreamEvent {
        public static Done of(String turnId, boolean replayed, long firstDeltaMs, long totalMs) {
            return new Done("done", 1, turnId, replayed, null, new Latency(firstDeltaMs, totalMs));
        }
    }

    record Usage(Integer inputTokens, Integer outputTokens) {
    }

    record Latency(long firstDeltaMs, long totalMs) {
    }
}
