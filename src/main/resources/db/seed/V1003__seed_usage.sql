-- Seed data (dev and test profiles only; location db/seed, A-82).
-- Source of truth: docs/03-development/seed-scenarios.md (approved 2026-09-25). Every amount
-- below must match that document; SeedDataIT recomputes GST and totals.
-- Bill-period usage aggregates for all 7 billed periods (seed-scenarios.md §4, §5).
-- usage_period = billed_period everywhere: no late usage in the MVP seed (scenario 7 comes in 6b).
-- Charges equal the matching line items (ADR-007 §6): DATA -> data_charge, VOICE -> voice_charge,
-- ROAMING -> roaming_charge. Domestic voice and SMS are within allowance.
INSERT INTO usage_period (account_id, billed_period, usage_period, data_mb, data_charge, voice_min, voice_charge,
                          sms_count, sms_charge, roaming_data_mb, roaming_voice_min, roaming_sms_count,
                          roaming_charge, source_row_count, rolled_up_at) VALUES
    (1001, DATE '2026-03-01', DATE '2026-03-01', 114688, 0.00, 340, 0.00, 23, 0.00, 0, 0, 0, 0.00, 0, TIMESTAMPTZ '2026-03-01 05:00:00+05:30'),
    (1001, DATE '2026-04-01', DATE '2026-04-01', 120832, 0.00, 347, 0.00, 24, 0.00, 0, 0, 0, 0.00, 0, TIMESTAMPTZ '2026-04-01 05:00:00+05:30'),
    (1001, DATE '2026-05-01', DATE '2026-05-01', 112640, 0.00, 354, 0.00, 25, 0.00, 0, 0, 0, 0.00, 0, TIMESTAMPTZ '2026-05-01 05:00:00+05:30'),
    (1001, DATE '2026-06-01', DATE '2026-06-01', 123904, 0.00, 361, 0.00, 26, 0.00, 0, 0, 0, 0.00, 0, TIMESTAMPTZ '2026-06-01 05:00:00+05:30'),
    (1001, DATE '2026-07-01', DATE '2026-07-01', 117760, 0.00, 368, 0.00, 27, 0.00, 0, 0, 0, 0.00, 0, TIMESTAMPTZ '2026-07-01 05:00:00+05:30'),
    (1001, DATE '2026-08-01', DATE '2026-08-01', 121856, 0.00, 375, 0.00, 28, 0.00, 0, 0, 0, 0.00, 31, TIMESTAMPTZ '2026-08-01 05:00:00+05:30'),
    (1001, DATE '2026-09-01', DATE '2026-09-01', 116736, 0.00, 382, 0.00, 29, 0.00, 550, 10, 3, 1775.00, 31, TIMESTAMPTZ '2026-09-01 05:00:00+05:30'),
    (1002, DATE '2026-03-01', DATE '2026-03-01', 31744, 0.00, 380, 0.00, 26, 0.00, 0, 0, 0, 0.00, 0, TIMESTAMPTZ '2026-03-06 05:00:00+05:30'),
    (1002, DATE '2026-04-01', DATE '2026-04-01', 33792, 0.00, 387, 0.00, 27, 0.00, 0, 0, 0, 0.00, 0, TIMESTAMPTZ '2026-04-06 05:00:00+05:30'),
    (1002, DATE '2026-05-01', DATE '2026-05-01', 35840, 0.00, 394, 0.00, 28, 0.00, 0, 0, 0, 0.00, 0, TIMESTAMPTZ '2026-05-06 05:00:00+05:30'),
    (1002, DATE '2026-06-01', DATE '2026-06-01', 36864, 0.00, 401, 0.00, 29, 0.00, 0, 0, 0, 0.00, 0, TIMESTAMPTZ '2026-06-06 05:00:00+05:30'),
    (1002, DATE '2026-07-01', DATE '2026-07-01', 38912, 0.00, 408, 0.00, 30, 0.00, 0, 0, 0, 0.00, 0, TIMESTAMPTZ '2026-07-06 05:00:00+05:30'),
    (1002, DATE '2026-08-01', DATE '2026-08-01', 44032, 61.44, 415, 0.00, 31, 0.00, 0, 0, 0, 0.00, 31, TIMESTAMPTZ '2026-08-06 05:00:00+05:30'),
    (1002, DATE '2026-09-01', DATE '2026-09-01', 59392, 368.64, 422, 0.00, 32, 0.00, 0, 0, 0, 0.00, 31, TIMESTAMPTZ '2026-09-06 05:00:00+05:30'),
    (1003, DATE '2026-03-01', DATE '2026-03-01', 56320, 0.00, 420, 0.00, 29, 0.00, 0, 0, 0, 0.00, 0, TIMESTAMPTZ '2026-03-11 05:00:00+05:30'),
    (1003, DATE '2026-04-01', DATE '2026-04-01', 59392, 0.00, 427, 0.00, 30, 0.00, 0, 0, 0, 0.00, 0, TIMESTAMPTZ '2026-04-11 05:00:00+05:30'),
    (1003, DATE '2026-05-01', DATE '2026-05-01', 62464, 0.00, 434, 0.00, 31, 0.00, 0, 0, 0, 0.00, 0, TIMESTAMPTZ '2026-05-11 05:00:00+05:30'),
    (1003, DATE '2026-06-01', DATE '2026-06-01', 58368, 0.00, 441, 0.00, 32, 0.00, 0, 0, 0, 0.00, 0, TIMESTAMPTZ '2026-06-11 05:00:00+05:30'),
    (1003, DATE '2026-07-01', DATE '2026-07-01', 64512, 0.00, 448, 0.00, 33, 0.00, 0, 0, 0, 0.00, 0, TIMESTAMPTZ '2026-07-11 05:00:00+05:30'),
    (1003, DATE '2026-08-01', DATE '2026-08-01', 61440, 0.00, 455, 0.00, 34, 0.00, 0, 0, 0, 0.00, 31, TIMESTAMPTZ '2026-08-11 05:00:00+05:30'),
    (1003, DATE '2026-09-01', DATE '2026-09-01', 63488, 0.00, 462, 0.00, 35, 0.00, 0, 0, 0, 0.00, 31, TIMESTAMPTZ '2026-09-11 05:00:00+05:30'),
    (1004, DATE '2026-03-01', DATE '2026-03-01', 36864, 0.00, 460, 0.00, 32, 0.00, 0, 0, 0, 0.00, 0, TIMESTAMPTZ '2026-03-16 05:00:00+05:30'),
    (1004, DATE '2026-04-01', DATE '2026-04-01', 37888, 0.00, 467, 0.00, 33, 0.00, 0, 0, 0, 0.00, 0, TIMESTAMPTZ '2026-04-16 05:00:00+05:30'),
    (1004, DATE '2026-05-01', DATE '2026-05-01', 38912, 0.00, 474, 0.00, 34, 0.00, 0, 0, 0, 0.00, 0, TIMESTAMPTZ '2026-05-16 05:00:00+05:30'),
    (1004, DATE '2026-06-01', DATE '2026-06-01', 36864, 0.00, 481, 0.00, 35, 0.00, 0, 0, 0, 0.00, 0, TIMESTAMPTZ '2026-06-16 05:00:00+05:30'),
    (1004, DATE '2026-07-01', DATE '2026-07-01', 39936, 0.00, 488, 0.00, 36, 0.00, 0, 0, 0, 0.00, 0, TIMESTAMPTZ '2026-07-16 05:00:00+05:30'),
    (1004, DATE '2026-08-01', DATE '2026-08-01', 37888, 0.00, 495, 0.00, 37, 0.00, 0, 0, 0, 0.00, 31, TIMESTAMPTZ '2026-08-16 05:00:00+05:30'),
    (1004, DATE '2026-09-01', DATE '2026-09-01', 39936, 0.00, 502, 0.00, 38, 0.00, 0, 0, 0, 0.00, 31, TIMESTAMPTZ '2026-09-16 05:00:00+05:30'),
    (1005, DATE '2026-03-01', DATE '2026-03-01', 88064, 0.00, 500, 0.00, 35, 0.00, 0, 0, 0, 0.00, 0, TIMESTAMPTZ '2026-03-21 05:00:00+05:30'),
    (1005, DATE '2026-04-01', DATE '2026-04-01', 92160, 0.00, 507, 0.00, 36, 0.00, 0, 0, 0, 0.00, 0, TIMESTAMPTZ '2026-04-21 05:00:00+05:30'),
    (1005, DATE '2026-05-01', DATE '2026-05-01', 90112, 0.00, 514, 0.00, 37, 0.00, 0, 0, 0, 0.00, 0, TIMESTAMPTZ '2026-05-21 05:00:00+05:30'),
    (1005, DATE '2026-06-01', DATE '2026-06-01', 95232, 0.00, 521, 0.00, 38, 0.00, 0, 0, 0, 0.00, 0, TIMESTAMPTZ '2026-06-21 05:00:00+05:30'),
    (1005, DATE '2026-07-01', DATE '2026-07-01', 93184, 0.00, 528, 0.00, 39, 0.00, 0, 0, 0, 0.00, 0, TIMESTAMPTZ '2026-07-21 05:00:00+05:30'),
    (1005, DATE '2026-08-01', DATE '2026-08-01', 97280, 0.00, 535, 0.00, 40, 0.00, 0, 0, 0, 0.00, 31, TIMESTAMPTZ '2026-08-21 05:00:00+05:30'),
    (1005, DATE '2026-09-01', DATE '2026-09-01', 96256, 0.00, 542, 0.00, 41, 0.00, 0, 0, 0, 0.00, 31, TIMESTAMPTZ '2026-09-21 05:00:00+05:30'),
    (1006, DATE '2026-03-01', DATE '2026-03-01', 63488, 0.00, 542, 12.00, 38, 0.00, 0, 0, 0, 0.00, 0, TIMESTAMPTZ '2026-03-01 05:00:00+05:30'),
    (1006, DATE '2026-04-01', DATE '2026-04-01', 66560, 0.00, 550, 18.00, 39, 0.00, 0, 0, 0, 0.00, 0, TIMESTAMPTZ '2026-04-01 05:00:00+05:30'),
    (1006, DATE '2026-05-01', DATE '2026-05-01', 69632, 0.00, 555, 6.00, 40, 0.00, 0, 0, 0, 0.00, 0, TIMESTAMPTZ '2026-05-01 05:00:00+05:30'),
    (1006, DATE '2026-06-01', DATE '2026-06-01', 65536, 0.00, 565, 24.00, 41, 0.00, 0, 0, 0, 0.00, 0, TIMESTAMPTZ '2026-06-01 05:00:00+05:30'),
    (1006, DATE '2026-07-01', DATE '2026-07-01', 67584, 0.00, 570, 12.00, 42, 0.00, 0, 0, 0, 0.00, 0, TIMESTAMPTZ '2026-07-01 05:00:00+05:30'),
    (1006, DATE '2026-08-01', DATE '2026-08-01', 71680, 0.00, 578, 18.00, 43, 0.00, 0, 0, 0, 0.00, 31, TIMESTAMPTZ '2026-08-01 05:00:00+05:30'),
    (1006, DATE '2026-09-01', DATE '2026-09-01', 68608, 0.00, 586, 24.00, 44, 0.00, 0, 0, 0, 0.00, 31, TIMESTAMPTZ '2026-09-01 05:00:00+05:30');

