package in.simplifymoney.ledgersync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.store.MongoDocumentStore;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MongoDocumentStoreTest {

    private static final String URI =
            "mongodb://localhost:27018";

    private MongoClient client;
    private MongoDocumentStore store;
    private String databaseName;

    @BeforeEach
    void setUp() {
        try {
            client = MongoClients.create(URI);
            client.getDatabase("admin")
                    .runCommand(new Document("ping", 1));
        } catch (RuntimeException exception) {
            if (client != null) {
                client.close();
            }

            Assumptions.assumeTrue(
                    false,
                    "MongoDB is not available on port 27018");
        }

        databaseName = "ledger_sync_test_"
                + UUID.randomUUID()
                .toString()
                .replace("-", "");

        store = new MongoDocumentStore(
                URI,
                databaseName);
    }

    @AfterEach
    void cleanUp() {
        if (client != null && databaseName != null) {
            client.getDatabase(databaseName).drop();
        }

        if (store != null) {
            store.close();
        }

        if (client != null) {
            client.close();
        }
    }

    @Test
    void supportsRequiredQueriesAndIdempotentWrites() {
        NormalizedTxn spend = transaction(
                "4821",
                "2026-07-04T20:24:00+05:30",
                Direction.DEBIT,
                "2499.50",
                Category.SPEND,
                "AMAZON PAY",
                List.of("m-sms"));

        NormalizedTxn duplicateEvidence = transaction(
                "4821",
                "2026-07-04T20:24:00+05:30",
                Direction.DEBIT,
                "2499.50",
                Category.SPEND,
                "AMAZON PAY",
                List.of("m-email"));

        NormalizedTxn income = transaction(
                "4821",
                "2026-07-05T09:15:00+05:30",
                Direction.CREDIT,
                "45000.00",
                Category.INCOME,
                "SALARY CREDIT",
                List.of("m-salary"));

        NormalizedTxn augustMicro = transaction(
                "4821",
                "2026-08-01T08:00:00+05:30",
                Direction.DEBIT,
                "50.00",
                Category.MICRO,
                "UPI/TEA",
                List.of("m-tea"));

        NormalizedTxn otherAccount = transaction(
                "9075",
                "2026-07-06T10:00:00+05:30",
                Direction.DEBIT,
                "100.00",
                Category.SPEND,
                "SHOP",
                List.of("m-other"));

        store.save(spend);
        store.save(duplicateEvidence);
        store.save(income);
        store.save(augustMicro);
        store.save(otherAccount);

        // Re-running the same write must change nothing.
        store.save(spend);

        List<NormalizedTxn> july =
                store.forAccountMonth(
                        "4821",
                        YearMonth.of(2026, 7));

        assertEquals(2, july.size());
        assertEquals("SALARY CREDIT", july.get(0).merchant());
        assertEquals("AMAZON PAY", july.get(1).merchant());

        Map<Category, BigDecimal> totals =
                store.categoryTotals("4821");

        assertEquals(
                new BigDecimal("2499.50"),
                totals.get(Category.SPEND));

        assertEquals(
                new BigDecimal("45000.00"),
                totals.get(Category.INCOME));

        assertEquals(
                new BigDecimal("50.00"),
                totals.get(Category.MICRO));

        assertEquals(
                new BigDecimal("0.00"),
                totals.get(Category.TRANSFER));

        NormalizedTxn found =
                store.byMessageId("m-email")
                        .orElseThrow();

        assertEquals("AMAZON PAY", found.merchant());

        assertTrue(
                found.sourceMessageIds()
                        .containsAll(
                                List.of("m-sms", "m-email")));
    }

    private NormalizedTxn transaction(
            String accountLast4,
            String occurredAt,
            Direction direction,
            String amount,
            Category category,
            String merchant,
            List<String> messageIds) {

        return new NormalizedTxn(
                accountLast4,
                OffsetDateTime.parse(occurredAt),
                direction,
                new BigDecimal(amount),
                category,
                merchant,
                messageIds);
    }
}