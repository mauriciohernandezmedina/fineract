# Technical Specification: Whole-Balance Tiered Interest Rates for Savings Products (Fineract 1.15)

## 1. Business Goal & Mathematical Model
Enable `SavingsProduct` (`deposit_type_enum = 100`) and `SavingsAccount` to use Fineract's native **Interest Rate Charts (`charts` / `chartSlabs`)** grouped by amount (`isPrimaryGroupingByAmount = true`) for daily balance interest calculations.

- **Calculation Model:** Whole-Balance Tiered (*Tasa por Nivel Total*).
- **Rule:** For each `EndOfDayBalance` slice inside a `PostingPeriod`, evaluate the full daily closing balance against the active `SavingsAccountInterestRateChart` slabs. Identify the single matching slab where `closingBalance >= amountRangeFrom` and (`amountRangeTo == null || closingBalance <= amountRangeTo`), and apply that slab's `annualInterestRate` to the **entire** closing balance for those days.
- **Fallback:** If a `SavingsProduct` or `SavingsAccount` has no `charts` defined, it MUST use the standard `nominalAnnualInterestRate` with 100% backward compatibility.
- **Database Schema:** NO Liquibase migrations required. Reuse existing tables `m_deposit_product_interest_rate_chart`, `m_interest_rate_chart`, `m_interest_rate_slab`, `m_savings_account_interest_rate_chart`, and `m_savings_account_interest_rate_slab`.

---

## PHASE 1: Enable `charts` in `SavingsProduct` API & Persistence

### Objective
Allow `POST /v1/savingsproducts`, `PUT /v1/savingsproducts/{id}`, and `GET /v1/savingsproducts/{id}` to accept, validate, persist, and return `charts` with `chartSlabs`, reusing the exact mechanism from `FixedDepositProduct` / `DepositProductAssembler`.

### Target Files to Modify (Phase 1)
1. **`org.apache.fineract.portfolio.savings.SavingsApiConstants`**
   - Ensure `chartsParamName` (`"charts"`) is included in `SAVINGS_PRODUCT_REQUEST_DATA_PARAMETERS` and `SAVINGS_PRODUCT_RESPONSE_DATA_PARAMETERS`.
2. **`org.apache.fineract.portfolio.savings.data.SavingsProductDataValidator`**
   - Inject `InterestRateChartDataValidator` (if not already present).
   - In `validateForCreate(String json)` and `validateForUpdate(String json)`: if the JSON element contains `charts` (`DepositApiConstants.chartsParamName`), delegate chart validation to `this.chartDataValidator.validateForCreate(element, baseDataValidator)` / `validateForUpdate(...)`.
3. **`org.apache.fineract.portfolio.savings.domain.SavingsProduct`**
   - Expose or pull up `@OneToMany(cascade = CascadeType.ALL, mappedBy = "product", orphanRemoval = true, fetch = FetchType.EAGER) protected Set<InterestRateChart> charts = new HashSet<>();` (verify if already present in `SavingsProduct` or `FixedDepositProduct` and ensure `SavingsProduct` can hold and update `charts` cleanly without breaking subclasses `FixedDepositProduct` and `RecurringDepositProduct`).
   - Add helper method `public InterestRateChart applicableChart(final LocalDate target)` if currently restricted to subclasses.
4. **`org.apache.fineract.portfolio.savings.domain.SavingsProductAssembler`**
   - Inject `DepositProductAssembler` or `InterestRateChartAssembler`.
   - In `assemble(JsonCommand command)`: assemble the `Set<InterestRateChart>` from the `charts` JSON array (when present) and attach it to the `SavingsProduct` instance.
   - Ensure `SavingsProduct.update(JsonCommand command)` also updates `charts` when modified.
5. **`org.apache.fineract.portfolio.savings.service.SavingsProductReadPlatformServiceImpl`** (and `SavingsProductData` / `SavingsProductApiResource`)
   - Inject `InterestRateChartReadPlatformService`.
   - When retrieving a single `SavingsProductData` (`retrieveOne`), load associated `Collection<InterestRateChartData>` and include `charts` in the serialized JSON response.

### Phase 1 Acceptance Criteria & Verification
- Create a unit/integration test or verify compilation and `SavingsProductDataValidator` acceptance of a `POST /v1/savingsproducts` payload containing `charts` with `isPrimaryGroupingByAmount: true` and 3 `chartSlabs`.
- Run `./gradlew :fineract-provider:spotlessApply` and `./gradlew :fineract-provider:test --tests "org.apache.fineract.portfolio.savings.*"`.

---

## PHASE 2: Clone Chart to `SavingsAccount` & Connect Daily Interest Calculation

### Objective
Copy the applicable `InterestRateChart` from `SavingsProduct` to `SavingsAccount` upon account creation/approval, and dynamically resolve each `EndOfDayBalance` rate from the account's chart during `PostingPeriod` interest calculation.

### Target Files to Modify (Phase 2)
1. **`org.apache.fineract.portfolio.savings.domain.SavingsAccountAssembler`**
   - When assembling a new `SavingsAccount` from a `SavingsProduct` that has an applicable `InterestRateChart`, clone/assemble the `SavingsAccountInterestRateChart` onto `this.chart` in `SavingsAccount` (mirroring how `DepositAccountAssembler` attaches `SavingsAccountInterestRateChart`).
2. **`org.apache.fineract.portfolio.savings.domain.SavingsAccount` & `SavingsHelper`**
   - When constructing `PostingPeriod` instances inside `calculateInterestUsing(...)`, pass the active `SavingsAccountInterestRateChart` (or a rate-lookup callback/reference) if present on the `SavingsAccount`.
3. **`org.apache.fineract.portfolio.savings.domain.interest.PostingPeriod`, `DailyCompoundingPeriod`, `SimpleCompoundingPeriod`, and `EndOfDayBalance`**
   - When an active `SavingsAccountInterestRateChart` with `isPrimaryGroupingByAmount` is present on the period:
     - For each `EndOfDayBalance`, resolve the effective annual interest rate using the chart slabs based on `endOfDayBalance.closingBalance(currency)` (for positive balances).
     - Convert that annual rate to the daily/periodic `interestRateAsFraction` and compute interest for that `EndOfDayBalance` slice.
   - If no chart is present on the account, execute the exact default calculation using `PostingPeriod.interestRateAsFraction`.

### Phase 2 Mandatory Unit Test
Create `org.apache.fineract.portfolio.savings.domain.interest.TieredSavingsInterestCalculationTest` verifying:
- Slabs: `0 to 50,000.00 -> 9%`, `50,000.01 to 75,000.00 -> 10%`, `75,000.01+ -> 11%` (365 days/year).
- Balance of `40,000` for 10 days accrues at `9%` (`40000 * 0.09 / 365 * 10`).
- Deposit brings balance to `60,000` for 10 days -> full `60,000` accrues at `10%` (`60000 * 0.10 / 365 * 10`).
- Deposit brings balance to `80,000` for 10 days -> full `80,000` accrues at `11%` (`80000 * 0.11 / 365 * 10`).
- Reversal of the second deposit recalculates the affected days back to the `10%` tier cleanly.
