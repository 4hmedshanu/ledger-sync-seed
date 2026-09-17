package in.simplifymoney.ledgersync;

import static org.junit.jupiter.api.Assertions.assertEquals;

import in.simplifymoney.ledgersync.ingest.IngestService;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.store.InMemoryLedgerStore;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IngestCategoryTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void classifiesUpiDebitOfOneHundredAsMicro()
            throws Exception {

        NormalizedTxn transaction = ingest(
                "Rs.100.00 debited from a/c **4821 "
                        + "on 04-07-26 at 08:25 "
                        + "to UPI/KIRANA STORE. "
                        + "Avl Bal: Rs.49,757.25.");

        assertEquals(Category.MICRO, transaction.category());
    }

    @Test
    void classifiesUpiDebitAboveOneHundredAsSpend()
            throws Exception {

        NormalizedTxn transaction = ingest(
                "Rs.100.01 debited from a/c **4821 "
                        + "on 04-07-26 at 08:26 "
                        + "to UPI/KIRANA STORE. "
                        + "Avl Bal: Rs.49,657.24.");

        assertEquals(Category.SPEND, transaction.category());
    }

    @Test
    void smallNonUpiDebitRemainsSpend()
            throws Exception {

        NormalizedTxn transaction = ingest(
                "Rs.50.00 debited from a/c **4821 "
                        + "on 04-07-26 at 08:27 "
                        + "to ATM CASH. "
                        + "Avl Bal: Rs.49,607.24.");

        assertEquals(Category.SPEND, transaction.category());
    }

    @Test
    void smallUpiCreditRemainsIncome()
            throws Exception {

        NormalizedTxn transaction = ingest(
                "Rs.50.00 credited to a/c **4821 "
                        + "on 04-07-26 at 08:28 "
                        + "by UPI/P2P/REFUND. "
                        + "Avl Bal: Rs.49,657.24.");

        assertEquals(Category.INCOME, transaction.category());
    }

    private NormalizedTxn ingest(String body)
            throws Exception {

        String escapedBody = body
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");

        String json =
                "{\"message_id\":\"m-category-test\","
                        + "\"channel\":\"sms\","
                        + "\"sender\":\"AD-HDFCBK-S\","
                        + "\"received_at\":\"2026-07-04T08:30:00+05:30\","
                        + "\"device_id\":\"dev-test\","
                        + "\"body\":\""
                        + escapedBody
                        + "\"}";

        Path corpus = temporaryDirectory.resolve("corpus.jsonl");
        Files.writeString(corpus, json);

        InMemoryLedgerStore store =
                new InMemoryLedgerStore();

        IngestService service =
                new IngestService(new Parsers(), store);

        service.ingestFile(corpus);

        return store.all().get(0);
    }
}