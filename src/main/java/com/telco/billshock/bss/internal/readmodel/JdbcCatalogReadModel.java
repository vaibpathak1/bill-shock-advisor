package com.telco.billshock.bss.internal.readmodel;

import com.telco.billshock.bss.CatalogReadModel;
import com.telco.billshock.bss.RoamingBandProperties;
import com.telco.billshock.domain.Money;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link CatalogReadModel} over the catalogue replica tables. Plain SQL rather than JPA:
 * the tables are small, read-only and read as a whole.
 */
@Service
@Transactional(readOnly = true)
class JdbcCatalogReadModel implements CatalogReadModel {

    private final JdbcClient jdbc;
    private final RoamingBandProperties roamingBands;

    JdbcCatalogReadModel(JdbcClient jdbc, RoamingBandProperties roamingBands) {
        this.jdbc = jdbc;
        this.roamingBands = roamingBands;
    }

    @Override
    public List<Plan> plans(LocalDate onDate) {
        record Row(long planId, String code, String name, Money rental) {
        }
        List<Row> rows = jdbc.sql("""
                SELECT plan_id, code, name, monthly_rental FROM plan
                WHERE valid_from <= :d AND (valid_to IS NULL OR valid_to > :d)
                ORDER BY monthly_rental, code""")
            .param("d", onDate)
            .query((rs, i) -> new Row(rs.getLong("plan_id"), rs.getString("code"), rs.getString("name"),
                    Money.of(rs.getBigDecimal("monthly_rental"))))
            .list();
        Map<Long, List<TariffRate>> tariffs = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT t.plan_id, t.usage_type, t.band, t.included_units, t.unit_price, t.unit, t.day_based
                FROM tariff_rate t JOIN plan p ON p.plan_id = t.plan_id
                WHERE p.valid_from <= :d AND (p.valid_to IS NULL OR p.valid_to > :d)""")
            .param("d", onDate)
            .query((rs, i) -> Map.entry(rs.getLong("plan_id"),
                    new TariffRate(UsageType.valueOf(rs.getString("usage_type")), Band.valueOf(rs.getString("band")),
                            rs.getBigDecimal("included_units"), rs.getBigDecimal("unit_price"), rs.getString("unit"),
                            rs.getBoolean("day_based"))))
            .list()
            .forEach(e -> tariffs.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue()));
        return rows.stream()
            .map(r -> new Plan(r.code(), r.name(), r.rental(), tariffs.getOrDefault(r.planId(), List.of())))
            .toList();
    }

    @Override
    public List<AddOn> addOns(LocalDate onDate) {
        return jdbc.sql("""
                SELECT code, name, price, data_mb, voice_min, sms_count, validity, validity_days, country_group
                FROM add_on
                WHERE valid_from <= :d AND (valid_to IS NULL OR valid_to > :d)
                ORDER BY price, code""")
            .param("d", onDate)
            .query((rs, i) -> new AddOn(rs.getString("code"), rs.getString("name"), Money.of(rs.getBigDecimal("price")),
                    rs.getBigDecimal("data_mb"), rs.getBigDecimal("voice_min"), rs.getObject("sms_count", Integer.class),
                    Validity.valueOf(rs.getString("validity")), rs.getObject("validity_days", Integer.class),
                    rs.getString("country_group") == null ? null : Band.valueOf(rs.getString("country_group"))))
            .list();
    }

    @Override
    public Optional<Band> roamingBand(String countryCode) {
        return roamingBands.roamingBands()
            .entrySet()
            .stream()
            .filter(e -> e.getValue().contains(countryCode))
            .map(Map.Entry::getKey)
            .findFirst();
    }
}
