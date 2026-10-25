package org.openelisglobal.bloodbank.isbt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.openelisglobal.bloodbank.isbt.Isbt128Parser.BloodGroup;
import org.openelisglobal.bloodbank.isbt.Isbt128Parser.Isbt128Label;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.Abo;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.RhD;

public class Isbt128ParserTest {

    // check characters computed independently (ISO 7064 MOD 37-2, as published for
    // ISBT 128 keyboard entry)
    private static final String DIN = "G123498654321";
    private static final char DIN_CHECK = 'H';

    @Test
    public void checkCharacterFollowsIso7064Mod37_2() {
        assertEquals(DIN_CHECK, Isbt128Parser.checkCharacter(DIN));
        assertEquals('5', Isbt128Parser.checkCharacter("W123412345678"));
        assertEquals('8', Isbt128Parser.checkCharacter("A999926000001"));
        assertEquals(17, Isbt128Parser.checksum(DIN));
    }

    @Test(expected = IllegalArgumentException.class)
    public void checksumRefusesACharacterOutsideTheDinAlphabet() {
        Isbt128Parser.checksum("G12349865432*");
    }

    @Test
    public void readsEachDataStructureScannedOnItsOwn() {
        Isbt128Label label = Isbt128Parser.parseScans(List.of("=" + DIN + "00", "=%5100", "=<E0291V00", "=>026306"));

        assertTrue(label.getErrorCodes().isEmpty());
        assertEquals(DIN, label.getDonationNumber());
        assertEquals("00", label.getFlags());
        assertEquals(Abo.O, label.getAbo());
        assertEquals(RhD.POS, label.getRhd());
        assertEquals("E0291V00", label.getProductCode());
        // 2026, day 306 = 2 November; a date without a time is good to the end of that
        // day
        assertEquals(LocalDateTime.of(2026, 11, 2, 23, 59), label.getExpiresAt());
    }

    @Test
    public void readsConcatenatedDataStructuresInOneScan() {
        Isbt128Label label = Isbt128Parser.parseScan("=" + DIN + "00=%0600=<E0291V00&>0263061430");

        assertTrue(label.getErrorCodes().isEmpty());
        assertEquals(DIN, label.getDonationNumber());
        assertEquals(Abo.A, label.getAbo());
        assertEquals(RhD.NEG, label.getRhd());
        assertEquals("E0291V00", label.getProductCode());
        assertEquals(LocalDateTime.of(2026, 11, 2, 14, 30), label.getExpiresAt());
    }

    @Test
    public void readsEveryGroupCodeOfTheStandard() {
        String[][] table = { { "95", "O", "NEG" }, { "51", "O", "POS" }, { "06", "A", "NEG" }, { "62", "A", "POS" },
                { "17", "B", "NEG" }, { "73", "B", "POS" }, { "28", "AB", "NEG" }, { "84", "AB", "POS" } };
        for (String[] row : table) {
            Isbt128Label label = Isbt128Parser.parseScan("=%" + row[0] + "00");
            assertEquals(row[0], Abo.valueOf(row[1]), label.getAbo());
            assertEquals(row[0], RhD.valueOf(row[2]), label.getRhd());
        }
    }

    @Test
    public void refusesAnUnknownGroupCode() {
        Isbt128Label label = Isbt128Parser.parseScan("=%5500");

        assertNull(label.getAbo());
        assertEquals(List.of(Isbt128Parser.ERR_GROUP), label.getErrorCodes());
    }

    @Test
    public void flagsCarryingTheCheckCharacterAreVerified() {
        // flag value 60 + checksum 17 = 77
        assertTrue(Isbt128Parser.parseScan("=" + DIN + "77").getErrorCodes().isEmpty());
        assertEquals(List.of(Isbt128Parser.ERR_DIN_CHECK), Isbt128Parser.parseScan("=" + DIN + "78").getErrorCodes());
    }

    @Test
    public void refusesMalformedStructures() {
        assertEquals(List.of(Isbt128Parser.ERR_DIN_FORMAT), Isbt128Parser.parseScan("=G1234").getErrorCodes());
        // O is not a valid first character: it reads like zero
        assertEquals(List.of(Isbt128Parser.ERR_UNKNOWN_STRUCTURE),
                Isbt128Parser.parseScan("=O12349865432100").getErrorCodes());
        assertEquals(List.of(Isbt128Parser.ERR_PRODUCT_CODE), Isbt128Parser.parseScan("=<E02").getErrorCodes());
        assertEquals(List.of(Isbt128Parser.ERR_PRODUCT_CODE), Isbt128Parser.parseScan("=<12345678").getErrorCodes());
        // day 366 of a non-leap year
        assertEquals(List.of(Isbt128Parser.ERR_EXPIRY), Isbt128Parser.parseScan("=>026366").getErrorCodes());
        assertEquals(List.of(Isbt128Parser.ERR_EXPIRY), Isbt128Parser.parseScan("&>0263062460").getErrorCodes());
        assertEquals(List.of(Isbt128Parser.ERR_UNKNOWN_STRUCTURE), Isbt128Parser.parseScan("hello").getErrorCodes());
        assertEquals(List.of(Isbt128Parser.ERR_UNKNOWN_STRUCTURE), Isbt128Parser.parseScan("=?123").getErrorCodes());
    }

