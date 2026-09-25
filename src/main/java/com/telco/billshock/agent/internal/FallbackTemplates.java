package com.telco.billshock.agent.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.stringtemplate.v4.ST;

import com.telco.billshock.analysis.BillDiff;
import com.telco.billshock.analysis.BillDiff.Cause;
import com.telco.billshock.analysis.BillDiff.Finding;
import com.telco.billshock.analysis.PlanSimulation;
import com.telco.billshock.analysis.PlanSimulation.Status;
import com.telco.billshock.domain.BillPeriod;
import com.telco.billshock.domain.InrFormat;

/**
 * Deterministic explanations rendered from the engine results (SPEC §2.6 fallback; ADR-003).
 * One code path serves the pre-fetch summary line (NFR-01a), the full fallback answer and the
 * hand-over line, so the fallback follows the same GST-label rule as the tools: every amount
 * is formatted by {@link InrFormat}.
 *
 * <p><b>Template Method:</b> {@link #answer} fixes the skeleton (summary → causes → findings →
 * recommendation); each step renders its own locale file from
 * {@code templates/fallback/<step>.<locale>.st} (StringTemplate, {@code $…$} delimiters).
 */
@Component
public class FallbackTemplates {

    private static final String LOCATION = "classpath:templates/fallback/*.en.st";
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

    private final Map<String, String> templates;

    public FallbackTemplates() {
        this.templates = load();
    }

    /** The one-line spike summary streamed before the first LLM call (SPEC §4.6). */
    public String summary(Optional<BillDiff> diff) {
        if (diff.isEmpty()) {
            return render("summary-no-bill", Map.of());
        }
        BillDiff d = diff.get();
        return switch (d.verdict()) {
            case MEANINGFUL_INCREASE -> d.causes().isEmpty()
                    ? render("summary-normal", Map.of("period", month(d.billPeriod()), "total",
                            InrFormat.inclGst(d.currentTotal())))
                    : render("summary-increase", Map.of("period", month(d.billPeriod()), "excess",
                            InrFormat.inclGst(d.totalExcess()), "count", d.baselineBillCount(), "group",
                            render("group-" + d.causes().getFirst().group().name(), Map.of()), "amount",
                            InrFormat.inclGst(d.causes().getFirst().amountInclGst())));
            case NORMAL -> render("summary-normal", Map.of("period", month(d.billPeriod()), "total",
                    InrFormat.inclGst(d.currentTotal())));
            case INSUFFICIENT_HISTORY -> render("summary-no-history", Map.of("period", month(d.billPeriod()), "total",
                    InrFormat.inclGst(d.currentTotal())));
        };
    }

    /** The full deterministic answer: the template method (see the class comment). */
    public String answer(Optional<BillDiff> diff, Optional<PlanSimulation> simulation) {
        List<String> lines = new ArrayList<>();
        lines.add(summary(diff));
        diff.ifPresent(d -> {
            lines.addAll(causes(d));
            lines.addAll(findings(d));
        });
        simulation.ifPresent(s -> lines.addAll(recommendations(s)));
        return String.join(" ", lines);
    }

    public String handover() {
        return render("handover", Map.of());
    }

    public String unavailable() {
        return render("unavailable", Map.of());
    }

    private List<String> causes(BillDiff d) {
        if (d.verdict() != BillDiff.Verdict.MEANINGFUL_INCREASE) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (Cause c : d.causes()) {
            lines.add(render("cause-" + c.group().name(), Map.of("amount", InrFormat.inclGst(c.amountInclGst()))));
        }
        return lines;
    }

    private List<String> findings(BillDiff d) {
        List<String> lines = new ArrayList<>();
        for (Finding f : d.findings()) {
            Map<String, Object> params = new HashMap<>();
            switch (f.type()) {
                case DUPLICATE_CHARGE -> {
                    params.put("first", f.lineItemIds().getFirst());
                    params.put("repeat", f.lineItemIds().get(f.lineItemIds().size() - 1));
                }
                case NEW_SUBSCRIPTION_CHARGE -> params.put("subscription", String.valueOf(f.subscriptionId()));
                case PLAN_CHANGE_PRORATION -> {
                }
            }
            lines.add(render("finding-" + f.type().name(), params));
        }
        return lines;
    }

    private List<String> recommendations(PlanSimulation s) {
        if (s.status() == Status.RECENT_PLAN_CHANGE) {
            return List.of(render("recommend-recent-plan-change",
                    Map.of("date", s.recentPlanChange().effectiveDate().toString())));
        }
        if (s.status() != Status.SIMULATED) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        if (!s.plans().isEmpty()) {
            PlanSimulation.PlanOption p = s.plans().getFirst();
            lines.add(render("recommend-plan", Map.of("name", p.planName(), "code", p.planCode(), "newBill",
                    InrFormat.inclGst(p.totalInclGst()), "saving", InrFormat.inclGst(p.savingInclGst()))));
        }
        if (s.bestAddOn() != null) {
            PlanSimulation.AddOnOption a = s.bestAddOn();
            lines.add(render("recommend-addon", Map.of("name", a.addOnName(), "code", a.addOnCode(), "newBill",
                    InrFormat.inclGst(a.totalInclGst()), "saving", InrFormat.inclGst(a.savingInclGst()))));
        }
        return lines;
    }

    static String month(BillPeriod period) {
        return YearMonth.from(period.firstDay()).format(MONTH);
    }

    private String render(String name, Map<String, ?> params) {
        String template = templates.get(name);
        if (template == null) {
            throw new IllegalStateException("Missing fallback template " + name);
        }
        ST st = new ST(template, '$', '$');
        params.forEach(st::add);
        return st.render();
    }

    private static Map<String, String> load() {
        try {
            Map<String, String> loaded = new HashMap<>();
            for (Resource r : new PathMatchingResourcePatternResolver().getResources(LOCATION)) {
                String file = r.getFilename();
                try (InputStream in = r.getInputStream()) {
                    loaded.put(file.substring(0, file.length() - ".en.st".length()),
                            new String(in.readAllBytes(), StandardCharsets.UTF_8).strip());
                }
            }
            return Map.copyOf(loaded);
        }
        catch (IOException e) {
            throw new IllegalStateException("Cannot load fallback templates", e);
        }
    }
}
