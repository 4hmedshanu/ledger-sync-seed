package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.TreeSet;
import java.time.YearMonth;

/**
 * Moves the logical SQL ledger into the document store.
 *
 * SQL rows are read through SqlLedgerStore.all(), which merges legacy
 * duplicates before they reach the document store. The operation is
 * idempotent and can safely be rerun after a partial failure.
 */
public final class Backfill {

    private final SqlLedgerStore source;
    private final DocumentStore target;

    public Backfill(
            SqlLedgerStore source,
            DocumentStore target) {

        this.source = source;
        this.target = target;
    }

    public Result run() {
        List<NormalizedTxn> transactions =
                source.all();

        long written = 0;
        long skipped = 0;

        for (NormalizedTxn transaction : transactions) {
            Optional<NormalizedTxn> existing =
                    findExisting(transaction);

            if (existing.isPresent()
                    && sameContent(
                    existing.get(),
                    transaction)) {

                skipped++;
                continue;
            }

            target.save(transaction);
            written++;
        }

        return new Result(
                transactions.size(),
                written,
                skipped);
    }

 private Optional<NormalizedTxn> findExisting(
        NormalizedTxn transaction) {

    YearMonth month =
            YearMonth.from(
                    transaction.occurredAt());

    return target.forAccountMonth(
                    transaction.accountLast4(),
                    month)
            .stream()
            .filter(existing ->
                    sameIdentity(
                            existing,
                            transaction))
            .findFirst();
}

private boolean sameIdentity(
        NormalizedTxn first,
        NormalizedTxn second) {

    return first.accountLast4()
            .equals(second.accountLast4())
            && first.occurredAt()
            .equals(second.occurredAt())
            && first.direction()
            == second.direction()
            && first.amount()
            .compareTo(second.amount()) == 0
            && normalizeMerchant(first.merchant())
            .equals(normalizeMerchant(
                    second.merchant()));
}

    private boolean sameContent(
            NormalizedTxn first,
            NormalizedTxn second) {

        return first.accountLast4()
                .equals(second.accountLast4())
                && first.occurredAt()
                .equals(second.occurredAt())
                && first.direction()
                == second.direction()
                && first.amount()
                .compareTo(second.amount()) == 0
                && first.category()
                == second.category()
                && normalizeMerchant(first.merchant())
                .equals(normalizeMerchant(
                        second.merchant()))
                && new TreeSet<>(
                first.sourceMessageIds())
                .equals(new TreeSet<>(
                        second.sourceMessageIds()));
    }

    private String normalizeMerchant(String merchant) {
        if (merchant == null) {
            return "";
        }

        return merchant
                .trim()
                .replaceAll("\\s+", " ")
                .toUpperCase(Locale.ROOT);
    }

    public record Result(
            long read,
            long written,
            long skipped) {
    }
}