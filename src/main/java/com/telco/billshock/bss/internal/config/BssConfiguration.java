package com.telco.billshock.bss.internal.config;

import com.telco.billshock.bss.TaxProperties;
import com.telco.billshock.domain.GstCalculator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class BssConfiguration {

    @Bean
    GstCalculator gstCalculator(TaxProperties taxProperties) {
        return new GstCalculator(taxProperties.gstRate());
    }
}
