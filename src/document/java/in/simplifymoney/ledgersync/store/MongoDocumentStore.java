package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MongoDB implementation of the document store.
 *
 * The implementation will support the three required access patterns:
 * account/month transactions, category totals and message lookup.
 */
public final class MongoDocumentStore
        implements DocumentStore, AutoCloseable {

    private final String connectionString;
    private final String databaseName;

    public MongoDocumentStore(
            String connectionString,
            String databaseName) {

        this.connectionString = connectionString;
        this.databaseName = databaseName;
    }

    @Override
    public List<NormalizedTxn> forAccountMonth(
            String accountLast4,
            YearMonth month) {

        throw new UnsupportedOperationException(
                "MongoDB account/month query is not implemented");
    }

    @Override
    public Map<Category, BigDecimal> categoryTotals(
            String accountLast4) {

        throw new UnsupportedOperationException(
                "MongoDB category totals query is not implemented");
    }

    @Override
    public Optional<NormalizedTxn> byMessageId(
            String messageId) {

        throw new UnsupportedOperationException(
                "MongoDB message lookup is not implemented");
    }

    @Override
    public void save(NormalizedTxn transaction) {
        throw new UnsupportedOperationException(
                "MongoDB save is not implemented");
    }

    @Override
    public void close() {
        // MongoDB client will be closed here after implementation.
    }
}