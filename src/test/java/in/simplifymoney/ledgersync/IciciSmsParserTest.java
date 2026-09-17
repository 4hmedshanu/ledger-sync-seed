package in.simplifymoney.ledgersync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import in.simplifymoney.ledgersync.parse.IciciSmsParser;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class IciciSmsParserTest {

    private RawMessage message(String id, String body) {
        return new RawMessage(
                id,
                "sms",
                "VM-ICICIB-T",
                OffsetDateTime.parse("2026-07-23T18:42:00+05:30"),
                "dev-34aed0f15820",
                body);
    }

    @Test
    void parsesCompactDebitFormat() {
        RawMessage message = message(
                "m-00162-9709eb",
                "ICICI Bank Acct XX9075 Dr INR 5 on 23-Jul-2026 18:41; "
                        + "UPI/BARBER ref no 154245459403. "
                        + "BalAvl Rs 52,841.30");

        Optional<ParsedTxn> result =
                new IciciSmsParser().parse(message);

        assertTrue(result.isPresent());

        ParsedTxn txn = result.orElseThrow();
        assertEquals("9075", txn.accountLast4());
        assertEquals(Direction.DEBIT, txn.direction());
        assertEquals(new BigDecimal("5.00"), txn.amount());
        assertEquals("UPI/BARBER", txn.merchant());
        assertEquals(
                OffsetDateTime.parse("2026-07-23T18:41:00+05:30"),
                txn.occurredAt());
        assertEquals(new BigDecimal("52841.30"), txn.statedBalance());
    }

    @Test
    void parsesCompactCreditFormat() {
        RawMessage message = message(
                "m-00161-5b493e",
                "ICICI Bank Acct XX9075 Cr INR 1250.33 on "
                        + "23-Jul-2026 16:52; INTEREST CREDIT "
                        + "ref no 424353460512. BalAvl Rs 52,846.30");

        ParsedTxn txn =
                new IciciSmsParser().parse(message).orElseThrow();

        assertEquals(Direction.CREDIT, txn.direction());
        assertEquals(new BigDecimal("1250.33"), txn.amount());
        assertEquals("INTEREST CREDIT", txn.merchant());
    }

    @Test
    void rejectsLoanPromotion() {
        RawMessage promotion = message(
                "m-00323-fd114c",
                "Get a pre-approved Personal Loan of upto "
                        + "Rs.5,00,000 at 10.5% p.a. "
                        + "Click to know more. T&C apply. -ICICI Bank");

        assertFalse(new IciciSmsParser().parse(promotion).isPresent());
    }
}