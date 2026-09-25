package com.telco.billshock.bss;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.util.Set;

/**
 * GST configuration (A-72, A-73; FOR TAX REVIEW).
 *
 * @param supplierStateCodes GST state codes in which the operator holds a GST registration,
 *        for example {@code [27]}. A bill whose place of supply is one of them is intra-state
 * @param gstRate the full GST rate in percent, for example {@code 18.00}
 */
@Validated
@ConfigurationProperties("billshock.tax")
public record TaxProperties(
        @NotEmpty Set<@Pattern(regexp = "[0-9]{2}") String> supplierStateCodes,
        @NotNull @DecimalMin("0.00") BigDecimal gstRate) {

    public TaxProperties {
        supplierStateCodes = supplierStateCodes == null ? null : Set.copyOf(supplierStateCodes);
    }
}
