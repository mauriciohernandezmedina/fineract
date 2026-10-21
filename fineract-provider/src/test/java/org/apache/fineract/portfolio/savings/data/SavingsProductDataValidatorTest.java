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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.portfolio.interestratechart.data.InterestRateChartDataValidator;
import org.apache.fineract.portfolio.interestratechart.data.InterestRateChartSlabDataValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SavingsProductDataValidatorTest {

    @Mock
    private SavingsProductAccountingDataValidator savingsProductAccountingDataValidator;

    private SavingsProductDataValidator validator;

    @BeforeEach
    void setUp() {
        FromJsonHelper fromApiJsonHelper = new FromJsonHelper();
        InterestRateChartSlabDataValidator chartSlabDataValidator = new InterestRateChartSlabDataValidator(fromApiJsonHelper);
        InterestRateChartDataValidator chartDataValidator = new InterestRateChartDataValidator(fromApiJsonHelper, chartSlabDataValidator);
        validator = new SavingsProductDataValidator(fromApiJsonHelper, savingsProductAccountingDataValidator, chartDataValidator);
    }

    @Test
    void validateForCreate_withChartsGroupingByAmountAnd3Slabs_doesNotThrow() {
        String json = """
                {
                  "name": "Tiered Savings Product",
                  "shortName": "TSP1",
                  "description": "Savings product with whole-balance tiered interest rate chart",
                  "currencyCode": "USD",
                  "digitsAfterDecimal": 2,
                  "inMultiplesOf": 1,
                  "nominalAnnualInterestRate": 0,
                  "interestCompoundingPeriodType": 1,
                  "interestPostingPeriodType": 4,
                  "interestCalculationType": 1,
                  "interestCalculationDaysInYearType": 365,
                  "accountingRule": 1,
                  "locale": "en",
                  "dateFormat": "dd MMMM yyyy",
                  "charts": [
                    {
                      "name": "Tiered Interest Chart",
                      "fromDate": "01 January 2024",
                      "isPrimaryGroupingByAmount": true,
                      "dateFormat": "dd MMMM yyyy",
                      "locale": "en",
                      "chartSlabs": [
                        {
                          "description": "Tier 1",
                          "amountRangeFrom": 0,
                          "amountRangeTo": 50000,
                          "annualInterestRate": 9,
                          "locale": "en"
                        },
                        {
                          "description": "Tier 2",
                          "amountRangeFrom": 50000.01,
                          "amountRangeTo": 75000,
                          "annualInterestRate": 10,
                          "locale": "en"
                        },
                        {
                          "description": "Tier 3",
                          "amountRangeFrom": 75000.01,
                          "annualInterestRate": 11,
                          "locale": "en"
                        }
                      ]
                    }
                  ]
                }
                """;

        assertDoesNotThrow(() -> validator.validateForCreate(json));
    }
}
