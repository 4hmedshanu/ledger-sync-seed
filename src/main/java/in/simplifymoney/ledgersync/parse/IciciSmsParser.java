package in.simplifymoney.ledgersync.parse;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses supported ICICI Bank SMS transaction formats. */
public final class IciciSmsParser implements MessageParser {

    public static final String SENDER = "VM-ICICIB-T";

    private static final Pattern V1 = Pattern.compile(
            "Acct XX(?<acct>\\d{4}) is (?<dir>debited|credited) with .*? "
                    + "on (?<when>\\d{2}/\\d{2}/\\d{4} \\d{2}:\\d{2})\\. "
                    + "Info: (?<merchant>[^.]+)\\.");

    private static final Pattern V2 = Pattern.compile(
            "\\AICICI Bank Acct XX(?<acct>\\d{4}) "
                    + "(?<dir>Dr|Cr) "
                    + "(?:Rs\\.?|INR)\\s*"
                    + "[0-9][0-9,]*(?:\\.[0-9]{1,2})? "
                    + "on (?<when>\\d{2}-[A-Za-z]{3}-\\d{4} "
                    + "\\d{2}:\\d{2}); "
                    + "(?<merchant>.+?) ref no \\d+\\. "
                    + "BalAvl\\s+(?:Rs\\.?|INR)\\s*"
                    + "[0-9][0-9,]*(?:\\.[0-9]{1,2})?\\s*\\z",
            Pattern.CASE_INSENSITIVE);

    @Override
    public boolean supports(RawMessage message) {
        return "sms".equals(message.channel())
                && SENDER.equals(message.sender());
    }

    @Override
    public Optional<ParsedTxn> parse(RawMessage message) {
        Matcher v1 = V1.matcher(message.body());

        if (v1.find()) {
            Direction direction =
                    "debited".equals(v1.group("dir"))
                            ? Direction.DEBIT
                            : Direction.CREDIT;

            return build(
                    message,
                    v1.group("acct"),
                    v1.group("when"),
                    direction,
                    v1.group("merchant"));
        }

        Matcher v2 = V2.matcher(message.body());

        if (v2.matches()) {
            Direction direction =
                    "Dr".equalsIgnoreCase(v2.group("dir"))
                            ? Direction.DEBIT
                            : Direction.CREDIT;

            return build(
                    message,
                    v2.group("acct"),
                    v2.group("when"),
                    direction,
                    v2.group("merchant"));
        }

        return Optional.empty();
    }

    private Optional<ParsedTxn> build(
            RawMessage message,
            String account,
            String when,
            Direction direction,
            String merchant) {

        BigDecimal amount = Amounts.first(message.body());
        OffsetDateTime occurredAt = Dates.ist(when);

        if (amount == null || occurredAt == null) {
            return Optional.empty();
        }

        return Optional.of(new ParsedTxn(
                account,
                occurredAt,
                direction,
                amount,
                merchant.trim(),
                Amounts.statedBalance(message.body()),
                message.messageId()));
    }
}