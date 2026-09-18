package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Compares the logical SQL ledger with the document store.
 *
 * The checker validates every message-to-transaction mapping, every
 * transaction field and every category total. It reports exact values instead
 * of only comparing row counts.
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

        List<Divergence> differences =
                new ArrayList<>();

        checkMessageMappings(
                sqlTransactions,
                differences);

        checkCategoryTotals(
                sqlTransactions,
                differences);

        return List.copyOf(differences);
    }

    private void checkMessageMappings(
            List<NormalizedTxn> sqlTransactions,
            List<Divergence> differences) {

        for (NormalizedTxn sqlTransaction :
                sqlTransactions) {

            for (String messageId :
                    sqlTransaction.sourceMessageIds()) {

                Optional<NormalizedTxn> documentTransaction =
                        documents.byMessageId(messageId);

                if (documentTransaction.isEmpty()) {
                    differences.add(new Divergence(
                            "message mapping " + messageId,
                            describe(sqlTransaction),
                            "<missing>"));

                    continue;
                }

                NormalizedTxn stored =
                        documentTransaction.get();

                if (!sameContent(
                        sqlTransaction,
                        stored)) {

                    differences.add(new Divergence(
                            "transaction for message "
                                    + messageId,
                            describe(sqlTransaction),
                            describe(stored)));
                }
            }
        }
    }

    private void checkCategoryTotals(
            List<NormalizedTxn> sqlTransactions,
            List<Divergence> differences) {

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

                    differences.add(new Divergence(
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
                && Objects.equals(
                first.merchant(),
                second.merchant())
                && new TreeSet<>(
                first.sourceMessageIds())
                .equals(new TreeSet<>(
                        second.sourceMessageIds()));
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

    /**
     * One exact place where SQL and the document store disagree.
     */
    public record Divergence(
            String what,
            String inSql,
            String inDocuments) {
    }
}