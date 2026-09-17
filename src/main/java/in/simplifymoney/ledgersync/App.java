package in.simplifymoney.ledgersync;

import in.simplifymoney.ledgersync.ingest.IngestService;
import in.simplifymoney.ledgersync.json.Json;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.report.Reports;
import in.simplifymoney.ledgersync.store.SqlLedgerStore;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Command line entry point.
 *
 * migrate                  create a clean ledger database
 * migrate-legacy           create the ledger and load dirty legacy SQL rows
 * ingest <corpus.jsonl>    read a corpus into the ledger
 * report <out-dir>         write the three report files
 *
 * Set LEDGER_DB_PATH to select a different database file.
 */
public final class App {

    private static final Path DEFAULT_DATABASE =
            Path.of("data", "ledger");

    private static final Path MIGRATIONS =
            Path.of("db", "migration");

    private static final Path LEGACY_MIGRATIONS =
            Path.of("db", "legacy");

    private App() {
    }

    public static void main(String[] args)
            throws Exception {

        if (args.length == 0) {
            printUsage();
            System.exit(2);
        }

        Path database = databasePath();
        Path parent = database.getParent();

        if (parent != null) {
            Files.createDirectories(parent);
        }

        switch (args[0]) {
            case "migrate" ->
                    migrate(database, false);

            case "migrate-legacy" ->
                    migrate(database, true);

            case "ingest" -> {
                if (args.length < 2) {
                    throw new IllegalArgumentException(
                            "ingest needs a corpus");
                }

                try (SqlLedgerStore store =
                             new SqlLedgerStore(database)) {

                    store.migrate(MIGRATIONS);

                    var stats =
                            new IngestService(
                                    new Parsers(),
                                    store)
                                    .ingestFile(
                                            Path.of(args[1]));

                    System.out.println(stats);
                    System.out.println(
                            "ledger rows: "
                                    + store.count());
                }
            }

            case "report" -> {
                if (args.length < 2) {
                    throw new IllegalArgumentException(
                            "report needs a directory");
                }

                Path outputDirectory =
                        Path.of(args[1]);

                Files.createDirectories(
                        outputDirectory);

                try (SqlLedgerStore store =
                             new SqlLedgerStore(database)) {

                    store.migrate(MIGRATIONS);

                    var ledger = store.all();

                    Files.writeString(
                            outputDirectory.resolve(
                                    "ledger.json"),
                            Json.writePretty(
                                    Reports.ledgerDocument(
                                            ledger)));

                    Files.writeString(
                            outputDirectory.resolve(
                                    "summary.json"),
                            Json.writePretty(
                                    Reports.summary(
                                            ledger)));

                    Files.writeString(
                            outputDirectory.resolve(
                                    "reconciliation.json"),
                            Json.writePretty(
                                    Reports.reconciliation(
                                            ledger)));

                    System.out.println(
                            "wrote 3 files to "
                                    + outputDirectory);

                    System.out.println(
                            "reported transactions: "
                                    + ledger.size());
                }
            }

            default -> {
                System.err.println(
                        "unknown command: "
                                + args[0]);

                printUsage();
                System.exit(2);
            }
        }
    }

    private static void migrate(
            Path database,
            boolean includeLegacyRows) {

        try (SqlLedgerStore store =
                     new SqlLedgerStore(database)) {

            store.migrate(MIGRATIONS);

            if (includeLegacyRows) {
                store.migrate(
                        LEGACY_MIGRATIONS);
            }

            System.out.println(
                    "database: " + database);

            System.out.println(
                    "ledger rows: "
                            + store.count());
        }
    }

    private static Path databasePath() {
        String configuredPath =
                System.getenv("LEDGER_DB_PATH");

        if (configuredPath == null
                || configuredPath.isBlank()) {

            return DEFAULT_DATABASE;
        }

        return Path.of(configuredPath);
    }

    private static void printUsage() {
        System.err.println(
                "usage: migrate | migrate-legacy | "
                        + "ingest <corpus.jsonl> | "
                        + "report <out-dir>");
    }
}