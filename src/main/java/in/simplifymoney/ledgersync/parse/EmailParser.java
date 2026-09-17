package in.simplifymoney.ledgersync.parse;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses transaction-alert emails from supported banks. */
public final class EmailParser implements MessageParser {

    private static final Set<String> TRUSTED_SENDERS = Set.of(
            "alerts@hdfcbank.net",
            "alerts@icicibank.com");

    private static final Pattern TRANSACTION = Pattern.compile(
            "\\ADate:[ \\t]*(?<when>[^\\r\\n]+)\\R"
                    + "Subject:[ \\t]*Transaction alert on your account\\R\\R"
                    + "Dear Customer,\\R\\R"
                    + "Your account ending (?<acct>\\d{4}) has been "
                    + "(?<dir>debited|credited) with "
                    + "(?:Rs\\.?|INR)\\s*[0-9][0-9,]*(?:\\.[0-9]{1,2})?\\.\\R"
                    + "Merchant / Remarks:[ \\t]*(?<merchant>[^\\r\\n]+)\\R"
                    + "Transaction reference:[ \\t]*\\d+\\R\\R"
                    + "This is a system generated email\\.[ \\t]*\\R?\\z",
            Pattern.CASE_INSENSITIVE);

    @Override
    public boolean supports(RawMessage message) {
        return "email".equals(message.channel())
                && TRUSTED_SENDERS.contains(
                message.sender().toLowerCase(Locale.ROOT));
    }

    @Override
    public Optional<ParsedTxn> parse(RawMessage message) {
        if (!supports(message)) {
            return Optional.empty();
        }

        Matcher matcher = TRANSACTION.matcher(message.body());
        if (!matcher.matches()) {
            return Optional.empty();
        }

        BigDecimal amount = Amounts.first(message.body());
        OffsetDateTime occurredAt =
                parseEmailDate(matcher.group("when"));

        if (amount == null || occurredAt == null) {
            return Optional.empty();
        }

        Direction direction =
                "debited".equalsIgnoreCase(matcher.group("dir"))
                        ? Direction.DEBIT
                        : Direction.CREDIT;

        return Optional.of(new ParsedTxn(
                matcher.group("acct"),
                occurredAt,
                direction,
                amount,
                matcher.group("merchant").trim(),
                null,
                message.messageId()));
    }

    private static OffsetDateTime parseEmailDate(String raw) {
        try {
            return ZonedDateTime
                    .parse(raw.trim(), DateTimeFormatter.RFC_1123_DATE_TIME)
                    .withZoneSameInstant(Dates.IST)
                    .toOffsetDateTime();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
}