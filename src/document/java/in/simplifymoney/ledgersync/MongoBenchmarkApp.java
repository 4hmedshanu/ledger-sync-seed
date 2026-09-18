package in.simplifymoney.ledgersync;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Indexes.ascending;
import static com.mongodb.client.model.Indexes.compoundIndex;
import static com.mongodb.client.model.Indexes.descending;

import com.mongodb.ExplainVerbosity;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import in.simplifymoney.ledgersync.model.Category;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.bson.types.Decimal128;

/**
 * Loads exactly 100,000 synthetic transactions and reports MongoDB
 * execution statistics for the three required access patterns.
 */
public final class MongoBenchmarkApp {

    private static final int TRANSACTION_COUNT =
            100_000;

    private static final int ACCOUNT_COUNT =
            100;

    private static final int MONTH_COUNT =
            24;

    private static final int BATCH_SIZE =
            1_000;

    private static final String DEFAULT_MONGO_URI =
            "mongodb://localhost:27018";

    private static final String DEFAULT_DATABASE =
            "ledger_sync_benchmark";

    private MongoBenchmarkApp() {
    }

    public static void main(String[] args) {
        String mongoUri =
                configuration(
                        "MONGO_URI",
                        DEFAULT_MONGO_URI);

        String databaseName =
                configuration(
                        "MONGO_BENCHMARK_DATABASE",
                        DEFAULT_DATABASE);

        if (!databaseName
                .toLowerCase()
                .contains("benchmark")) {

            throw new IllegalArgumentException(
                    "Benchmark database name must contain "
                            + "'benchmark': "
                            + databaseName);
        }

        try (MongoClient client =
                     MongoClients.create(mongoUri)) {

            MongoDatabase database =
                    client.getDatabase(databaseName);

            // Only the explicitly named benchmark database is deleted.
            database.drop();

            MongoCollection<Document> transactions =
                    database.getCollection(
                            "transactions");

            MongoCollection<Document> accountTotals =
                    database.getCollection(
                            "account_totals");

            long[][] categoryCounts =
                    loadTransactions(transactions);

            loadAccountTotals(
                    accountTotals,
                    categoryCounts);

            createIndexes(transactions);

            System.out.println(
                    "loaded transactions: "
                            + transactions.countDocuments());

            reportQueryStatistics(
                    transactions,
                    accountTotals);
        }
    }

    private static long[][] loadTransactions(
            MongoCollection<Document> transactions) {

        long[][] categoryCounts =
                new long[ACCOUNT_COUNT]
                        [Category.values().length];

        List<Document> batch =
                new ArrayList<>(BATCH_SIZE);

        OffsetDateTime baseTime =
                OffsetDateTime.parse(
                        "2025-01-01T00:00:00+05:30");

        for (int index = 0;
             index < TRANSACTION_COUNT;
             index++) {

            int accountIndex =
                    index % ACCOUNT_COUNT;

            int monthIndex =
                    (index / ACCOUNT_COUNT)
                            % MONTH_COUNT;

            String account =
                    String.format(
                            "%04d",
                            1000 + accountIndex);

            OffsetDateTime occurredAt =
                    baseTime
                            .plusMonths(monthIndex)
                            .plusSeconds(
                                    index
                                            / (ACCOUNT_COUNT
                                            * MONTH_COUNT));

            Category category =
                    Category.values()[
                            index
                                    % Category.values().length];

            categoryCounts[
                    accountIndex][category.ordinal()]++;

            Document transaction =
                    new Document(
                            "_id",
                            "txn-" + index)
                            .append(
                                    "account_last4",
                                    account)
                            .append(
                                    "month",
                                    YearMonth.from(
                                                    occurredAt)
                                            .toString())
                            .append(
                                    "occurred_at",
                                    Date.from(
                                            occurredAt.toInstant()))
                            .append(
                                    "occurred_at_text",
                                    occurredAt.toString())
                            .append(
                                    "direction",
                                    index % 2 == 0
                                            ? "DEBIT"
                                            : "CREDIT")
                            .append(
                                    "amount",
                                    new Decimal128(
                                            new BigDecimal(
                                                    "1.00")))
                            .append(
                                    "category",
                                    category.name())
                            .append(
                                    "merchant",
                                    "BENCHMARK MERCHANT "
                                            + (index % 1000))
                            .append(
                                    "source_message_ids",
                                    List.of(
                                            "m-benchmark-"
                                                    + index));

            batch.add(transaction);

            if (batch.size() == BATCH_SIZE) {
                transactions.insertMany(batch);
                batch.clear();
            }
        }

        if (!batch.isEmpty()) {
            transactions.insertMany(batch);
        }

        return categoryCounts;
    }

