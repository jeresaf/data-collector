package org.odk.platform.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReportDefinitionServiceTest {

    @Test
    void canonicalFormPath_removesInstanceRootAndRepeatIndexes() {
        assertEquals("household/members/name",
                ReportDefinitionService.canonicalFormPath("/data/household/members[0]/name"));
    }

    @Test
    void canonicalAnswerPath_keepsGenericHierarchyButRemovesMultiplicities() {
        assertEquals("teams/players/score",
                ReportDefinitionService.canonicalAnswerPath("teams/players[12]/score"));
    }
}
