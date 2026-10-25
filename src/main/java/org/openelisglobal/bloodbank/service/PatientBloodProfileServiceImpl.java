package org.openelisglobal.bloodbank.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.openelisglobal.bloodbank.dao.BloodProfileResultDAO;
import org.openelisglobal.bloodbank.dao.BloodProfileResultDAO.ProfileResultRow;
import org.openelisglobal.bloodbank.dao.BloodUnitReservationDAO;
import org.openelisglobal.bloodbank.safety.PatientBloodProfile;
import org.openelisglobal.bloodbank.safety.PatientBloodProfile.CrossmatchResult;
import org.openelisglobal.bloodbank.safety.PatientBloodProfile.LabValue;
import org.openelisglobal.bloodbank.safety.PatientBloodProfile.PatientGroup;
import org.openelisglobal.bloodbank.safety.PatientBloodProfile.TypingResult;
import org.openelisglobal.bloodbank.safety.PatientBloodProfile.TypingResult.Kind;
import org.openelisglobal.bloodbank.safety.PatientGroupResolver;
import org.openelisglobal.bloodbank.valueholder.BloodUnitReservation;
import org.openelisglobal.common.services.IStatusService;
import org.openelisglobal.common.services.StatusService.AnalysisStatus;
import org.openelisglobal.dictionary.service.DictionaryService;
import org.openelisglobal.dictionary.valueholder.Dictionary;
import org.openelisglobal.patient.service.PatientService;
import org.openelisglobal.patient.valueholder.Patient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class PatientBloodProfileServiceImpl implements PatientBloodProfileService {

    /** Result types whose value is a dictionary id. */
    private static final Set<String> DICTIONARY_TYPES = Set.of("D", "M", "C");

    @Autowired
    private BloodProfileResultDAO resultDAO;

    @Autowired
    private BloodUnitReservationDAO reservationDAO;

    @Autowired
    private BloodBankSettingsService settingsService;

    @Autowired
    private IStatusService statusService;

    @Autowired
    private DictionaryService dictionaryService;

    @Autowired
    private PatientService patientService;

    @Override
    public PatientBloodProfile getProfile(Long patientId) {
        if (patientId == null || patientService.getData(String.valueOf(patientId)) == null) {
            throw new BloodBankException(BloodBankException.Kind.NOT_FOUND, "bloodbank.error.patientNotFound",
                    String.valueOf(patientId));
        }
        BloodBankSettings settings = settingsService.get();
        Map<String, String> codeToRole = new LinkedHashMap<>();
        List<String> notConfigured = new ArrayList<>();
        configure(codeToRole, notConfigured, settings.testAbo(), BloodBankSettings.TEST_ABO, "ABO");
        configure(codeToRole, notConfigured, settings.testRhd(), BloodBankSettings.TEST_RHD, "RHD");
        configure(codeToRole, notConfigured, settings.testAntibodyScreen(), BloodBankSettings.TEST_ANTIBODY_SCREEN,
                "SCREEN");
        configure(codeToRole, notConfigured, settings.testDat(), BloodBankSettings.TEST_DAT, "DAT");
        configure(codeToRole, notConfigured, settings.testCrossmatch(), BloodBankSettings.TEST_CROSSMATCH, "XM");

        List<ProfileResultRow> rows = resultDAO.getResults(String.valueOf(patientId),
                new ArrayList<>(codeToRole.keySet()), statusService.getStatusID(AnalysisStatus.Finalized));

        List<TypingResult> typings = new ArrayList<>();
        LabValue screen = null;
        LabValue dat = null;
        List<CrossmatchResult> crossmatches = new ArrayList<>();
        Set<String> compatibleValues = settings.crossmatchCompatibleValues().stream()
                .map(v -> v.trim().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        Duration validity = Duration.ofHours(settings.crossmatchValidHours());

        // rows arrive latest sample first
        for (ProfileResultRow row : rows) {
            String role = codeToRole
                    .get(row.testLocalCode() == null ? "" : row.testLocalCode().trim().toLowerCase(Locale.ROOT));
            if (role == null) {
                continue;
            }
            String value = displayValue(row);
            Long resultId = toLong(row.resultId());
            Long sampleId = toLong(row.sampleId());
            Instant collected = row.collectionDate() == null ? null : row.collectionDate().toInstant();
            switch (role) {
            case "ABO":
                typings.add(new TypingResult(Kind.ABO, resultId, sampleId, row.accessionNumber(), collected, value));
                break;
            case "RHD":
                typings.add(new TypingResult(Kind.RHD, resultId, sampleId, row.accessionNumber(), collected, value));
                break;
            case "SCREEN":
                screen = screen != null ? screen : new LabValue(value, resultId, row.accessionNumber(), collected);
                break;
            case "DAT":
                dat = dat != null ? dat : new LabValue(value, resultId, row.accessionNumber(), collected);
                break;
            default:
                boolean compatible = value != null && compatibleValues.contains(value.trim().toLowerCase(Locale.ROOT));
                // without a collection time the validity cannot be counted: treat as already
                // expired
                Instant validUntil = collected == null ? null : collected.plus(validity);
                crossmatches.add(new CrossmatchResult(resultId, sampleId, row.accessionNumber(), collected, validUntil,
                        value, compatible, null, null));
            }
        }
        PatientGroup group = PatientGroupResolver.resolve(typings, settings.twoSampleGroup());
        return new PatientBloodProfile(patientId, group, typings, screen, dat, withLinks(crossmatches), notConfigured);
    }

    @Override
    public Long resolvePatientIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return null;
        }
        String value = identifier.trim();
        String byAccession = resultDAO.getPatientIdForAccession(value);
        if (byAccession != null) {
            return toLong(byAccession);
        }
        Patient patient = patientService.getPatientByNationalId(value);
        if (patient == null) {
            patient = patientService.getPatientByExternalId(value);
        }
        return patient == null ? null : toLong(patient.getId());
    }

    private static void configure(Map<String, String> codeToRole, List<String> notConfigured, String code,
            String settingName, String role) {
        if (code == null || code.isBlank()) {
            notConfigured.add(settingName);
        } else {
            codeToRole.put(code.trim().toLowerCase(Locale.ROOT), role);
        }
    }

    /** Dictionary results hold an id; show the entry it names. */
    private String displayValue(ProfileResultRow row) {
        if (row.value() == null || row.resultType() == null || !DICTIONARY_TYPES.contains(row.resultType())) {
            return row.value();
        }
        Dictionary entry = dictionaryService.getDataForId(row.value());
        return entry == null ? row.value() : entry.getDictEntry();
    }

    /** Marks each crossmatch already tied to a live reservation, with that unit. */
    private List<CrossmatchResult> withLinks(List<CrossmatchResult> crossmatches) {
        List<Long> ids = crossmatches.stream().map(CrossmatchResult::resultId).toList();
        Map<Long, BloodUnitReservation> live = new HashMap<>();
        for (BloodUnitReservation reservation : reservationDAO.getLiveByCrossmatchResultIds(ids)) {
            live.put(reservation.getCrossmatchResultId(), reservation);
        }
        return crossmatches.stream().map(c -> {
            BloodUnitReservation reservation = live.get(c.resultId());
            return reservation == null ? c
                    : c.withLink(reservation.getUnit().getId(), reservation.getUnit().getDonationNumber());
        }).toList();
    }

    private static Long toLong(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
