package org.openelisglobal.analyzer.service;

import org.openelisglobal.siteinformation.service.SiteInformationService;
import org.openelisglobal.siteinformation.valueholder.SiteInformation;
import org.springframework.stereotype.Service;

/**
 * {@code analyzer.worklist.enabled}: whether new orders are queued for, and
 * sent to, analysers. On unless the setting says exactly "false" (a missing row
 * counts as on).
 */
@Service
public class AnalyzerWorklistSettings {

    public static final String ENABLED = "analyzer.worklist.enabled";

    private final SiteInformationService siteInformationService;

    public AnalyzerWorklistSettings(SiteInformationService siteInformationService) {
        this.siteInformationService = siteInformationService;
    }

    public boolean isEnabled() {
        SiteInformation setting = siteInformationService.getSiteInformationByName(ENABLED);
        return setting == null || setting.getValue() == null || !"false".equalsIgnoreCase(setting.getValue().trim());
    }
}
