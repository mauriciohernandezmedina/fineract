# Technical Specification: Whole-Balance Tiered Interest Rates for Savings Products (Fineract 1.15)

## CRITICAL MODULE BOUNDARY RULE (DO NOT VIOLATE)
Fineract 1.15 enforces strict Gradle module dependencies:
`fineract-core` <- `fineract-savings` <- `fineract-provider`
- **NEVER move (`git mv`) any class between modules.**
- **NEVER import `fineract-savings` classes into `fineract-core`** (e.g., DO NOT add `InterestRateChartData` to `SavingsProductData.java` in `fineract-core`).
- **NEVER import `fineract-provider` classes into `fineract-savings`** (e.g., DO NOT inject `InterestRateChartDataValidator` into `SavingsProductDataValidator.java` in `fineract-savings`).

---

## PHASE 1: Enable `charts` in `SavingsProduct` API & Persistence (Strictly 6 Files)

### Target Files to Modify (DO NOT touch `fineract-core` except `SavingsApiConstants` if needed)
1. **`fineract-savings/.../portfolio/savings/data/SavingsProductDataValidator.java`**
   - Add `SavingsApiConstants.chartsParamName` (`"charts"`) to `SAVINGS_PRODUCT_REQUEST_DATA_PARAMETERS`.
   - Do NOT inject `InterestRateChartDataValidator` here (it lives in `fineract-provider`).
2. **`fineract-savings/.../portfolio/savings/domain/SavingsProduct.java` & `FixedDepositProduct.java`**
   - Move the existing `@OneToMany` + `@JoinTable(name = "m_deposit_product_interest_rate_chart", ...)` field `protected Set<InterestRateChart> charts` and its helper methods (`setOfCharts()`, `addCharts()`, `addChart()`, `findChart()`, `applicableChart()`) from `FixedDepositProduct` up to `SavingsProduct`.
   - Remove the duplicate `charts` field declaration from `FixedDepositProduct`.
   - In `SavingsProduct.update(JsonCommand command)`, support updating `charts` when `chartsParamName` is present (mirroring `FixedDepositProduct.update`).
3. **`fineract-savings/.../portfolio/savings/domain/SavingsProductAssembler.java`**
   - Inject `InterestRateChartAssembler` (both live in `fineract-savings`).
   - In `assemble(JsonCommand command)`, assemble `Set<InterestRateChart>` when `charts` is present in the JSON and pass it to `SavingsProduct` (or via `addCharts`).
4. **`fineract-provider/.../portfolio/savings/starter/SavingsConfiguration.java`**
   - Update the `savingsProductAssembler` `@Bean` factory method signature if needed to inject `InterestRateChartAssembler`.
5. **`fineract-provider/.../portfolio/savings/service/SavingsProductWritePlatformServiceJpaRepositoryImpl.java`**
   - Inject `InterestRateChartDataValidator` and `InterestRateChartAssembler`.
   - In `create(JsonCommand command)` and `update(Long productId, JsonCommand command)`: if the command JSON contains `"charts"`, validate each chart object in the `"charts"` `JsonArray` using `this.chartDataValidator` (or via a helper method), and wire `product.setHelpers(this.chartAssembler)` before calling `product.update(command)`.

### Phase 1 Verification
- Create a unit test in `fineract-provider` testing `SavingsProductDataValidator` acceptance of `"charts"`.
- Run `./gradlew :fineract-savings:spotlessApply :fineract-provider:spotlessApply --max-workers=2`
- Run `./gradlew :fineract-savings:compileJava :fineract-provider:compileJava --max-workers=2 -x spotlessJavaCheck -x checkstyleMain`
