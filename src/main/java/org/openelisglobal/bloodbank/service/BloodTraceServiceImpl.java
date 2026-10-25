package org.openelisglobal.bloodbank.service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.openelisglobal.bloodbank.dao.BloodUnitDAO;
import org.openelisglobal.bloodbank.dao.BloodUnitEventDAO;
import org.openelisglobal.bloodbank.form.BloodBankViews.EventView;
import org.openelisglobal.bloodbank.isbt.Isbt128Parser;
import org.openelisglobal.bloodbank.service.BloodBankException.Kind;
import org.openelisglobal.bloodbank.valueholder.BloodUnit;
import org.openelisglobal.bloodbank.valueholder.BloodUnitEvent;
import org.openelisglobal.patient.service.PatientService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class BloodTraceServiceImpl implements BloodTraceService {

    private static final String[] HEADER = { "occurredAt", "eventType", "donationNumber", "productCode", "unitId",
            "patientId", "reservationId", "performedBy", "secondChecker", "location", "collectorName", "reason",
            "details" };

    @Autowired
    private BloodUnitDAO unitDAO;

    @Autowired
    private BloodUnitEventDAO eventDAO;

    @Autowired
    private PatientService patientService;

    @Autowired
    private BloodUnitTrail trail;

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
                    str(e.unitId()), str(e.patientId()), str(e.reservationId()), e.performedByName(),
                    e.secondCheckerName(), e.location(), e.collectorName(), e.reason(), e.details() };
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
        return events.stream().map(e -> BloodBankMapper.event(e, names)).toList();
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
