package in.simplifymoney.ledgersync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import in.simplifymoney.ledgersync.parse.EmailParser;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class EmailParserTest {

    private RawMessage emailMessage() {
        String body = """
                Date: Wed, 01 Jul 2026 09:02:00 +0530
                Subject: Transaction alert on your account

                Dear Customer,

                Your account ending 4821 has been credited with INR 45,000.
                Merchant / Remarks: SALARY CREDIT
                Transaction reference: 1597155421

                This is a system generated email.
                """;

        return new RawMessage(
                "m-00002-69e4cd",
                "email",
                "alerts@hdfcbank.net",
                OffsetDateTime.parse("2026-07-01T09:47:00+05:30"),
                "dev-34aed0f15820",
                body);
    }

    @Test
    void parsesBankTransactionEmail() {
        Optional<ParsedTxn> result = new EmailParser().parse(emailMessage());

        assertTrue(result.isPresent());

        ParsedTxn txn = result.orElseThrow();
        assertEquals("4821", txn.accountLast4());
        assertEquals(Direction.CREDIT, txn.direction());
        assertEquals(new BigDecimal("45000.00"), txn.amount());
        assertEquals("SALARY CREDIT", txn.merchant());
        assertEquals(
                OffsetDateTime.parse("2026-07-01T09:02:00+05:30"),
                txn.occurredAt());
        assertEquals("m-00002-69e4cd", txn.sourceMessageId());
    }

    @Test
    void emailParserIsRegistered() {
        assertTrue(new Parsers().parse(emailMessage()).isPresent());
    }
}