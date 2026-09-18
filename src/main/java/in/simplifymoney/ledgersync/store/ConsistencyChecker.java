package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Compares the logical SQL ledger with the document store.
 *
 * Transactions are primarily matched by their logical identity. Message-ID
 * lookup is checked only when that message belongs to exactly one SQL
 * transaction, because reconciliation evidence may be shared.
 */
public final class ConsistencyChecker {

    private static final BigDecimal ZERO =
            BigDecimal.ZERO.setScale(2);

    private final SqlLedgerStore sql;
    private final DocumentStore documents;

    public ConsistencyChecker(
            SqlLedgerStore sql,
            DocumentStore documents) {

        this.sql = sql;
        this.documents = documents;
    }

    public List<Divergence> check() {
        List<NormalizedTxn> sqlTransactions =
                sql.all();

        Set<Divergence> differences =
                new LinkedHashSet<>();

        checkTransactions(
                sqlTransactions,
                differences);

        checkUniqueMessageMappings(
                sqlTransactions,
                differences);

        checkCategoryTotals(
                sqlTransactions,
                differences);

        return List.copyOf(differences);
    }

    private void checkTransactions(
            List<NormalizedTxn> sqlTransactions,
            Set<Divergence> differences) {

        Map<AccountMonth, List<NormalizedTxn>> groups =
                new LinkedHashMap<>();

        for (NormalizedTxn transaction :
                sqlTransactions) {

            AccountMonth key =
                    new AccountMonth(
                            transaction.accountLast4(),
                            YearMonth.from(
                                    transaction.occurredAt()));

            groups.computeIfAbsent(
                            key,
                            ignored -> new ArrayList<>())
                    .add(transaction);
        }

        for (Map.Entry<AccountMonth,
                List<NormalizedTxn>> entry :
                groups.entrySet()) {

            AccountMonth key = entry.getKey();

            List<NormalizedTxn> storedTransactions =
                    documents.forAccountMonth(
                            key.accountLast4(),
                            key.month());

            boolean[] matched =
                    new boolean[storedTransactions.size()];

            for (NormalizedTxn sqlTransaction :
                    entry.getValue()) {

                int matchIndex =
                        findIdentityMatch(
                                sqlTransaction,
                                storedTransactions,
                                matched);

                if (matchIndex >= 0) {
                    matched[matchIndex] = true;

                    NormalizedTxn stored =
                            storedTransactions.get(
                                    matchIndex);

                    if (!sameContent(
                            sqlTransaction,
                            stored)) {

                        addTransactionDifference(
                                sqlTransaction,
                                stored,
                                differences);
                    }

                    continue;
                }

                int relatedIndex =
                        findBestEvidenceMatch(
                                sqlTransaction,
                                storedTransactions,
                                matched);

                if (relatedIndex >= 0) {
                    matched[relatedIndex] = true;

                    addTransactionDifference(
                            sqlTransaction,
                            storedTransactions.get(
                                    relatedIndex),
                            differences);
                } else {
                    differences.add(
                            new Divergence(
                                    "missing transaction "
                                            + transactionLabel(
                                            sqlTransaction),
                                    describe(sqlTransaction),
                                    "<missing>"));
                }
            }

            for (int index = 0;
                 index < storedTransactions.size();
                 index++) {

                if (!matched[index]) {
                    NormalizedTxn extra =
                            storedTransactions.get(index);

                    differences.add(
                            new Divergence(
                                    "extra transaction "
                                            + transactionLabel(extra),
                                    "<missing>",
                                    describe(extra)));
                }
            }
        }
    }

    private int findIdentityMatch(
            NormalizedTxn expected,
            List<NormalizedTxn> candidates,
            boolean[] matched) {

        for (int index = 0;
             index < candidates.size();
             index++) {

            if (!matched[index]
                    && sameIdentity(
                    expected,
                    candidates.get(index))) {

                return index;
            }
        }

        return -1;
    }

    private int findBestEvidenceMatch(
            NormalizedTxn expected,
            List<NormalizedTxn> candidates,
            boolean[] matched) {

        int bestIndex = -1;
        int bestSharedCount = 0;

        for (int index = 0;
             index < candidates.size();
             index++) {

            if (matched[index]) {
                continue;
            }

            int sharedCount =
                    sharedEvidenceCount(
                            expected,
                            candidates.get(index));

            if (sharedCount > bestSharedCount) {
                bestSharedCount = sharedCount;
                bestIndex = index;
            }
        }

        return bestIndex;
    }

    private int sharedEvidenceCount(
            NormalizedTxn first,
            NormalizedTxn second) {

        Set<String> secondIds =
                new TreeSet<>(
                        second.sourceMessageIds());

        int count = 0;

        for (String messageId :
                first.sourceMessageIds()) {

            if (secondIds.contains(messageId)) {
                count++;
            }
        }

        return count;
    }

