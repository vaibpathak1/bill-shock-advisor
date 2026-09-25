package com.telco.billshock.bss.internal.readmodel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Read-only mapping of {@code usage_period_roaming}: roaming by country per bill period (ADR-007). */
@Entity
@Immutable
@Table(name = "usage_period_roaming")
@IdClass(UsagePeriodRoamingKey.class)
class UsagePeriodRoamingEntity {

    @Id
    @Column(name = "account_id")
    private long accountId;

    @Id
    @Column(name = "billed_period")
    private LocalDate billedPeriod;

    @Id
    @Column(name = "usage_period")
    private LocalDate usagePeriod;

    @Id
    @Column(name = "country_code")
    private String countryCode;

    @Column(name = "first_day")
    private LocalDate firstDay;

    @Column(name = "last_day")
    private LocalDate lastDay;

    @Column(name = "data_mb")
    private BigDecimal dataMb;

    @Column(name = "voice_min")
    private BigDecimal voiceMin;

    @Column(name = "sms_count")
    private int smsCount;

    @Column(name = "charge")
    private BigDecimal charge;

    protected UsagePeriodRoamingEntity() {
    }

    LocalDate getBilledPeriod() {
        return billedPeriod;
    }

    LocalDate getUsagePeriod() {
        return usagePeriod;
    }

    String getCountryCode() {
        return countryCode;
    }

    LocalDate getFirstDay() {
        return firstDay;
    }

    LocalDate getLastDay() {
        return lastDay;
    }

    BigDecimal getDataMb() {
        return dataMb;
    }

    BigDecimal getVoiceMin() {
        return voiceMin;
    }

    int getSmsCount() {
        return smsCount;
    }

    BigDecimal getCharge() {
        return charge;
    }
}
