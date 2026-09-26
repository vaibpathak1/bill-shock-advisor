package com.telco.billshock.api;

/** A 400 with an error code (SPEC §4.8). */
class ApiRequestException extends RuntimeException {

    private final String code;

    ApiRequestException(String code, String detail) {
        super(detail);
        this.code = code;
    }

    String code() {
        return code;
    }
}
