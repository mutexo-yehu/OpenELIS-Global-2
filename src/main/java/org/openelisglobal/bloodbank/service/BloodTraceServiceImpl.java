package org.openelisglobal.bloodbank.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.openelisglobal.bloodbank.dao.BloodProfileResultDAO;
import org.openelisglobal.bloodbank.dao.BloodUnitDAO;
import org.openelisglobal.bloodbank.dao.BloodUnitEventDAO;
import org.openelisglobal.bloodbank.dao.BloodUnitReservationDAO;
import org.openelisglobal.bloodbank.form.BloodBankViews.EventView;
import org.openelisglobal.bloodbank.isbt.Isbt128Parser;
import org.openelisglobal.bloodbank.service.BloodBankException.Kind;
import org.openelisglobal.bloodbank.valueholder.BloodUnit;
import org.openelisglobal.bloodbank.valueholder.BloodUnitEvent;
import org.openelisglobal.bloodbank.valueholder.BloodUnitReservation;
import org.openelisglobal.patient.service.PatientService;
import org.openelisglobal.patient.valueholder.Patient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class BloodTraceServiceImpl implements BloodTraceService {

    /** Times are written as ISO 8601 instants in UTC, as the header says. */
    private static final String[] HEADER = { "occurredAtUtc", "eventType", "donationNumber", "productCode", "unitId",
            "patientId", "patientName", "patientNationalId", "labNumber", "reservationId", "performedBy",
            "secondChecker", "location", "collectorName", "reason", "details" };

    @Autowired
    private BloodUnitDAO unitDAO;

    @Autowired
    private BloodUnitEventDAO eventDAO;

    @Autowired
    private PatientService patientService;

    @Autowired
    private BloodUnitTrail trail;

    @Autowired
    private BloodUnitReservationDAO reservationDAO;

    @Autowired
    private BloodProfileResultDAO profileResultDAO;

    @Override
    public List<EventView> forUnit(Long unitId) {
        unitDAO.get(unitId).orElseThrow(() -> BloodUnitServiceImpl.notFound(unitId));
        return views(eventDAO.getByUnit(unitId));
    }

    @Override
    public List<EventView> forDonationNumber(String donationNumber) {
        List<String> errors = new ArrayList<>();
        String din = Isbt128Parser.typedDonationNumber(donationNumber, errors);
        if (din == null) {
            throw new BloodBankException(Kind.INVALID, errors.isEmpty() ? Isbt128Parser.ERR_DIN_FORMAT : errors.get(0));
        }
        List<Long> unitIds = unitDAO.getByDonationNumber(din).stream().map(BloodUnit::getId).toList();
        return views(eventDAO.getByUnits(unitIds));
    }

    @Override
    public List<EventView> forPatient(Long patientId) {
        if (patientId == null || patientService.getData(String.valueOf(patientId)) == null) {
            throw new BloodBankException(Kind.NOT_FOUND, "bloodbank.error.patientNotFound", String.valueOf(patientId));
        }
        return views(eventDAO.getByPatient(patientId));
    }

    @Override
    public String toCsv(List<EventView> events) {
        StringBuilder csv = new StringBuilder(String.join(",", HEADER)).append("\r\n");
        for (EventView e : events) {
            String[] cells = { e.occurredAt(), String.valueOf(e.eventType()), e.donationNumber(), e.productCode(),
                    str(e.unitId()), str(e.patientId()), e.patientName(), e.patientNationalId(), e.labNumber(),
                    str(e.reservationId()), e.performedByName(), e.secondCheckerName(), e.location(), e.collectorName(),
                    e.reason(), e.details() };
            for (int i = 0; i < cells.length; i++) {
                if (i > 0) {
                    csv.append(',');
                }
                csv.append(cell(cells[i]));
            }
            csv.append("\r\n");
        }
        return csv.toString();
    }

    private List<EventView> views(List<BloodUnitEvent> events) {
        Function<Integer, String> names = trail.userNames();
        Map<Long, String[]> patients = new HashMap<>();
        Map<Long, String> labNumbers = new HashMap<>();
        return events.stream().map(e -> {
            EventView view = BloodBankMapper.event(e, names);
            if (view.patientId() == null) {
                return view;
            }
            String[] patient = patients.computeIfAbsent(view.patientId(), this::patientText);
            String lab = view.reservationId() == null ? ""
                    : labNumbers.computeIfAbsent(view.reservationId(), this::crossmatchLabNumber);
            return view.withPatient(patient[0], patient[1], lab.isEmpty() ? null : lab);
        }).toList();
    }

    /** {"First Last", national ID}; either may be null. */
    private String[] patientText(Long patientId) {
        Patient patient = patientService.getData(String.valueOf(patientId));
        if (patient == null) {
            return new String[2];
        }
        String name = Stream.of(patientService.getFirstName(patient), patientService.getLastName(patient))
                .filter(part -> part != null && !part.isBlank()).map(String::trim).collect(Collectors.joining(" "));
        String nationalId = patientService.getNationalId(patient);
        return new String[] { name.isEmpty() ? null : name,
                nationalId == null || nationalId.isBlank() ? null : nationalId.trim() };
    }

    /**
     * The lab number of the sample whose crossmatch the reservation used, if any.
     */
    private String crossmatchLabNumber(Long reservationId) {
        Long resultId = reservationDAO.get(reservationId).map(BloodUnitReservation::getCrossmatchResultId).orElse(null);
        // computeIfAbsent keeps no null: an empty string stands for "none"
        String lab = resultId == null ? null : profileResultDAO.getAccessionForResult(String.valueOf(resultId));
        return lab == null ? "" : lab;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * RFC 4180 quoting, and a leading quote mark on anything a spreadsheet would
     * run as a formula (=, +, -, @).
     */
    static String cell(String value) {
        if (value == null) {
            return "";
        }
        String text = value;
        if (!text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }
}
