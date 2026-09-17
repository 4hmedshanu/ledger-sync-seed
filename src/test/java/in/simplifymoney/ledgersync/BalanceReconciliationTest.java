package in.simplifymoney.ledgersync;

import static org.junit.jupiter.api.Assertions.assertEquals;

import in.simplifymoney.ledgersync.ingest.IngestService;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.store.InMemoryLedgerStore;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BalanceReconciliationTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void infersMissingDebitFromConsecutiveBalances()
            throws Exception {

        String beforeGap = json(
                "m-before-gap",
                "2026-07-29T11:53:00+05:30",
                "Sent INR899.99\n"
                        + "To: IRCTC\n"
                        + "On: 29 Jul 26 11:53\n"
                        + "A/c: XX4821\n"
                        + "Available Balance: INR 36054.05\n"
                        + "-HDFC Bank");

        String afterGap = json(
                "m-after-gap",
                "2026-07-29T17:08:00+05:30",
                "Sent INR 75.00\n"
                        + "To: UPI/STATIONERY\n"
                        + "On: 29 Jul 26 17:06\n"
                        + "A/c: XX4821\n"
                        + "Available Balance: INR 28479.05\n"
                        + "-HDFC Bank");

        Path corpus =
                temporaryDirectory.resolve("balance-gap.jsonl");

        Files.writeString(
                corpus,
                beforeGap
                        + System.lineSeparator()
                        + afterGap);

        InMemoryLedgerStore store =
                new InMemoryLedgerStore();

        IngestService service =
                new IngestService(new Parsers(), store);

        service.ingestFile(corpus);

        assertEquals(3, store.count());

        NormalizedTxn inferred = store.all()
                .stream()
                .filter(transaction ->
                        transaction.amount().compareTo(
                                new BigDecimal("7500.00")) == 0)
                .findFirst()
                .orElseThrow();

        assertEquals(Direction.DEBIT, inferred.direction());
        assertEquals(Category.SPEND, inferred.category());
        assertEquals(
                "UNATTRIBUTED BALANCE MOVEMENT",
                inferred.merchant());

        assertEquals(
                List.of("m-after-gap", "m-before-gap"),
                inferred.sourceMessageIds());
    }

    private String json(
            String messageId,
            String receivedAt,
            String body) {

        String escapedBody = body
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");

        return "{\"message_id\":\"" + messageId + "\","
                + "\"channel\":\"sms\","
                + "\"sender\":\"AD-HDFCBK-S\","
                + "\"received_at\":\"" + receivedAt + "\","
                + "\"device_id\":\"dev-test\","
                + "\"body\":\"" + escapedBody + "\"}";
    }
}