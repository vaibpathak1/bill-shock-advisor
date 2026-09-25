package com.telco.billshock.bss.internal.readmodel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Read-only mapping of {@code usage_period} (ADR-007). */
@Entity
@Immutable
@Table(name = "usage_period")
@IdClass(UsagePeriodKey.class)
class UsagePeriodEntity {

    @Id
    @Column(name = "account_id")
    private long accountId;

    @Id
    @Column(name = "billed_period")
    private LocalDate billedPeriod;

    @Id
    @Column(name = "usage_period")
    private LocalDate usagePeriod;

    @Column(name = "data_mb")
    private BigDecimal dataMb;

    @Column(name = "data_charge")
    private BigDecimal dataCharge;

    @Column(name = "voice_min")
    private BigDecimal voiceMin;

    @Column(name = "voice_charge")
    private BigDecimal voiceCharge;

    @Column(name = "sms_count")
    private int smsCount;

    @Column(name = "sms_charge")
    private BigDecimal smsCharge;

    @Column(name = "roaming_data_mb")
    private BigDecimal roamingDataMb;

    @Column(name = "roaming_voice_min")
    private BigDecimal roamingVoiceMin;

    @Column(name = "roaming_sms_count")
    private int roamingSmsCount;

    @Column(name = "roaming_charge")
    private BigDecimal roamingCharge;

    protected UsagePeriodEntity() {
    }

    long getAccountId() {
        return accountId;
    }

    LocalDate getBilledPeriod() {
        return billedPeriod;
    }

    LocalDate getUsagePeriod() {
        return usagePeriod;
    }

    BigDecimal getDataMb() {
        return dataMb;
    }

    BigDecimal getDataCharge() {
        return dataCharge;
    }

    BigDecimal getVoiceMin() {
        return voiceMin;
    }

    BigDecimal getVoiceCharge() {
        return voiceCharge;
    }

    int getSmsCount() {
        return smsCount;
    }

    BigDecimal getSmsCharge() {
        return smsCharge;
    }

    BigDecimal getRoamingDataMb() {
        return roamingDataMb;
    }

    BigDecimal getRoamingVoiceMin() {
        return roamingVoiceMin;
    }

    int getRoamingSmsCount() {
        return roamingSmsCount;
    }

    BigDecimal getRoamingCharge() {
        return roamingCharge;
    }
}
