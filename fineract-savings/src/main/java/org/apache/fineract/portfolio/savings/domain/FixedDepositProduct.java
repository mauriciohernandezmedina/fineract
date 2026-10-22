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

import static org.apache.fineract.portfolio.savings.DepositsApiConstants.FIXED_DEPOSIT_PRODUCT_RESOURCE_NAME;
import static org.apache.fineract.portfolio.savings.DepositsApiConstants.maxDepositTermParamName;

import jakarta.persistence.CascadeType;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.OneToOne;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.fineract.accounting.common.AccountingRuleType;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.data.DataValidatorBuilder;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.portfolio.charge.domain.Charge;
import org.apache.fineract.portfolio.interestratechart.domain.InterestRateChart;
import org.apache.fineract.portfolio.savings.DepositsApiConstants;
import org.apache.fineract.portfolio.savings.SavingsCompoundingInterestPeriodType;
import org.apache.fineract.portfolio.savings.SavingsInterestCalculationDaysInYearType;
import org.apache.fineract.portfolio.savings.SavingsInterestCalculationType;
import org.apache.fineract.portfolio.savings.SavingsPeriodFrequencyType;
import org.apache.fineract.portfolio.savings.SavingsPostingInterestPeriodType;
import org.apache.fineract.portfolio.tax.domain.TaxGroup;

@Entity
@DiscriminatorValue("200")
public class FixedDepositProduct extends SavingsProduct {

    @OneToOne(mappedBy = "product", cascade = CascadeType.ALL)
    private DepositProductTermAndPreClosure productTermAndPreClosure;

    protected FixedDepositProduct() {

    }

    public static FixedDepositProduct createNew(final String name, final String shortName, final String description,
            final MonetaryCurrency currency, final BigDecimal interestRate,
            final SavingsCompoundingInterestPeriodType interestCompoundingPeriodType,
            final SavingsPostingInterestPeriodType interestPostingPeriodType, final SavingsInterestCalculationType interestCalculationType,
            final SavingsInterestCalculationDaysInYearType interestCalculationDaysInYearType, final Integer lockinPeriodFrequency,
            final SavingsPeriodFrequencyType lockinPeriodFrequencyType, final AccountingRuleType accountingRuleType,
            final Set<Charge> charges, final DepositProductTermAndPreClosure productTermAndPreClosure, final Set<InterestRateChart> charts,
            BigDecimal minBalanceForInterestCalculation, boolean withHoldTax, TaxGroup taxGroup) {

        final BigDecimal minRequiredOpeningBalance = null;
        final boolean withdrawalFeeApplicableForTransfer = false;
        final boolean allowOverdraft = false;
        final BigDecimal overdraftLimit = null;

        return new FixedDepositProduct(name, shortName, description, currency, interestRate, interestCompoundingPeriodType,
                interestPostingPeriodType, interestCalculationType, interestCalculationDaysInYearType, minRequiredOpeningBalance,
                lockinPeriodFrequency, lockinPeriodFrequencyType, withdrawalFeeApplicableForTransfer, accountingRuleType, charges,
                productTermAndPreClosure, charts, allowOverdraft, overdraftLimit, minBalanceForInterestCalculation, withHoldTax, taxGroup);
    }

    protected FixedDepositProduct(final String name, final String shortName, final String description, final MonetaryCurrency currency,
            final BigDecimal interestRate, final SavingsCompoundingInterestPeriodType interestCompoundingPeriodType,
            final SavingsPostingInterestPeriodType interestPostingPeriodType, final SavingsInterestCalculationType interestCalculationType,
            final SavingsInterestCalculationDaysInYearType interestCalculationDaysInYearType, final BigDecimal minRequiredOpeningBalance,
            final Integer lockinPeriodFrequency, final SavingsPeriodFrequencyType lockinPeriodFrequencyType,
            final boolean withdrawalFeeApplicableForTransfer, final AccountingRuleType accountingRuleType, final Set<Charge> charges,
            final DepositProductTermAndPreClosure productTermAndPreClosure, final Set<InterestRateChart> charts,
            final boolean allowOverdraft, final BigDecimal overdraftLimit, final BigDecimal minBalanceForInterestCalculation,
            boolean withHoldTax, TaxGroup taxGroup) {

        super(name, shortName, description, currency, interestRate, interestCompoundingPeriodType, interestPostingPeriodType,
                interestCalculationType, interestCalculationDaysInYearType, minRequiredOpeningBalance, lockinPeriodFrequency,
                lockinPeriodFrequencyType, withdrawalFeeApplicableForTransfer, accountingRuleType, charges, allowOverdraft, overdraftLimit,
                minBalanceForInterestCalculation, withHoldTax, taxGroup);

        if (charts != null) {
            this.charts = charts;
        }

        this.productTermAndPreClosure = productTermAndPreClosure;
    }

