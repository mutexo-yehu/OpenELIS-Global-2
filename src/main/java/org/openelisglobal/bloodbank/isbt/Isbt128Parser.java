package org.openelisglobal.bloodbank.isbt;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Year;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.Abo;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.RhD;

/**
 * Reads the ISBT 128 data structures printed on a blood unit's label, one per
 * scan or several concatenated, and the same fields typed by hand.
 *
 * <p>
 * Supported: donation identification number ({@code =} + 13 characters + 2 flag
 * characters), blood groups ({@code =%}), product code ({@code =<}), expiry
 * date ({@code =>}) and expiry date and time ({@code &>}). The typed donation
 * number may carry its ISO 7064 mod 37-2 check character, which is then
 * verified.
 *
 * <p>
 * This reads the label structure only. It is not certified by ICCBBA, and it
 * does not know what a product code means: each site maps its own codes. Times
 * are local to the laboratory.
 */
public final class Isbt128Parser {

    public static final String ERR_UNKNOWN_STRUCTURE = "bloodbank.isbt.error.unknownStructure";
    public static final String ERR_DIN_FORMAT = "bloodbank.isbt.error.dinFormat";
    public static final String ERR_DIN_CHECK = "bloodbank.isbt.error.dinCheckCharacter";
    public static final String ERR_GROUP = "bloodbank.isbt.error.bloodGroup";
    public static final String ERR_PRODUCT_CODE = "bloodbank.isbt.error.productCode";
    public static final String ERR_EXPIRY = "bloodbank.isbt.error.expiry";
    public static final String ERR_EMPTY = "bloodbank.isbt.error.empty";

    /** First character of a DIN: A–N, P–Z (no O, confusable with zero) or 1–9. */
    private static final String DIN_FIRST = "[A-NP-Z1-9]";
    private static final Pattern DIN = Pattern.compile(DIN_FIRST + "[0-9A-Z]{4}[0-9]{8}");
    private static final Pattern PRODUCT_CODE = Pattern.compile("[A-Z][0-9]{4}[0-9A-Z][0-9A-Z]{2}");
    private static final Pattern TYPED_GROUP = Pattern
            .compile("(AB|A|B|O)\\s*(RHD|RH)?\\s*(POS|POSITIVE|NEG|NEGATIVE|\\+|-)");

    /** ISBT 128 blood group codes (first two characters of {@code =%}). */
    private static final Map<String, BloodGroup> GROUP_CODES = Map.of("95", new BloodGroup(Abo.O, RhD.NEG), "51",
            new BloodGroup(Abo.O, RhD.POS), "06", new BloodGroup(Abo.A, RhD.NEG), "62", new BloodGroup(Abo.A, RhD.POS),
            "17", new BloodGroup(Abo.B, RhD.NEG), "73", new BloodGroup(Abo.B, RhD.POS), "28",
            new BloodGroup(Abo.AB, RhD.NEG), "84", new BloodGroup(Abo.AB, RhD.POS));

    private static final String CHECK_ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ*";

    private Isbt128Parser() {
    }

    /**
     * Parses one or more scans. Later scans fill fields earlier ones left empty; a
     * field read twice with different values is an error.
     */
    public static Isbt128Label parseScans(List<String> scans) {
        Isbt128Label label = new Isbt128Label();
        if (scans == null || scans.stream().allMatch(s -> s == null || s.isBlank())) {
            label.addError(ERR_EMPTY, "");
            return label;
        }
        for (String scan : scans) {
            if (scan != null && !scan.isBlank()) {
                parseScan(scan, label);
            }
        }
        return label;
    }

    /** Parses one scan, which may hold several concatenated data structures. */
    public static Isbt128Label parseScan(String scan) {
        Isbt128Label label = new Isbt128Label();
        if (scan == null || scan.isBlank()) {
            label.addError(ERR_EMPTY, "");
            return label;
        }
        parseScan(scan, label);
        return label;
    }