    @Test
    public void leapDayIsAccepted() {
        // 2028 is a leap year: day 366 is 31 December
        assertEquals(LocalDateTime.of(2028, 12, 31, 23, 59), Isbt128Parser.parseScan("=>028366").getExpiresAt());
    }

    @Test
    public void emptyInputIsAnError() {
        assertEquals(List.of(Isbt128Parser.ERR_EMPTY), Isbt128Parser.parseScans(null).getErrorCodes());
        assertEquals(List.of(Isbt128Parser.ERR_EMPTY), Isbt128Parser.parseScans(List.of(" ")).getErrorCodes());
        assertEquals(List.of(Isbt128Parser.ERR_EMPTY), Isbt128Parser.parseScan("").getErrorCodes());
    }

    @Test
    public void twoScansThatDisagreeAreAnError() {
        Isbt128Label label = Isbt128Parser.parseScans(List.of("=%5100", "=%9500"));

        assertEquals(Abo.O, label.getAbo());
        assertEquals(RhD.POS, label.getRhd());
        assertEquals(List.of(Isbt128Parser.ERR_GROUP), label.getErrorCodes());
    }

    @Test
    public void typedDonationNumberWithItsCheckCharacterIsVerified() {
        List<String> errors = new ArrayList<>();
        assertEquals(DIN, Isbt128Parser.typedDonationNumber("g123 498 654 321 h", errors));
        assertEquals(DIN, Isbt128Parser.typedDonationNumber(DIN, errors));
        assertEquals(DIN, Isbt128Parser.typedDonationNumber("=" + DIN + "00", errors));
        assertTrue(errors.isEmpty());

        assertNull(Isbt128Parser.typedDonationNumber(DIN + "K", errors));
        assertEquals(List.of(Isbt128Parser.ERR_DIN_CHECK), errors);
    }

    @Test
    public void typedDonationNumberOfTheWrongShapeIsRefused() {
        List<String> errors = new ArrayList<>();
        assertNull(Isbt128Parser.typedDonationNumber("G12349865432", errors));
        assertNull(Isbt128Parser.typedDonationNumber("", errors));
        assertNull(Isbt128Parser.typedDonationNumber(null, errors));
        assertEquals(List.of(Isbt128Parser.ERR_DIN_FORMAT, Isbt128Parser.ERR_DIN_FORMAT, Isbt128Parser.ERR_DIN_FORMAT),
                errors);
    }

    @Test
    public void typedGroupsInTheUsualSpellings() {
        List<String> errors = new ArrayList<>();
        assertEquals(new BloodGroup(Abo.O, RhD.NEG), Isbt128Parser.typedGroup("O NEG", errors));
        assertEquals(new BloodGroup(Abo.A, RhD.POS), Isbt128Parser.typedGroup("a+", errors));
        assertEquals(new BloodGroup(Abo.AB, RhD.POS), Isbt128Parser.typedGroup("AB RhD positive", errors));
        assertEquals(new BloodGroup(Abo.B, RhD.NEG), Isbt128Parser.typedGroup("group B negative", errors));
        assertEquals(new BloodGroup(Abo.AB, RhD.NEG), Isbt128Parser.typedGroup("28", errors));
        assertEquals(new BloodGroup(Abo.O, RhD.POS), Isbt128Parser.typedGroup("=%5100", errors));
        assertTrue(errors.isEmpty());

        assertNull(Isbt128Parser.typedGroup("C POS", errors));
        assertNull(Isbt128Parser.typedGroup("O", errors));
        assertEquals(List.of(Isbt128Parser.ERR_GROUP, Isbt128Parser.ERR_GROUP), errors);
    }

    @Test
    public void typedProductCodeAndExpiry() {
        List<String> errors = new ArrayList<>();
        assertEquals("E0291V00", Isbt128Parser.typedProductCode("e0291v00", errors));
        assertEquals("E0291V00", Isbt128Parser.typedProductCode("=<E0291V00", errors));
        assertEquals(LocalDateTime.of(2026, 11, 2, 23, 59), Isbt128Parser.typedExpiry("2026-11-02", errors));
        assertEquals(LocalDateTime.of(2026, 11, 2, 14, 30), Isbt128Parser.typedExpiry("2026-11-02T14:30", errors));
        assertEquals(LocalDateTime.of(2026, 11, 2, 14, 30), Isbt128Parser.typedExpiry("&>0263061430", errors));
        assertTrue(errors.isEmpty());

        assertNull(Isbt128Parser.typedProductCode("E029", errors));
        assertNull(Isbt128Parser.typedExpiry("02/11/2026", errors));
        assertEquals(List.of(Isbt128Parser.ERR_PRODUCT_CODE, Isbt128Parser.ERR_EXPIRY), errors);
    }
}
