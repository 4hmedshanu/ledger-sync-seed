package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * Relational ledger store backed by H2 and plain JDBC.
 *
 * Legacy SQL data may contain duplicate rows. The store exposes a logical
 * deduplicated ledger and merges message evidence when a transaction is saved
 * more than once.
 */
public final class SqlLedgerStore
        implements LedgerStore, AutoCloseable {

    private static final String URL_PREFIX = "jdbc:h2:";

    private final Connection connection;

    public SqlLedgerStore(Path databaseFile) {
        try {
            connection = DriverManager.getConnection(
                    URL_PREFIX
                            + databaseFile.toAbsolutePath()
                            + ";MODE=PostgreSQL",
                    "sa",
                    "");
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "could not open the ledger database at "
                            + databaseFile
                            + " (is the H2 driver on the runtime classpath?)",
                    exception);
        }
    }

    /**
     * Applies every db/migration/V*.sql in filename order.
     */
    public void migrate(Path migrationDirectory) {
        try (Statement statement =
                     connection.createStatement()) {

            statement.execute(
                    "CREATE TABLE IF NOT EXISTS schema_history ("
                            + "filename VARCHAR(200) PRIMARY KEY,"
                            + "applied_at TIMESTAMP NOT NULL "
                            + "DEFAULT CURRENT_TIMESTAMP)");

            List<Path> files;

            try (var paths = Files.list(migrationDirectory)) {
                files = paths
                        .filter(path ->
                                path.getFileName()
                                        .toString()
                                        .endsWith(".sql"))
                        .sorted()
                        .toList();
            }

            for (Path file : files) {
                String filename =
                        file.getFileName().toString();

                if (wasApplied(filename)) {
                    continue;
                }

                String sql = Files.readString(file);

                for (String sqlStatement : sql.split(";")) {
                    if (!sqlStatement.isBlank()) {
                        statement.execute(sqlStatement);
                    }
                }

                try (PreparedStatement insertHistory =
                             connection.prepareStatement(
                                     "INSERT INTO schema_history(filename) "
                                             + "VALUES (?)")) {

                    insertHistory.setString(1, filename);
                    insertHistory.executeUpdate();
                }

                System.out.println("applied " + filename);
            }
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "migration failed",
                    exception);
        }
    }

    private boolean wasApplied(String filename)
            throws SQLException {

        try (PreparedStatement query =
                     connection.prepareStatement(
                             "SELECT 1 FROM schema_history "
                                     + "WHERE filename = ?")) {

            query.setString(1, filename);

            try (ResultSet resultSet =
                         query.executeQuery()) {

                return resultSet.next();
            }
        }
    }

    @Override
    public void save(NormalizedTxn transaction) {
        try {
            List<StoredRow> matchingRows =
                    findMatchingRows(transaction);

            if (matchingRows.isEmpty()) {
                insert(transaction);
                return;
            }

            InMemoryLedgerStore merger =
                    new InMemoryLedgerStore();

            for (StoredRow storedRow : matchingRows) {
                merger.save(storedRow.transaction());
            }

            merger.save(transaction);

            NormalizedTxn merged =
                    merger.all().get(0);

            if (matchingRows.size() == 1
                    && sameStoredContent(
                    matchingRows.get(0).transaction(),
                    merged)) {

                return;
            }

            replaceRows(matchingRows, merged);
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "could not save " + transaction,
                    exception);
        }
    }

    private List<StoredRow> findMatchingRows(
            NormalizedTxn transaction)
            throws SQLException {

        List<StoredRow> matches =
                new ArrayList<>();

        String sql =
                "SELECT id, account_last4, occurred_at,"
                        + " direction, amount, category, merchant,"
                        + " source_message_ids "
                        + "FROM ledger "
                        + "WHERE account_last4 = ? "
                        + "AND occurred_at = ? "
                        + "AND direction = ? "
                        + "AND amount = ? "
                        + "ORDER BY id";

        try (PreparedStatement query =
                     connection.prepareStatement(sql)) {

            query.setString(
                    1,
                    transaction.accountLast4());

            query.setString(
                    2,
                    transaction.occurredAt().toString());

            query.setString(
                    3,
                    transaction.direction().name());

            query.setBigDecimal(
                    4,
                    transaction.amount());

            try (ResultSet resultSet =
                         query.executeQuery()) {

                while (resultSet.next()) {
                    NormalizedTxn stored =
                            readTransaction(resultSet, 2);

                    if (normalizeMerchant(stored.merchant())
                            .equals(normalizeMerchant(
                                    transaction.merchant()))) {

                        matches.add(new StoredRow(
                                resultSet.getLong(1),
                                stored));
                    }
                }
            }
        }

        return matches;
    }

    private void replaceRows(
            List<StoredRow> rows,
            NormalizedTxn merged)
            throws SQLException {

        boolean originalAutoCommit =
                connection.getAutoCommit();

        try {
            connection.setAutoCommit(false);

            try (PreparedStatement delete =
                         connection.prepareStatement(
                                 "DELETE FROM ledger WHERE id = ?")) {

                for (StoredRow row : rows) {
                    delete.setLong(1, row.id());
                    delete.addBatch();
                }

                delete.executeBatch();
            }

            insert(merged);
            connection.commit();
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(originalAutoCommit);
        }
    }

    private void insert(NormalizedTxn transaction)
            throws SQLException {

        String sql =
                "INSERT INTO ledger("
                        + "account_last4, occurred_at, direction,"
                        + " amount, category, merchant,"
                        + " source_message_ids)"
                        + " VALUES (?,?,?,?,?,?,?)";

        try (PreparedStatement insert =
                     connection.prepareStatement(sql)) {

            insert.setString(
                    1,
                    transaction.accountLast4());

            insert.setString(
                    2,
                    transaction.occurredAt().toString());

            insert.setString(
                    3,
                    transaction.direction().name());

            insert.setBigDecimal(
                    4,
                    transaction.amount());

            insert.setString(
                    5,
                    transaction.category().name());

            insert.setString(
                    6,
                    transaction.merchant());

            insert.setString(
                    7,
                    String.join(
                            ",",
                            transaction.sourceMessageIds()));

            insert.executeUpdate();
        }
    }

    @Override
    public List<NormalizedTxn> all() {
        InMemoryLedgerStore logicalLedger =
                new InMemoryLedgerStore();

        String sql =
                "SELECT account_last4, occurred_at, direction,"
                        + " amount, category, merchant,"
                        + " source_message_ids "
                        + "FROM ledger "
                        + "ORDER BY occurred_at, id";

        try (Statement statement =
                     connection.createStatement();
             ResultSet resultSet =
                     statement.executeQuery(sql)) {

            while (resultSet.next()) {
                logicalLedger.save(
                        readTransaction(resultSet, 1));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "could not read the ledger",
                    exception);
        }

        return List.copyOf(logicalLedger.all());
    }

    private NormalizedTxn readTransaction(
            ResultSet resultSet,
            int firstColumn)
            throws SQLException {

        return new NormalizedTxn(
                resultSet.getString(firstColumn),
                OffsetDateTime.parse(
                        resultSet.getString(firstColumn + 1)),
                Direction.valueOf(
                        resultSet.getString(firstColumn + 2)),
                resultSet.getBigDecimal(firstColumn + 3)
                        .setScale(2),
                Category.valueOf(
                        resultSet.getString(firstColumn + 4)),
                resultSet.getString(firstColumn + 5),
                Arrays.stream(
                                resultSet.getString(firstColumn + 6)
                                        .split(","))
                        .filter(value -> !value.isBlank())
                        .toList());
    }

    private boolean sameStoredContent(
            NormalizedTxn first,
            NormalizedTxn second) {

        return first.accountLast4()
                .equals(second.accountLast4())
                && first.occurredAt()
                .equals(second.occurredAt())
                && first.direction()
                == second.direction()
                && first.amount()
                .compareTo(second.amount()) == 0
                && first.category()
                == second.category()
                && normalizeMerchant(first.merchant())
                .equals(normalizeMerchant(second.merchant()))
                && new TreeSet<>(first.sourceMessageIds())
                .equals(new TreeSet<>(
                        second.sourceMessageIds()));
    }

    private String normalizeMerchant(String merchant) {
        if (merchant == null) {
            return "";
        }

        return merchant
                .trim()
                .replaceAll("\\s+", " ")
                .toUpperCase(Locale.ROOT);
    }

    @Override
    public long count() {
        return all().size();
    }

    public BigDecimal sumAmounts() {
        BigDecimal total =
                BigDecimal.ZERO.setScale(2);

        for (NormalizedTxn transaction : all()) {
            total = total.add(transaction.amount());
        }

        return total.setScale(2);
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Nothing useful can be done while closing.
        }
    }

    private record StoredRow(
            long id,
            NormalizedTxn transaction) {
    }
}