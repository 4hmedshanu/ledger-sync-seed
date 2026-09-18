# Simplify Money Ledger Sync

Java 21 service that converts bank SMS and email uploads into a deduplicated, categorized, traceable ledger. It produces:

- `ledger.json`
- `summary.json`
- `reconciliation.json`

This repository contains the engineering work for Tasks 2–4. The app teardown, referral feedback, screenshots and other product-facing submission items are supplied separately.

## Quick start

Requirements:

- JDK 21
- Docker Desktop with Docker Compose
- Git Bash on Windows

One command runs the complete reproducible walkthrough:

```bash
bash ./demo.sh
```

On Windows PowerShell:

```powershell
& "C:\Program Files\Git\bin\bash.exe" ./demo.sh
```

The script:

1. starts MongoDB through Docker Compose;
2. creates a clean H2 database;
3. ingests `fixtures/corpus-a.jsonl`;
4. ingests it again to prove idempotency;
5. writes all three reports to `submission/`;
6. creates the dirty legacy SQL database;
7. backfills it into MongoDB;
8. runs the backfill again to prove idempotency;
9. checks SQL and MongoDB field-by-field;
10. runs the full test suite and `verify.sh`.

Start only the document store with:

```bash
docker compose up -d
```

MongoDB runs on `localhost:27018` and persists data in a named Docker volume.

## Verified corpus result

The completed pipeline reads 522 messages and produces 257 real transactions:

```text
messages read          522
transactions written   257
messages skipped        43

SPEND       150067.64
INCOME      142791.16
MICRO         4443.85
TRANSFER     62000.00
```

Balance reconciliation:

```text
Account 4821: 146 transactions, difference 0.00
Account 9075:  91 transactions, difference 0.00
```

The second ingestion writes zero transactions, so rerunning the same or overlapping corpus does not change the ledger.

## Manual commands

### Tests and offline verifier

```bash
./gradlew test
./verify.sh
```

`verify.sh` intentionally compiles `src/main/java` using only JDK 21. MongoDB-specific code lives in `src/document/java`, so the original offline verifier remains dependency-free.

### Clean corpus database

PowerShell:

```powershell
$env:LEDGER_DB_PATH = "data/corpus-a-ledger"

.\gradlew.bat run --args="migrate"
.\gradlew.bat run --args="ingest fixtures/corpus-a.jsonl"
.\gradlew.bat run --args="report submission"
```

Expected report transaction count: `257`.

### Legacy SQL database

```powershell
$env:LEDGER_DB_PATH = "data/legacy-ledger"

.\gradlew.bat run --args="migrate-legacy"
```

`db/legacy/V2__seed.sql` deliberately contains duplicate and historically incorrect rows. It is separated from the clean corpus migrations so production-history fixtures do not pollute corpus reports.

### MongoDB backfill

```powershell
$env:LEDGER_DB_PATH = "data/legacy-ledger"
$env:MONGO_DATABASE = "ledger_sync"

.\gradlew.bat documentStore --args="backfill"
```

Running the same command again skips every existing logical transaction.

### Consistency checker

```powershell
.\gradlew.bat documentStore --args="check"
```

A successful result is:

```text
CONSISTENCY CHECK
  SQL and MongoDB agree
```

The checker does not compare only row counts. It compares logical transactions, every transaction field, source evidence and category totals, and reports exact SQL and MongoDB values when they differ.

## Pipeline design

```text
Raw JSONL messages
        |
        v
Trusted sender/channel selection
        |
        v
Bank-specific SMS and email parsers
        |
        v
Parsed transactions
        |
        +--> duplicate evidence merge
        +--> MICRO classification
        +--> own-account transfer matching
        +--> balance-gap reconciliation
        |
        v
Normalized ledger
        |
        +--> ledger.json
        +--> summary.json
        +--> reconciliation.json
        +--> H2 SQL store
        +--> MongoDB document store
```

The parser uses the transaction time stated by the bank, not the message upload time. Money is represented with `BigDecimal` in Java and `Decimal128` in MongoDB.

