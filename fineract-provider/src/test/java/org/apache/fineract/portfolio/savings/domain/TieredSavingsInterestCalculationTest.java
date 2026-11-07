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
package org.apache.fineract.portfolio.savings.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.domain.LocalDateInterval;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.organisation.monetary.data.CurrencyData;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.portfolio.account.service.AccountTransfersReadPlatformService;
import org.apache.fineract.portfolio.interestratechart.domain.InterestRateChart;
import org.apache.fineract.portfolio.interestratechart.domain.InterestRateChartFields;
import org.apache.fineract.portfolio.interestratechart.domain.InterestRateChartSlab;
import org.apache.fineract.portfolio.interestratechart.domain.InterestRateChartSlabFields;
import org.apache.fineract.portfolio.savings.DepositAccountType;
import org.apache.fineract.portfolio.savings.SavingsAccountTransactionType;
import org.apache.fineract.portfolio.savings.SavingsCompoundingInterestPeriodType;
import org.apache.fineract.portfolio.savings.SavingsInterestCalculationDaysInYearType;
import org.apache.fineract.portfolio.savings.SavingsInterestCalculationType;
import org.apache.fineract.portfolio.savings.SavingsPostingInterestPeriodType;
import org.apache.fineract.portfolio.savings.data.DepositAccountInterestRateChartData;
import org.apache.fineract.portfolio.savings.data.DepositAccountInterestRateChartSlabData;
import org.apache.fineract.portfolio.savings.data.SavingsAccountData;
import org.apache.fineract.portfolio.savings.data.SavingsAccountSummaryData;
import org.apache.fineract.portfolio.savings.data.SavingsAccountTransactionData;
import org.apache.fineract.portfolio.savings.data.SavingsAccountTransactionEnumData;
import org.apache.fineract.portfolio.savings.domain.interest.PostingPeriod;
import org.apache.fineract.portfolio.savings.domain.interest.SavingsAccountTransactionDetailsForPostingPeriod;
import org.apache.fineract.portfolio.savings.service.SavingsAccountInterestPostingServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

class TieredSavingsInterestCalculationTest {

    private static final MonetaryCurrency MXN = new MonetaryCurrency("MXN", 2, null);
    private static final MathContext MC = new MathContext(15, RoundingMode.HALF_EVEN);
    private MockedStatic<MoneyHelper> moneyHelperMock;

    @BeforeEach
    void setUp() {
        moneyHelperMock = Mockito.mockStatic(MoneyHelper.class);
        moneyHelperMock.when(MoneyHelper::getMathContext).thenReturn(MC);
        moneyHelperMock.when(MoneyHelper::getRoundingMode).thenReturn(RoundingMode.HALF_EVEN);
    }

    @AfterEach
    void tearDown() {
        if (moneyHelperMock != null) {
            moneyHelperMock.close();
        }
        ThreadLocalContextUtil.reset();
    }

    private DepositAccountInterestRateChart buildWholeBalanceChart() {
        final InterestRateChartFields chartFields = InterestRateChartFields.createNew("Tabla Nivel Total", "Escalonada por saldo",
                LocalDate.of(2026, 1, 1), null, true);

        // Tramo 1: 0 a 10,000 -> 3.00% anual (sin periodType ni fromPeriod)
        final InterestRateChartSlabFields slab1Fields = InterestRateChartSlabFields.createNew("Nivel 1", null, null, null,
                new BigDecimal("0"), new BigDecimal("10000"), new BigDecimal("3.00"), "MXN");
        // Tramo 2: 10,001 a 50,000 -> 6.00% anual
        final InterestRateChartSlabFields slab2Fields = InterestRateChartSlabFields.createNew("Nivel 2", null, null, null,
                new BigDecimal("10001"), new BigDecimal("50000"), new BigDecimal("6.00"), "MXN");
        // Tramo 3: 50,001 en adelante -> 9.00% anual
        final InterestRateChartSlabFields slab3Fields = InterestRateChartSlabFields.createNew("Nivel 3", null, null, null,
                new BigDecimal("50001"), null, new BigDecimal("9.00"), "MXN");

        final Set<InterestRateChartSlab> slabs = new HashSet<>();
        slabs.add(InterestRateChartSlab.createNew(slab1Fields, null));
        slabs.add(InterestRateChartSlab.createNew(slab2Fields, null));
        slabs.add(InterestRateChartSlab.createNew(slab3Fields, null));

        final InterestRateChart productChart = InterestRateChart.createNew(chartFields, slabs);
        return DepositAccountInterestRateChart.from(productChart);
    }

    @Test
    void shouldResolveWholeBalanceTieredRateWithoutPeriodFields() {
        final DepositAccountInterestRateChart chart = buildWholeBalanceChart();
        final LocalDate calcDate = LocalDate.of(2026, 1, 31);

        assertEquals(0,
                new BigDecimal("3.00").compareTo(chart.getApplicableInterestRate(new BigDecimal("5000"), calcDate, calcDate, null)));
        assertEquals(0,
                new BigDecimal("6.00").compareTo(chart.getApplicableInterestRate(new BigDecimal("25000"), calcDate, calcDate, null)));
        assertEquals(0,
                new BigDecimal("9.00").compareTo(chart.getApplicableInterestRate(new BigDecimal("75000"), calcDate, calcDate, null)));
    }

