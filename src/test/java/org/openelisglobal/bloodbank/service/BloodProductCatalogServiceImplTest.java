package org.openelisglobal.bloodbank.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.openelisglobal.bloodbank.dao.BloodProductCodeDAO;
import org.openelisglobal.bloodbank.dao.BloodProductTypeDAO;
import org.openelisglobal.bloodbank.form.BloodBankForms.ProductCodeForm;
import org.openelisglobal.bloodbank.form.BloodBankForms.ProductTypeForm;
import org.openelisglobal.bloodbank.service.BloodBankException.Kind;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.ComponentClass;
import org.openelisglobal.bloodbank.valueholder.BloodProductCode;
import org.openelisglobal.bloodbank.valueholder.BloodProductType;

@RunWith(MockitoJUnitRunner.class)
public class BloodProductCatalogServiceImplTest {

    @Mock
    private BloodProductTypeDAO typeDAO;
    @Mock
    private BloodProductCodeDAO codeDAO;

    @InjectMocks
    private BloodProductCatalogServiceImpl service;

    private static BloodProductType type(long id, String code, boolean active) {
        BloodProductType type = new BloodProductType();
        type.setId(id);
        type.setCode(code);
        type.setName(code);
        type.setComponentClass(ComponentClass.RED_CELLS);
        type.setActive(active);
        return type;
    }

    private static BloodProductCode mapping(String prefix, BloodProductType type) {
        BloodProductCode code = new BloodProductCode();
        code.setIsbtCodePrefix(prefix);
        code.setProductType(type);
        return code;
    }

    @Test
    public void theLongestMatchingPrefixWins() {
        BloodProductType generic = type(1, "RED_CELLS", true);
        BloodProductType irradiated = type(2, "RED_CELLS_IRR", true);
        List<String> prefixes = List.of("E0291V00", "E0291V0", "E0291V", "E0291");
        when(codeDAO.getActiveByPrefixes(prefixes))
                .thenReturn(List.of(mapping("E0291", generic), mapping("E0291V", irradiated)));

        assertEquals(irradiated, service.resolve("E0291V00").orElseThrow());
    }

    @Test
    public void aMappingToAnInactiveTypeDoesNotCount() {
        BloodProductType retired = type(3, "OLD", false);
        when(codeDAO.getActiveByPrefixes(List.of("E0291V00", "E0291V0", "E0291V", "E0291")))
                .thenReturn(List.of(mapping("E0291V00", retired)));

        assertEquals(Optional.empty(), service.resolve("E0291V00"));
    }

    @Test
    public void tooShortACodeMapsToNothing() {
        assertEquals(Optional.empty(), service.resolve("E029"));
        assertEquals(Optional.empty(), service.resolve(null));
        verifyZeroInteractions(codeDAO);
    }

    @Test
    public void aPrefixMustLookLikeTheStartOfAProductCode() {
        assertEquals("E0291", BloodProductCatalogServiceImpl.normalisePrefix(" =<e0291 "));
        for (String bad : List.of("E02", "12345", "E0291V001", "E02X1")) {
            try {
                BloodProductCatalogServiceImpl.normalisePrefix(bad);
                fail(bad);
            } catch (BloodBankException e) {
                assertEquals(bad, "bloodbank.catalog.error.codePrefix", e.getErrorCode());
            }
        }
    }

    @Test
    public void aDuplicatePrefixIsAConflict() {
        when(codeDAO.getByPrefix("E0291")).thenReturn(Optional.of(new BloodProductCode()));

        try {
            service.createCode(new ProductCodeForm("E0291", 1L, null, null), "1");
            fail("expected a conflict");
        } catch (BloodBankException e) {
            assertEquals(Kind.CONFLICT, e.getKind());
        }
    }

    @Test
    public void aNewMappingIsSavedActiveByDefault() {
        BloodProductType generic = type(1, "RED_CELLS", true);
        when(codeDAO.getByPrefix("E0291")).thenReturn(Optional.empty());
        when(typeDAO.get(1L)).thenReturn(Optional.of(generic));

        assertEquals("E0291",
                service.createCode(new ProductCodeForm("e0291", 1L, "Red cells", null), "1").isbtCodePrefix());
        verify(codeDAO).insert(argThat(c -> "E0291".equals(c.getIsbtCodePrefix()) && c.isActive()
                && c.getProductType() == generic && "1".equals(c.getSysUserId())));
    }

    @Test
    public void aTypeNeedsCodeNameAndClassAndAUniqueCode() {
        try {
            service.createType(new ProductTypeForm("PLT", null, ComponentClass.PLATELETS, null, null), "1");
            fail("expected a refusal");
        } catch (BloodBankException e) {
            assertEquals(Kind.INVALID, e.getKind());
        }
        when(typeDAO.getByCode("PLATELETS")).thenReturn(Optional.of(type(4, "PLATELETS", true)));
        try {
            service.createType(new ProductTypeForm("platelets", "Platelets", ComponentClass.PLATELETS, null, null),
                    "1");
            fail("expected a conflict");
        } catch (BloodBankException e) {
            assertEquals(Kind.CONFLICT, e.getKind());
        }
    }

    @Test
    public void updatingATypeKeepsItsCodeAndClass() {
        BloodProductType generic = type(1, "RED_CELLS", true);
        when(typeDAO.get(1L)).thenReturn(Optional.of(generic));

        service.updateType(1L,
                new ProductTypeForm("CHANGED", "Red cells, leucodepleted", ComponentClass.PLASMA, null, false), "1");

        assertEquals("RED_CELLS", generic.getCode());
        assertEquals(ComponentClass.RED_CELLS, generic.getComponentClass());
        assertEquals("Red cells, leucodepleted", generic.getName());
        assertTrue(!generic.isActive());
    }
}