## Transaction identity and idempotency

`message_id` identifies an upload, not a real transaction. The same transaction may be supported by an SMS, an email and duplicate uploads.

A logical transaction is identified using:

- account last four digits;
- bank-stated occurrence time;
- direction;
- amount;
- normalized merchant.

MongoDB uses a deterministic SHA-256 hash of these values as `_id`. Repeated saves therefore replace or merge the same transaction instead of inserting another one. Source message IDs are merged and sorted.

## Categories

Every ledger transaction has exactly one category:

- `SPEND`: debit that is not micro spending or an internal transfer;
- `INCOME`: credit that is not an internal transfer;
- `MICRO`: UPI debit of ₹100.00 or less;
- `TRANSFER`: matched debit and credit legs between owned accounts.

Transfers are matched using different owned accounts, equal amounts, opposite directions, normalized merchant names and a five-minute time window.

`summary.json` excludes `MICRO` and `TRANSFER` from spend, and excludes `TRANSFER` from income.

## Balance reconciliation

Bank alerts containing stated balances act as checkpoints.

For each account, transactions are ordered by occurrence time. Starting from one stated balance, the reconciler applies all later parsed debits and credits. When the next stated balance differs from the expected balance, it records the unexplained movement.

Corpus A contains one inferred debit:

```text
Account: 4821
Amount: 7500.00
Merchant: UNATTRIBUTED BALANCE MOVEMENT
```

The amount is supported by balance checkpoints, but the exact merchant and exact transaction time are unknown. `reconciliation.json` reports that uncertainty instead of inventing details.

## Incident INC-2026-09-11

The customer spent ₹5 on a water can but was shown ₹92,213.10.

The original amount regular expression accepted decimal amounts but not whole-rupee amounts. It skipped `Rs.5` and selected the later available balance `Rs.92,213.10` as the transaction amount.

The investigation found:

- 28 affected message uploads;
- 19 unique affected transactions;
- the failure occurred when a whole-rupee transaction amount appeared before a decimal balance;
- the old tests stayed green because they covered decimal transaction amounts only.

The regression test uses the exact production-shaped ₹5 message. The five-line incident-channel note is in:

```text
incident/INC-2026-09-11-resolution.md
```

## Why MongoDB

I chose MongoDB instead of DynamoDB for this submission because it provides a real document database locally through Docker Compose, requires no cloud account, and exposes `totalDocsExamined` and `nReturned` directly through execution statistics.

The access patterns are fixed and small, so the model is designed around those queries instead of treating MongoDB like a relational table.

## Document model

### `transactions`

One document per logical transaction:

```json
{
  "_id": "deterministic SHA-256 transaction identity",
  "account_last4": "4821",
  "month": "2026-07",
  "occurred_at": "BSON date",
  "occurred_at_text": "2026-07-04T20:24:00+05:30",
  "direction": "DEBIT",
  "amount": "Decimal128(2499.50)",
  "category": "SPEND",
  "merchant": "AMAZON PAY",
  "source_message_ids": [
    "m-sms",
    "m-email"
  ]
}
```

Indexes:

```javascript
{
  account_last4: 1,
  month: 1,
  occurred_at: -1
}
```

This serves account/month transactions in newest-first order.

```javascript
{
  source_message_ids: 1
}
```

This serves message-ID lookup using a multikey index.

The message index is intentionally not unique. Real corpus reconciliation showed that one balance-checkpoint message can support its directly parsed transaction and an inferred balance movement.

### `account_totals`

One document per account:

```json
{
  "_id": "4821",
  "categories": {
    "SPEND": "Decimal128(...)",
    "INCOME": "Decimal128(...)",
    "MICRO": "Decimal128(...)",
    "TRANSFER": "Decimal128(...)"
  }
}
```

This makes category totals a direct `_id` lookup instead of a transaction collection scan.

## Query benchmark at 100,000 transactions

Run:

```bash
./gradlew documentBenchmark
```

