package com.security.security.service;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.async.ByteArrayFeeder;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Incrementally extracts only summary/details values from the JSON response
 * schema used by RAG. Raw JSON is never forwarded to voice output.
 */
@Component
public class MeetingAiStreamTextExtractor {

    public Session open() {
        return new Session();
    }

    public static final class Session implements AutoCloseable {
        private static final int MAX_TEXT_CHARS = 20_000;

        private final JsonParser parser;
        private final ByteArrayFeeder feeder;
        private final StringBuilder pendingHighSurrogate = new StringBuilder();
        private String currentField;
        private int emittedTextChars;
        private boolean emittedSummary;
        private boolean completed;

        private Session() {
            try {
                parser = new JsonFactory().createNonBlockingByteArrayParser();
                feeder = (ByteArrayFeeder) parser.getNonBlockingInputFeeder();
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to create incremental JSON parser", exception);
            }
        }

        public List<TextFragment> accept(String chunk) {
            if (completed) {
                throw new IllegalStateException("Meeting AI stream received text after completion");
            }
            if (chunk == null || chunk.isEmpty()) {
                return List.of();
            }

            String stableChunk = appendAndSplitSurrogate(chunk);
            if (stableChunk.isEmpty()) {
                return List.of();
            }

            try {
                byte[] bytes = stableChunk.getBytes(StandardCharsets.UTF_8);
                if (!feeder.needMoreInput()) {
                    throw new IllegalStateException("Meeting AI JSON parser did not consume the previous chunk");
                }
                feeder.feedInput(bytes, 0, bytes.length);
                return drainTokens();
            } catch (IOException exception) {
                throw new IllegalArgumentException("Meeting AI provider returned malformed structured output", exception);
            }
        }

        public List<TextFragment> complete() {
            if (completed) {
                throw new IllegalStateException("Meeting AI stream completed more than once");
            }
            completed = true;
            try {
                if (pendingHighSurrogate.length() > 0) {
                    throw new IllegalArgumentException("Meeting AI provider ended with an incomplete Unicode character");
                }
                feeder.endOfInput();
                List<TextFragment> fragments = drainTokens();
                if (emittedTextChars == 0) {
                    throw new IllegalArgumentException("Meeting AI provider returned no speech-safe text");
                }
                parser.close();
                return fragments;
            } catch (IOException exception) {
                throw new IllegalArgumentException("Meeting AI provider ended with malformed structured output", exception);
            }
        }

        private String appendAndSplitSurrogate(String chunk) {
            String combined = pendingHighSurrogate + chunk;
            pendingHighSurrogate.setLength(0);
            if (!combined.isEmpty() && Character.isHighSurrogate(combined.charAt(combined.length() - 1))) {
                pendingHighSurrogate.append(combined.charAt(combined.length() - 1));
                return combined.substring(0, combined.length() - 1);
            }
            return combined;
        }

        private List<TextFragment> drainTokens() throws IOException {
            List<TextFragment> fragments = new ArrayList<>();
            JsonToken token;
            while ((token = parser.nextToken()) != JsonToken.NOT_AVAILABLE) {
                if (token == null) {
                    break;
                }
                if (token == JsonToken.FIELD_NAME) {
                    currentField = parser.currentName();
                    continue;
                }
                if (token == JsonToken.VALUE_STRING && ("summary".equals(currentField) || "details".equals(currentField))) {
                    String displayText = parser.getValueAsString();
                    String speechText = speechSafe(displayText);
                    if (!speechText.isBlank()) {
                        String displayPrefix = emittedSummary ? "\n" : "";
                        String speechPrefix = emittedSummary ? " " : "";
                        emittedSummary = true;
                        emittedTextChars += displayText.length();
                        if (emittedTextChars > MAX_TEXT_CHARS) {
                            throw new IllegalArgumentException("Meeting AI response exceeds the streaming text limit");
                        }
                        fragments.add(new TextFragment(displayPrefix + displayText, speechPrefix + speechText));
                    }
                }
            }
            return fragments;
        }

        private String speechSafe(String displayText) {
            return displayText
                    .replaceAll("\\[[^\\]]*]\\([^)]*\\)", "")
                    .replaceAll("https?://\\S+", "")
                    .replaceAll("\\[\\d+]", "")
                    .replaceAll("```[\\s\\S]*?```", "")
                    .replaceAll("[`*_#>]", "")
                    .replaceAll("\\s+", " ")
                    .trim();
        }

        @Override
        public void close() {
            try {
                parser.close();
            } catch (IOException ignored) {
                // The source stream has already reached a terminal state.
            }
        }
    }

    public record TextFragment(String displayText, String speechText) {
    }
}