    @Test
    void shouldCalculateDailyBalanceInterestUsingWholeBalanceTieredRate() {
        final DepositAccountInterestRateChart chart = buildWholeBalanceChart();
        final LocalDate startDate = LocalDate.of(2026, 1, 1);
        final LocalDate endDate = LocalDate.of(2026, 1, 31);
        final LocalDateInterval interval = LocalDateInterval.create(startDate, endDate);

        // Saldo inicial: 25,000 MXN -> cae íntegramente en Nivel 2 (6.00% anual sobre los 25,000 completos)
        final Money startingBalance = Money.of(MXN, new BigDecimal("25000.00"));
        final List<SavingsAccountTransactionDetailsForPostingPeriod> txns = Collections.emptyList();
        final Collection<Long> interestPostTxns = Collections.emptyList();

        final PostingPeriod postingPeriod = PostingPeriod.createFrom(interval, startingBalance, txns, MXN,
                SavingsCompoundingInterestPeriodType.MONTHLY, SavingsInterestCalculationType.DAILY_BALANCE, BigDecimal.ZERO,
                SavingsInterestCalculationDaysInYearType.DAYS_365.getValue(), endDate, interestPostTxns, false, Money.zero(MXN), false,
                BigDecimal.ZERO, Money.zero(MXN), false, 1);

        final Function<BigDecimal, BigDecimal> resolver = balance -> {
            final BigDecimal rate = chart.getApplicableInterestRate(balance, endDate, endDate, null);
            return rate != null ? rate.divide(BigDecimal.valueOf(100L), MC) : BigDecimal.ZERO;
        };
        postingPeriod.setTieredRateResolver(resolver);

        postingPeriod.calculateInterest(
                new org.apache.fineract.portfolio.savings.domain.interest.CompoundInterestValues(BigDecimal.ZERO, BigDecimal.ZERO));

        final BigDecimal earned = postingPeriod.getInterestEarned().getAmount();
        assertNotNull(earned);
        // 25,000 * 0.06 * (31 / 365) = 127.39726... -> redondeado a 2 decimales MXN = 127.40
        assertEquals(new BigDecimal("127.40"), earned);
    }

    @Test
    void shouldCalculateAverageDailyBalanceInterestUsingWholeBalanceTieredRate() {
        final DepositAccountInterestRateChart chart = buildWholeBalanceChart();
        final LocalDate startDate = LocalDate.of(2026, 1, 1);
        final LocalDate endDate = LocalDate.of(2026, 1, 31);
        final LocalDateInterval interval = LocalDateInterval.create(startDate, endDate);

        // Saldo inicial: 60,000 MXN -> cae íntegramente en Nivel 3 (9.00% anual sobre el saldo promedio)
        final Money startingBalance = Money.of(MXN, new BigDecimal("60000.00"));
        final List<SavingsAccountTransactionDetailsForPostingPeriod> txns = Collections.emptyList();
        final Collection<Long> interestPostTxns = Collections.emptyList();

        final PostingPeriod postingPeriod = PostingPeriod.createFrom(interval, startingBalance, txns, MXN,
                SavingsCompoundingInterestPeriodType.MONTHLY, SavingsInterestCalculationType.AVERAGE_DAILY_BALANCE, BigDecimal.ZERO,
                SavingsInterestCalculationDaysInYearType.DAYS_365.getValue(), endDate, interestPostTxns, false, Money.zero(MXN), false,
                BigDecimal.ZERO, Money.zero(MXN), false, 1);

        final Function<BigDecimal, BigDecimal> resolver = balance -> {
            final BigDecimal rate = chart.getApplicableInterestRate(balance, endDate, endDate, null);
            return rate != null ? rate.divide(BigDecimal.valueOf(100L), MC) : BigDecimal.ZERO;
        };
        postingPeriod.setTieredRateResolver(resolver);

        postingPeriod.calculateInterest(
                new org.apache.fineract.portfolio.savings.domain.interest.CompoundInterestValues(BigDecimal.ZERO, BigDecimal.ZERO));

        final BigDecimal earned = postingPeriod.getInterestEarned().getAmount();
        assertNotNull(earned);
        // 60,000 * 0.09 * (31 / 365) = 458.63013... -> redondeado a 2 decimales MXN = 458.63
        assertEquals(new BigDecimal("458.63"), earned);
    }

