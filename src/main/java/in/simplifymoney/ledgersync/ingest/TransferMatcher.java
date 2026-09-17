package in.simplifymoney.ledgersync.ingest;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Identifies both legs of transfers between the user's own accounts.
 */
public final class TransferMatcher {

    private static final Duration MATCH_WINDOW =
            Duration.ofMinutes(5);

    public List<NormalizedTxn> markTransfers(
            List<NormalizedTxn> transactions) {

        Set<Integer> transferIndexes = new HashSet<>();

        for (int firstIndex = 0;
             firstIndex < transactions.size();
             firstIndex++) {

            NormalizedTxn first =
                    transactions.get(firstIndex);

            for (int secondIndex = firstIndex + 1;
                 secondIndex < transactions.size();
                 secondIndex++) {

                NormalizedTxn second =
                        transactions.get(secondIndex);

                if (isMatchingTransfer(first, second)) {
                    transferIndexes.add(firstIndex);
                    transferIndexes.add(secondIndex);
                }
            }
        }

        List<NormalizedTxn> result = new ArrayList<>();

        for (int index = 0;
             index < transactions.size();
             index++) {

            NormalizedTxn transaction =
                    transactions.get(index);

            if (transferIndexes.contains(index)) {
                result.add(withTransferCategory(transaction));
            } else {
                result.add(transaction);
            }
        }

        return List.copyOf(result);
    }

    private boolean isMatchingTransfer(
            NormalizedTxn first,
            NormalizedTxn second) {

        if (first.accountLast4()
                .equals(second.accountLast4())) {
            return false;
        }

        if (first.direction() == second.direction()) {
            return false;
        }

        if (first.amount()
                .compareTo(second.amount()) != 0) {
            return false;
        }

        String firstMerchant =
                normalizeMerchant(first.merchant());

        String secondMerchant =
                normalizeMerchant(second.merchant());

        if (firstMerchant.isBlank()
                || !firstMerchant.equals(secondMerchant)) {
            return false;
        }

        Duration difference = Duration.between(
                first.occurredAt().toInstant(),
                second.occurredAt().toInstant()).abs();

        return difference.compareTo(MATCH_WINDOW) <= 0;
    }

    private String normalizeMerchant(String merchant) {
        return merchant
                .trim()
                .replaceAll("\\s+", " ")
                .toUpperCase(Locale.ROOT);
    }

    private NormalizedTxn withTransferCategory(
            NormalizedTxn transaction) {

        return new NormalizedTxn(
                transaction.accountLast4(),
                transaction.occurredAt(),
                transaction.direction(),
                transaction.amount(),
                Category.TRANSFER,
                transaction.merchant(),
                transaction.sourceMessageIds());
    }
}