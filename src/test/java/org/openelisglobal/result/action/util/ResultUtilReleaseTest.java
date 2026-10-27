package org.openelisglobal.result.action.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import org.junit.Test;
import org.openelisglobal.analysis.valueholder.Analysis;
import org.openelisglobal.common.services.IStatusService;
import org.openelisglobal.common.services.StatusService.AnalysisStatus;

/**
 * A result finalized at entry without a validator is released at that moment,
 * as a manual validation releases it, so the patient report can show when and
 * by whom.
 */
public class ResultUtilReleaseTest {

    private static final Timestamp NOW = Timestamp.valueOf("2026-10-27 09:30:00");
    private static final Timestamp EARLIER = Timestamp.valueOf("2026-10-01 08:00:00");

    private final IStatusService statusService = mock(IStatusService.class);

    @Test
    public void aResultFinalizedAtEntryIsStampedReleasedNow() {
        when(statusService.matches("4", AnalysisStatus.Finalized)).thenReturn(true);
        Analysis analysis = new Analysis();

        ResultUtil.setStatusAndRelease(analysis, "4", statusService, NOW);

        assertEquals("4", analysis.getStatusId());
        assertEquals(NOW, analysis.getReleasedDate());
    }

    @Test
    public void aResultThatStillAwaitsValidationIsNotStampedReleased() {
        when(statusService.matches("6", AnalysisStatus.Finalized)).thenReturn(false);
        Analysis analysis = new Analysis();

        ResultUtil.setStatusAndRelease(analysis, "6", statusService, NOW);

        assertEquals("6", analysis.getStatusId());
        assertNull(analysis.getReleasedDate());
    }

    @Test
    public void aResultHeldForValidationKeepsAnEarlierReleaseDate() {
        when(statusService.matches("6", AnalysisStatus.Finalized)).thenReturn(false);
        Analysis analysis = new Analysis();
        analysis.setReleasedDate(EARLIER);

        ResultUtil.setStatusAndRelease(analysis, "6", statusService, NOW);

        assertEquals(EARLIER, analysis.getReleasedDate());
    }
}
