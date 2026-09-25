package com.telco.billshock.security;

/**
 * Masks a mobile number to its last 4 digits (security.md §6.1). The full MSISDN is used only
 * in BSS gateway calls.
 */
public final class MsisdnMask {

    private MsisdnMask() {
    }

    /** {@code +915550001001} → {@code ******1001}; fewer than 4 digits → {@code ******}. */
    public static String mask(String msisdn) {
        String digits = msisdn == null ? "" : msisdn.replaceAll("\\D", "");
        return digits.length() < 4 ? "******" : "******" + digits.substring(digits.length() - 4);
    }
}
