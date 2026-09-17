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

class TransferCategoryTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void matchesOppositeLegsBetweenOwnedAccountsAsTransfers()
            throws Exception {

        String debit = json(
                "m-transfer-debit",
                "AD-HDFCBK-S",
                "2026-07-05T11:00:00+05:30",
                "Rs 8,000.00 debited from a/c **4821 "
                        + "on 05-07-26 at 11:00 "
                        + "to IMPS/P2A/PARAG KAPOOR. "
                        + "Avl Bal: Rs.80,071.04.");

        String credit = json(
                "m-transfer-credit",
                "VM-ICICIB-T",
                "2026-07-05T11:02:00+05:30",
                "Dear Customer, Acct XX9075 is credited "
                        + "with INR 8000.00 "
                        + "on 05/07/2026 11:02. "
                        + "Info: IMPS/P2A/PARAG KAPOOR. "
                        + "Avl Bal Rs.57,757.25 -ICICI Bank");

        InMemoryLedgerStore store = ingest(debit, credit);

        NormalizedTxn debitLeg =
                transactionFor(store, "4821");

        NormalizedTxn creditLeg =
                transactionFor(store, "9075");

        assertEquals(
                Category.TRANSFER,
                debitLeg.category());

        assertEquals(
                Category.TRANSFER,
                creditLeg.category());
    }

    @Test
    void unpairedSelfCreditRemainsIncome()
            throws Exception {

        String credit = json(
                "m-unpaired-credit",
                "VM-ICICIB-T",
                "2026-07-01T21:16:00+05:30",
                "Dear Customer, Acct XX9075 is credited "
                        + "with INR 18,000.00 "
                        + "on 01/07/2026 21:14. "
                        + "Info: NEFT INWARD SELF. "
                        + "Avl Bal Rs.49,882.25 -ICICI Bank");

        InMemoryLedgerStore store = ingest(credit);

        assertEquals(
                Category.INCOME,
                store.all().get(0).category());
    }

    @Test
    void matchesTransferWhenLegsArriveSeparately()
            throws Exception {

        String debit = json(
                "m-separate-debit",
                "AD-HDFCBK-S",
                "2026-07-05T11:00:00+05:30",
                "Rs 8,000.00 debited from a/c **4821 "
                        + "on 05-07-26 at 11:00 "
                        + "to IMPS/P2A/PARAG KAPOOR. "
                        + "Avl Bal: Rs.80,071.04.");

        String credit = json(
                "m-separate-credit",
                "VM-ICICIB-T",
                "2026-07-05T11:02:00+05:30",
                "Dear Customer, Acct XX9075 is credited "
                        + "with INR 8000.00 "
                        + "on 05/07/2026 11:02. "
                        + "Info: IMPS/P2A/PARAG KAPOOR. "
                        + "Avl Bal Rs.57,757.25 -ICICI Bank");

        InMemoryLedgerStore store =
                new InMemoryLedgerStore();

        IngestService service =
                new IngestService(new Parsers(), store);

        Path firstCorpus =
                temporaryDirectory.resolve("first.jsonl");

        Files.writeString(firstCorpus, debit);
        service.ingestFile(firstCorpus);

        assertEquals(
                Category.SPEND,
                transactionFor(store, "4821").category());

        Path secondCorpus =
                temporaryDirectory.resolve("second.jsonl");

        Files.writeString(secondCorpus, credit);
        service.ingestFile(secondCorpus);

        assertEquals(
                Category.TRANSFER,
                transactionFor(store, "4821").category());

        assertEquals(
                Category.TRANSFER,
                transactionFor(store, "9075").category());
    }

    private InMemoryLedgerStore ingest(String... jsonLines)
            throws Exception {

        Path corpus =
                temporaryDirectory.resolve(
                        "transfer-corpus.jsonl");

        Files.writeString(
                corpus,
                String.join(
                        System.lineSeparator(),
                        jsonLines));

        InMemoryLedgerStore store =
                new InMemoryLedgerStore();

        IngestService service =
                new IngestService(new Parsers(), store);

        service.ingestFile(corpus);

        return store;
    }

    private NormalizedTxn transactionFor(
            InMemoryLedgerStore store,
            String accountLast4) {

        return store.all()
                .stream()
                .filter(transaction ->
                        accountLast4.equals(
                                transaction.accountLast4()))
                .findFirst()
                .orElseThrow();
    }

    private String json(
            String messageId,
            String sender,
            String receivedAt,
            String body) {

        String escapedBody = body
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");

        return "{\"message_id\":\"" + messageId + "\","
                + "\"channel\":\"sms\","
                + "\"sender\":\"" + sender + "\","
                + "\"received_at\":\"" + receivedAt + "\","
                + "\"device_id\":\"dev-test\","
                + "\"body\":\"" + escapedBody + "\"}";
    }
}