    private static void parseScan(String raw, Isbt128Label label) {
        String scan = raw.trim().replace(" ", "").toUpperCase(Locale.ROOT);
        if (scan.charAt(0) != '=' && scan.charAt(0) != '&') {
            label.addError(ERR_UNKNOWN_STRUCTURE, raw.trim());
            return;
        }
        int i = 0;
        while (i < scan.length()) {
            String rest = scan.substring(i);
            if (rest.startsWith("=%")) {
                i += readGroup(rest, label);
            } else if (rest.startsWith("=<")) {
                i += readProductCode(rest, label);
            } else if (rest.startsWith("=>")) {
                i += readExpiryDate(rest, label);
            } else if (rest.startsWith("&>")) {
                i += readExpiryDateTime(rest, label);
            } else if (rest.length() >= 2 && rest.charAt(0) == '='
                    && String.valueOf(rest.charAt(1)).matches(DIN_FIRST)) {
                i += readDonationNumber(rest, label);
            } else {
                label.addError(ERR_UNKNOWN_STRUCTURE, rest);
                return;
            }
        }
    }

    /**
     * {@code =} + DIN (13) + flags (2); the flags may be absent at the end of a
     * scan.
     */
    private static int readDonationNumber(String rest, Isbt128Label label) {
        if (rest.length() < 14 || !DIN.matcher(rest.substring(1, 14)).matches()) {
            label.addError(ERR_DIN_FORMAT, rest.substring(0, Math.min(rest.length(), 16)));
            return rest.length();
        }
        String din = rest.substring(1, 14);
        String flags = null;
        int consumed = 14;
        if (rest.length() >= 16 && !rest.substring(14).startsWith("=") && !rest.substring(14).startsWith("&")) {
            flags = rest.substring(14, 16);
            consumed = 16;
        }
        // Flag values 60–96 carry the check character (checksum + 60)
        if (flags != null && flags.matches("[0-9]{2}")) {
            int flagValue = Integer.parseInt(flags);
            if (flagValue >= 60 && flagValue <= 96 && flagValue - 60 != checksum(din)) {
                label.addError(ERR_DIN_CHECK, din);
                return consumed;
            }
        }
        label.setDonationNumber(din);
        label.setFlags(flags);
        return consumed;
    }

    private static int readGroup(String rest, Isbt128Label label) {
        if (rest.length() < 6) {
            label.addError(ERR_GROUP, rest);
            return rest.length();
        }
        BloodGroup group = GROUP_CODES.get(rest.substring(2, 4));
        if (group == null) {
            label.addError(ERR_GROUP, rest.substring(0, 6));
        } else {
            label.setGroup(group.abo(), group.rhd());
        }
        return 6;
    }

    private static int readProductCode(String rest, Isbt128Label label) {
        if (rest.length() < 10 || !PRODUCT_CODE.matcher(rest.substring(2, 10)).matches()) {
            label.addError(ERR_PRODUCT_CODE, rest.substring(0, Math.min(rest.length(), 10)));
            return rest.length();
        }
        label.setProductCode(rest.substring(2, 10));
        return 10;
    }

    private static int readExpiryDate(String rest, Isbt128Label label) {
        if (rest.length() < 8) {
            label.addError(ERR_EXPIRY, rest);
            return rest.length();
        }
        LocalDate date = julianDate(rest.substring(2, 8));
        if (date == null) {
            label.addError(ERR_EXPIRY, rest.substring(0, 8));
        } else {
            // a unit with an expiry date and no time is usable to the end of that day
            label.setExpiresAt(date.atTime(23, 59));
        }
        return 8;
    }

    private static int readExpiryDateTime(String rest, Isbt128Label label) {
        if (rest.length() < 12) {
            label.addError(ERR_EXPIRY, rest);
            return rest.length();
        }
        LocalDate date = julianDate(rest.substring(2, 8));
        LocalTime time = hourMinute(rest.substring(8, 12));
        if (date == null || time == null) {
            label.addError(ERR_EXPIRY, rest.substring(0, 12));
        } else {
            label.setExpiresAt(date.atTime(time));
        }
        return 12;
    }