    public void addCharts(final Set<InterestRateChart> newCharts) {
        final Set<InterestRateChart> existingCharts = setOfCharts();
        existingCharts.addAll(newCharts);
    }

    public void addChart(final InterestRateChart newChart) {
        final Set<InterestRateChart> existingCharts = setOfCharts();
        existingCharts.add(newChart);
    }

    public Set<InterestRateChart> setOfCharts() {
        if (this.charts == null) {
            this.charts = new HashSet<>();
        }
        return this.charts;
    }

    @Override
    public Map<String, Object> update(final JsonCommand command) {
        final Map<String, Object> actualChanges = new LinkedHashMap<>(10);

        final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
        final DataValidatorBuilder baseDataValidator = new DataValidatorBuilder(dataValidationErrors)
                .resource(FIXED_DEPOSIT_PRODUCT_RESOURCE_NAME);

        actualChanges.putAll(this.update(command, baseDataValidator));

        validateDomainRules(baseDataValidator);

        throwExceptionIfValidationWarningsExist(dataValidationErrors);

        return actualChanges;
    }

    protected Map<String, Object> update(final JsonCommand command, final DataValidatorBuilder baseDataValidator) {
        final Map<String, Object> actualChanges = new LinkedHashMap<>(10);

        actualChanges.putAll(super.update(command));

        if (this.productTermAndPreClosure != null) {
            actualChanges.putAll(this.productTermAndPreClosure.update(command, baseDataValidator));
        }

        // update chart Slabs
        if (command.hasParameter(DepositsApiConstants.chartsParamName)) {
            updateCharts(command, actualChanges, baseDataValidator);
        }

        return actualChanges;
    }

    private void throwExceptionIfValidationWarningsExist(final List<ApiParameterError> dataValidationErrors) {
        if (!dataValidationErrors.isEmpty()) {
            throw new PlatformApiDataValidationException(dataValidationErrors);
        }
    }

    public DepositProductTermAndPreClosure depositProductTermAndPreClosure() {
        return this.productTermAndPreClosure;
    }

