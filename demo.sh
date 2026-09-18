#!/usr/bin/env bash

set -euo pipefail

cd "$(dirname "$0")"

echo "==> starting MongoDB"
docker compose up -d --wait

echo
echo "==> preparing clean corpus SQL database"
rm -f \
  data/demo-corpus-ledger.mv.db \
  data/demo-corpus-ledger.trace.db

export LEDGER_DB_PATH="data/demo-corpus-ledger"

./gradlew run --args="migrate"
./gradlew run --args="ingest fixtures/corpus-a.jsonl"

echo
echo "==> proving repeated ingestion is idempotent"
./gradlew run --args="ingest fixtures/corpus-a.jsonl"

echo
echo "==> writing submission reports"
./gradlew run --args="report submission"

echo
echo "==> preparing dirty legacy SQL database"
rm -f \
  data/demo-legacy-ledger.mv.db \
  data/demo-legacy-ledger.trace.db

export LEDGER_DB_PATH="data/demo-legacy-ledger"
./gradlew run --args="migrate-legacy"

echo
echo "==> resetting demo MongoDB database"
docker compose exec -T mongodb \
  mongosh ledger_sync_demo \
  --quiet \
  --eval "db.dropDatabase()"

export MONGO_DATABASE="ledger_sync_demo"

echo
echo "==> backfilling SQL into MongoDB"
./gradlew documentStore --args="backfill"

echo
echo "==> proving backfill is idempotent"
./gradlew documentStore --args="backfill"

echo
echo "==> checking SQL and MongoDB consistency"
./gradlew documentStore --args="check"

echo
echo "==> running tests"
./gradlew test

echo
echo "==> running dependency-free verifier"
./verify.sh

echo
echo "==> demo complete"