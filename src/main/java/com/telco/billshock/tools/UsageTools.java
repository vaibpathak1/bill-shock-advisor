package com.telco.billshock.tools;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import com.fasterxml.jackson.annotation.JsonInclude;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.telco.billshock.bss.BillingReadModel;
import com.telco.billshock.bss.BillingReadModel.BillSummary;
import com.telco.billshock.bss.BillingReadModel.UsagePeriodSummary;
import com.telco.billshock.bss.BssUnavailableException;
import com.telco.billshock.bss.UsageGateway;
import com.telco.billshock.bss.UsageGateway.UsageKind;
import com.telco.billshock.bss.UsageGateway.UsageSession;
import com.telco.billshock.domain.AccountId;
import com.telco.billshock.domain.BillPeriod;
import com.telco.billshock.domain.Money;
import com.telco.billshock.security.CurrentCustomer;

/**
 * Usage behind a bill (SPEC §4.4 {@code getUsageDetails}). Period aggregates come from the
 * local replica; DATA and ROAMING add a per-day breakdown from BSS (TMF635, A-25). Raw
 * sessions never reach the model, only daily sums (security.md §6.3).
 */
@Component
public class UsageTools {

    private static final BigDecimal MB_PER_GB = BigDecimal.valueOf(1024);
    private static final Set<UsageKind> DATA_KINDS = Set.of(UsageKind.DATA);
    private static final Set<UsageKind> ROAMING_KINDS = Set.of(UsageKind.ROAMING_DATA, UsageKind.ROAMING_VOICE,
            UsageKind.ROAMING_SMS);

    private final BillingReadModel billing;
    private final UsageGateway usageGateway;

    public UsageTools(BillingReadModel billing, UsageGateway usageGateway) {
        this.billing = billing;
        this.usageGateway = usageGateway;
    }

    public enum UsageType {
        DATA, VOICE, SMS, ROAMING
    }