    /** {@code cyyjjj}: century digit (0 = 2000s), year, day of the year. */
    private static LocalDate julianDate(String cyyjjj) {
        if (!cyyjjj.matches("[0-9]{6}")) {
            return null;
        }
        int year = 2000 + Integer.parseInt(cyyjjj.substring(0, 1)) * 100 + Integer.parseInt(cyyjjj.substring(1, 3));
        int day = Integer.parseInt(cyyjjj.substring(3, 6));
        if (day < 1 || day > Year.of(year).length()) {
            return null;
        }
        return LocalDate.ofYearDay(year, day);
    }

    private static LocalTime hourMinute(String hhmm) {
        if (!hhmm.matches("[0-9]{4}")) {
            return null;
        }
        try {
            return LocalTime.of(Integer.parseInt(hhmm.substring(0, 2)), Integer.parseInt(hhmm.substring(2, 4)));
        } catch (DateTimeException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- typed fields

    /**
     * A donation number as typed: the 13 characters, optionally followed by the
     * check character from the eye-readable label (verified), or a scan of the DIN
     * structure.
     *
     * @return the 13-character DIN, or null when it is not one
     */
    public static String typedDonationNumber(String typed, List<String> errors) {
        if (typed == null || typed.isBlank()) {
            errors.add(ERR_DIN_FORMAT);
            return null;
        }
        String value = typed.trim().replace(" ", "").toUpperCase(Locale.ROOT);
        if (value.startsWith("=")) {
            Isbt128Label label = parseScan(value);
            errors.addAll(label.getErrorCodes());
            return label.getDonationNumber();
        }
        if (value.length() == 13 && DIN.matcher(value).matches()) {
            return value;
        }
        if (value.length() == 14 && DIN.matcher(value.substring(0, 13)).matches()) {
            if (checkCharacter(value.substring(0, 13)) == value.charAt(13)) {
                return value.substring(0, 13);
            }
            errors.add(ERR_DIN_CHECK);
            return null;
        }
        errors.add(ERR_DIN_FORMAT);
        return null;
    }

    /**
     * A product code as typed ({@code E0291V00}) or scanned ({@code =<E0291V00}).
     */
    public static String typedProductCode(String typed, List<String> errors) {
        if (typed == null || typed.isBlank()) {
            errors.add(ERR_PRODUCT_CODE);
            return null;
        }
        String value = typed.trim().replace(" ", "").toUpperCase(Locale.ROOT);
        if (value.startsWith("=<")) {
            value = value.substring(2);
        }
        if (PRODUCT_CODE.matcher(value).matches()) {
            return value;
        }
        errors.add(ERR_PRODUCT_CODE);
        return null;
    }

    /**
     * A blood group as typed ("O NEG", "A+", "AB RhD positive"), as an ISBT code
     * ("95") or scanned ({@code =%9500}).
     *
     * @return the group, or null when it is not one
     */
    public static BloodGroup typedGroup(String typed, List<String> errors) {
        if (typed == null || typed.isBlank()) {
            errors.add(ERR_GROUP);
            return null;
        }
        String value = typed.trim().toUpperCase(Locale.ROOT);
        if (value.startsWith("=%")) {
            Isbt128Label label = parseScan(value);
            if (label.getErrorCodes().isEmpty()) {
                return new BloodGroup(label.getAbo(), label.getRhd());
            }
            errors.addAll(label.getErrorCodes());
            return null;
        }
        if (GROUP_CODES.containsKey(value)) {
            return GROUP_CODES.get(value);
        }
        Matcher matcher = TYPED_GROUP.matcher(value.replace("GROUP", "").trim());
        if (matcher.matches()) {
            String sign = matcher.group(3);
            RhD rhd = sign.startsWith("P") || "+".equals(sign) ? RhD.POS : RhD.NEG;
            return new BloodGroup(Abo.valueOf(matcher.group(1)), rhd);
        }
        errors.add(ERR_GROUP);
        return null;
    }

    /**
     * An expiry as typed (ISO {@code 2026-11-02} or {@code 2026-11-02T14:30}) or
     * scanned ({@code =>026306}, {@code &>0263061430}).
     */
    public static LocalDateTime typedExpiry(String typed, List<String> errors) {
        if (typed == null || typed.isBlank()) {
            errors.add(ERR_EXPIRY);
            return null;
        }
        String value = typed.trim();
        if (value.startsWith("=>") || value.startsWith("&>")) {
            Isbt128Label label = parseScan(value);
            errors.addAll(label.getErrorCodes());
            return label.getExpiresAt();
        }
        try {
            if (value.length() == 10) {
                return LocalDate.parse(value).atTime(23, 59);
            }
            return LocalDateTime.parse(value.length() > 16 ? value.substring(0, 16) : value);
        } catch (DateTimeParseException e) {
            errors.add(ERR_EXPIRY);
            return null;
        }
    }

    // ---------------------------------------------------------------- check
    // character

    /** ISO 7064 mod 37-2 checksum (0–36) of a donation number's 13 characters. */
    public static int checksum(String din) {
        int sum = 0;
        for (char c : din.toUpperCase(Locale.ROOT).toCharArray()) {
            int value = CHECK_ALPHABET.indexOf(c);
            if (value < 0 || value > 35) {
                throw new IllegalArgumentException("not a donation number character: " + c);
            }
            sum = ((sum + value) * 2) % 37;
        }
        return (38 - sum) % 37;
    }

    /** The check character printed beside the eye-readable donation number. */
    public static char checkCharacter(String din) {
        return CHECK_ALPHABET.charAt(checksum(din));
    }

    /** An ABO group with its RhD type. */
    public record BloodGroup(Abo abo, RhD rhd) {
    }

    /** Collects what the scans said, and what could not be read. */
    public static final class Isbt128Label {
        private String donationNumber;
        private String flags;
        private Abo abo;
        private RhD rhd;
        private String productCode;
        private LocalDateTime expiresAt;
        private final List<String> errorCodes = new ArrayList<>();
        private final List<String> errorInputs = new ArrayList<>();

        void addError(String code, String input) {
            errorCodes.add(code);
            errorInputs.add(input);
        }

        private void setDonationNumber(String value) {
            if (donationNumber != null && !donationNumber.equals(value)) {
                addError(ERR_DIN_FORMAT, value);
                return;
            }
            donationNumber = value;
        }

        private void setFlags(String value) {
            flags = value;
        }

        private void setGroup(Abo aboValue, RhD rhdValue) {
            if (abo != null && (abo != aboValue || rhd != rhdValue)) {
                addError(ERR_GROUP, aboValue + " " + rhdValue);
                return;
            }
            abo = aboValue;
            rhd = rhdValue;
        }

        private void setProductCode(String value) {
            if (productCode != null && !productCode.equals(value)) {
                addError(ERR_PRODUCT_CODE, value);
                return;
            }
            productCode = value;
        }

        private void setExpiresAt(LocalDateTime value) {
            if (expiresAt != null && !expiresAt.equals(value)) {
                addError(ERR_EXPIRY, value.toString());
                return;
            }
            expiresAt = value;
        }

        public String getDonationNumber() {
            return donationNumber;
        }

        public String getFlags() {
            return flags;
        }

        public Abo getAbo() {
            return abo;
        }

        public RhD getRhd() {
            return rhd;
        }

        public String getProductCode() {
            return productCode;
        }

        public LocalDateTime getExpiresAt() {
            return expiresAt;
        }

        public List<String> getErrorCodes() {
            return List.copyOf(errorCodes);
        }

        public List<String> getErrorInputs() {
            return List.copyOf(errorInputs);
        }
    }
}
