package in.simplifymoney.ledgersync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.simplifymoney.ledgersync.ingest.IngestService;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.report.Reports;
import in.simplifymoney.ledgersync.store.InMemoryLedgerStore;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReportsReconciliationTest {

    @Test
    void reportsInferredBalanceMovement()
            throws Exception {

        InMemoryLedgerStore store =
                new InMemoryLedgerStore();

        IngestService service =
                new IngestService(new Parsers(), store);

        service.ingestFile(
                Path.of("fixtures/corpus-a.jsonl"));

        Map<String, Object> reconciliation =
                Reports.reconciliation(store.all());

        List<Object> discrepancies =
                objectList(
                        reconciliation.get(
                                "discrepancies"));

        assertEquals(1, discrepancies.size());

        Map<String, Object> discrepancy =
                objectMap(discrepancies.get(0));

        assertEquals(
                "4821",
                discrepancy.get("account_last4"));

        assertEquals(
                "7500.00",
                discrepancy.get("amount"));

        assertEquals(
                "2026-07-29T17:05:59+05:30",
                discrepancy.get("occurred_at"));

        String note =
                (String) discrepancy.get("note");

        assertTrue(
                note.contains("balance checkpoints"));

        assertTrue(
                note.contains(
                        "exact time and merchant are unknown"));
    }

    @SuppressWarnings("unchecked")
    private List<Object> objectList(Object value) {
        return (List<Object>) value;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(
            Object value) {

        return (Map<String, Object>) value;
    }
}