    public void validateInterestPostingAndCompoundingPeriodTypes(final DataValidatorBuilder baseDataValidator) {
        Map<SavingsPostingInterestPeriodType, List<SavingsCompoundingInterestPeriodType>> postingtoCompoundMap = new HashMap<>();
        postingtoCompoundMap.put(SavingsPostingInterestPeriodType.MONTHLY, Arrays.asList(new SavingsCompoundingInterestPeriodType[] {
                SavingsCompoundingInterestPeriodType.DAILY, SavingsCompoundingInterestPeriodType.MONTHLY }));

        postingtoCompoundMap.put(SavingsPostingInterestPeriodType.QUATERLY,
                Arrays.asList(new SavingsCompoundingInterestPeriodType[] { SavingsCompoundingInterestPeriodType.DAILY,
                        SavingsCompoundingInterestPeriodType.MONTHLY, SavingsCompoundingInterestPeriodType.QUATERLY }));

        postingtoCompoundMap.put(SavingsPostingInterestPeriodType.BIANNUAL,
                Arrays.asList(new SavingsCompoundingInterestPeriodType[] { SavingsCompoundingInterestPeriodType.DAILY,
                        SavingsCompoundingInterestPeriodType.MONTHLY, SavingsCompoundingInterestPeriodType.QUATERLY,
                        SavingsCompoundingInterestPeriodType.BI_ANNUAL }));

        postingtoCompoundMap.put(SavingsPostingInterestPeriodType.ANNUAL,
                Arrays.asList(new SavingsCompoundingInterestPeriodType[] { SavingsCompoundingInterestPeriodType.DAILY,
                        SavingsCompoundingInterestPeriodType.MONTHLY, SavingsCompoundingInterestPeriodType.QUATERLY,
                        SavingsCompoundingInterestPeriodType.BI_ANNUAL, SavingsCompoundingInterestPeriodType.ANNUAL }));

        postingtoCompoundMap.put(SavingsPostingInterestPeriodType.ANNIVERSARY_MONTHLY,
                Arrays.asList(new SavingsCompoundingInterestPeriodType[] { SavingsCompoundingInterestPeriodType.DAILY,
                        SavingsCompoundingInterestPeriodType.MONTHLY }));

        postingtoCompoundMap.put(SavingsPostingInterestPeriodType.ANNIVERSARY_QUARTERLY,
                Arrays.asList(new SavingsCompoundingInterestPeriodType[] { SavingsCompoundingInterestPeriodType.DAILY,
                        SavingsCompoundingInterestPeriodType.MONTHLY, SavingsCompoundingInterestPeriodType.QUATERLY }));

        postingtoCompoundMap.put(SavingsPostingInterestPeriodType.ANNIVERSARY_BIANNUAL,
                Arrays.asList(new SavingsCompoundingInterestPeriodType[] { SavingsCompoundingInterestPeriodType.DAILY,
                        SavingsCompoundingInterestPeriodType.MONTHLY, SavingsCompoundingInterestPeriodType.QUATERLY,
                        SavingsCompoundingInterestPeriodType.BI_ANNUAL }));

        postingtoCompoundMap.put(SavingsPostingInterestPeriodType.ANNIVERSARY_ANNUAL,
                Arrays.asList(new SavingsCompoundingInterestPeriodType[] { SavingsCompoundingInterestPeriodType.DAILY,
                        SavingsCompoundingInterestPeriodType.MONTHLY, SavingsCompoundingInterestPeriodType.QUATERLY,
                        SavingsCompoundingInterestPeriodType.BI_ANNUAL, SavingsCompoundingInterestPeriodType.ANNUAL }));

        SavingsPostingInterestPeriodType savingsPostingInterestPeriodType = SavingsPostingInterestPeriodType
                .fromInt(interestPostingPeriodType);
        SavingsCompoundingInterestPeriodType savingsCompoundingInterestPeriodType = SavingsCompoundingInterestPeriodType
                .fromInt(interestCompoundingPeriodType);

        if (postingtoCompoundMap.get(savingsPostingInterestPeriodType) == null
                || !postingtoCompoundMap.get(savingsPostingInterestPeriodType).contains(savingsCompoundingInterestPeriodType)) {
            baseDataValidator.failWithCodeNoParameterAddedToErrorCode("posting.period.type.is.less.than.compound.period.type",
                    savingsPostingInterestPeriodType.name(), savingsCompoundingInterestPeriodType.name());

        }
    }

    public void validateDomainRules() {
        final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
        final DataValidatorBuilder baseDataValidator = new DataValidatorBuilder(dataValidationErrors)
                .resource(FIXED_DEPOSIT_PRODUCT_RESOURCE_NAME);
        validateDomainRules(baseDataValidator);
        throwExceptionIfValidationWarningsExist(dataValidationErrors);
    }

    private void validateDomainRules(final DataValidatorBuilder baseDataValidator) {

        final DepositTermDetail termDetails = this.depositProductTermAndPreClosure().depositTermDetail();
        final boolean isMinTermGreaterThanMax = termDetails.isMinDepositTermGreaterThanMaxDepositTerm();
        if (isMinTermGreaterThanMax) {
            final Integer maxTerm = termDetails.maxDepositTerm();
            baseDataValidator.reset().parameter(maxDepositTermParamName).value(maxTerm)
                    .failWithCodeNoParameterAddedToErrorCode("max.term.lessthan.min.term");
        }

        if (this.charts != null) {
            validateCharts(baseDataValidator);
        } else if (this.nominalAnnualInterestRate == null || this.nominalAnnualInterestRate.compareTo(BigDecimal.ZERO) == 0) {
            baseDataValidator.reset().parameter(DepositsApiConstants.nominalAnnualInterestRateParamName).value(nominalAnnualInterestRate)
                    .failWithCodeNoParameterAddedToErrorCode("interest.chart.or.nominal.interest.rate.required");
        }

        this.validateInterestPostingAndCompoundingPeriodTypes(baseDataValidator);
    }

}
