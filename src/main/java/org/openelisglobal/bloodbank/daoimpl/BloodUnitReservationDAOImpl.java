package org.openelisglobal.bloodbank.daoimpl;

import java.util.List;
import java.util.Optional;
import org.hibernate.Session;
import org.openelisglobal.bloodbank.dao.BloodUnitReservationDAO;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.ReservationStatus;
import org.openelisglobal.bloodbank.valueholder.BloodUnitReservation;
import org.openelisglobal.common.daoimpl.BaseDAOImpl;
import org.openelisglobal.common.exception.LIMSRuntimeException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional
public class BloodUnitReservationDAOImpl extends BaseDAOImpl<BloodUnitReservation, Long>
        implements BloodUnitReservationDAO {

    public BloodUnitReservationDAOImpl() {
        super(BloodUnitReservation.class);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BloodUnitReservation> getForUnit(Long unitId, ReservationStatus status) {
        try {
            return entityManager.unwrap(Session.class)
                    .createQuery("FROM BloodUnitReservation r WHERE r.unit.id = :unitId AND r.status = :status"
                            + " ORDER BY r.reservedAt DESC, r.id DESC", BloodUnitReservation.class)
                    .setParameter("unitId", unitId).setParameter("status", status).setMaxResults(1)
                    .uniqueResultOptional();
        } catch (RuntimeException e) {
            throw new LIMSRuntimeException("Error reading blood unit reservation", e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<BloodUnitReservation> getLiveByCrossmatchResultIds(List<Long> crossmatchResultIds) {
        if (crossmatchResultIds == null || crossmatchResultIds.isEmpty()) {
            return List.of();
        }
        try {
            return entityManager.unwrap(Session.class)
                    .createQuery(
                            "FROM BloodUnitReservation r JOIN FETCH r.unit"
                                    + " WHERE r.crossmatchResultId IN (:ids) AND r.status IN (:live)",
                            BloodUnitReservation.class)
                    .setParameterList("ids", crossmatchResultIds)
                    .setParameterList("live", List.of(ReservationStatus.ACTIVE, ReservationStatus.ISSUED)).list();
        } catch (RuntimeException e) {
            throw new LIMSRuntimeException("Error reading reservations by crossmatch", e);
        }
    }
}
