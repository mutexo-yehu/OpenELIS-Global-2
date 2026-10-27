package org.openelisglobal.bloodbank.valueholder;

import java.util.Locale;

/** The fixed vocabularies of the blood product inventory. */
public final class BloodBankEnums {

    private BloodBankEnums() {
    }

    /** What a component is, which decides the compatibility rules it follows. */
    public enum ComponentClass {
        RED_CELLS, WHOLE_BLOOD, PLASMA, PLATELETS, CRYO;

        /**
         * Red cells are transfused, so the ABO/RhD red-cell rules and a crossmatch
         * apply.
         */
        public boolean carriesRedCells() {
            return this == RED_CELLS || this == WHOLE_BLOOD;
        }
    }

    public enum Abo {
        O, A, B, AB;

        /**
         * Reads a group as typed or as an OpenELIS result value ("A", "group AB", "o"),
         * or null when it is not one.
         */
        public static Abo parse(String text) {
            if (text == null) {
                return null;
            }
            String value = text.trim().toUpperCase(Locale.ROOT).replace("GROUP", "").replace("BLOOD", "").trim();
            for (Abo abo : values()) {
                if (abo.name().equals(value)) {
                    return abo;
                }
            }
            return null;
        }
    }

    public enum RhD {
        POS, NEG;

        /**
         * Reads an RhD type as typed or as an OpenELIS result value ("Positive", "neg",
         * "+", "Rh-"), or null when it is not one.
         */
        public static RhD parse(String text) {
            if (text == null) {
                return null;
            }
            String value = text.trim().toUpperCase(Locale.ROOT).replace("RHD", "").replace("RH(D)", "")
                    .replace("RH", "").replace(" ", "");
            switch (value) {
            case "POS":
            case "POSITIVE":
            case "+":
            case "D+":
                return POS;
            case "NEG":
            case "NEGATIVE":
            case "-":
            case "D-":
                return NEG;
            default:
                return null;
            }
        }
    }

    /**
     * Where a unit is. Receipt makes it AVAILABLE; see
     * {@link org.openelisglobal.bloodbank.service.UnitStatusTransitions} for the
     * allowed moves.
     */
    public enum UnitStatus {
        AVAILABLE, QUARANTINED, RESERVED, ISSUED, TRANSFUSED, DISCARDED, RETURNED_TO_SUPPLIER;

        public boolean isFinal() {
            return this == TRANSFUSED || this == DISCARDED || this == RETURNED_TO_SUPPLIER;
        }
    }

    public enum ReservationStatus {
        ACTIVE, ISSUED, RELEASED, EXPIRED
    }

    /**
     * EMERGENCY_ISSUED is an issue without a confirmed group or crossmatch;
     * RETROSPECTIVE_CHECK is the lab's later group and crossmatch of that unit.
     */
    public enum EventType {
        RECEIVED, QUARANTINED, RELEASED, RESERVED, UNRESERVED, ISSUED, RETURNED, TRANSFUSED, REACTION, DISCARDED,
        RETURNED_TO_SUPPLIER, EMERGENCY_ISSUED, RETROSPECTIVE_CHECK
    }

    /** The lab's retrospective group and crossmatch of an emergency unit. */
    public enum RetrospectiveResult {
        COMPATIBLE, INCOMPATIBLE;

        /** Reads "compatible", " INCOMPATIBLE ", or null when it is neither. */
        public static RetrospectiveResult parse(String text) {
            if (text == null) {
                return null;
            }
            String value = text.trim().toUpperCase(Locale.ROOT);
            for (RetrospectiveResult result : values()) {
                if (result.name().equals(value)) {
                    return result;
                }
            }
            return null;
        }
    }
}
