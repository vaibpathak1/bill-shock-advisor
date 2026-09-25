package com.telco.billshock.bss.internal.mock;

import com.telco.billshock.bss.UsageGateway;
import com.telco.billshock.domain.AccountId;
import com.telco.billshock.domain.BillPeriod;
import com.telco.billshock.domain.Money;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Mock TMF635 per-session detail from {@code usage-sessions.json}: account 1001's UAE trip
 * and account 1002's September data, consistent with the seeded aggregates. Any other
 * account or period has no detail.
 */
@Component
@Profile("mock-bss")
class MockUsageGateway implements UsageGateway {

    private final Map<String, Map<String, List<SessionFixture>>> sessions;

    MockUsageGateway(JsonMapper jsonMapper) {
        this.sessions = BssFixtures.load(jsonMapper, "usage-sessions.json", UsageFixture.class).sessions();
    }

    @Override
    public List<UsageSession> usageSessions(AccountId accountId, BillPeriod billedPeriod) {
        return sessions.getOrDefault(String.valueOf(accountId.value()), Map.of())
            .getOrDefault(billedPeriod.toString(), List.of())
            .stream()
            .map(s -> new UsageSession(s.usageDate(), s.kind(), s.countryCode(), s.quantity(), s.unit(),
                    Money.of(s.charge())))
            .toList();
    }

    record UsageFixture(Map<String, Map<String, List<SessionFixture>>> sessions) {
    }

    record SessionFixture(LocalDate usageDate, UsageKind kind, String countryCode, BigDecimal quantity, String unit,
            BigDecimal charge) {
    }
}
