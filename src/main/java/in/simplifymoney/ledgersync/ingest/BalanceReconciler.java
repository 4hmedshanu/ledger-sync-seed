package in.simplifymoney.ledgersync.ingest;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * Finds balance movements not explained by parsed transaction alerts.
 */
public final class BalanceReconciler {

    public static final String UNKNOWN_MERCHANT  =
            "UNATTRIBUTED BALANCE MOVEMENT";

    public List<NormalizedTxn> inferMissingTransactions(
            List<ParsedTxn> parsedTransactions) {

        Map<TransactionKey, Evidence> uniqueTransactions =
                mergeDuplicateEvidence(parsedTransactions);

        Map<String, List<Evidence>> byAccount =
                new LinkedHashMap<>();

        for (Evidence evidence :
                uniqueTransactions.values()) {

            byAccount.computeIfAbsent(
                            evidence.accountLast4(),
                            ignored -> new ArrayList<>())
                    .add(evidence);
        }

        List<NormalizedTxn> inferred =
                new ArrayList<>();

        for (List<Evidence> accountEvidence :
                byAccount.values()) {

            accountEvidence.sort(
                    Comparator.comparing(
                            Evidence::occurredAt));

            inferForAccount(
                    accountEvidence,
                    inferred);
        }

        return List.copyOf(inferred);
    }

    private void inferForAccount(
            List<Evidence> evidence,
            List<NormalizedTxn> inferred) {

        Evidence previousCheckpoint = null;
        BigDecimal expectedBalance = null;

        for (Evidence current : evidence) {

            if (previousCheckpoint == null) {
                if (current.statedBalance() != null) {
                    previousCheckpoint = current;
                    expectedBalance =
                            current.statedBalance();
                }

                continue;
            }

            expectedBalance = applyTransaction(
                    expectedBalance,
                    current.direction(),
                    current.amount());

            if (current.statedBalance() == null) {
                continue;
            }

            BigDecimal unexplainedChange =
                    current.statedBalance()
                            .subtract(expectedBalance)
                            .setScale(2);

            if (unexplainedChange.signum() != 0) {
                addInferredTransaction(
                        previousCheckpoint,
                        current,
                        unexplainedChange,
                        inferred);
            }

            previousCheckpoint = current;
            expectedBalance =
                    current.statedBalance();
        }
    }

    private void addInferredTransaction(
            Evidence previous,
            Evidence current,
            BigDecimal unexplainedChange,
            List<NormalizedTxn> inferred) {

        Direction direction =
                unexplainedChange.signum() < 0
                        ? Direction.DEBIT
                        : Direction.CREDIT;

        BigDecimal amount =
                unexplainedChange.abs().setScale(2);

        TreeSet<String> sourceMessageIds =
                new TreeSet<>();

        sourceMessageIds.addAll(
                previous.sourceMessageIds());

        sourceMessageIds.addAll(
                current.sourceMessageIds());

        Category category =
                direction == Direction.DEBIT
                        ? Category.SPEND
                        : Category.INCOME;

        inferred.add(new NormalizedTxn(
                current.accountLast4(),
                current.occurredAt().minusSeconds(1),
                direction,
                amount,
                category,
                UNKNOWN_MERCHANT,
                new ArrayList<>(sourceMessageIds)));
    }

    private BigDecimal applyTransaction(
            BigDecimal previousBalance,
            Direction direction,
            BigDecimal amount) {

        return switch (direction) {
            case DEBIT ->
                    previousBalance.subtract(amount);

            case CREDIT ->
                    previousBalance.add(amount);
        };
    }

    private Map<TransactionKey, Evidence>
    mergeDuplicateEvidence(
            List<ParsedTxn> parsedTransactions) {

        Map<TransactionKey, Evidence> unique =
                new LinkedHashMap<>();

        for (ParsedTxn transaction :
                parsedTransactions) {

            TransactionKey key =
                    TransactionKey.from(transaction);

            Evidence existing =
                    unique.get(key);

            if (existing == null) {
                unique.put(
                        key,
                        new Evidence(transaction));
            } else {
                existing.merge(transaction);
            }
        }

        return unique;
    }

    private record TransactionKey(
            String accountLast4,
            OffsetDateTime occurredAt,
            Direction direction,
            String amount,
            String merchant) {

        private static TransactionKey from(
                ParsedTxn transaction) {

            return new TransactionKey(
                    transaction.accountLast4(),
                    transaction.occurredAt(),
                    transaction.direction(),
                    transaction.amount()
                            .setScale(2)
                            .toPlainString(),
                    normalizeMerchant(
                            transaction.merchant()));
        }

        private static String normalizeMerchant(
                String merchant) {

            if (merchant == null) {
                return "";
            }

            return merchant
                    .trim()
                    .replaceAll("\\s+", " ")
                    .toUpperCase(Locale.ROOT);
        }
    }

    private static final class Evidence {

        private final ParsedTxn transaction;
        private BigDecimal statedBalance;

        private final TreeSet<String> sourceMessageIds =
                new TreeSet<>();

        private Evidence(ParsedTxn transaction) {
            this.transaction = transaction;
            this.statedBalance =
                    transaction.statedBalance();

            sourceMessageIds.add(
                    transaction.sourceMessageId());
        }

        private void merge(ParsedTxn duplicate) {
            sourceMessageIds.add(
                    duplicate.sourceMessageId());

            if (statedBalance == null
                    && duplicate.statedBalance() != null) {

                statedBalance =
                        duplicate.statedBalance();
            }
        }

        private String accountLast4() {
            return transaction.accountLast4();
        }

        private OffsetDateTime occurredAt() {
            return transaction.occurredAt();
        }

        private Direction direction() {
            return transaction.direction();
        }

        private BigDecimal amount() {
            return transaction.amount();
        }

        private BigDecimal statedBalance() {
            return statedBalance;
        }

        private List<String> sourceMessageIds() {
            return new ArrayList<>(
                    sourceMessageIds);
        }
    }
}