package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * In-memory ledger store.
 *
 * Saving evidence for an existing transaction merges its message IDs instead
 * of creating another ledger row.
 */
public final class InMemoryLedgerStore implements LedgerStore {

    private final List<NormalizedTxn> rows =
            new ArrayList<>();

    @Override
    public void save(NormalizedTxn transaction) {
        for (int index = 0; index < rows.size(); index++) {
            NormalizedTxn existing = rows.get(index);

            if (!sameTransaction(existing, transaction)) {
                continue;
            }

            TreeSet<String> messageIds = new TreeSet<>();
            messageIds.addAll(existing.sourceMessageIds());
            messageIds.addAll(transaction.sourceMessageIds());

            NormalizedTxn merged = new NormalizedTxn(
                    existing.accountLast4(),
                    existing.occurredAt(),
                    existing.direction(),
                    existing.amount(),
                    mergeCategory(
                            existing.category(),
                            transaction.category()),
                    existing.merchant(),
                    new ArrayList<>(messageIds));

            rows.set(index, merged);
            return;
        }

        rows.add(transaction);
    }

    private Category mergeCategory(
            Category existing,
            Category incoming) {

        if (existing == Category.TRANSFER
                || incoming == Category.TRANSFER) {
            return Category.TRANSFER;
        }

        if (existing == Category.MICRO
                || incoming == Category.MICRO) {
            return Category.MICRO;
        }

        return existing;
    }

    private boolean sameTransaction(
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
                .equals(normalizeMerchant(second.merchant()));
    }

    private String normalizeMerchant(String merchant) {
        return merchant
                .trim()
                .replaceAll("\\s+", " ")
                .toUpperCase(Locale.ROOT);
    }

    @Override
    public List<NormalizedTxn> all() {
        return Collections.unmodifiableList(rows);
    }

    @Override
    public long count() {
        return rows.size();
    }
}