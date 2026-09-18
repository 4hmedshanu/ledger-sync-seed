package in.simplifymoney.ledgersync.store;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Indexes.ascending;
import static com.mongodb.client.model.Indexes.compoundIndex;
import static com.mongodb.client.model.Indexes.descending;

import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.ReplaceOptions;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import org.bson.Document;
import org.bson.types.Decimal128;

/**
 * MongoDB implementation of the document store.
 *
 * Transactions use a deterministic ID so saving the same real transaction
 * more than once merges its evidence instead of creating duplicates.
 */
public final class MongoDocumentStore
        implements DocumentStore, AutoCloseable {

    private static final String TRANSACTIONS =
            "transactions";

    private static final String ACCOUNT_TOTALS =
            "account_totals";

    private static final BigDecimal ZERO =
            new BigDecimal("0.00");

    private final MongoClient client;

    private final MongoCollection<Document> transactions;

    private final MongoCollection<Document> accountTotals;

    public MongoDocumentStore(
            String connectionString,
            String databaseName) {

        client = MongoClients.create(connectionString);

        MongoDatabase database =
                client.getDatabase(databaseName);

        transactions =
                database.getCollection(TRANSACTIONS);

        accountTotals =
                database.getCollection(ACCOUNT_TOTALS);

        createIndexes();
    }

    private void createIndexes() {
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
                        .name("message_lookup")
                        .unique(true));
    }

    @Override
    public List<NormalizedTxn> forAccountMonth(
            String accountLast4,
            YearMonth month) {

        List<NormalizedTxn> result =
                new ArrayList<>();

        transactions.find(
                        and(
                                eq("account_last4", accountLast4),
                                eq("month", month.toString())))
                .sort(descending("occurred_at"))
                .map(this::fromDocument)
                .into(result);

        return List.copyOf(result);
    }

    @Override
    public Map<Category, BigDecimal> categoryTotals(
            String accountLast4) {

        Map<Category, BigDecimal> result =
                emptyTotals();

        Document totalsDocument =
                accountTotals.find(
                                eq("_id", accountLast4))
                        .first();

        if (totalsDocument == null) {
            return Map.copyOf(result);
        }

        Document categories =
                totalsDocument.get(
                        "categories",
                        Document.class);

        if (categories == null) {
            return Map.copyOf(result);
        }

        for (Category category : Category.values()) {
            Decimal128 amount =
                    categories.get(
                            category.name(),
                            Decimal128.class);

            if (amount != null) {
                result.put(
                        category,
                        amount.bigDecimalValue()
                                .setScale(2));
            }
        }

        return Map.copyOf(result);
    }

    @Override
    public Optional<NormalizedTxn> byMessageId(
            String messageId) {

        Document document =
                transactions.find(
                                eq(
                                        "source_message_ids",
                                        messageId))
                        .first();

        if (document == null) {
            return Optional.empty();
        }

        return Optional.of(
                fromDocument(document));
    }

    @Override
    public void save(NormalizedTxn transaction) {
        String transactionId =
                transactionId(transaction);

        Document existingDocument =
                transactions.find(
                                eq("_id", transactionId))
                        .first();

        NormalizedTxn transactionToSave =
                existingDocument == null
                        ? transaction
                        : merge(
                                fromDocument(existingDocument),
                                transaction);

        try {
            transactions.replaceOne(
                    eq("_id", transactionId),
                    toDocument(
                            transactionId,
                            transactionToSave),
                    new ReplaceOptions().upsert(true));
        } catch (MongoWriteException exception) {
            throw new IllegalStateException(
                    "A source message is already mapped "
                            + "to another transaction: "
                            + transaction.sourceMessageIds(),
                    exception);
        }

        rebuildTotals(
                transactionToSave.accountLast4());
    }

    private NormalizedTxn merge(
            NormalizedTxn existing,
            NormalizedTxn incoming) {

        TreeSet<String> messageIds =
                new TreeSet<>();

        messageIds.addAll(
                existing.sourceMessageIds());

        messageIds.addAll(
                incoming.sourceMessageIds());

        return new NormalizedTxn(
                existing.accountLast4(),
                existing.occurredAt(),
                existing.direction(),
                existing.amount(),
                incoming.category(),
                incoming.merchant(),
                new ArrayList<>(messageIds));
    }

    private void rebuildTotals(
            String accountLast4) {

        Map<Category, BigDecimal> totals =
                emptyTotals();

        for (Document document :
                transactions.find(
                        eq(
                                "account_last4",
                                accountLast4))) {

            NormalizedTxn transaction =
                    fromDocument(document);

            totals.put(
                    transaction.category(),
                    totals.get(transaction.category())
                            .add(transaction.amount())
                            .setScale(2));
        }

        Document categoryDocument =
                new Document();

        for (Category category : Category.values()) {
            categoryDocument.append(
                    category.name(),
                    new Decimal128(
                            totals.get(category)
                                    .setScale(2)));
        }

        Document totalsDocument =
                new Document("_id", accountLast4)
                        .append(
                                "categories",
                                categoryDocument);

        accountTotals.replaceOne(
                eq("_id", accountLast4),
                totalsDocument,
                new ReplaceOptions().upsert(true));
    }

    private Map<Category, BigDecimal> emptyTotals() {
        Map<Category, BigDecimal> totals =
                new EnumMap<>(Category.class);

        for (Category category : Category.values()) {
            totals.put(category, ZERO);
        }

        return totals;
    }

    private Document toDocument(
            String transactionId,
            NormalizedTxn transaction) {

        return new Document(
                "_id",
                transactionId)
                .append(
                        "account_last4",
                        transaction.accountLast4())
                .append(
                        "month",
                        YearMonth.from(
                                        transaction.occurredAt())
                                .toString())
                .append(
                        "occurred_at",
                        Date.from(
                                transaction.occurredAt()
                                        .toInstant()))
                .append(
                        "occurred_at_text",
                        transaction.occurredAt()
                                .toString())
                .append(
                        "direction",
                        transaction.direction()
                                .name())
                .append(
                        "amount",
                        new Decimal128(
                                transaction.amount()
                                        .setScale(2)))
                .append(
                        "category",
                        transaction.category()
                                .name())
                .append(
                        "merchant",
                        transaction.merchant())
                .append(
                        "source_message_ids",
                        new ArrayList<>(
                                transaction
                                        .sourceMessageIds()));
    }

    private NormalizedTxn fromDocument(
            Document document) {

        Decimal128 decimalAmount =
                document.get(
                        "amount",
                        Decimal128.class);

        List<String> messageIds =
                document.getList(
                        "source_message_ids",
                        String.class);

        if (messageIds == null) {
            messageIds = List.of();
        }

        return new NormalizedTxn(
                document.getString("account_last4"),
                OffsetDateTime.parse(
                        document.getString(
                                "occurred_at_text")),
                Direction.valueOf(
                        document.getString(
                                "direction")),
                decimalAmount.bigDecimalValue()
                        .setScale(2),
                Category.valueOf(
                        document.getString(
                                "category")),
                document.getString("merchant"),
                List.copyOf(messageIds));
    }

    private String transactionId(
            NormalizedTxn transaction) {

        String identity =
                transaction.accountLast4()
                        + "|"
                        + transaction.occurredAt()
                        + "|"
                        + transaction.direction()
                        + "|"
                        + transaction.amount()
                                .setScale(2)
                                .toPlainString()
                        + "|"
                        + normalizeMerchant(
                                transaction.merchant());

        try {
            MessageDigest digest =
                    MessageDigest.getInstance(
                            "SHA-256");

            byte[] hash =
                    digest.digest(
                            identity.getBytes(
                                    StandardCharsets.UTF_8));

            return HexFormat.of()
                    .formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable",
                    exception);
        }
    }

    private String normalizeMerchant(
            String merchant) {

        if (merchant == null) {
            return "";
        }

        return merchant
                .trim()
                .replaceAll("\\s+", " ")
                .toUpperCase(Locale.ROOT);
    }

    @Override
    public void close() {
        client.close();
    }
}