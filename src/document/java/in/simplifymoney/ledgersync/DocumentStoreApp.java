package in.simplifymoney.ledgersync;

import in.simplifymoney.ledgersync.store.Backfill;
import in.simplifymoney.ledgersync.store.ConsistencyChecker;
import in.simplifymoney.ledgersync.store.MongoDocumentStore;
import in.simplifymoney.ledgersync.store.SqlLedgerStore;
import java.nio.file.Path;
import java.util.List;

/**
 * Commands used while moving the ledger from SQL to MongoDB.
 *
 * Environment variables:
 * LEDGER_DB_PATH - H2 database path
 * MONGO_URI      - MongoDB connection string
 * MONGO_DATABASE - MongoDB database name
 */
public final class DocumentStoreApp {

    private static final String DEFAULT_SQL_DATABASE =
            "data/ledger";

    private static final String DEFAULT_MONGO_URI =
            "mongodb://localhost:27018";

    private static final String DEFAULT_MONGO_DATABASE =
            "ledger_sync";

    private DocumentStoreApp() {
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            printUsage();
            return;
        }

        Path sqlDatabase =
                Path.of(configuration(
                        "LEDGER_DB_PATH",
                        DEFAULT_SQL_DATABASE));

        String mongoUri =
                configuration(
                        "MONGO_URI",
                        DEFAULT_MONGO_URI);

        String mongoDatabase =
                configuration(
                        "MONGO_DATABASE",
                        DEFAULT_MONGO_DATABASE);

        System.out.println(
                "SQL database: " + sqlDatabase);

        System.out.println(
                "Mongo database: " + mongoDatabase);

        try (SqlLedgerStore sql =
                     new SqlLedgerStore(sqlDatabase);
             MongoDocumentStore documents =
                     new MongoDocumentStore(
                             mongoUri,
                             mongoDatabase)) {

            switch (args[0]) {
                case "backfill" ->
                        runBackfill(sql, documents);

                case "check" ->
                        runConsistencyCheck(
                                sql,
                                documents);

                default ->
                        printUsage();
            }
        }
    }

    private static void runBackfill(
            SqlLedgerStore sql,
            MongoDocumentStore documents) {

        Backfill.Result result =
                new Backfill(sql, documents).run();

        System.out.println(
                "BACKFILL");

        System.out.println(
                "  SQL transactions read  "
                        + result.read());

        System.out.println(
                "  Mongo documents written "
                        + result.written());

        System.out.println(
                "  documents skipped       "
                        + result.skipped());
    }

    private static void runConsistencyCheck(
            SqlLedgerStore sql,
            MongoDocumentStore documents) {

        List<ConsistencyChecker.Divergence> differences =
                new ConsistencyChecker(
                        sql,
                        documents)
                        .check();

        System.out.println(
                "CONSISTENCY CHECK");

        if (differences.isEmpty()) {
            System.out.println(
                    "  SQL and MongoDB agree");
            return;
        }

        System.out.println(
                "  divergences found: "
                        + differences.size());

        for (ConsistencyChecker.Divergence difference :
                differences) {

            System.out.println();
            System.out.println(
                    "  what: "
                            + difference.what());

            System.out.println(
                    "  SQL: "
                            + difference.inSql());

            System.out.println(
                    "  MongoDB: "
                            + difference.inDocuments());
        }
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

    private static void printUsage() {
        System.err.println(
                "usage: backfill | check");
    }
}