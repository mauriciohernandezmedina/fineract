# Technical Specification: Whole-Balance Tiered Interest Rates for Savings Products (Fineract 1.15)

## CRITICAL MODULE BOUNDARY RULE (DO NOT VIOLATE)
Fineract 1.15 enforces strict Gradle module dependencies:
`fineract-core` <- `fineract-savings` <- `fineract-provider`
- **NEVER move (`git mv`) any class between modules.**
- **NEVER import `fineract-savings` classes into `fineract-core`** (e.g., DO NOT import `DepositAccountInterestRateChart` into `PostingPeriod`, `CompoundingPeriod`, or `EndOfDayBalance` in `fineract-core`).
- **NEVER import `fineract-provider` classes into `fineract-savings`** (e.g., DO NOT inject `InterestRateChartDataValidator` into `SavingsProductDataValidator.java` in `fineract-savings`).

---

## PHASE 1: Enable `charts` in `SavingsProduct` API & Persistence (COMPLETED)

### Target Files Modified
1. **`fineract-core/.../portfolio/savings/SavingsApiConstants.java`**
   - Added `public static final String chartsParamName = "charts";`.
2. **`fineract-savings/.../portfolio/savings/data/SavingsProductDataValidator.java`**
   - Added `SavingsApiConstants.chartsParamName` (`"charts"`) to `SAVINGS_PRODUCT_REQUEST_DATA_PARAMETERS`.
3. **`fineract-savings/.../portfolio/savings/domain/SavingsProduct.java` & `FixedDepositProduct.java`**
   - Moved `@OneToMany` + `@JoinTable(name = "m_deposit_product_interest_rate_chart", ...)` field `protected Set<InterestRateChart> charts` and its helper methods (`setOfCharts()`, `addCharts()`, `addChart()`, `findChart()`, `removeChart()`, `applicableChart()`, `validateCharts()`, `validateChart()`, `updateCharts()`, `setHelpers()`) from `FixedDepositProduct` up to `SavingsProduct`.
   - Removed duplicate `charts` and `chartAssembler` field declarations from `FixedDepositProduct`.
   - In `SavingsProduct.update(JsonCommand command)`, enabled updating `charts` when `chartsParamName` is present.
4. **`fineract-savings/.../portfolio/savings/domain/SavingsProductAssembler.java`**
   - Injected `InterestRateChartAssembler`, assembled `Set<InterestRateChart>` when `charts` is present in JSON, attached via `product.addCharts(charts)`, and validated date overlaps via `product.validateCharts(baseDataValidator)`.
5. **`fineract-provider/.../portfolio/savings/service/SavingsProductWritePlatformServiceJpaRepositoryImpl.java`**
   - Injected `InterestRateChartDataValidator`, `InterestRateChartAssembler`, and `FromJsonHelper`.
   - Added `validateChartsData(command)` in `create` and `update`, and wired `product.setHelpers(this.chartAssembler)` in `update`.
6. **`fineract-provider/.../portfolio/savings/starter/SavingsConfiguration.java`**
   - Updated `savingsProductWritePlatformService` `@Bean` factory method to inject `InterestRateChartDataValidator`, `InterestRateChartAssembler`, and `FromJsonHelper`.

---

## PHASE 2: Clone & Associate Active Chart to `SavingsAccount` (COMPLETED)

### Target Files Modified
1. **`fineract-savings/.../portfolio/savings/data/SavingsAccountConstant.java`**
   - Added `DepositsApiConstants.chartIdParamName` (`"chartId"`) to `SAVINGS_ACCOUNT_REQUEST_DATA_PARAMETERS`.
2. **`fineract-savings/.../portfolio/savings/domain/SavingsAccount.java`**
   - Promoted `@OneToOne(fetch = FetchType.LAZY, cascade = CascadeType.ALL, mappedBy = "account", orphanRemoval = true) protected DepositAccountInterestRateChart chart;` to `SavingsAccount` (reusing the existing `DepositAccountInterestRateChart` entity in `fineract-savings` mapped to `m_savings_account_interest_rate_chart`).
   - Added `getChart()` and `setChart(DepositAccountInterestRateChart chart)` (maintaining bidirectional reference `this.chart.updateDepositAccountReference(this)`).
   - Updated `hasInterestCalculation()` to return `!MathUtil.isEmpty(nominalAnnualInterestRate) || this.chart != null;`.
   - Updated `update(SavingsProduct product)` and `modifyApplication(...)` to refresh the cloned `DepositAccountInterestRateChart` when product or `submittedOnDate` changes on a pending application.
   - Initialized `this.chart.setOfChartSlabs().size()` in `loadLazyCollections()`.
