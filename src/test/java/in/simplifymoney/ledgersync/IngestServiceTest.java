package in.simplifymoney.ledgersync;

import static org.junit.jupiter.api.Assertions.assertEquals;

import in.simplifymoney.ledgersync.ingest.IngestService;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.store.InMemoryLedgerStore;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class IngestServiceTest {

    @Test
    void ingestingSameCorpusTwiceChangesNothing()
            throws Exception {

        InMemoryLedgerStore store =
                new InMemoryLedgerStore();

        IngestService service =
                new IngestService(new Parsers(), store);

        Path corpus =
                Path.of("fixtures/corpus-a.jsonl");

        IngestService.Stats first =
                service.ingestFile(corpus);

        List<NormalizedTxn> firstLedger =
                List.copyOf(store.all());

        IngestService.Stats second =
                service.ingestFile(corpus);

        assertEquals(
                store.count(),
                first.transactionsWritten());

        assertEquals(
                0,
                second.transactionsWritten());

        assertEquals(
                firstLedger,
                store.all());
    }
}