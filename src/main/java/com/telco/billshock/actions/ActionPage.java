package com.telco.billshock.actions;

import java.util.List;

/** One page of {@code GET /api/v1/actions}, newest first (SPEC §4.7 pagination). */
public record ActionPage(List<ActionView> items, int page, int size, long totalElements) {

    public ActionPage {
        items = List.copyOf(items);
    }
}