The benchmark resets only the specifically named benchmark database, loads exactly 100,000 synthetic transactions, creates the production indexes and obtains MongoDB `executionStats`.

Measured results on MongoDB 8.0:

| Access pattern | Documents examined | Documents returned |
|---|---:|---:|
| One account's transactions for one month, newest first | 42 | 42 |
| Running totals per category for one account | 1 | 1 |
| Transaction lookup by message ID | 1 | 1 |

These are real `totalDocsExamined` and `nReturned` values, not estimates.

## Backfill

`Backfill` reads `SqlLedgerStore.all()`, which first merges dirty physical SQL duplicates into logical transactions.

For each transaction it:

1. queries the target account and month;
2. matches the logical transaction identity;
3. skips identical content;
4. writes only missing or changed content.

This makes the operation safe to rerun after success or partial failure.

Demonstrated result using the dirty legacy database:

```text
First run:
  SQL transactions read   10
  Mongo documents written 10
  documents skipped        0

Second run:
  SQL transactions read   10
  Mongo documents written  0
  documents skipped       10
```

## Consistency checker

The checker groups SQL transactions by account and month and compares them with the document-store query results.

It detects and names:

- missing transactions;
- extra transactions within checked account/month partitions;
- changed account, time, direction, amount, category or merchant;
- changed source-message evidence;
- incorrect per-account category totals;
- missing or incorrect unambiguous message mappings.

Shared reconciliation evidence is matched by logical transaction identity instead of assuming that every message ID is globally unique.

## Decision log

### 1. Keep the core pipeline dependency-free

I kept parsing, ingestion and reporting under `src/main/java` with JDK-only code. MongoDB integration lives in `src/document/java`. This preserves the provided offline `verify.sh` while allowing Gradle to compile the complete application.

### 2. Parse the first valid monetary amount

The incident initially looked like a balance-selection problem. The data showed that the real cause was the amount regex rejecting whole rupees. I changed amount recognition to accept both `Rs.5` and `Rs.5.00`, while still choosing the first valid monetary amount.

### 3. Trust sender and channel before parsing content

The corpus contains messages that mention money but are not transactions, including hostile content. Parsers are registered for known bank sender/channel combinations instead of accepting any text that looks financial.

### 4. Treat message IDs as evidence, not transaction identity

Duplicate SMS/email uploads can describe one real transaction. I rejected message ID as the ledger key and deduplicate using account, occurrence time, direction, amount and normalized merchant. All evidence IDs are retained.

### 5. Apply MICRO only to qualifying debits

The ₹100 boundary is inclusive, but only for UPI debits. Small non-UPI debits remain `SPEND`, and small UPI credits remain `INCOME`.

### 6. Infer transfers from both legs

Merchant text alone was not enough to classify a transfer. I require two owned accounts, equal amount, opposite directions, normalized merchant equality and occurrence within five minutes. An unmatched self-looking credit remains income.

### 7. Use balances as checkpoints, not row-level answers

A balance gap proves an unexplained movement but does not prove its merchant or exact timestamp. I add a clearly unattributed transaction and expose it in reconciliation instead of fabricating details.

### 8. Separate clean and legacy SQL data

The seed migration contains dirty production-history rows. Loading them into the corpus database produced 267 report rows instead of 257. I moved the seed rows into `db/legacy` and added `migrate-legacy`, keeping corpus reporting and migration testing separate.

### 9. Choose MongoDB and denormalize totals

MongoDB runs locally in Docker and supports direct query-plan measurements. I store transaction documents plus one totals document per account. The duplicate totals are deliberate because category totals are a required direct access pattern.

### 10. Remove uniqueness from the message index

My first Mongo model used a unique multikey index for `source_message_ids`. Real backfill failed with `E11000` because reconciliation evidence is shared. I removed uniqueness and changed backfill and consistency matching to use logical transaction identity.

## What the data made me decide

