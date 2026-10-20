# Agent guidance

This file is read by automated agents (security scanners, code
analyzers, AI assistants) operating on this repository. It
points them at the human-authored references they should
consult before producing output.

## Security

Security model: [SECURITY.md](./SECURITY.md)

Agents that scan this repository should consult `SECURITY.md`
for the project's threat model, in-scope / out-of-scope
declarations, and known non-findings before reporting issues.

---

## Development & Architecture Rules for AI Coding Agents (Google Jules)

### 1. Context & Scope Isolation (CRITICAL)
When working on the **Tiered Savings Interest (Interest Rate Charts for Savings Products)** feature, strictly limit file exploration and modifications to the following packages:
- **ALLOWED PACKAGES:**
  - `org.apache.fineract.portfolio.savings.*`
  - `org.apache.fineract.portfolio.interestratechart.*`
- **STRICTLY FORBIDDEN (DO NOT READ OR MODIFY):**
  - `org.apache.fineract.accounting.*`
  - `org.apache.fineract.portfolio.loanaccount.*`
  - `org.apache.fineract.portfolio.client.*`
  - `org.apache.fineract.infrastructure.*`
  - Database migration scripts (`fineract-provider/src/main/resources/db/changelog/**`) — No Liquibase/database schema changes are needed. Existing tables (`m_deposit_product_interest_rate_chart` and `m_savings_account_interest_rate_chart`) already reference `m_savings_product.id` and `m_savings_account.id`.

### 2. Core Banking Architectural Guardrails
1. **Zero Transaction Flow Impact:** NEVER modify `SavingsAccountWritePlatformServiceJpaRepositoryImpl`, `SavingsAccountDomainServiceJpa`, or any deposit, withdrawal, transfer, reversal, or accounting posting services. Transactions do not calculate interest directly; they only trigger `PostingPeriod` reconstruction.
2. **Whole-Balance Tiered Calculation (Nivel Total):** When a `SavingsAccount` has an active `SavingsAccountInterestRateChart` with `isPrimaryGroupingByAmount = true`, each daily balance (`EndOfDayBalance`) must look up the applicable annual interest rate from the matching `SavingsAccountInterestRateChartSlab` based on the full closing balance of that day, and apply that single rate to the entire balance for that period.
3. **100% Backward Compatibility:** Standard `SavingsProduct` and `SavingsAccount` instances without `charts` (`charts == null || charts.isEmpty()`) MUST execute the exact existing calculation path using `nominalAnnualInterestRate`.
4. **Precision & Rounding:** Always use `MoneyHelper.getMathContext()` and `MoneyHelper.getRoundingMode()` for `BigDecimal` operations.

### 3. Fast Build, Formatting & Test Commands (Prevent Timeouts)
Fineract is a large monorepo. **NEVER** run full builds (`./gradlew build` or `./gradlew test`) across the entire repository as it will cause execution timeouts. Always run targeted tasks on `:fineract-provider`:

- **Fast compilation check:**
  `./gradlew :fineract-provider:compileJava :fineract-provider:compileTestJava -x spotlessJavaCheck -x checkstyleMain -x checkstyleTest`
- **Mandatory code formatting (run before every commit):**
  `./gradlew :fineract-provider:spotlessApply`
- **Targeted unit test execution:**
  `./gradlew :fineract-provider:test --tests "org.apache.fineract.portfolio.savings.*"`
