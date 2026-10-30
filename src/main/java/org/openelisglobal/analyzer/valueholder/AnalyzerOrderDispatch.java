package org.openelisglobal.analyzer.valueholder;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;
import org.hibernate.annotations.Type;
import org.openelisglobal.common.valueholder.BaseObject;

/**
 * One order sent (or to be sent) to one analyser through the Analyzer Bridge:
 * the tests of one sample that are mapped to that analyser, as LOINC codes. One
 * row per sample per analyser.
 */
@Entity
@Table(name = "analyzer_order_dispatch", schema = "clinlims")
@DynamicUpdate
public class AnalyzerOrderDispatch extends BaseObject<String> {

    private static final long serialVersionUID = 1L;

    public enum Status {
        PENDING, SENT, FAILED
    }

    @Id
    @Column(name = "id", precision = 10, scale = 0)
    @GeneratedValue(generator = "analyzer_order_dispatch_seq_gen")
    @GenericGenerator(name = "analyzer_order_dispatch_seq_gen", strategy = "org.openelisglobal.hibernate.resources.StringSequenceGenerator", parameters = @Parameter(name = "sequence_name", value = "analyzer_order_dispatch_seq"))
    @Type(type = "org.openelisglobal.hibernate.resources.usertype.LIMSStringNumberUserType")
    private String id;

    @Column(name = "sample_id", nullable = false)
    @Type(type = "org.openelisglobal.hibernate.resources.usertype.LIMSStringNumberUserType")
    private String sampleId;

    @Column(name = "accession_number", length = 50, nullable = false)
    private String accessionNumber;

    @Column(name = "analyzer_id", nullable = false)
    @Type(type = "org.openelisglobal.hibernate.resources.usertype.LIMSStringNumberUserType")
    private String analyzerId;

    /** Comma-separated OpenELIS test ids. */
    @Column(name = "test_ids", columnDefinition = "TEXT", nullable = false)
    private String testIds;

    /** Comma-separated test names, for the worklist screen. */
    @Column(name = "test_names", columnDefinition = "TEXT")
    private String testNames;

    /** Comma-separated LOINC codes sent to the Bridge. */
    @Column(name = "loinc_codes", columnDefinition = "TEXT", nullable = false)
    private String loincCodes;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 10, nullable = false)
    private Status status = Status.PENDING;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Timestamp createdAt;

    @Column(name = "sent_at")
    private Timestamp sentAt;

    @Override
    public String getId() {
        return id;
    }

    @Override
    public void setId(String id) {
        this.id = id;
    }

    public String getSampleId() {
        return sampleId;
    }

    public void setSampleId(String sampleId) {
        this.sampleId = sampleId;
    }

    public String getAccessionNumber() {
        return accessionNumber;
    }

    public void setAccessionNumber(String accessionNumber) {
        this.accessionNumber = accessionNumber;
    }

    public String getAnalyzerId() {
        return analyzerId;
    }

    public void setAnalyzerId(String analyzerId) {
        this.analyzerId = analyzerId;
    }

    public String getTestIds() {
        return testIds;
    }

    public void setTestIds(String testIds) {
        this.testIds = testIds;
    }

    public String getTestNames() {
        return testNames;
    }

    public void setTestNames(String testNames) {
        this.testNames = testNames;
    }

    public String getLoincCodes() {
        return loincCodes;
    }

    public void setLoincCodes(String loincCodes) {
        this.loincCodes = loincCodes;
    }

    public List<String> getLoincCodeList() {
        return loincCodes == null || loincCodes.isBlank() ? List.of()
                : Arrays.stream(loincCodes.split(",")).map(String::trim).filter(code -> !code.isEmpty()).toList();
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public Timestamp getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Timestamp createdAt) {
        this.createdAt = createdAt;
    }

    public Timestamp getSentAt() {
        return sentAt;
    }

    public void setSentAt(Timestamp sentAt) {
        this.sentAt = sentAt;
    }
}