- HDFC whole-rupee messages required amount parsing without mandatory decimals.
- ICICI had more than one SMS shape, including compact debit and credit alerts.
- Trusted bank emails had to be parsed, while unrelated emails had to remain skipped.
- Multiple uploads could represent one transaction, so evidence had to merge.
- UPI debit amount `100.00` belongs to `MICRO`; `100.01` belongs to `SPEND`.
- Matching own-account transfer legs must work even when they arrive in separate ingestion calls.
- The final ₹7,500 account-4821 discrepancy was visible only by carrying expected balance across messages without stated balances.
- Reconciliation checkpoint messages can be shared evidence, so source IDs cannot be a unique document key.
- Credit-card account `3310` reports available limit rather than savings-account balance, so it is not reconciled like accounts `4821` and `9075`.

With more time I would test more sender variations and collect additional real bank formats before broadening any regular expressions.

## Testing

The suite contains focused tests for:

- frozen model contract;
- whole-rupee incident regression;
- trusted bank email parsing;
- compact ICICI formats;
- transaction deduplication and evidence merging;
- repeated-ingestion idempotency;
- MICRO boundary rules;
- transfer matching, including separate arrival;
- balance-gap inference;
- summary and reconciliation reports;
- SQL-store deduplication;
- repeatable backfill;
- precise consistency differences;
- MongoDB access patterns and idempotent writes.

Commands:

```bash
./gradlew test
./verify.sh
```

## Environment variables

| Variable | Default | Purpose |
|---|---|---|
| `LEDGER_DB_PATH` | `data/ledger` | H2 SQL database path |
| `MONGO_URI` | `mongodb://localhost:27018` | MongoDB connection |
| `MONGO_DATABASE` | `ledger_sync` | Backfill/check database |
| `MONGO_BENCHMARK_DATABASE` | `ledger_sync_benchmark` | Disposable benchmark database |

## AI disclosure

I used ChatGPT/Codex for brainstorming tests, reviewing parser edge cases, proposing MongoDB document designs, explaining failures and drafting command sequences. I did not accept output only because it compiled: each change was checked with focused tests, the full suite, `verify.sh`, corpus totals, repeated ingestion, repeated backfill and a real SQL-to-Mongo consistency run.

One concrete case where AI output was wrong:

Initial AI-proposed index:

```java
transactions.createIndex(
        ascending("source_message_ids"),
        new IndexOptions()
                .name("message_lookup")
                .unique(true));
```

This sounded reasonable because the interface asks for lookup by message ID. Real corpus backfill rejected it with `E11000 duplicate key` because balance-reconciliation transactions share checkpoint message IDs with parsed transactions.

Final version:

```java
transactions.createIndex(
        ascending("source_message_ids"),
        new IndexOptions()
                .name("message_lookup"));
```

I also changed backfill and consistency checking from message-ID identity to logical transaction identity. The important difference is that the final design follows observed data rather than the plausible but false assumption that one source message can evidence only one ledger row.

## Known limitations and unfinished work

- MongoDB is configured for local assignment use without authentication or TLS.
- Category totals are rebuilt for the affected account after a save. This favors simple recovery and correctness at the assignment scale, but a production version should use transactions, change streams or a repairable event-driven projection.
- The current document-store contract returns one transaction for a message ID, while reconciliation evidence can support more than one ledger row. Direct parsed transactions are still queryable, and consistency checking handles shared evidence through logical identity, but a production API should explicitly distinguish direct evidence from supporting checkpoint evidence.
- The consistency contract has no collection-wide “list every account” query. The checker detects extra documents inside SQL-known account/month partitions and total differences; a production audit interface should additionally enumerate document-only accounts.
- Task 0 referral material and Task 1 app teardown are external submission documents and are not stored in this source repository.

## Submission artifacts

Generate:

```bash
bash ./demo.sh
```

Then attach separately:

- public repository link;
- walkthrough recording;
- `submission/ledger.json`;
- `submission/summary.json`;
- `submission/reconciliation.json`;
- `incident/INC-2026-09-11-resolution.md`;
- Task 0 one-pager;
- Task 1 Track teardown;
- updated CV.