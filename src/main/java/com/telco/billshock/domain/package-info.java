/**
 * Shared kernel: value objects used by every module ({@code Money}, {@code BillPeriod},
 * {@code AccountId}) and the GST rule (A-72). Open module: any module may use it.
 */
@ApplicationModule(type = ApplicationModule.Type.OPEN)
package com.telco.billshock.domain;

import org.springframework.modulith.ApplicationModule;