-- Account 1001's UAE trip, 12-18 Aug 2026, billed on the September bill.
INSERT INTO usage_period_roaming (account_id, billed_period, usage_period, country_code, first_day, last_day,
                                  data_mb, voice_min, sms_count, charge) VALUES
    (1001, DATE '2026-09-01', DATE '2026-09-01', 'AE', DATE '2026-08-12', DATE '2026-08-18', 550, 10, 3, 1775.00);

INSERT INTO usage_ingest_batch (batch_id, source, checksum, status, row_count, received_at, loaded_at) VALUES
    ('SEED-2026-08', 'SEED', 'seed', 'LOADED', 0, TIMESTAMPTZ '2026-08-01 00:00:00+00', TIMESTAMPTZ '2026-08-01 00:00:00+00'),
    ('SEED-2026-09', 'SEED', 'seed', 'LOADED', 0, TIMESTAMPTZ '2026-09-01 00:00:00+00', TIMESTAMPTZ '2026-09-01 00:00:00+00');

-- Daily rows for the previous and current billed periods only (August and September 2026, A-60),
-- derived from the period rows with the A-81 rule:
--   * each quantity is split evenly across the days of the usage period in whole units,
--     with the remainder on the last day;
--   * data overage is charged in date order from the day the included allowance runs out,
--     at the seed rate of Rs 0.02/MB (A-74), so the daily charges add up to data_charge;
--   * other domestic charges (ISD voice) are placed on the last day;
--   * roaming is spread over the trip days only, rated at the GCC pay-per-use rates (A-75).
-- SeedDataIT checks that the daily rows add up to the period rows exactly.
DO $$
DECLARE
    p           record;
    r           record;
    n           integer;
    d           integer;
    usage_day   date;
    data_day    numeric;
    voice_day   numeric;
    sms_day     integer;
    cum_before  numeric;
    allowance   numeric;
    over_mb     numeric;
