package in.simplifymoney.ledgersync;

import static org.junit.jupiter.api.Assertions.assertEquals;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.store.InMemoryLedgerStore;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class InMemoryLedgerStoreTest {

    private NormalizedTxn transaction(
            OffsetDateTime occurredAt,
            String merchant,
            String messageId) {

        return new NormalizedTxn(
                "4821",
                occurredAt,
                Direction.DEBIT,
                new BigDecimal("5.00"),
                Category.SPEND,
                merchant,
                List.of(messageId));
    }

    @Test
    void mergesEvidenceForSameTransaction() {
        InMemoryLedgerStore store =
                new InMemoryLedgerStore();

        OffsetDateTime occurredAt =
                OffsetDateTime.parse(
                        "2026-07-04T07:19:00+05:30");

        store.save(transaction(
                occurredAt,
                "UPI/WATER CAN",
                "m-sms"));

        store.save(transaction(
                occurredAt,
                "UPI/WATER CAN",
                "m-email"));

        assertEquals(1, store.count());
        assertEquals(
                List.of("m-email", "m-sms"),
                store.all().getFirst().sourceMessageIds());
    }

    @Test
    void repeatedMessageDoesNotCreateDuplicate() {
        InMemoryLedgerStore store =
                new InMemoryLedgerStore();

        NormalizedTxn transaction = transaction(
                OffsetDateTime.parse(
                        "2026-07-04T07:19:00+05:30"),
                "UPI/WATER CAN",
                "m-sms");

        store.save(transaction);
        store.save(transaction);

        assertEquals(1, store.count());
    }

    @Test
    void differentTransactionIsNotMerged() {
        InMemoryLedgerStore store =
                new InMemoryLedgerStore();

        OffsetDateTime first =
                OffsetDateTime.parse(
                        "2026-07-04T07:19:00+05:30");

        store.save(transaction(
                first,
                "UPI/WATER CAN",
                "m-1"));

        store.save(transaction(
                first.plusMinutes(1),
                "UPI/WATER CAN",
                "m-2"));

        assertEquals(2, store.count());
    }
}