    @Tool(name = "getUsageDetails", description = """
            Returns the usage billed on one bill for one type: DATA, VOICE (domestic and international \
            minutes), SMS or ROAMING (per country, with the travel dates). DATA and ROAMING include a \
            per-day breakdown when the billing system can provide it. Charges are before GST.""")
    public ToolResult getUsageDetails(
            @ToolParam(required = false, description = "Bill month as YYYY-MM; omit for the latest bill") String billPeriod,
            @ToolParam(description = "DATA, VOICE, SMS or ROAMING") String type) {
        AccountId account = CurrentCustomer.require();
        UsageType usageType;
        try {
            usageType = UsageType.valueOf(type == null ? "" : type.strip().toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException e) {
            return ToolMessage.invalid("type must be DATA, VOICE, SMS or ROAMING");
        }
        Optional<BillPeriod> period;
        try {
            period = Periods.parse(billPeriod);
        }
        catch (IllegalArgumentException e) {
            return ToolMessage.invalid(e.getMessage());
        }
        Optional<BillSummary> bill = Periods.bill(billing, account, period);
        if (bill.isEmpty()) {
            return ToolMessage.noBill(billPeriod);
        }
        BillPeriod billed = bill.get().billPeriod();
        List<UsagePeriodSummary> usage = billing.usage(account, billed);
        return switch (usageType) {
            case DATA -> withDetail(new UsageResult("OK", billed.toString(), usageType.name(),
                    usage.stream().map(UsageTools::data).toList(), null, null, null), account, billed, DATA_KINDS);
            case VOICE -> new UsageResult("OK", billed.toString(), usageType.name(),
                    usage.stream().map(UsageTools::voice).toList(), null, null, null);
            case SMS -> new UsageResult("OK", billed.toString(), usageType.name(),
                    usage.stream().map(UsageTools::sms).toList(), null, null, null);
            case ROAMING -> withDetail(new UsageResult("OK", billed.toString(), usageType.name(), null,
                    billing.roamingUsage(account, billed)
                        .stream()
                        .map(r -> new Country(r.countryCode(), r.usagePeriod().toString(), r.firstDay().toString(),
                                r.lastDay().toString(), plain(r.dataMb()), plain(r.voiceMin()), r.smsCount(),
                                Amount.exclGst(r.charge())))
                        .toList(),
                    null, null), account, billed, ROAMING_KINDS);
        };
    }

    private UsageResult withDetail(UsageResult result, AccountId account, BillPeriod billed, Set<UsageKind> kinds) {
        List<UsageSession> sessions;
        try {
            sessions = usageGateway.usageSessions(account, billed);
        }
        catch (BssUnavailableException e) {
            return new UsageResult(result.status(), result.billPeriod(), result.type(), result.periods(),
                    result.countries(), false, null);
        }
        Map<String, Day> days = new TreeMap<>();
        for (UsageSession s : sessions) {
            if (!kinds.contains(s.kind())) {
                continue;
            }
            String key = s.usageDate() + "|" + (s.countryCode() == null ? "" : s.countryCode());
            days.merge(key, Day.of(s), Day::plus);
        }
        List<DayView> byDay = new ArrayList<>();
        days.values().forEach(d -> byDay.add(d.view()));
        return new UsageResult(result.status(), result.billPeriod(), result.type(), result.periods(),
                result.countries(), true, byDay);
    }

    private static Period data(UsagePeriodSummary u) {
        return new Period(u.usagePeriod().toString(), plain(u.dataMb()),
                u.dataMb().divide(MB_PER_GB, 2, RoundingMode.HALF_EVEN).toPlainString(), null, null, null,
                Amount.exclGst(u.dataCharge()));
    }

    private static Period voice(UsagePeriodSummary u) {
        return new Period(u.usagePeriod().toString(), null, null, plain(u.voiceMin()), plain(u.isdMin()), null,
                Amount.exclGst(u.voiceCharge()));
    }

    private static Period sms(UsagePeriodSummary u) {
        return new Period(u.usagePeriod().toString(), null, null, null, null, u.smsCount(),
                Amount.exclGst(u.smsCharge()));
    }

    private static String plain(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    /**
     * @param periods one entry per usage period billed on this bill (late usage has its own entry)
     * @param detailAvailable {@code false} when BSS could not provide the per-day breakdown
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UsageResult(String status, String billPeriod, String type, List<Period> periods,
            List<Country> countries, Boolean detailAvailable, List<DayView> byDay) implements ToolResult {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Period(String usagePeriod, String dataMb, String dataGb, String domesticMinutes,
            String internationalMinutes, Integer smsCount, Amount charge) {
    }

    public record Country(String countryCode, String usagePeriod, String firstDay, String lastDay, String dataMb,
            String voiceMinutes, int smsCount, Amount charge) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DayView(String date, String countryCode, String dataMb, String voiceMinutes, Integer smsCount,
            Amount charge) {
    }

    /** Daily sums of the sessions of one day (and country, for roaming). */
    private record Day(String date, String countryCode, BigDecimal dataMb, BigDecimal voiceMin, int sms,
            Money charge) {

        static Day of(UsageSession s) {
            BigDecimal mb = isData(s.kind()) ? s.quantity() : BigDecimal.ZERO;
            BigDecimal min = isVoice(s.kind()) ? s.quantity() : BigDecimal.ZERO;
            int sms = isSms(s.kind()) ? s.quantity().intValueExact() : 0;
            return new Day(s.usageDate().toString(), s.countryCode(), mb, min, sms, s.charge());
        }

        Day plus(Day o) {
            return new Day(date, countryCode, dataMb.add(o.dataMb), voiceMin.add(o.voiceMin), sms + o.sms,
                    charge.plus(o.charge));
        }

        DayView view() {
            return new DayView(date, countryCode, dataMb.signum() == 0 ? null : plain(dataMb),
                    voiceMin.signum() == 0 ? null : plain(voiceMin), sms == 0 ? null : sms, Amount.exclGst(charge));
        }

        private static boolean isData(UsageKind k) {
            return k == UsageKind.DATA || k == UsageKind.ROAMING_DATA;
        }

        private static boolean isVoice(UsageKind k) {
            return k == UsageKind.VOICE || k == UsageKind.ISD || k == UsageKind.ROAMING_VOICE;
        }

        private static boolean isSms(UsageKind k) {
            return k == UsageKind.SMS || k == UsageKind.ROAMING_SMS;
        }
    }
}
