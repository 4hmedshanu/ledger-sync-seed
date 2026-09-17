package in.simplifymoney.ledgersync;

import static org.junit.jupiter.api.Assertions.assertEquals;

import in.simplifymoney.ledgersync.ingest.IngestService;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.report.Reports;
import in.simplifymoney.ledgersync.store.InMemoryLedgerStore;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReportsSummaryTest {

    @Test
    void producesExpectedSavingsAccountTotals()
            throws Exception {

        InMemoryLedgerStore store =
                new InMemoryLedgerStore();

        IngestService service =
                new IngestService(new Parsers(), store);

        service.ingestFile(
                Path.of("fixtures/corpus-a.jsonl"));

        Map<String, Object> summary =
                Reports.summary(store.all());

        Map<String, Object> accounts =
                objectMap(summary.get("accounts"));

        assertAccount(
                objectMap(accounts.get("4821")),
                "87068.38",
                "101340.83",
                52,
                "2357.51",
                "25000.00",
                "6000.00");

        assertAccount(
                objectMap(accounts.get("9075")),
                "39058.11",
                "41450.33",
                45,
                "2086.34",
                "6000.00",
                "25000.00");
    }

    private void assertAccount(
            Map<String, Object> account,
            String spend,
            String income,
            int microCount,
            String microTotal,
            String transferredOut,
            String transferredIn) {

        assertEquals(spend, account.get("spend"));
        assertEquals(income, account.get("income"));

        assertEquals(
                microCount,
                account.get("micro_count"));

        assertEquals(
                microTotal,
                account.get("micro_total"));

        assertEquals(
                transferredOut,
                account.get("transferred_out"));

        assertEquals(
                transferredIn,
                account.get("transferred_in"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(
            Object value) {

        return (Map<String, Object>) value;
    }
}