    @Test
    void shouldCalculateTieredInterestInBatchPostingServiceFromDto() {
        final DepositAccountInterestRateChartSlabData slab1 = DepositAccountInterestRateChartSlabData.instance(1L, "Nivel 1", null, null,
                null, new BigDecimal("0"), new BigDecimal("10000"), new BigDecimal("3.00"), null);
        final DepositAccountInterestRateChartSlabData slab2 = DepositAccountInterestRateChartSlabData.instance(2L, "Nivel 2", null, null,
                null, new BigDecimal("10001"), new BigDecimal("50000"), new BigDecimal("6.00"), null);
        final DepositAccountInterestRateChartSlabData slab3 = DepositAccountInterestRateChartSlabData.instance(3L, "Nivel 3", null, null,
                null, new BigDecimal("50001"), null, new BigDecimal("9.00"), null);

        final DepositAccountInterestRateChartData chartData = DepositAccountInterestRateChartData.instance(1L, "Tabla Saldo", "Escalonada",
                LocalDate.of(2026, 1, 1), null, true, 100L, "SA-100", List.of(slab1, slab2, slab3), null);

        final LocalDate startDate = LocalDate.of(2026, 1, 1);
        final LocalDate endDate = LocalDate.of(2026, 1, 31);
        ThreadLocalContextUtil.setBusinessDates(new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, endDate)));
        final CurrencyData currencyData = new CurrencyData("MXN", "Mexican Peso", 2, 0, "$", "currency.MXN");

        final AccountTransfersReadPlatformService accountTransfersReadPlatformService = Mockito
                .mock(AccountTransfersReadPlatformService.class);
        when(accountTransfersReadPlatformService.fetchPostInterestTransactionIds(any())).thenReturn(Collections.emptyList());
        final SavingsHelper savingsHelper = new SavingsHelper(accountTransfersReadPlatformService);
        final SavingsAccountInterestPostingServiceImpl postingService = new SavingsAccountInterestPostingServiceImpl(savingsHelper);

        final SavingsAccountData savingsAccountData = Mockito.mock(SavingsAccountData.class);
        final SavingsAccountSummaryData summary = new SavingsAccountSummaryData(currencyData, new BigDecimal("25000.00"), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("25000.00"), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, new BigDecimal("25000.00"), null);

        when(savingsAccountData.getCurrency()).thenReturn(currencyData);
        when(savingsAccountData.getSummary()).thenReturn(summary);
        when(savingsAccountData.getStartInterestCalculationDate()).thenReturn(startDate);
        when(savingsAccountData.getActivationLocalDate()).thenReturn(startDate);
        when(savingsAccountData.getInterestPostingPeriodTypeId()).thenReturn(SavingsPostingInterestPeriodType.MONTHLY.getValue());
        when(savingsAccountData.getInterestCompoundingPeriodTypeId()).thenReturn(SavingsCompoundingInterestPeriodType.MONTHLY.getValue());
        when(savingsAccountData.getInterestCalculationDaysInYearTypeId())
                .thenReturn(SavingsInterestCalculationDaysInYearType.DAYS_365.getValue());
        when(savingsAccountData.getInterestCalculationTypeId()).thenReturn(SavingsInterestCalculationType.DAILY_BALANCE.getValue());
        when(savingsAccountData.getNominalAnnualInterestRate()).thenReturn(BigDecimal.ZERO);
        when(savingsAccountData.getNominalAnnualInterestRateOverdraft()).thenReturn(BigDecimal.ZERO);
        when(savingsAccountData.getMinBalanceForInterestCalculation()).thenReturn(BigDecimal.ZERO);
        when(savingsAccountData.getMinOverdraftForInterestCalculation()).thenReturn(BigDecimal.ZERO);
        when(savingsAccountData.isAllowOverdraft()).thenReturn(false);
        when(savingsAccountData.getAccountChart()).thenReturn(chartData);
        when(savingsAccountData.depositAccountType()).thenReturn(DepositAccountType.SAVINGS_DEPOSIT);
        when(savingsAccountData.getSavingsAccountTransactionSummaryWrapper()).thenReturn(new SavingsAccountTransactionDataSummaryWrapper());

        final SavingsAccountTransactionEnumData depositType = new SavingsAccountTransactionEnumData(1L,
                SavingsAccountTransactionType.DEPOSIT.getCode(), "Deposit");
        final SavingsAccountTransactionData tx = SavingsAccountTransactionData.create(1L, depositType, null, 100L, "SA-100", startDate,
                currencyData, new BigDecimal("25000.00"), BigDecimal.ZERO, new BigDecimal("25000.00"), false, startDate, false,
                BigDecimal.ZERO, null);

        final List<SavingsAccountTransactionData> txList = new ArrayList<>(List.of(tx));
        when(savingsAccountData.getSavingsAccountTransactionData()).thenReturn(txList);

        final List<PostingPeriod> periods = postingService.calculateInterestUsing(MC, endDate, false, false, 1, null, false,
                savingsAccountData);

        assertNotNull(periods);
        assertEquals(1, periods.size());
        final PostingPeriod period = periods.get(0);
        final BigDecimal earned = period.getInterestEarned().getAmount();
        assertNotNull(earned);
        // 25,000 * 0.06 * (31 / 365) = 127.39726... -> redondeado a 2 decimales MXN = 127.40
        assertEquals(new BigDecimal("127.40"), earned);
    }
}
