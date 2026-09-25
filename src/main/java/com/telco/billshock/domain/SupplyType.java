package com.telco.billshock.domain;

import java.util.Set;

/**
 * GST supply type (A-72, A-73): intra-state when the place of supply (the account's GST
 * state) is a state in which the supplier holds a GST registration, otherwise inter-state.
 */
public enum SupplyType {

    /** CGST + SGST. */
    INTRA,

    /** IGST. */
    INTER;

    public static SupplyType of(String placeOfSupplyState, Set<String> supplierRegisteredStates) {
        return supplierRegisteredStates.contains(placeOfSupplyState) ? INTRA : INTER;
    }
}