BEGIN
    FOR p IN
        SELECT u.*, b.period_start, b.period_end
          FROM usage_period u
          JOIN bill b ON b.account_id = u.account_id AND b.bill_period = u.billed_period
         WHERE u.billed_period IN (DATE '2026-08-01', DATE '2026-09-01')
    LOOP
        n := p.period_end - p.period_start + 1;
        allowance := p.data_mb - p.data_charge / 0.02;
        cum_before := 0;
        FOR d IN 0 .. n - 1 LOOP
            usage_day := p.period_start + d;
            IF d < n - 1 THEN
                data_day  := floor(p.data_mb / n);
                voice_day := floor(p.voice_min / n);
                sms_day   := floor(p.sms_count::numeric / n);
            ELSE
                data_day  := p.data_mb - floor(p.data_mb / n) * (n - 1);
                voice_day := p.voice_min - floor(p.voice_min / n) * (n - 1);
                sms_day   := p.sms_count - floor(p.sms_count::numeric / n)::integer * (n - 1);
            END IF;
            over_mb := greatest(0, cum_before + data_day - greatest(allowance, cum_before));
            INSERT INTO usage_daily (account_id, billed_period, usage_date, source_batch_id, usage_period,
                                     data_mb, data_charge, voice_min, voice_charge, sms_count, sms_charge)
            VALUES (p.account_id, p.billed_period, usage_day, 'SEED-' || to_char(p.billed_period, 'YYYY-MM'), p.usage_period,
                    data_day, round(over_mb * 0.02, 2), voice_day,
                    CASE WHEN d = n - 1 THEN p.voice_charge ELSE 0 END,
                    sms_day, CASE WHEN d = n - 1 THEN p.sms_charge ELSE 0 END);
            cum_before := cum_before + data_day;
        END LOOP;
    END LOOP;

    FOR r IN
        SELECT * FROM usage_period_roaming
         WHERE billed_period IN (DATE '2026-08-01', DATE '2026-09-01')
    LOOP
        n := r.last_day - r.first_day + 1;
        FOR d IN 0 .. n - 1 LOOP
            usage_day := r.first_day + d;
            IF d < n - 1 THEN
                data_day  := floor(r.data_mb / n);
                voice_day := floor(r.voice_min / n);
                sms_day   := floor(r.sms_count::numeric / n);
            ELSE
                data_day  := r.data_mb - floor(r.data_mb / n) * (n - 1);
                voice_day := r.voice_min - floor(r.voice_min / n) * (n - 1);
                sms_day   := r.sms_count - floor(r.sms_count::numeric / n)::integer * (n - 1);
            END IF;
            INSERT INTO usage_daily_roaming (account_id, billed_period, usage_date, country_code, source_batch_id,
                                             usage_period, data_mb, voice_min, sms_count, charge)
            VALUES (r.account_id, r.billed_period, usage_day, r.country_code, 'SEED-' || to_char(r.billed_period, 'YYYY-MM'),
                    r.usage_period, data_day, voice_day, sms_day,
                    data_day * 2.00 + voice_day * 60.00 + sms_day * 25.00);
            UPDATE usage_daily
               SET roaming_data_mb = roaming_data_mb + data_day,
                   roaming_voice_min = roaming_voice_min + voice_day,
                   roaming_sms_count = roaming_sms_count + sms_day,
                   roaming_charge = roaming_charge + data_day * 2.00 + voice_day * 60.00 + sms_day * 25.00
             WHERE account_id = r.account_id AND billed_period = r.billed_period AND usage_date = usage_day;
        END LOOP;
    END LOOP;

    UPDATE usage_ingest_batch b
       SET row_count = (SELECT count(*) FROM usage_daily u WHERE u.source_batch_id = b.batch_id)
     WHERE b.source = 'SEED';
END;
$$;
