package in.simplifymoney.ledgersync.ingest;

import in.simplifymoney.ledgersync.json.Json;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.model.RawMessage;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.store.LedgerStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Reads a corpus of raw messages and puts transactions in the ledger.
 */
public final class IngestService {

    private final Parsers parsers;
    private final LedgerStore store;

    public IngestService(Parsers parsers, LedgerStore store) {
        this.parsers = parsers;
        this.store = store;
    }

    public Stats ingestFile(Path corpus) throws IOException {
        List<RawMessage> messages = readCorpus(corpus);
        long countBefore = store.count();
        int skipped = 0;

        for (RawMessage message : messages) {
            Optional<ParsedTxn> parsedTransaction =
                    parsers.parse(message);

            if (parsedTransaction.isEmpty()) {
                skipped++;
                continue;
            }

            store.save(toTransaction(parsedTransaction.get()));
        }

        int transactionsWritten = Math.toIntExact(
                store.count() - countBefore);

        return new Stats(
                messages.size(),
                transactionsWritten,
                skipped);
    }

    public static List<RawMessage> readCorpus(Path corpus)
            throws IOException {

        List<RawMessage> out = new ArrayList<>();

        try (Stream<String> lines = Files.lines(corpus)) {
            for (String line :
                    (Iterable<String>) lines
                            .filter(s -> !s.isBlank())::iterator) {

                Map<String, Object> object =
                        Json.parseObject(line);

                out.add(new RawMessage(
                        (String) object.get("message_id"),
                        (String) object.get("channel"),
                        (String) object.get("sender"),
                        OffsetDateTime.parse(
                                (String) object.get("received_at")),
                        (String) object.get("device_id"),
                        (String) object.get("body")));
            }
        }

        return out;
    }

    private NormalizedTxn toTransaction(ParsedTxn parsed) {
        Category category =
                parsed.direction() == Direction.DEBIT
                        ? Category.SPEND
                        : Category.INCOME;

        return new NormalizedTxn(
                parsed.accountLast4(),
                parsed.occurredAt(),
                parsed.direction(),
                parsed.amount(),
                category,
                parsed.merchant(),
                List.of(parsed.sourceMessageId()));
    }

    public record Stats(
            int messagesRead,
            int transactionsWritten,
            int messagesSkipped) {
    }
}