package com.telco.billshock.api;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.telco.billshock.actions.ActionPage;
import com.telco.billshock.actions.ActionStatus;
import com.telco.billshock.actions.CommandResponse;
import com.telco.billshock.actions.IdempotencyKeys;
import com.telco.billshock.actions.ProposedActionService;
import com.telco.billshock.security.CurrentCustomer;

/**
 * The customer's proposed actions (SPEC §4.7; actions.md §4). Confirm and reject require an
 * {@code Idempotency-Key}; their response is stored with the key and replayed exactly, so it is
 * passed through as stored. The account always comes from the SecurityContext.
 */
@RestController
@RequestMapping("/api/v1/actions")
class ActionController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final int MAX_PAGE_SIZE = 100;

    private final ProposedActionService actions;

    ActionController(ProposedActionService actions) {
        this.actions = actions;
    }

    /** @param status optional filter, for example {@code PENDING_CONFIRMATION} */
    @GetMapping
    ActionPage list(@RequestParam(required = false) ActionStatus status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new ApiRequestException("INVALID_PAGE", "page must be 0 or more and size between 1 and 100");
        }
        return actions.list(CurrentCustomer.require(), status, page, size);
    }

    @PostMapping("/{actionId}/confirm")
    ResponseEntity<String> confirm(@PathVariable long actionId,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String key) {
        requireKey(key);
        String path = "/api/v1/actions/" + actionId + "/confirm";
        return response(actions.confirm(CurrentCustomer.require(), CurrentCustomer.username(), actionId, key,
                IdempotencyKeys.requestHash("POST", path, "")));
    }

    /** @param reason optional; PII-scrubbed and cut to 200 characters before it is stored */
    record RejectRequest(String reason) {
    }

    @PostMapping("/{actionId}/reject")
    ResponseEntity<String> reject(@PathVariable long actionId,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String key,
            @RequestBody(required = false) RejectRequest body) {
        requireKey(key);
        String reason = body == null ? null : body.reason();
        String path = "/api/v1/actions/" + actionId + "/reject";
        return response(actions.reject(CurrentCustomer.require(), CurrentCustomer.username(), actionId, key,
                IdempotencyKeys.requestHash("POST", path, reason == null ? "" : reason), reason));
    }

    private static void requireKey(String key) {
        if (!IdempotencyKeys.valid(key)) {
            throw new ApiRequestException("IDEMPOTENCY_KEY_REQUIRED",
                    "An Idempotency-Key header of 1 to 100 characters (letters, digits, - and _) is required");
        }
    }

    private static ResponseEntity<String> response(CommandResponse r) {
        return ResponseEntity.status(r.status())
            .contentType(r.problem() ? MediaType.APPLICATION_PROBLEM_JSON : MediaType.APPLICATION_JSON)
            .body(r.body());
    }
}
