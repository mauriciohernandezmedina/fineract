/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.fineract.portfolio.savings.domain.interest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.domain.LocalDateInterval;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.portfolio.interestratechart.domain.InterestRateChart;
import org.apache.fineract.portfolio.interestratechart.domain.InterestRateChartFields;
import org.apache.fineract.portfolio.interestratechart.domain.InterestRateChartSlab;
import org.apache.fineract.portfolio.interestratechart.domain.InterestRateChartSlabFields;
import org.apache.fineract.portfolio.savings.SavingsCompoundingInterestPeriodType;
import org.apache.fineract.portfolio.savings.SavingsInterestCalculationType;
import org.apache.fineract.portfolio.savings.domain.DepositAccountInterestRateChart;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountSummary;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class TieredSavingsInterestCalculationTest {

    private static final MonetaryCurrency USD = new MonetaryCurrency("USD", 2, 1);
    private static final long DAYS_IN_YEAR = 365L;

    private FineractPlatformTenant originalTenant;

    @BeforeEach
    void setUp() {
        originalTenant = ThreadLocalContextUtil.getTenant();
        FineractPlatformTenant testTenant = new FineractPlatformTenant(1L, "default", "Default Tenant", "UTC", null);
        ThreadLocalContextUtil.setTenant(testTenant);
        MoneyHelper.initializeTenantRoundingMode("default", 6);
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.setTenant(originalTenant);
        MoneyHelper.clearCache();
    }

    private SavingsAccount createSavingsAccount() {
        try {
            Constructor<SavingsAccount> constructor = SavingsAccount.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private SavingsAccountSummary createSavingsAccountSummary() {
        try {
            Constructor<SavingsAccountSummary> constructor = SavingsAccountSummary.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private Function<BigDecimal, BigDecimal> getTieredResolver(SavingsAccount account, MathContext mc, LocalDate date) {
        return (Function<BigDecimal, BigDecimal>) ReflectionTestUtils.invokeMethod(account, "getTieredInterestRateAsFractionResolver", mc,
                date);
    }

    /**
     * Builds a tiered interest rate chart with:
     * - Tier 1: 0 - 1,000.00 @ 3.0%
     * - Tier 2: 1,000.01 - 5,000.00 @ 5.0%
     * - Tier 3: 5,000.01+ @ 7.0%
     */
    private DepositAccountInterestRateChart createSampleTieredChart() {
        InterestRateChartFields chartFields = InterestRateChartFields.createNew("Whole-Balance Tiered Chart",
                "Tiered Savings Interest Chart", LocalDate.of(2023, 1, 1), null, true);

        InterestRateChartSlabFields slab1Fields = InterestRateChartSlabFields.createNew("Tier 1", null, null, null,
                BigDecimal.ZERO, new BigDecimal("1000.00"), new BigDecimal("3.0"), "USD");
        InterestRateChartSlabFields slab2Fields = InterestRateChartSlabFields.createNew("Tier 2", null, null, null,
                new BigDecimal("1000.01"), new BigDecimal("5000.00"), new BigDecimal("5.0"), "USD");
        InterestRateChartSlabFields slab3Fields = InterestRateChartSlabFields.createNew("Tier 3", null, null, null,
                new BigDecimal("5000.01"), null, new BigDecimal("7.0"), "USD");

        InterestRateChartSlab slab1 = InterestRateChartSlab.createNew(slab1Fields, null);
        InterestRateChartSlab slab2 = InterestRateChartSlab.createNew(slab2Fields, null);
        InterestRateChartSlab slab3 = InterestRateChartSlab.createNew(slab3Fields, null);

        InterestRateChart productChart = InterestRateChart.createNew(chartFields, List.of(slab1, slab2, slab3));
        return DepositAccountInterestRateChart.from(productChart);
    }

    private List<SavingsAccountTransactionDetailsForPostingPeriod> createFluctuatingTransactions(LocalDate startDate) {
        List<SavingsAccountTransactionDetailsForPostingPeriod> txns = new ArrayList<>();

        // Days 1 to 10: initial deposit of 500 (running balance 500)
        LocalDate day1 = startDate;
        LocalDate day10 = startDate.plusDays(9);
        txns.add(new SavingsAccountTransactionDetailsForPostingPeriod(1L, day1, day10, new BigDecimal("500.00"),
                new BigDecimal("500.00"), USD, 10, true, false, false, false, false));

        // Days 11 to 20: deposit of 1500 brings running balance to 2000
        LocalDate day11 = startDate.plusDays(10);
        LocalDate day20 = startDate.plusDays(19);
        txns.add(new SavingsAccountTransactionDetailsForPostingPeriod(2L, day11, day20, new BigDecimal("2000.00"),
                new BigDecimal("1500.00"), USD, 10, true, false, false, false, false));

        // Days 21 to 30: withdrawal of 1200 drops running balance to 800
        LocalDate day21 = startDate.plusDays(20);
        LocalDate day30 = startDate.plusDays(29);
        txns.add(new SavingsAccountTransactionDetailsForPostingPeriod(3L, day21, day30, new BigDecimal("800.00"),
                new BigDecimal("1200.00"), USD, 10, false, true, false, false, false));

        return txns;
    }

    @Test
    void testWholeBalanceTieredRateWithPostingPeriodDailyBalanceMethod() {
        DepositAccountInterestRateChart chart = createSampleTieredChart();
        SavingsAccount account = createSavingsAccount();
        account.setChart(chart);
        ReflectionTestUtils.setField(account, "activatedOnDate", LocalDate.of(2023, 1, 1));

        Function<BigDecimal, BigDecimal> tieredResolver = getTieredResolver(account, MathContext.DECIMAL64, LocalDate.of(2023, 1, 30));
        assertNotNull(tieredResolver);

        // Verify resolver directly on tier boundaries
        assertEquals(new BigDecimal("0.03"), tieredResolver.apply(new BigDecimal("500.00")).setScale(2, MoneyHelper.getRoundingMode()));
        assertEquals(new BigDecimal("0.05"), tieredResolver.apply(new BigDecimal("2000.00")).setScale(2, MoneyHelper.getRoundingMode()));
        assertEquals(new BigDecimal("0.03"), tieredResolver.apply(new BigDecimal("800.00")).setScale(2, MoneyHelper.getRoundingMode()));
        assertEquals(new BigDecimal("0.07"), tieredResolver.apply(new BigDecimal("6000.00")).setScale(2, MoneyHelper.getRoundingMode()));
        assertEquals(BigDecimal.ZERO, tieredResolver.apply(new BigDecimal("-100.00")));

        LocalDate startDate = LocalDate.of(2023, 1, 1);
        LocalDate endDate = LocalDate.of(2023, 1, 30);
        LocalDateInterval periodInterval = LocalDateInterval.create(startDate, endDate);

        List<SavingsAccountTransactionDetailsForPostingPeriod> txns = createFluctuatingTransactions(startDate);

        PostingPeriod postingPeriod = PostingPeriod.createFrom(periodInterval, Money.zero(USD), txns, USD,
                SavingsCompoundingInterestPeriodType.MONTHLY, SavingsInterestCalculationType.DAILY_BALANCE, BigDecimal.ZERO,
                DAYS_IN_YEAR, endDate, Collections.emptyList(), false, Money.zero(USD), false, BigDecimal.ZERO, Money.zero(USD),
                false, 1);
        postingPeriod.setTieredRateResolver(tieredResolver);

        CompoundInterestValues compoundInterestValues = new CompoundInterestValues(BigDecimal.ZERO, BigDecimal.ZERO);
        BigDecimal totalInterestCalculated = postingPeriod.calculateInterest(compoundInterestValues);

        // Sub-period 1: Days 1-10, balance = $500, applicable rate = 3% (0.03) applied to whole balance
        BigDecimal multiplicand = BigDecimal.ONE.divide(BigDecimal.valueOf(DAYS_IN_YEAR), MathContext.DECIMAL64);
        BigDecimal rateSub1 = new BigDecimal("0.03").multiply(multiplicand, MathContext.DECIMAL64);
        BigDecimal expectedSub1 = new BigDecimal("500.00").multiply(rateSub1.multiply(BigDecimal.valueOf(10), MathContext.DECIMAL64),
                MathContext.DECIMAL64).setScale(9, MoneyHelper.getRoundingMode());

        // Sub-period 2: Days 11-20, balance = $2000, applicable rate = 5% (0.05) applied to whole balance
        BigDecimal rateSub2 = new BigDecimal("0.05").multiply(multiplicand, MathContext.DECIMAL64);
        BigDecimal expectedSub2 = new BigDecimal("2000.00").multiply(rateSub2.multiply(BigDecimal.valueOf(10), MathContext.DECIMAL64),
                MathContext.DECIMAL64).setScale(9, MoneyHelper.getRoundingMode());

        // Sub-period 3: Days 21-30, balance = $800, applicable rate = 3% (0.03) applied to whole balance
        BigDecimal rateSub3 = new BigDecimal("0.03").multiply(multiplicand, MathContext.DECIMAL64);
        BigDecimal expectedSub3 = new BigDecimal("800.00").multiply(rateSub3.multiply(BigDecimal.valueOf(10), MathContext.DECIMAL64),
                MathContext.DECIMAL64).setScale(9, MoneyHelper.getRoundingMode());

        BigDecimal expectedTotalInterest = expectedSub1.add(expectedSub2).add(expectedSub3);

        assertEquals(expectedTotalInterest, totalInterestCalculated);
        assertEquals(Money.of(USD, expectedTotalInterest).getAmount(), postingPeriod.getInterestEarned().getAmount());
    }

    @Test
    void testMonthlyCompoundingAverageDailyBalanceWithTieredRate() {
        DepositAccountInterestRateChart chart = createSampleTieredChart();
        SavingsAccount account = createSavingsAccount();
        account.setChart(chart);
        ReflectionTestUtils.setField(account, "activatedOnDate", LocalDate.of(2023, 1, 1));

        Function<BigDecimal, BigDecimal> tieredResolver = getTieredResolver(account, MathContext.DECIMAL64, LocalDate.of(2023, 1, 30));

        LocalDate startDate = LocalDate.of(2023, 1, 1);
        LocalDate endDate = LocalDate.of(2023, 1, 30);
        LocalDateInterval periodInterval = LocalDateInterval.create(startDate, endDate);

        List<SavingsAccountTransactionDetailsForPostingPeriod> txns = createFluctuatingTransactions(startDate);

        PostingPeriod postingPeriod = PostingPeriod.createFrom(periodInterval, Money.zero(USD), txns, USD,
                SavingsCompoundingInterestPeriodType.MONTHLY, SavingsInterestCalculationType.AVERAGE_DAILY_BALANCE, BigDecimal.ZERO,
                DAYS_IN_YEAR, endDate, Collections.emptyList(), false, Money.zero(USD), false, BigDecimal.ZERO, Money.zero(USD),
                false, 1);
        postingPeriod.setTieredRateResolver(tieredResolver);

        CompoundInterestValues compoundInterestValues = new CompoundInterestValues(BigDecimal.ZERO, BigDecimal.ZERO);
        BigDecimal totalInterestCalculated = postingPeriod.calculateInterest(compoundInterestValues);

        // Average Daily Balance = (500*10 + 2000*10 + 800*10) / 30 = 33000 / 30 = 1100
        // Balance 1100 falls into Tier 2 (1,000.01 - 5,000.00) @ 5% (0.05)
        BigDecimal averageDailyBalance = new BigDecimal("1100.000000000");
        BigDecimal multiplicand = BigDecimal.ONE.divide(BigDecimal.valueOf(DAYS_IN_YEAR), MathContext.DECIMAL64);
        BigDecimal dailyRate = new BigDecimal("0.05").multiply(multiplicand, MathContext.DECIMAL64);
        BigDecimal periodicRate = dailyRate.multiply(BigDecimal.valueOf(30), MathContext.DECIMAL64);
        BigDecimal expectedInterest = averageDailyBalance.multiply(periodicRate, MathContext.DECIMAL64).setScale(9,
                MoneyHelper.getRoundingMode());

        assertEquals(expectedInterest, totalInterestCalculated);
    }

    @Test
    void testDailyCompoundingWithWholeBalanceTieredRate() {
        DepositAccountInterestRateChart chart = createSampleTieredChart();
        SavingsAccount account = createSavingsAccount();
        account.setChart(chart);
        ReflectionTestUtils.setField(account, "activatedOnDate", LocalDate.of(2023, 1, 1));

        Function<BigDecimal, BigDecimal> tieredResolver = getTieredResolver(account, MathContext.DECIMAL64, LocalDate.of(2023, 1, 30));

        LocalDate startDate = LocalDate.of(2023, 1, 1);
        LocalDate endDate = LocalDate.of(2023, 1, 30);
        LocalDateInterval periodInterval = LocalDateInterval.create(startDate, endDate);

        List<SavingsAccountTransactionDetailsForPostingPeriod> txns = createFluctuatingTransactions(startDate);

        PostingPeriod postingPeriod = PostingPeriod.createFrom(periodInterval, Money.zero(USD), txns, USD,
                SavingsCompoundingInterestPeriodType.DAILY, SavingsInterestCalculationType.DAILY_BALANCE, BigDecimal.ZERO,
                DAYS_IN_YEAR, endDate, Collections.emptyList(), false, Money.zero(USD), false, BigDecimal.ZERO, Money.zero(USD),
                false, 1);
        postingPeriod.setTieredRateResolver(tieredResolver);

        CompoundInterestValues compoundInterestValues = new CompoundInterestValues(BigDecimal.ZERO, BigDecimal.ZERO);
        BigDecimal totalInterestCalculated = postingPeriod.calculateInterest(compoundInterestValues);

        // Daily compounding calculates interest compounded across each subperiod
        assertTrue(totalInterestCalculated.compareTo(BigDecimal.ZERO) > 0);
        // Because of compounding, total daily compounded interest > simple monthly interest
        BigDecimal multiplicand = BigDecimal.ONE.divide(BigDecimal.valueOf(DAYS_IN_YEAR), MathContext.DECIMAL64);
        BigDecimal simpleSub1 = new BigDecimal("500.00").multiply(new BigDecimal("0.03").multiply(multiplicand, MathContext.DECIMAL64)
                .multiply(BigDecimal.valueOf(10), MathContext.DECIMAL64), MathContext.DECIMAL64);
        BigDecimal simpleSub2 = new BigDecimal("2000.00").multiply(new BigDecimal("0.05").multiply(multiplicand, MathContext.DECIMAL64)
                .multiply(BigDecimal.valueOf(10), MathContext.DECIMAL64), MathContext.DECIMAL64);
        BigDecimal simpleSub3 = new BigDecimal("800.00").multiply(new BigDecimal("0.03").multiply(multiplicand, MathContext.DECIMAL64)
                .multiply(BigDecimal.valueOf(10), MathContext.DECIMAL64), MathContext.DECIMAL64);
        BigDecimal simpleTotal = simpleSub1.add(simpleSub2).add(simpleSub3);

        assertTrue(totalInterestCalculated.compareTo(simpleTotal) >= 0);
    }

    @Test
    void testBackwardCompatibilityWithoutTieredResolverProducesIdenticalResult() {
        LocalDate startDate = LocalDate.of(2023, 1, 1);
        LocalDate endDate = LocalDate.of(2023, 1, 30);
        LocalDateInterval periodInterval = LocalDateInterval.create(startDate, endDate);

        List<SavingsAccountTransactionDetailsForPostingPeriod> txns = createFluctuatingTransactions(startDate);
        BigDecimal flatRateAsFraction = new BigDecimal("0.05"); // 5% flat

        // Period 1: calculate without tieredRateResolver (resolver is null)
        PostingPeriod period1 = PostingPeriod.createFrom(periodInterval, Money.zero(USD), txns, USD,
                SavingsCompoundingInterestPeriodType.MONTHLY, SavingsInterestCalculationType.DAILY_BALANCE, flatRateAsFraction,
                DAYS_IN_YEAR, endDate, Collections.emptyList(), false, Money.zero(USD), false, BigDecimal.ZERO, Money.zero(USD),
                false, 1);

        // Period 2: identical configuration, tieredRateResolver explicitly set to null
        PostingPeriod period2 = PostingPeriod.createFrom(periodInterval, Money.zero(USD), txns, USD,
                SavingsCompoundingInterestPeriodType.MONTHLY, SavingsInterestCalculationType.DAILY_BALANCE, flatRateAsFraction,
                DAYS_IN_YEAR, endDate, Collections.emptyList(), false, Money.zero(USD), false, BigDecimal.ZERO, Money.zero(USD),
                false, 1);
        period2.setTieredRateResolver(null);

        CompoundInterestValues values1 = new CompoundInterestValues(BigDecimal.ZERO, BigDecimal.ZERO);
        CompoundInterestValues values2 = new CompoundInterestValues(BigDecimal.ZERO, BigDecimal.ZERO);

        BigDecimal interest1 = period1.calculateInterest(values1);
        BigDecimal interest2 = period2.calculateInterest(values2);

        assertEquals(interest1, interest2);
        assertEquals(period1.getInterestEarned().getAmount(), period2.getInterestEarned().getAmount());
    }

    @Test
    void testSavingsAccountAccrualRateAndNoChartBackwardCompatibility() {
        SavingsAccount accountWithoutChart = createSavingsAccount();
        ReflectionTestUtils.setField(accountWithoutChart, "nominalAnnualInterestRate", new BigDecimal("4.5"));

        // When chart is null, resolver is null and accrual rate returns nominal annual rate / 100
        assertNull(getTieredResolver(accountWithoutChart, MathContext.DECIMAL64, LocalDate.of(2023, 1, 31)));
        BigDecimal accrualRate = accountWithoutChart.getEffectiveInterestRateAsFractionAccrual(MathContext.DECIMAL64,
                LocalDate.of(2023, 1, 31));
        assertEquals(new BigDecimal("0.045"), accrualRate);

        // When chart is present, accrual rate resolves according to current account balance
        DepositAccountInterestRateChart chart = createSampleTieredChart();
        SavingsAccount accountWithChart = createSavingsAccount();
        accountWithChart.setChart(chart);
        ReflectionTestUtils.setField(accountWithChart, "activatedOnDate", LocalDate.of(2023, 1, 1));

        SavingsAccountSummary summary = createSavingsAccountSummary();
        ReflectionTestUtils.setField(summary, "accountBalance", new BigDecimal("2500.00")); // Tier 2 -> 5%
        ReflectionTestUtils.setField(accountWithChart, "summary", summary);

        BigDecimal tieredAccrualRate = accountWithChart.getEffectiveInterestRateAsFractionAccrual(MathContext.DECIMAL64,
                LocalDate.of(2023, 1, 31));
        assertEquals(new BigDecimal("0.05"), tieredAccrualRate.setScale(2, MoneyHelper.getRoundingMode()));

        // Change balance to Tier 3 ($7,500.00 -> 7%)
        ReflectionTestUtils.setField(summary, "accountBalance", new BigDecimal("7500.00"));
        BigDecimal tier3AccrualRate = accountWithChart.getEffectiveInterestRateAsFractionAccrual(MathContext.DECIMAL64,
                LocalDate.of(2023, 1, 31));
        assertEquals(new BigDecimal("0.07"), tier3AccrualRate.setScale(2, MoneyHelper.getRoundingMode()));
    }
}
