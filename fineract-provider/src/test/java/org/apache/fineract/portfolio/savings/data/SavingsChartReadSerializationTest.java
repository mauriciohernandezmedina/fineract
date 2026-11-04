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
package org.apache.fineract.portfolio.savings.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import org.apache.fineract.organisation.monetary.data.CurrencyData;
import org.apache.fineract.portfolio.interestratechart.data.InterestRateChartData;
import org.apache.fineract.portfolio.interestratechart.data.InterestRateChartSlabData;
import org.junit.jupiter.api.Test;

class SavingsChartReadSerializationTest {

    @Test
    void shouldPreserveChartsAcrossSavingsProductDataWithMethodsAndSerializeIsPrimaryGroupingByAmount() throws Exception {
        final CurrencyData mxn = new CurrencyData("MXN", "Mexican Peso", 2, 1, "$", "currency.MXN");
        final InterestRateChartSlabData slab = InterestRateChartSlabData.instance(1L, "Nivel 1", null, null, null, new BigDecimal("0"),
                new BigDecimal("10000"), new BigDecimal("4.50"), mxn);
        final InterestRateChartData chart = InterestRateChartData.withSlabs(
                InterestRateChartData.instance(10L, "Chart Ahorro", "Por monto", LocalDate.of(2026, 1, 1), null, true, 1L, "Ahorro Plus"),
                List.of(slab));

        SavingsProductData productData = SavingsProductData.lookup(1L, "Ahorro Plus");
        productData.setCharts(List.of(chart));
        productData.setInterestRateCharts(List.of(chart));
        productData.setActiveChart(chart);

        productData = SavingsProductData.withCharges(productData, Collections.emptyList());
        assertNotNull(productData.getCharts());
        assertNotNull(productData.getInterestRateCharts());
        assertEquals(chart, productData.getActiveChart());

        final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        final String json = mapper.writeValueAsString(chart);
        assertTrue(json.contains("\"isPrimaryGroupingByAmount\":true"));
        assertTrue(json.contains("\"amountRangeFrom\":0"));
        assertTrue(json.contains("\"annualInterestRate\":4.50"));
    }

    @Test
    void shouldPreserveAccountChartInSavingsAccountDataAndSerializeViaJackson() throws Exception {
        final CurrencyData mxn = new CurrencyData("MXN", "Mexican Peso", 2, 1, "$", "currency.MXN");
        final DepositAccountInterestRateChartSlabData slab = DepositAccountInterestRateChartSlabData.instance(1L, "Nivel 1", null, null,
                null, new BigDecimal("0"), new BigDecimal("10000"), new BigDecimal("5.25"), mxn);
        final DepositAccountInterestRateChartData accountChart = DepositAccountInterestRateChartData.instance(20L, "Chart Cuenta",
                "Clonado", LocalDate.of(2026, 1, 1), null, true, 100L, "000000100", List.of(slab), Collections.emptyList());

        SavingsAccountData accountData = SavingsAccountData.lookup(100L, "000000100", null);
        accountData.setAccountChart(accountChart);

        final SavingsAccountData merged = SavingsAccountData.withTemplateOptions(accountData, null, null, null);
        assertNotNull(merged.getAccountChart());

        final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        final String json = mapper.writeValueAsString(merged.getAccountChart());
        assertTrue(json.contains("\"isPrimaryGroupingByAmount\":true"));
        assertTrue(json.contains("\"annualInterestRate\":5.25"));
        assertTrue(json.contains("\"chartSlabs\""));
    }
}
