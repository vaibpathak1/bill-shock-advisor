package com.telco.billshock.agent.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Buffers streamed text and releases whole sentences, so each one can pass the grounding gate
 * before it is sent (5a answer Q-34; agent.md §8.1). A sentence ends at {@code .}, {@code !} or
 * {@code ?} followed by whitespace, or at a line break. It never ends inside an amount
 * ({@code ₹2,094.50}) or after an abbreviation such as {@code incl.} or {@code e.g.}.
 * The released text keeps its trailing whitespace, so the sentences join back exactly.
 */
final class SentenceSplitter {

    private static final Pattern ABBREVIATION = Pattern.compile(
            "(?i)(?:^|[^\\p{L}])(incl|excl|e\\.g|i\\.e|rs|approx|vs|no|mr|mrs|ms|dr|etc|st)$");

    private final StringBuilder buffer = new StringBuilder();

    /** Adds streamed text; returns the sentences it completed, in order. */
    List<String> accept(String text) {
        buffer.append(text);
        List<String> sentences = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < buffer.length(); i++) {
            char c = buffer.charAt(i);
            boolean end;
            if (c == '\n') {
                end = true;
            }
            else if ((c == '.' || c == '!' || c == '?') && i + 1 < buffer.length()
                    && Character.isWhitespace(buffer.charAt(i + 1))) {
                end = c != '.' || !ABBREVIATION.matcher(buffer.substring(start, i)).find();
            }
            else {
                end = false;
            }
            if (end) {
                int stop = i + 1;
                while (stop < buffer.length() && Character.isWhitespace(buffer.charAt(stop))) {
                    stop++;
                }
                if (stop == buffer.length()) {
                    // Trailing whitespace may continue in the next chunk; still release the sentence now.
                    sentences.add(buffer.substring(start, stop));
                    start = stop;
                    break;
                }
                sentences.add(buffer.substring(start, stop));
                start = stop;
                i = stop - 1;
            }
        }
        buffer.delete(0, start);
        return sentences;
    }

    /** The rest of the text at the end of the stream (may be empty). */
    String flush() {
        String rest = buffer.toString();
        buffer.setLength(0);
        return rest;
    }

    boolean isEmpty() {
        return buffer.isEmpty();
    }
}
