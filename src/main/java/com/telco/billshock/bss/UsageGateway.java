package com.telco.billshock.bss;

import com.telco.billshock.domain.AccountId;
import com.telco.billshock.domain.BillPeriod;
import com.telco.billshock.domain.Money;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * TMF635 Usage Management: per-session detail, fetched from BSS on demand and never
 * stored locally (K-Q3, A-25). Local aggregates live in the usage read model.
 */
public interface UsageGateway {

    List<UsageSession> usageSessions(AccountId accountId, BillPeriod billedPeriod);

    enum UsageKind {
        DATA, VOICE, SMS, ISD, ROAMING_DATA, ROAMING_VOICE, ROAMING_SMS
    }

    /**
     * @param countryCode ISO 3166-1 alpha-2 for roaming sessions, otherwise {@code null}
     * @param unit {@code MB}, {@code MIN} or {@code SMS}
     */
    record UsageSession(LocalDate usageDate, UsageKind kind, String countryCode,
            BigDecimal quantity, String unit, Money charge) {
    }
}
