package in.simplifymoney.ledgersync.report;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Creates ledger, summary and reconciliation reports.
 */
public final class Reports {

    private Reports() {
    }

    private static final BigDecimal ZERO =
            BigDecimal.ZERO.setScale(2);

    public static Map<String, Object> summary(
            List<NormalizedTxn> ledger) {

        Map<String, Object> accounts =
                new LinkedHashMap<>();

        TreeSet<String> accountNumbers =
                new TreeSet<>();

        for (NormalizedTxn transaction : ledger) {
            accountNumbers.add(
                    transaction.accountLast4());
        }

        for (String accountNumber : accountNumbers) {
            BigDecimal spend = ZERO;
            BigDecimal income = ZERO;
            BigDecimal microTotal = ZERO;
            BigDecimal transferredOut = ZERO;
            BigDecimal transferredIn = ZERO;
            int microCount = 0;

            for (NormalizedTxn transaction : ledger) {
                if (!transaction.accountLast4()
                        .equals(accountNumber)) {
                    continue;
                }

                switch (transaction.category()) {
                    case SPEND ->
                            spend = spend.add(
                                    transaction.amount());

                    case INCOME ->
                            income = income.add(
                                    transaction.amount());

                    case MICRO -> {
                        microCount++;

                        microTotal = microTotal.add(
                                transaction.amount());
                    }

                    case TRANSFER -> {
                        if (transaction.direction()
                                == Direction.DEBIT) {

                            transferredOut =
                                    transferredOut.add(
                                            transaction.amount());
                        } else {
                            transferredIn =
                                    transferredIn.add(
                                            transaction.amount());
                        }
                    }
                }
            }

            Map<String, Object> account =
                    new LinkedHashMap<>();

            account.put(
                    "spend",
                    spend.toPlainString());

            account.put(
                    "income",
                    income.toPlainString());

            account.put(
                    "micro_count",
                    microCount);

            account.put(
                    "micro_total",
                    microTotal.toPlainString());

            account.put(
                    "transferred_out",
                    transferredOut.toPlainString());

            account.put(
                    "transferred_in",
                    transferredIn.toPlainString());

            accounts.put(
                    accountNumber,
                    account);
        }

        Map<String, Object> document =
                new LinkedHashMap<>();

        document.put("accounts", accounts);

        return document;
    }

    public static Map<String, Object> ledgerDocument(
            List<NormalizedTxn> ledger) {

        List<Object> rows = ledger.stream()
                .map(transaction -> {

                    Map<String, Object> row =
                            new LinkedHashMap<>();

                    row.put(
                            "account_last4",
                            transaction.accountLast4());

                    row.put(
                            "occurred_at",
                            transaction.occurredAt()
                                    .toString());

                    row.put(
                            "direction",
                            transaction.direction()
                                    .name()
                                    .toLowerCase());

                    row.put(
                            "amount",
                            transaction.amount()
                                    .toPlainString());

                    row.put(
                            "category",
                            transaction.category()
                                    .name());

                    row.put(
                            "merchant",
                            transaction.merchant());

                    row.put(
                            "source_message_ids",
                            transaction.sourceMessageIds());

                    return (Object) row;
                })
                .toList();

        Map<String, Object> document =
                new LinkedHashMap<>();

        document.put("transactions", rows);

        return document;
    }

    public static Map<String, Object> reconciliation(
            List<NormalizedTxn> ledger) {

        throw new UnsupportedOperationException(
                "reconciliation is not implemented");
    }

    public static Map<Category, BigDecimal> byCategory(
            List<NormalizedTxn> ledger) {

        Map<Category, BigDecimal> totals =
                new LinkedHashMap<>();

        for (Category category : Category.values()) {
            totals.put(category, ZERO);
        }

        for (NormalizedTxn transaction : ledger) {
            totals.put(
                    transaction.category(),
                    totals.get(transaction.category())
                            .add(transaction.amount()));
        }

        return totals;
    }
}