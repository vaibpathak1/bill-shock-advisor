package com.telco.billshock.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.telco.billshock.bss.BillingReadModel;
import com.telco.billshock.bss.BssUnavailableException;
import com.telco.billshock.domain.AccountId;
import com.telco.billshock.security.CustomerPrincipal;
import com.telco.billshock.support.IntegrationTest;

/**
 * The read-only tools on the real seed, through the same JSON conversion the model sees
 * ({@code ToolCallback.call}). Amounts are GST-labelled display strings; identity comes only
 * from the SecurityContext (agent.md §6).
 */
@IntegrationTest
class ToolsSeedIT {

    @Autowired
    BillingTools billingTools;

    @Autowired
    UsageTools usageTools;

    @Autowired
    CatalogTools catalogTools;

    @Autowired
    BillingReadModel billing;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private Map<String, ToolCallback> tools() {
        return Arrays.stream(MethodToolCallbackProvider.builder()
            .toolObjects(billingTools, usageTools, catalogTools)
            .build()
            .getToolCallbacks()).collect(Collectors.toMap(c -> c.getToolDefinition().name(), Function.identity()));
    }

    private static void login(long account) {
        CustomerPrincipal principal = new CustomerPrincipal("cust" + account, "x", AccountId.of(account));
        SecurityContextHolder.getContext()
            .setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
    }

    private String call(long account, String tool, String json) {
        login(account);
        return tools().get(tool).call(json);
    }

    @Test
    void billSummaryIsGstLabelledAndMasksTheNumber() {
        assertThat(call(1001, "getBillSummary", "{}"))
            .contains("\"billPeriod\":\"2026-09\"")
            .contains("\"mobileNumber\":\"******1001\"")
            .doesNotContain("+915550001001")
            .contains("\"display\":\"₹2,919.32 incl. GST\"")
            .contains("CGST + SGST (intra-state)");
        assertThat(call(1002, "getBillSummary", "{\"billPeriod\":\"2026-09\"}"))
            .contains("\"display\":\"₹905.82 incl. GST\"")
            .contains("IGST (inter-state)");
    }

    @Test
    void historyIsNewestFirstWithUpToSixMonthsBeforeTheLatest() {
        String history = call(1001, "getBillHistory", "{}");
        assertThat(history.indexOf("2026-09")).isLessThan(history.indexOf("2026-03"));
        assertThat(history.split("\"billPeriod\"", -1)).hasSize(8); // 7 bills
        assertThat(call(1001, "getBillHistory", "{\"months\":2}").split("\"billPeriod\"", -1)).hasSize(4);
        assertThat(call(1001, "getBillHistory", "{\"months\":7}")).contains("INVALID_ARGUMENT");
    }

    @Test
    void diffBillsShowsEachCauseExclGstAndIncl() {
        assertThat(call(1002, "diffBills", "{}"))
            .contains("\"verdict\":\"MEANINGFUL_INCREASE\"")
            .contains("\"group\":\"DATA\"")
            .contains("₹348.16 excl. GST").contains("₹62.67 GST").contains("₹410.83 incl. GST")
            .contains("\"excessPercent\":\"83.0\"");
        assertThat(call(1006, "diffBills", "{}")).contains("\"verdict\":\"NORMAL\"").contains("\"causes\":[]")
            .contains("do not present causes");
    }

    @Test
    void lineItemsKeepUntrustedTextUnderItsKey() {
        String vas = call(1003, "getLineItems", "{\"category\":\"vas\"}");
        assertThat(vas).contains("\"category\":\"VAS\"").contains("\"untrusted\":{\"description\":")
            .contains("₹49.00 excl. GST").doesNotContain("\"TAX\"");
        assertThat(call(1003, "getLineItems", "{\"category\":\"TAX\"}")).contains(" GST\"").contains("\"taxComponent\":\"CGST\"");
        assertThat(call(1003, "getLineItems", "{\"category\":\"FOOD\"}")).contains("INVALID_ARGUMENT");
    }

