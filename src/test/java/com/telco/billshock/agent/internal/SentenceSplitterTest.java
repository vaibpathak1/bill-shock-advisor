package com.telco.billshock.agent.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Sentence buffering for the gate (5a answer Q-34). */
class SentenceSplitterTest {

    private static List<String> split(String... chunks) {
        SentenceSplitter splitter = new SentenceSplitter();
        List<String> out = new ArrayList<>();
        for (String c : chunks) {
            out.addAll(splitter.accept(c));
        }
        String rest = splitter.flush();
        if (!rest.isEmpty()) {
            out.add(rest);
        }
        return out;
    }

    @Test
    void releasesWholeSentencesAcrossChunksAndKeepsTheText() {
        List<String> sentences = split("Your bill is hi", "gher. Roaming", " is the cause! Want a pack?", " Yes");
        assertThat(sentences).containsExactly("Your bill is higher. ", "Roaming is the cause! ", "Want a pack? ", "Yes");
        assertThat(String.join("", sentences)).isEqualTo("Your bill is higher. Roaming is the cause! Want a pack? Yes");
    }

    @Test
    void neverSplitsInsideAnAmountOrAfterGstAbbreviations() {
        assertThat(split("It is ₹2,094.50 incl. GST higher, and ₹1,775.00 excl. GST before tax. Done."))
            .containsExactly("It is ₹2,094.50 incl. GST higher, and ₹1,775.00 excl. GST before tax. ", "Done.");
    }

    @Test
    void doesNotSplitAfterCommonAbbreviations() {
        assertThat(split("Some add-ons, e.g. roaming packs, i.e. IR_GCC_7D, cost Rs. 500 vs. more. End."))
            .containsExactly("Some add-ons, e.g. roaming packs, i.e. IR_GCC_7D, cost Rs. 500 vs. more. ", "End.");
    }

    @Test
    void aLineBreakEndsASentence() {
        assertThat(split("Causes:\n- Roaming\n- Data")).containsExactly("Causes:\n", "- Roaming\n", "- Data");
    }

    @Test
    void aFullStopAtTheEndOfAChunkWaitsForTheNextChunk() {
        SentenceSplitter splitter = new SentenceSplitter();
        assertThat(splitter.accept("The total is ₹2,094.")).isEmpty();
        assertThat(splitter.accept("50 incl. GST. ")).containsExactly("The total is ₹2,094.50 incl. GST. ");
    }
}
