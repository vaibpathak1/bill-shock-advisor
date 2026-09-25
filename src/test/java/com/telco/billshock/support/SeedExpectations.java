package com.telco.billshock.support;

import com.telco.billshock.analysis.BillDiff.Verdict;
import com.telco.billshock.analysis.CauseGroup;

import java.util.stream.Stream;

/**
 * The expected engine results on the seed, copied from seed-scenarios.md §5.7 (chat
 * column, Q-26) and §7 (causes, Q-23). Shared by the unit tests (in-memory fixtures) and the
 * ITs (real seed), so both are held to the same numbers.
 */
public final class SeedExpectations {

    private SeedExpectations() {
    }

    public record Chat(long account, String currentTotal, String baselineTotal, String totalExcess,
            String excessPercent, String ratio, Verdict verdict) {
    }

    public record CauseRow(long account, CauseGroup group, String exclGst, String gst, String inclGst) {
    }

    /** seed-scenarios.md §5.7, the chat column. */
    public static Stream<Chat> chatColumn() {
        return Stream.of(
                new Chat(1001, "2919.32", "824.82", "2094.50", "253.9", "3.539", Verdict.MEANINGFUL_INCREASE),
                new Chat(1002, "905.82", "494.99", "410.83", "83.0", "1.830", Verdict.MEANINGFUL_INCREASE),
                new Chat(1003, "854.90", "623.62", "231.28", "37.1", "1.371", Verdict.MEANINGFUL_INCREASE),
                new Chat(1004, "642.12", "470.82", "171.30", "36.4", "1.364", Verdict.MEANINGFUL_INCREASE),
                new Chat(1005, "1413.64", "706.82", "706.82", "100.0", "2.000", Verdict.MEANINGFUL_INCREASE),
                new Chat(1006, "617.14", "610.06", "7.08", "1.2", "1.012", Verdict.NORMAL));
    }

    /** seed-scenarios.md §7 point 2: one cause per flagged account. */
    public static Stream<CauseRow> causes() {
        return Stream.of(
                new CauseRow(1001, CauseGroup.ROAMING, "1775.00", "319.50", "2094.50"),
                new CauseRow(1002, CauseGroup.DATA, "348.16", "62.67", "410.83"),
                new CauseRow(1003, CauseGroup.VAS, "196.00", "35.28", "231.28"),
                new CauseRow(1004, CauseGroup.PLAN, "145.17", "26.13", "171.30"),
                new CauseRow(1005, CauseGroup.PLAN, "599.00", "107.82", "706.82"));
    }
}
