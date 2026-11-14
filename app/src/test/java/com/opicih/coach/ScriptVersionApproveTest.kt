package com.opicih.coach

import com.opicih.coach.data.db.ScriptVersion
import org.junit.Assert.assertEquals
import org.junit.Test

class ScriptVersionApproveTest {
    /** Approving Short or Keywords must not replace the study text; only full-length answers become it. */
    @Test fun onlyFullAnswersBecomeStudyTextOnApprove() {
        assertEquals(
            setOf(ScriptVersion.NATURAL, ScriptVersion.IH_TARGET),
            ScriptVersion.entries.filter { it.becomesStudyTextOnApprove }.toSet(),
        )
    }
}
