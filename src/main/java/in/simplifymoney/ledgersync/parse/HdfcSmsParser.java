package in.simplifymoney.ledgersync.parse;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses HDFC Bank SMS transaction alerts.
 */
public final class HdfcSmsParser implements MessageParser {

    public static final String SENDER = "AD-HDFCBK-S";

    private static final Pattern V1 = Pattern.compile(
            "(?<dir>debited from|credited to) "
                    + "a/c \\*\\*(?<acct>\\d{4}) "
                    + "on (?<when>\\d{2}-\\d{2}-\\d{2} "
                    + "at \\d{2}:\\d{2}) "
                    + "(?:to|by) (?<merchant>[^.]+)\\.");

    private static final Pattern V2 = Pattern.compile(
            "^(?<dir>Sent|Received) .*?\\n"
                    + "(?:To|From): (?<merchant>.+?)\\n"
                    + "On: (?<when>\\d{2} \\w{3} \\d{2} "
                    + "\\d{2}:\\d{2})\\n"
                    + "A/c: XX(?<acct>\\d{4})",
            Pattern.DOTALL);

    private static final Pattern CARD = Pattern.compile(
            "spent on HDFC Bank Card "
                    + "x(?<acct>\\d{4}) "
                    + "at (?<merchant>.+?) "
                    + "on (?<when>\\d{2}-\\d{2}-\\d{2} "
                    + "\\d{2}:\\d{2})\\.");

    @Override
    public boolean supports(RawMessage message) {
        return "sms".equals(message.channel())
                && SENDER.equals(message.sender());
    }

    @Override
    public Optional<ParsedTxn> parse(RawMessage message) {
        String body = message.body();

        Matcher v1 = V1.matcher(body);

        if (v1.find()) {
            Direction direction =
                    v1.group("dir").startsWith("debited")
                            ? Direction.DEBIT
                            : Direction.CREDIT;

            return build(
                    message,
                    v1.group("acct"),
                    v1.group("when")
                            .replace(" at ", " "),
                    direction,
                    v1.group("merchant"),
                    true);
        }

        Matcher v2 = V2.matcher(body);

        if (v2.find()) {
            Direction direction =
                    "Sent".equals(v2.group("dir"))
                            ? Direction.DEBIT
                            : Direction.CREDIT;

            return build(
                    message,
                    v2.group("acct"),
                    v2.group("when"),
                    direction,
                    v2.group("merchant"),
                    true);
        }

        Matcher card = CARD.matcher(body);

        if (card.find()) {
            return build(
                    message,
                    card.group("acct"),
                    card.group("when"),
                    Direction.DEBIT,
                    card.group("merchant"),
                    false);
        }

        return Optional.empty();
    }

    private Optional<ParsedTxn> build(
            RawMessage message,
            String account,
            String when,
            Direction direction,
            String merchant,
            boolean hasAccountBalance) {

        BigDecimal amount =
                Amounts.first(message.body());

        OffsetDateTime occurredAt =
                Dates.ist(when);

        if (amount == null || occurredAt == null) {
            return Optional.empty();
        }

        BigDecimal statedBalance =
                hasAccountBalance
                        ? Amounts.statedBalance(
                        message.body())
                        : null;

        return Optional.of(new ParsedTxn(
                account,
                occurredAt,
                direction,
                amount,
                merchant.trim(),
                statedBalance,
                message.messageId()));
    }
}