    @Test
    void roamingUsageIsPerCountryWithADailyBreakdownFromBss() {
        String roaming = call(1001, "getUsageDetails", "{\"type\":\"ROAMING\"}");
        assertThat(roaming).contains("\"countryCode\":\"AE\"").contains("₹1,775.00 excl. GST")
            .contains("\"detailAvailable\":true").contains("\"byDay\":[{\"date\":\"2026-08-12\"");
        assertThat(call(1002, "getUsageDetails", "{\"type\":\"DATA\"}")).contains("\"dataGb\":\"58");
        assertThat(call(1002, "getUsageDetails", "{\"type\":\"FAX\"}")).contains("INVALID_ARGUMENT");
    }

    @Test
    void whenBssIsDownTheAggregatesStillComeBack() {
        UsageTools down = new UsageTools(billing, (account, period) -> {
            throw new BssUnavailableException("TMF635 down", null);
        });
        login(1001);
        String result = MethodToolCallbackProvider.builder().toolObjects(down).build().getToolCallbacks()[0]
            .call("{\"type\":\"ROAMING\"}");
        assertThat(result).contains("\"detailAvailable\":false").contains("\"countryCode\":\"AE\"").doesNotContain("byDay");
    }

    @Test
    void subscriptionsShowTheOptInEvidenceOrItsAbsence() {
        String products = call(1003, "getActiveSubscriptions", "{}");
        assertThat(products).contains("\"code\":\"ASTRO_DAILY\"").contains("\"code\":\"CRICKET_SCORES\"")
            .contains("\"name\":\"Astro Daily\"").contains("₹49 + GST").contains("₹29.50 + GST");
        String astro = products.substring(products.indexOf("ASTRO_DAILY"));
        assertThat(astro).contains("\"doubleOptIn\":false").doesNotContain("confirmationAt\":\"2026-08");
        String cricket = products.substring(products.indexOf("CRICKET_SCORES"), products.indexOf("ASTRO_DAILY"));
        assertThat(cricket).contains("\"doubleOptIn\":true");
    }

    @Test
    void catalogueSearchFilters() {
        String cheap = call(1001, "searchPlanCatalog", "{\"type\":\"PLAN\",\"maxMonthlyPriceInr\":500}");
        assertThat(cheap).contains("PP_299").contains("PP_499").doesNotContain("PP_599").contains("₹499 + GST")
            .contains("\"addOns\":[]");
        assertThat(call(1001, "searchPlanCatalog", "{\"roamingBand\":\"GCC\"}")).contains("IR_GCC_7D")
            .contains("\"plans\":[]").doesNotContain("DATA_10GB");
        assertThat(call(1001, "searchPlanCatalog", "{\"roamingBand\":\"MARS\"}")).contains("INVALID_ARGUMENT");
    }

    @Test
    void simulationShowsNewBillAndSavingAsTwoLabelledAmounts() {
        assertThat(call(1002, "simulatePlans", "{}"))
            .contains("\"status\":\"SIMULATED\"")
            .contains("\"code\":\"PP_499\"")
            .contains("\"newBill\":{\"value\":\"588.82\",\"display\":\"₹588.82 incl. GST\"}")
            .contains("\"saving\":{\"value\":\"317.00\",\"display\":\"₹317.00 incl. GST\"}")
            .contains("DATA_10GB");
        assertThat(call(1004, "simulatePlans", "{}")).contains("RECENT_PLAN_CHANGE")
            .contains("\"planChangedOn\":\"2026-09-01\"").contains("\"plans\":[]");
        assertThat(call(1006, "simulatePlans", "{}")).contains("No plan or add-on would have made this bill cheaper");
    }

    @Test
    void periodsAreValidated() {
        assertThat(call(1001, "getBillSummary", "{\"billPeriod\":\"Sept\"}")).contains("INVALID_ARGUMENT");
        assertThat(call(1001, "getBillSummary", "{\"billPeriod\":\"2025-01\"}")).contains("NOT_FOUND");
    }

    @Test
    void withoutACustomerNoToolRuns() {
        assertThatThrownBy(() -> billingTools.getBillSummary(null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> catalogTools.getActiveSubscriptions()).isInstanceOf(AccessDeniedException.class);
    }
}
