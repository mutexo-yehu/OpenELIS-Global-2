package org.openelisglobal.bloodbank;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.Test;
import org.openelisglobal.bloodbank.valueholder.BloodProductCode;
import org.openelisglobal.bloodbank.valueholder.BloodProductType;
import org.openelisglobal.bloodbank.valueholder.BloodUnit;
import org.openelisglobal.bloodbank.valueholder.BloodUnitEvent;
import org.openelisglobal.bloodbank.valueholder.BloodUnitReservation;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Constitution V.4: the five entities map without a database, and the changelog
 * parses with a rollback for every changeset.
 */
public class BloodBankOrmValidationTest {

    private static final List<Class<?>> ENTITIES = List.of(BloodProductType.class, BloodProductCode.class,
            BloodUnit.class, BloodUnitReservation.class, BloodUnitEvent.class);

    @Test
    public void allBloodBankEntitiesBuildWithoutADatabase() {
        Configuration configuration = new Configuration();
        configuration.setProperty("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
        configuration.setProperty("hibernate.temp.use_jdbc_metadata_defaults", "false");
        ENTITIES.forEach(configuration::addAnnotatedClass);
        try (SessionFactory factory = configuration.buildSessionFactory()) {
            for (Class<?> entity : ENTITIES) {
                assertNotNull(entity.getSimpleName(),
                        factory.getMetamodel().entity(entity).getVersion(java.sql.Timestamp.class));
            }
            // the trail is append-only at the Hibernate level too
            assertTrue(BloodUnitEvent.class.isAnnotationPresent(org.hibernate.annotations.Immutable.class));
        }
    }

    @Test
    public void theEntitiesAreListedInThePersistenceUnit() throws Exception {
        try (InputStream xml = getClass().getClassLoader().getResourceAsStream("persistence/persistence.xml")) {
            Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml);
            NodeList classes = document.getElementsByTagName("class");
            List<String> listed = new java.util.ArrayList<>();
            for (int i = 0; i < classes.getLength(); i++) {
                listed.add(classes.item(i).getTextContent().trim());
            }
            for (Class<?> entity : ENTITIES) {
                assertTrue(entity.getName(), listed.contains(entity.getName()));
            }
        }
    }

    @Test
    public void everyChangesetHasARollbackAndTheBloodbankPrefix() throws Exception {
        try (InputStream xml = getClass().getClassLoader()
                .getResourceAsStream("liquibase/3.6.x.x/024-blood-product-inventory.xml")) {
            assertNotNull("changelog on the classpath", xml);
            Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml);
            NodeList changeSets = document.getElementsByTagName("changeSet");
            assertEquals(22, changeSets.getLength());
            for (int i = 0; i < changeSets.getLength(); i++) {
                Element changeSet = (Element) changeSets.item(i);
                String id = changeSet.getAttribute("id");
                assertTrue(id, id.startsWith("bloodbank-"));
                assertEquals(id + " has a rollback", 1, changeSet.getElementsByTagName("rollback").getLength());
            }
        }
    }

    @Test
    public void theChangelogIsIncludedFromTheVersionBase() throws Exception {
        try (InputStream xml = getClass().getClassLoader().getResourceAsStream("liquibase/3.6.x.x/base.xml")) {
            Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml);
            NodeList includes = document.getElementsByTagName("include");
            boolean found = false;
            for (int i = 0; i < includes.getLength(); i++) {
                found |= "024-blood-product-inventory.xml".equals(((Element) includes.item(i)).getAttribute("file"));
            }
            assertTrue(found);
        }
    }
}