3. **`fineract-provider/.../portfolio/savings/domain/FixedDepositAccount.java` & `RecurringDepositAccount.java`**
   - Removed duplicate `chart` field declarations so both subclasses inherit `protected DepositAccountInterestRateChart chart` from `SavingsAccount`.
4. **`fineract-provider/.../portfolio/savings/domain/SavingsAccountAssembler.java`**
   - In `assembleFrom(JsonCommand command, AppUser submittedBy)`: resolved the product's active `InterestRateChart` via `product.findChart(chartId)` or `product.applicableChart(submittedOnDate)`, cloned it via `DepositAccountInterestRateChart.from(productChart)`, and linked it via `account.setChart(accountChart)`.

---

## PHASE 3: Whole-Balance Tiered Calculation Engine (`PostingPeriod` & `EndOfDayBalance`)

### Architectural Design (Respecting `fineract-core` <- `fineract-savings` Boundary)
- `PostingPeriod`, `CompoundingPeriod` (`DailyCompoundingPeriod`, `MonthlyCompoundingPeriod`, `QuarterlyCompoundingPeriod`, `BiAnnualCompoundingPeriod`, `AnnualCompoundingPeriod`), and `EndOfDayBalance` live in **`fineract-core`** (`org.apache.fineract.portfolio.savings.domain.interest`).
- `DepositAccountInterestRateChart` and `SavingsAccount` live in **`fineract-savings`**.
- Therefore, `fineract-core` **MUST NOT** import `DepositAccountInterestRateChart`. Instead, `SavingsAccount` (in `fineract-savings`) will pass a rate resolver function (`Function<BigDecimal, BigDecimal> tieredRateResolver`, or `null` when no amount-tiered chart applies) into `PostingPeriod.createFrom(...)`.
- When `tieredRateResolver != null`:
  - **Daily Balance Method (`DAILY_BALANCE`)**: For each `EndOfDayBalance`, the effective balance for that day (`realBalanceForInterestCalculation = endOfDayBalance.getAmount().add(interestToCompound)`) is passed to `tieredRateResolver.apply(realBalanceForInterestCalculation)` to obtain the applicable annual interest rate as a fraction (whole-balance / *Nivel Total* rate for that tier), which is then used in `calculateInterestOnBalance` or `calculateInterestOnBalanceAndInterest`.
  - **Average Daily Balance Method (`AVERAGE_DAILY_BALANCE`)**: Once `averageDailyBalance` is computed for the `CompoundingPeriod`, `tieredRateResolver.apply(averageDailyBalance)` resolves the applicable annual interest rate as a fraction for that `averageDailyBalance`.
- **100% Backward Compatibility & Deposit Account Preservation**:
  - Standard `SavingsAccount` instances without a chart (`this.chart == null || !this.chart.isPrimaryGroupingByAmount()`) pass `tieredRateResolver = null` and execute the exact existing calculation path using `interestRateAsFraction`.
  - `FixedDepositAccount` and `RecurringDepositAccount` override `getEffectiveInterestRateAsFraction` and use term-based / deposit-amount calculation; in `SavingsAccount`, the tiered resolver is only active when `this.depositAccountType() == DepositAccountType.SAVINGS_DEPOSIT && this.chart != null && this.chart.isPrimaryGroupingByAmount()`.

### Target Files to Modify in Phase 3
1. **`fineract-savings/.../portfolio/savings/domain/SavingsAccount.java`**
   - Add helper method `protected Function<BigDecimal, BigDecimal> getTieredInterestRateAsFractionResolver(final MathContext mc, final LocalDate upToInterestCalculationDate)`:
     - If `this.depositAccountType() == DepositAccountType.SAVINGS_DEPOSIT && this.chart != null && this.chart.isPrimaryGroupingByAmount()`, return a `Function<BigDecimal, BigDecimal>` that, given a `BigDecimal balance`, looks up `BigDecimal rate = this.chart.getApplicableInterestRate(balance, upToInterestCalculationDate, upToInterestCalculationDate, this.client);` and returns `rate != null ? rate.divide(BigDecimal.valueOf(100L), mc) : BigDecimal.ZERO`.
     - Otherwise, return `null`.
   - In `calculateInterestUsing(...)`, pass `getTieredInterestRateAsFractionResolver(mc, periodInterval.endDate())` (or configure it on `PostingPeriod` via `postingPeriod.setTieredRateResolver(...)`) before `this.savingsHelper.calculateInterestForAllPostingPeriods(...)`.
   - Also check `getEffectiveInterestRateAsFractionAccrual(MathContext mc, LocalDate upToInterestCalculationDate)`: if `this.depositAccountType() == DepositAccountType.SAVINGS_DEPOSIT && this.chart != null && this.chart.isPrimaryGroupingByAmount()`, resolve the rate from `this.chart` using the current account balance (`this.summary.getAccountBalance()`) so accrual jobs also respect the tiered chart when applicable.
