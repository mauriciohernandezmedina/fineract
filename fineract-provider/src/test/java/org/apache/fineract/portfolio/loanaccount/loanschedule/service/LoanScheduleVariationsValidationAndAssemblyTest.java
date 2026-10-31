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
package org.apache.fineract.portfolio.loanaccount.loanschedule.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.data.DataValidatorBuilder;
import org.apache.fineract.infrastructure.core.exception.UnsupportedParameterException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.portfolio.loanaccount.data.LoanTermVariationsData;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTermVariationType;
import org.apache.fineract.portfolio.loanaccount.serialization.LoanApplicationValidator;
import org.apache.fineract.portfolio.loanaccount.serialization.LoanScheduleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LoanScheduleVariationsValidationAndAssemblyTest {

    private FromJsonHelper fromJsonHelper;
    private LoanScheduleValidator loanScheduleValidator;
    private LoanApplicationValidator loanApplicationValidator;
    private LoanScheduleAssembler loanScheduleAssembler;

    @BeforeEach
    void setUp() {
        this.fromJsonHelper = new FromJsonHelper();
        this.loanScheduleValidator = new LoanScheduleValidator(this.fromJsonHelper);
        this.loanApplicationValidator = new LoanApplicationValidator(this.fromJsonHelper, this.loanScheduleValidator, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null);
        this.loanScheduleAssembler = new LoanScheduleAssembler(this.fromJsonHelper, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void shouldAcceptAndExtractLoanTermVariationsInCalculateLoanSchedulePayload() {
        final String payload = """
                {
                  "clientId": 1,
                  "productId": 1,
                  "loanType": "individual",
                  "locale": "en",
                  "dateFormat": "dd MMMM yyyy",
                  "principal": "10000.00",
                  "loanTermFrequency": 12,
                  "loanTermFrequencyType": 2,
                  "numberOfRepayments": 12,
                  "repaymentEvery": 1,
                  "repaymentFrequencyType": 2,
                  "interestRatePerPeriod": 12,
                  "amortizationType": 1,
                  "interestType": 0,
                  "interestCalculationPeriodType": 1,
                  "expectedDisbursementDate": "01 January 2026",
                  "transactionProcessingStrategyCode": "mifos-standard-strategy",
                  "loanTermVariations": [
                    {
                      "termType": 1,
                      "applicableFromDate": "01 February 2026",
                      "decimalValue": 950.00,
                      "isSpecificToInstallment": true
                    },
                    {
                      "termType": "interestRateFromInstallment",
                      "applicableFromDate": "01 March 2026",
                      "decimalValue": 10.50,
                      "isSpecificToInstallment": false
                    },
                    {
                      "termType": 3,
                      "applicableFromDate": "01 April 2026",
                      "decimalValue": 800.00,
                      "isSpecificToInstallment": true
                    },
                    {
                      "termType": "graceOnPrincipal",
                      "applicableFromDate": "01 May 2026",
                      "decimalValue": 1,
                      "isSpecificToInstallment": true
                    },
                    {
                      "termType": 4,
                      "applicableFromDate": "01 June 2026",
                      "dateValue": "05 June 2026",
                      "isSpecificToInstallment": true
                    }
                  ]
                }
                """;

        assertDoesNotThrow(() -> this.loanScheduleValidator.validate(payload));

        final JsonElement parsed = this.fromJsonHelper.parse(payload);
        final List<ApiParameterError> errors = new ArrayList<>();
        final DataValidatorBuilder validatorBuilder = new DataValidatorBuilder(errors).resource("loan");
        this.loanApplicationValidator.validateLoanTermVariations(parsed, validatorBuilder);
        assertTrue(errors.isEmpty(), "Expected no validation errors for valid loanTermVariations");

        final List<LoanTermVariationsData> extracted = new ArrayList<>();
        this.loanScheduleAssembler.extractLoanTermVariationsFromJson(parsed, extracted);

        assertEquals(5, extracted.size());
        assertEquals(LoanTermVariationType.EMI_AMOUNT.getValue(), extracted.get(0).getTermVariationType().getValue());
        assertEquals(LocalDate.of(2026, 2, 1), extracted.get(0).getTermVariationApplicableFrom());
        assertEquals(0, new BigDecimal("950.00").compareTo(extracted.get(0).getDecimalValue()));
        assertTrue(extracted.get(0).isSpecificToInstallment());

        assertEquals(LoanTermVariationType.INTEREST_RATE_FROM_INSTALLMENT.getValue(), extracted.get(1).getTermVariationType().getValue());
        assertFalse(extracted.get(1).isSpecificToInstallment());

        assertEquals(LoanTermVariationType.PRINCIPAL_AMOUNT.getValue(), extracted.get(2).getTermVariationType().getValue());
        assertEquals(LoanTermVariationType.GRACE_ON_PRINCIPAL.getValue(), extracted.get(3).getTermVariationType().getValue());
        assertEquals(LoanTermVariationType.DUE_DATE.getValue(), extracted.get(4).getTermVariationType().getValue());
        assertEquals(LocalDate.of(2026, 6, 5), extracted.get(4).getDateValue());
    }

    @Test
    void shouldRejectUnsupportedNestedParameterInLoanTermVariations() {
        final String invalidPayload = """
                {
                  "locale": "en",
                  "dateFormat": "dd MMMM yyyy",
                  "loanTermVariations": [
                    {
                      "termType": 1,
                      "applicableFromDate": "01 February 2026",
                      "decimalValue": 950.00,
                      "unknownField": "invalid"
                    }
                  ]
                }
                """;
        final JsonElement parsed = this.fromJsonHelper.parse(invalidPayload);
        final List<ApiParameterError> errors = new ArrayList<>();
        final DataValidatorBuilder validatorBuilder = new DataValidatorBuilder(errors).resource("loan");

        assertThrows(UnsupportedParameterException.class,
                () -> this.loanApplicationValidator.validateLoanTermVariations(parsed, validatorBuilder));
    }
}