    private static void loadAccountTotals(
            MongoCollection<Document> accountTotals,
            long[][] categoryCounts) {

        List<Document> documents =
                new ArrayList<>(ACCOUNT_COUNT);

        for (int accountIndex = 0;
             accountIndex < ACCOUNT_COUNT;
             accountIndex++) {

            String account =
                    String.format(
                            "%04d",
                            1000 + accountIndex);

            Document categories =
                    new Document();

            for (Category category :
                    Category.values()) {

                BigDecimal total =
                        BigDecimal.valueOf(
                                        categoryCounts[
                                                accountIndex]
                                                [category.ordinal()])
                                .setScale(2);

                categories.append(
                        category.name(),
                        new Decimal128(total));
            }

            documents.add(
                    new Document("_id", account)
                            .append(
                                    "categories",
                                    categories));
        }

        accountTotals.insertMany(documents);
    }

    private static void createIndexes(
            MongoCollection<Document> transactions) {

        transactions.createIndex(
                compoundIndex(
                        ascending("account_last4"),
                        ascending("month"),
                        descending("occurred_at")),
                new IndexOptions()
                        .name("account_month_newest"));

        transactions.createIndex(
                ascending("source_message_ids"),
                new IndexOptions()
                        .name("message_lookup"));
    }

    private static void reportQueryStatistics(
            MongoCollection<Document> transactions,
            MongoCollection<Document> accountTotals) {

        Document accountMonthExplain =
                transactions.find(
                                and(
                                        eq(
                                                "account_last4",
                                                "1000"),
                                        eq(
                                                "month",
                                                "2025-01")))
                        .sort(
                                descending("occurred_at"))
                        .explain(
                                ExplainVerbosity
                                        .EXECUTION_STATS);

        Document categoryTotalsExplain =
                accountTotals.find(
                                eq("_id", "1000"))
                        .explain(
                                ExplainVerbosity
                                        .EXECUTION_STATS);

        Document messageLookupExplain =
                transactions.find(
                                eq(
                                        "source_message_ids",
                                        "m-benchmark-50000"))
                        .explain(
                                ExplainVerbosity
                                        .EXECUTION_STATS);

        printStatistics(
                "Q1 account/month newest first",
                accountMonthExplain);

        printStatistics(
                "Q2 category totals",
                categoryTotalsExplain);

        printStatistics(
                "Q3 message lookup",
                messageLookupExplain);
    }

    private static void printStatistics(
            String queryName,
            Document explain) {

        Document executionStats =
                explain.get(
                        "executionStats",
                        Document.class);

        long examined =
                ((Number) executionStats.get(
                        "totalDocsExamined"))
                        .longValue();

        long returned =
                ((Number) executionStats.get(
                        "nReturned"))
                        .longValue();

        System.out.println(queryName);
        System.out.println(
                "  examined: " + examined);
        System.out.println(
                "  returned: " + returned);
    }

    private static String configuration(
            String name,
            String defaultValue) {

        String value =
                System.getenv(name);

        if (value == null || value.isBlank()) {
            return defaultValue;
        }

        return value;
    }
}