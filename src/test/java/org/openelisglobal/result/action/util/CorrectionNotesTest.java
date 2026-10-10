package org.openelisglobal.result.action.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;
import org.openelisglobal.note.valueholder.Note;
import org.openelisglobal.systemuser.valueholder.SystemUser;

public class CorrectionNotesTest {

    @Test
    public void previouslyReportedNamesTheValueAndWhoCorrectedIt() {
        assertEquals("Previously reported: 250 10^3/µl; corrected by Ann Smith",
                CorrectionNotes.previouslyReported("250 10^3/µl", "Ann Smith"));
    }

    @Test
    public void aPrintedCorrectionIsStillFoundByItsNote() {
        Note corrected = note(Note.EXTERNAL, " Result corrected ");
        Note internal = note(Note.INTERNAL, "Result corrected");
        Note other = note(Note.EXTERNAL, "Haemolysed sample");

        assertTrue(CorrectionNotes.hasCorrectionNote(List.of(other, corrected), "Result corrected"));
        assertFalse(CorrectionNotes.hasCorrectionNote(List.of(other, internal), "Result corrected"));
        assertFalse(CorrectionNotes.hasCorrectionNote(null, "Result corrected"));
        assertFalse(CorrectionNotes.hasCorrectionNote(List.of(corrected), ""));
    }

    @Test
    public void reportedValueIsEmptyWithoutResults() {
        assertEquals("", CorrectionNotes.reportedValue(null));
        assertEquals("", CorrectionNotes.reportedValue(List.of()));
    }

    @Test
    public void displayNameFallsBackToTheLoginThenTheId() {
        SystemUser named = new SystemUser();
        named.setFirstName("Ann");
        named.setLastName("Smith");
        named.setLoginName("tech1");
        SystemUser unnamed = new SystemUser();
        unnamed.setLoginName("tech2");

        assertEquals("Ann Smith", CorrectionNotes.displayName(named, "1"));
        assertEquals("tech2", CorrectionNotes.displayName(unnamed, "2"));
        assertEquals("3", CorrectionNotes.displayName(null, "3"));
    }

    private static Note note(String type, String text) {
        Note note = new Note();
        note.setNoteType(type);
        note.setText(text);
        return note;
    }
}
