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
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.apache.fineract.infrastructure.core.exception.UnsupportedParameterException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
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
        this.validator = new SavingsProductDataValidator(new FromJsonHelper(), this.savingsProductAccountingDataValidator);
    }

    private String createValidSavingsProductJson(final boolean includeCharts) {
        final StringBuilder sb = new StringBuilder();
        sb.append("{")
                .append("\"name\": \"Savings Product Test\",")
                .append("\"shortName\": \"SP01\",")
                .append("\"description\": \"Savings Product Description\",")
                .append("\"currencyCode\": \"USD\",")
                .append("\"digitsAfterDecimal\": 2,")
                .append("\"inMultiplesOf\": 1,")
                .append("\"nominalAnnualInterestRate\": 5.0,")
                .append("\"interestCompoundingPeriodType\": 1,")
                .append("\"interestPostingPeriodType\": 4,")
                .append("\"interestCalculationType\": 1,")
                .append("\"interestCalculationDaysInYearType\": 365,")
                .append("\"accountingRule\": 1,")
                .append("\"locale\": \"en\"");
        if (includeCharts) {
            sb.append(",\"charts\": []");
        }
        sb.append("}");
        return sb.toString();
    }

    @Test
    void validateForCreate_withCharts_doesNotThrowUnsupportedParameterException() {
        final String jsonWithCharts = createValidSavingsProductJson(true);
        assertDoesNotThrow(() -> this.validator.validateForCreate(jsonWithCharts));
    }

    @Test
    void validateForCreate_withoutCharts_backwardCompatibility() {
        final String jsonWithoutCharts = createValidSavingsProductJson(false);
        assertDoesNotThrow(() -> this.validator.validateForCreate(jsonWithoutCharts));
    }

    @Test
    void validateForCreate_withUnsupportedParameter_throwsException() {
        final String jsonWithUnsupported = "{\"name\": \"Savings Product Test\", \"unsupportedParameter\": \"invalid\"}";
        assertThrows(UnsupportedParameterException.class, () -> this.validator.validateForCreate(jsonWithUnsupported));
    }
}
