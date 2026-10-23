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
package org.apache.fineract.portfolio.savings.api;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.gson.Gson;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import org.apache.fineract.infrastructure.core.serialization.GoogleGsonSerializerHelper;
import org.apache.fineract.portfolio.interestratechart.data.InterestRateChartData;
import org.apache.fineract.portfolio.interestratechart.data.InterestRateChartSlabData;
import org.apache.fineract.portfolio.savings.data.DepositAccountInterestRateChartData;
import org.apache.fineract.portfolio.savings.data.SavingsAccountData;
import org.apache.fineract.portfolio.savings.data.SavingsProductData;
import org.junit.jupiter.api.Test;

class SavingsChartReadSerializationTest {

    private InterestRateChartData createSampleChartData() {
        final InterestRateChartSlabData slab = InterestRateChartSlabData.instance(1L, "Tier 1 Slab", null, null, null,
                BigDecimal.ZERO, new BigDecimal("1000.00"), new BigDecimal("3.0"), null);
        final InterestRateChartData chart = InterestRateChartData.instance(10L, "Sample Chart", "Tiered Savings Interest Chart",
                LocalDate.of(2023, 1, 1), null, true, 1L, "Savings Product");
        chart.chartSlabs().add(slab);
        return chart;
    }

    @Test
    void testSavingsProductDataPreservesChartsAcrossFactoryMethods() {
        final InterestRateChartData chart = createSampleChartData();
        final List<InterestRateChartData> charts = List.of(chart);

        final SavingsProductData product = SavingsProductData.lookup(1L, "Tiered Savings Product");
        product.setCharts(charts);
        product.setInterestRateCharts(charts);
        product.setActiveChart(chart);
        product.setChartTemplate(chart);

        // 1. withCharges
        final SavingsProductData withCharges = SavingsProductData.withCharges(product, Collections.emptyList());
        assertSame(charts, withCharges.getCharts());
        assertSame(charts, withCharges.getInterestRateCharts());
        assertSame(chart, withCharges.getActiveChart());
        assertSame(chart, withCharges.getChartTemplate());

        // 2. withAccountingDetails
        final SavingsProductData withAccounting = SavingsProductData.withAccountingDetails(product, null, null, null, null);
        assertSame(charts, withAccounting.getCharts());
        assertSame(charts, withAccounting.getInterestRateCharts());
        assertSame(chart, withAccounting.getActiveChart());
        assertSame(chart, withAccounting.getChartTemplate());

        // 3. withTemplate
        final SavingsProductData withTemplate = SavingsProductData.withTemplate(product, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null);
        assertSame(charts, withTemplate.getCharts());
        assertSame(charts, withTemplate.getInterestRateCharts());
        assertSame(chart, withTemplate.getActiveChart());
        assertSame(chart, withTemplate.getChartTemplate());
    }

    @Test
    void testSavingsAccountDataPreservesAccountChartAcrossTemplateOptions() {
        final InterestRateChartData productChart = createSampleChartData();
        final DepositAccountInterestRateChartData accountChart = DepositAccountInterestRateChartData.from(productChart);
        assertNotNull(accountChart);

        final SavingsAccountData account = SavingsAccountData.lookup(1L, "SA-0001", null);
        account.setAccountChart(accountChart);
        account.setChartTemplate(accountChart);

        // 1. withTemplateOptions (with template parameter)
        final SavingsAccountData withTemplate1 = SavingsAccountData.withTemplateOptions(account, null, null, null);
        assertSame(accountChart, withTemplate1.getAccountChart());
        assertSame(accountChart, withTemplate1.getChartTemplate());

        // 2. withTemplateOptions (with full options)
        final SavingsAccountData withTemplate2 = SavingsAccountData.withTemplateOptions(account, null, null, null, null, null, null,
                null, null, null, null, null);
        assertSame(accountChart, withTemplate2.getAccountChart());
        assertSame(accountChart, withTemplate2.getChartTemplate());
    }

    @Test
    void testJacksonAndGsonSerializationOfChartsAndAccountCharts() throws Exception {
        final InterestRateChartData productChart = createSampleChartData();
        final List<InterestRateChartData> charts = List.of(productChart);

        final SavingsProductData product = SavingsProductData.lookup(1L, "Tiered Savings Product");
        product.setCharts(charts);
        product.setInterestRateCharts(charts);
        product.setActiveChart(productChart);
        product.setChartTemplate(productChart);

        final DepositAccountInterestRateChartData accountChart = DepositAccountInterestRateChartData.from(productChart);
        final SavingsAccountData account = SavingsAccountData.lookup(1L, "SA-0001", null);
        account.setAccountChart(accountChart);
        account.setChartTemplate(accountChart);

        // Jackson serialization test
        final ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());

        final String productJacksonJson = mapper.writeValueAsString(product);
        assertTrue(productJacksonJson.contains("\"charts\""), "Jackson output must contain 'charts'");
        assertTrue(productJacksonJson.contains("\"interestRateCharts\""), "Jackson output must contain 'interestRateCharts'");
        assertTrue(productJacksonJson.contains("\"activeChart\""), "Jackson output must contain 'activeChart'");
        assertTrue(productJacksonJson.contains("\"chartSlabs\""), "Jackson output must contain 'chartSlabs'");
        assertTrue(productJacksonJson.contains("\"isPrimaryGroupingByAmount\":true"),
                "Jackson output must contain '\"isPrimaryGroupingByAmount\":true'");

        final String accountJacksonJson = mapper.writeValueAsString(account);
        assertTrue(accountJacksonJson.contains("\"accountChart\""), "Jackson output must contain 'accountChart'");
        assertTrue(accountJacksonJson.contains("\"chartSlabs\""), "Jackson output must contain 'chartSlabs'");
        assertTrue(accountJacksonJson.contains("\"isPrimaryGroupingByAmount\":true"),
                "Jackson output must contain '\"isPrimaryGroupingByAmount\":true'");

        // Gson serialization test
        final Gson gson = GoogleGsonSerializerHelper.createSimpleGson();

        final String productGsonJson = gson.toJson(product);
        assertTrue(productGsonJson.contains("\"charts\""), "Gson output must contain 'charts'");
        assertTrue(productGsonJson.contains("\"interestRateCharts\""), "Gson output must contain 'interestRateCharts'");
        assertTrue(productGsonJson.contains("\"activeChart\""), "Gson output must contain 'activeChart'");
        assertTrue(productGsonJson.contains("\"chartSlabs\""), "Gson output must contain 'chartSlabs'");
        assertTrue(productGsonJson.contains("\"isPrimaryGroupingByAmount\":true"),
                "Gson output must contain '\"isPrimaryGroupingByAmount\":true'");

        final String accountGsonJson = gson.toJson(account);
        assertTrue(accountGsonJson.contains("\"accountChart\""), "Gson output must contain 'accountChart'");
        assertTrue(accountGsonJson.contains("\"chartSlabs\""), "Gson output must contain 'chartSlabs'");
        assertTrue(accountGsonJson.contains("\"isPrimaryGroupingByAmount\":true"),
                "Gson output must contain '\"isPrimaryGroupingByAmount\":true'");
    }
}