2. **`fineract-core/.../portfolio/savings/domain/interest/PostingPeriod.java`**
   - Add field `private Function<BigDecimal, BigDecimal> tieredRateResolver;` (with Lombok `@Getter`/`@Setter` or explicit setter `setTieredRateResolver(Function<BigDecimal, BigDecimal> tieredRateResolver)`).
   - In `calculateInterest(CompoundInterestValues compoundInterestValues)`: if `this.tieredRateResolver != null`, invoke the overloaded `compoundingPeriod.calculateInterest(..., this.tieredRateResolver)` (defaulting on `CompoundingPeriod` interface for backward compatibility).
3. **`fineract-core/.../portfolio/savings/domain/interest/CompoundingPeriod.java` & Implementations (`DailyCompoundingPeriod`, `MonthlyCompoundingPeriod`, `QuarterlyCompoundingPeriod`, `BiAnnualCompoundingPeriod`, `AnnualCompoundingPeriod`)**
   - Add a `default` method in `CompoundingPeriod`:
     ```java
     default BigDecimal calculateInterest(SavingsCompoundingInterestPeriodType compoundingInterestPeriodType,
             SavingsInterestCalculationType interestCalculationType, BigDecimal interestFromPreviousPostingPeriod,
             BigDecimal interestRateAsFraction, long daysInYear, BigDecimal minBalanceForInterestCalculation,
             BigDecimal overdraftInterestRateAsFraction, BigDecimal minOverdraftForInterestCalculation,
             Function<BigDecimal, BigDecimal> tieredRateResolver) {
         return calculateInterest(compoundingInterestPeriodType, interestCalculationType, interestFromPreviousPostingPeriod,
                 interestRateAsFraction, daysInYear, minBalanceForInterestCalculation, overdraftInterestRateAsFraction,
                 minOverdraftForInterestCalculation);
     }
     ```
   - Implement the overloaded `calculateInterest(..., Function<BigDecimal, BigDecimal> tieredRateResolver)` in `DailyCompoundingPeriod`, `MonthlyCompoundingPeriod`, `QuarterlyCompoundingPeriod`, `BiAnnualCompoundingPeriod`, and `AnnualCompoundingPeriod`:
     - In `DAILY_BALANCE` mode: for each `EndOfDayBalance balance`, resolve `BigDecimal effectiveRate = tieredRateResolver != null ? tieredRateResolver.apply(balance.closingBalance().getAmount().add(interestToCompound)) : interestRateAsFraction;` and pass `effectiveRate` to `balance.calculateInterestOnBalance(...)` or `balance.calculateInterestOnBalanceAndInterest(...)`.
     - In `AVERAGE_DAILY_BALANCE` mode: after computing `averageDailyBalance`, resolve `BigDecimal effectiveRate = tieredRateResolver != null ? tieredRateResolver.apply(averageDailyBalance) : interestRateAsFraction;` and use `effectiveRate` to compute `dailyInterestRate`.

### Phase 3 Verification
- Create a comprehensive unit test `TieredSavingsInterestCalculationTest` in `fineract-provider/src/test/java/org/apache/fineract/portfolio/savings/domain/interest/` verifying:
  1. **Whole-Balance Tiered Rate (Nivel Total)**: A balance of `$500` in Tier 1 (`0 - 1,000` @ `3%`) earns `3%` on the full `$500`; when a deposit brings the balance to `$2,500` in Tier 2 (`1,000.01 - 5,000` @ `5%`), it earns `5%` on the full `$2,500` for those days, and when a withdrawal brings it to `$800`, it reverts to `3%` on `$800`.
  2. **Multi-tier mid-period balance fluctuation**: A single `PostingPeriod` with multiple `EndOfDayBalance` entries across different tiers calculates the exact sum of daily tiered interests.
  3. **100% Backward Compatibility**: When `tieredRateResolver == null`, `PostingPeriod.calculateInterest(...)` produces the exact same result as the flat `interestRateAsFraction`.