    private void addTransactionDifference(
            NormalizedTxn expected,
            NormalizedTxn stored,
            Set<Divergence> differences) {

        if (expected.sourceMessageIds().isEmpty()) {
            differences.add(
                    new Divergence(
                            "transaction "
                                    + transactionLabel(expected),
                            describe(expected),
                            describe(stored)));

            return;
        }

        for (String messageId :
                expected.sourceMessageIds()) {

            differences.add(
                    new Divergence(
                            "transaction for message "
                                    + messageId,
                            describe(expected),
                            describe(stored)));
        }
    }

    private void checkUniqueMessageMappings(
            List<NormalizedTxn> sqlTransactions,
            Set<Divergence> differences) {

        Map<String, Integer> messageUsage =
                new HashMap<>();

        for (NormalizedTxn transaction :
                sqlTransactions) {

            for (String messageId :
                    transaction.sourceMessageIds()) {

                messageUsage.merge(
                        messageId,
                        1,
                        Integer::sum);
            }
        }

        for (NormalizedTxn expected :
                sqlTransactions) {

            for (String messageId :
                    expected.sourceMessageIds()) {

                // Shared reconciliation evidence cannot map
                // unambiguously to one transaction.
                if (messageUsage.get(messageId) != 1) {
                    continue;
                }

                var stored =
                        documents.byMessageId(messageId);

                if (stored.isEmpty()) {
                    differences.add(
                            new Divergence(
                                    "message mapping "
                                            + messageId,
                                    describe(expected),
                                    "<missing>"));

                    continue;
                }

                if (!sameContent(
                        expected,
                        stored.get())) {

                    differences.add(
                            new Divergence(
                                    "transaction for message "
                                            + messageId,
                                    describe(expected),
                                    describe(stored.get())));
                }
            }
        }
    }

    private void checkCategoryTotals(
            List<NormalizedTxn> sqlTransactions,
            Set<Divergence> differences) {

        TreeSet<String> accounts =
                new TreeSet<>();

        for (NormalizedTxn transaction :
                sqlTransactions) {

            accounts.add(
                    transaction.accountLast4());
        }

        for (String account : accounts) {
            Map<Category, BigDecimal> documentTotals =
                    documents.categoryTotals(account);

            for (Category category :
                    Category.values()) {

                BigDecimal sqlTotal =
                        totalFor(
                                sqlTransactions,
                                account,
                                category);

                BigDecimal documentTotal =
                        documentTotals.getOrDefault(
                                        category,
                                        ZERO)
                                .setScale(2);

                if (sqlTotal.compareTo(
                        documentTotal) != 0) {

                    differences.add(
                            new Divergence(
                                    "category total "
                                            + account
                                            + "/"
                                            + category.name(),
                                    sqlTotal.toPlainString(),
                                    documentTotal.toPlainString()));
                }
            }
        }
    }

    private BigDecimal totalFor(
            List<NormalizedTxn> transactions,
            String account,
            Category category) {

        BigDecimal total = ZERO;

        for (NormalizedTxn transaction :
                transactions) {

            if (transaction.accountLast4()
                    .equals(account)
                    && transaction.category()
                    == category) {

                total = total.add(
                        transaction.amount());
            }
        }

        return total.setScale(2);
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

        return sameIdentity(first, second)
                && first.category()
                == second.category()
                && Objects.equals(
                first.merchant(),
                second.merchant())
                && new TreeSet<>(
                first.sourceMessageIds())
                .equals(new TreeSet<>(
                        second.sourceMessageIds()));
    }

    private String normalizeMerchant(
            String merchant) {

        if (merchant == null) {
            return "";
        }

        return merchant
                .trim()
                .replaceAll("\\s+", " ")
                .toUpperCase(Locale.ROOT);
    }

    private String transactionLabel(
            NormalizedTxn transaction) {

        return transaction.accountLast4()
                + "/"
                + transaction.occurredAt()
                + "/"
                + transaction.amount()
                .setScale(2)
                .toPlainString();
    }

    private String describe(
            NormalizedTxn transaction) {

        return "account_last4="
                + transaction.accountLast4()
                + ", occurred_at="
                + transaction.occurredAt()
                + ", direction="
                + transaction.direction().name()
                + ", amount="
                + transaction.amount()
                .setScale(2)
                .toPlainString()
                + ", category="
                + transaction.category().name()
                + ", merchant="
                + transaction.merchant()
                + ", source_message_ids="
                + new TreeSet<>(
                transaction.sourceMessageIds());
    }

    private record AccountMonth(
            String accountLast4,
            YearMonth month) {
    }

    /**
     * One exact place where SQL and the document store disagree.
     */
    public record Divergence(
            String what,
            String inSql,
            String inDocuments) {
    }
}