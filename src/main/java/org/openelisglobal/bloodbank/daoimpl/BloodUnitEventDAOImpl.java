package org.openelisglobal.bloodbank.daoimpl;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import org.hibernate.Session;
import org.openelisglobal.bloodbank.dao.BloodUnitEventDAO;
import org.openelisglobal.bloodbank.valueholder.BloodUnitEvent;
import org.openelisglobal.common.exception.LIMSRuntimeException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional
public class BloodUnitEventDAOImpl implements BloodUnitEventDAO {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public Long append(BloodUnitEvent event) {
        if (event.getId() != null) {
            throw new IllegalArgumentException("A trail entry is only ever added, never changed");
        }
        try {
            entityManager.persist(event);
            entityManager.flush();
            return event.getId();
        } catch (RuntimeException e) {
            throw new LIMSRuntimeException("Error adding to the blood unit trail", e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<BloodUnitEvent> getByUnit(Long unitId) {
        try {
            return entityManager.unwrap(Session.class)
                    .createQuery("FROM BloodUnitEvent e JOIN FETCH e.unit u JOIN FETCH u.productType"
                            + " WHERE u.id = :unitId ORDER BY e.occurredAt, e.id", BloodUnitEvent.class)
                    .setParameter("unitId", unitId).list();
        } catch (RuntimeException e) {
            throw new LIMSRuntimeException("Error reading the blood unit trail", e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<BloodUnitEvent> getByPatient(Long patientId) {
        try {
            return entityManager.unwrap(Session.class)
                    .createQuery(
                            "FROM BloodUnitEvent e JOIN FETCH e.unit u JOIN FETCH u.productType"
                                    + " WHERE e.patientId = :patientId ORDER BY e.occurredAt, e.id",
                            BloodUnitEvent.class)
                    .setParameter("patientId", patientId).list();
        } catch (RuntimeException e) {
            throw new LIMSRuntimeException("Error reading the patient's blood unit trail", e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<BloodUnitEvent> getByUnits(List<Long> unitIds) {
        if (unitIds == null || unitIds.isEmpty()) {
            return List.of();
        }
        try {
            return entityManager.unwrap(Session.class)
                    .createQuery("FROM BloodUnitEvent e JOIN FETCH e.unit u JOIN FETCH u.productType"
                            + " WHERE u.id IN (:unitIds) ORDER BY e.occurredAt, e.id", BloodUnitEvent.class)
                    .setParameterList("unitIds", unitIds).list();
        } catch (RuntimeException e) {
            throw new LIMSRuntimeException("Error reading blood unit trails", e);
        }
    }
}
