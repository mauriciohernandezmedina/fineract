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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.apache.fineract.portfolio.interestratechart.domain.InterestRateChart;
import org.apache.fineract.portfolio.interestratechart.domain.InterestRateChartFields;
import org.apache.fineract.portfolio.interestratechart.domain.InterestRateChartSlab;
import org.apache.fineract.portfolio.interestratechart.domain.InterestRateChartSlabFields;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class SavingsAccountChartCloningTest {

    private InterestRateChart createSampleProductChart() {
        InterestRateChartFields chartFields = InterestRateChartFields.createNew("Tiered Chart", "Tiered Savings Interest Chart",
                LocalDate.of(2023, 1, 1), null, true);

        InterestRateChartSlabFields slab1Fields = InterestRateChartSlabFields.createNew("Slab 1", null, null, null,
                BigDecimal.ZERO, new BigDecimal("1000.00"), new BigDecimal("3.0"), "USD");
        InterestRateChartSlabFields slab2Fields = InterestRateChartSlabFields.createNew("Slab 2", null, null, null,
                new BigDecimal("1000.01"), null, new BigDecimal("5.0"), "USD");

        InterestRateChartSlab slab1 = InterestRateChartSlab.createNew(slab1Fields, null);
        InterestRateChartSlab slab2 = InterestRateChartSlab.createNew(slab2Fields, null);

        return InterestRateChart.createNew(chartFields, List.of(slab1, slab2));
    }

    @Test
    void testDepositAccountInterestRateChartCloningFromProductChart() {
        InterestRateChart productChart = createSampleProductChart();

        DepositAccountInterestRateChart accountChart = DepositAccountInterestRateChart.from(productChart);

        assertNotNull(accountChart);
        assertTrue(accountChart.isPrimaryGroupingByAmount());
        assertEquals(LocalDate.of(2023, 1, 1), accountChart.getFromDate());
        assertNull(accountChart.getEndDate());
        assertEquals("Tiered Chart", accountChart.chartFields().getName());

        Set<DepositAccountInterestRateChartSlabs> slabs = accountChart.setOfChartSlabs();
        assertEquals(2, slabs.size());
        for (DepositAccountInterestRateChartSlabs slab : slabs) {
            assertSame(accountChart, ReflectionTestUtils.getField(slab, "depositAccountInterestRateChart"));
            assertNotNull(slab.slabFields().annualInterestRate());
            assertNotNull(slab.slabFields().getAmountRangeFrom());
        }
    }

    @Test
    void testAssociationAndBidirectionalReferenceWithSavingsAccount() {
        InterestRateChart productChart = createSampleProductChart();
        DepositAccountInterestRateChart accountChart = DepositAccountInterestRateChart.from(productChart);

        SavingsAccount account = new SavingsAccount();
        assertNull(account.getChart());

        account.setChart(accountChart);

        assertSame(accountChart, account.getChart());
        assertSame(account, accountChart.savingsAccount());

        // Also verify removing chart
        account.setChart(null);
        assertNull(account.getChart());
    }

    @Test
    void testSavingsAccountWithoutChartRemainsNullBackwardCompatibility() {
        SavingsProduct productWithoutChart = mock(SavingsProduct.class);
        when(productWithoutChart.applicableChart(LocalDate.of(2023, 1, 1))).thenReturn(null);
        when(productWithoutChart.minBalanceForInterestCalculation()).thenReturn(BigDecimal.ZERO);

        SavingsAccount account = new SavingsAccount();
        account.submittedOnDate = LocalDate.of(2023, 1, 1);
        account.nominalAnnualInterestRate = new BigDecimal("4.5");

        account.update(productWithoutChart);

        assertNull(account.getChart());
        Boolean hasCalc = ReflectionTestUtils.invokeMethod(account, "hasInterestCalculation");
        assertTrue(Boolean.TRUE.equals(hasCalc));
    }

    @Test
    void testHasInterestCalculationWithZeroRateAndChart() {
        SavingsAccount account = new SavingsAccount();
        account.nominalAnnualInterestRate = BigDecimal.ZERO;
        assertNull(account.getChart());

        Boolean hasCalcBefore = ReflectionTestUtils.invokeMethod(account, "hasInterestCalculation");
        assertFalse(Boolean.TRUE.equals(hasCalcBefore));

        InterestRateChart productChart = createSampleProductChart();
        DepositAccountInterestRateChart accountChart = DepositAccountInterestRateChart.from(productChart);
        account.setChart(accountChart);

        Boolean hasCalcAfter = ReflectionTestUtils.invokeMethod(account, "hasInterestCalculation");
        assertTrue(Boolean.TRUE.equals(hasCalcAfter));
    }

    @Test
    void testUpdateProductUpdatesChart() {
        InterestRateChart productChart = createSampleProductChart();
        SavingsProduct productWithChart = mock(SavingsProduct.class);
        LocalDate date = LocalDate.of(2023, 1, 1);
        when(productWithChart.applicableChart(date)).thenReturn(productChart);
        when(productWithChart.minBalanceForInterestCalculation()).thenReturn(BigDecimal.valueOf(100));

        SavingsAccount account = new SavingsAccount();
        account.submittedOnDate = date;

        account.update(productWithChart);

        assertNotNull(account.getChart());
        assertSame(account, account.getChart().savingsAccount());
        assertEquals(2, account.getChart().setOfChartSlabs().size());

        // Update to product without chart clears chart
        SavingsProduct productWithoutChart = mock(SavingsProduct.class);
        when(productWithoutChart.applicableChart(date)).thenReturn(null);
        account.update(productWithoutChart);

        assertNull(account.getChart());
    }
}
