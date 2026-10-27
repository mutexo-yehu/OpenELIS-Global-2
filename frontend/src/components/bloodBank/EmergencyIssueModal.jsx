import React, { useEffect, useState } from "react";
import {
  Button,
  Checkbox,
  InlineLoading,
  InlineNotification,
  Modal,
  PasswordInput,
  Select,
  SelectItem,
  TextArea,
  TextInput,
} from "@carbon/react";
import { useIntl } from "react-intl";
import BloodBankService from "./BloodBankService";
import SafetyChecklist from "./SafetyChecklist";
import { dinKey } from "./IssueModal";
import {
  errorText,
  formatInstant,
  groupText,
  productText,
} from "./bloodBankFormat";
import "./BloodBank.css";

/** Only these may go out before group and crossmatch; the server decides. */
export const emergencyEligible = (unit) => {
  const component = unit?.productType?.componentClass;
  return (
    (component === "RED_CELLS" && unit.abo === "O") ||
    (component === "PLASMA" && unit.abo === "AB")
  );
};

/** O RhD positive red cells: the acknowledgement is asked for. */
export const needsRhdAcknowledgement = (unit) =>
  unit?.productType?.componentClass === "RED_CELLS" && unit.rhd === "POS";

// O RhD negative red cells first, then O RhD positive, then AB plasma; the
// unit that expires first comes first within each
const rank = (unit) => {
  if (unit.productType?.componentClass !== "RED_CELLS") return 2;
  return unit.rhd === "NEG" ? 0 : 1;
};
export const orderForEmergency = (units) =>
  [...units].sort(
    (a, b) =>
      rank(a) - rank(b) ||
      String(a.expiresAt || "").localeCompare(String(b.expiresAt || "")),
  );

const EMPTY = {
  unitId: "",
  unitScan: "",
  patientIdentifier: "",
  rhdPositiveAcknowledged: false,
  requestingClinician: "",
  clinicalReason: "",
  location: "",
  collectorName: "",
  secondCheckerLogin: "",
  secondCheckerPassword: "",
  secondCheckerOtp: "",
  warningReason: "",
};

/**
 * Emergency issue, before the patient's group and crossmatch: group O red
 * cells or group AB plasma only. Step 1: pick or scan the unit, scan the
 * patient's identifier, give the O RhD positive acknowledgement when it
 * applies, and run the server's checks. Step 2: who asked for the blood, why,
 * the ward and the collector. Step 3: the second check the site's setting asks
 * for. The server repeats every check when the issue is submitted.
 */
// The page mounts this modal afresh each time it opens, so every field starts
// empty without a reset.
const EmergencyIssueModal = ({ open, patientId, onClose, onIssued }) => {
  const intl = useIntl();
  const t = (id, values) => intl.formatMessage({ id }, values);
  const [units, setUnits] = useState([]);
  const [loading, setLoading] = useState(true);
  const [form, setForm] = useState(EMPTY);
  const [check, setCheck] = useState(null);
  const [checks, setChecks] = useState([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  useEffect(() => {
    if (!open) return;
    Promise.all([
      BloodBankService.listUnits({ status: ["AVAILABLE"], abo: "O" }),
      BloodBankService.listUnits({ status: ["AVAILABLE"], abo: "AB" }),
    ])
      .then(([o, ab]) =>
        setUnits(
          orderForEmergency(
            [...(o || []), ...(ab || [])].filter(emergencyEligible),
          ),
        ),
      )
      .catch((e) => {
        setUnits([]);
        setError(errorText(intl, e));
      })
      .finally(() => setLoading(false));
  }, [open, intl]);

  const unit = units.find((u) => String(u.id) === String(form.unitId));
  const unitMatches =
    !!unit &&
    form.unitScan.trim() !== "" &&
    dinKey(form.unitScan) === dinKey(unit.donationNumber);
  const askAcknowledgement = needsRhdAcknowledgement(unit);

  // changing the unit, the scans or the acknowledgement invalidates the checks
  const setIdentity = (changes) => {
    setForm((f) => ({ ...f, ...changes }));
    setCheck(null);
    setChecks([]);
  };

  // a scanned bag picks its unit from the list
  const scanUnit = (value) => {
    const found = units.find((u) => dinKey(u.donationNumber) === dinKey(value));
    setIdentity({
      unitScan: value,
      unitId: found ? String(found.id) : form.unitId,
      rhdPositiveAcknowledged:
        found && String(found.id) !== form.unitId
          ? false
          : form.rhdPositiveAcknowledged,
    });
  };

  const runCheck = () => {
    setBusy(true);
    setError(null);
    BloodBankService.emergencyCheck(unit.id, { ...form, patientId })
      .then((result) => {
        setCheck(result);
        setChecks(result.checks || []);
      })
      .catch((e) => {
        setError(errorText(intl, e));
        setChecks(e.checks || []);
      })
      .finally(() => setBusy(false));
  };

  const mode = check?.secondCheckMode || "REQUIRED";
  const loginGiven = form.secondCheckerLogin.trim() !== "";
  const passwordGiven = form.secondCheckerPassword !== "";
  const secondGiven = loginGiven && passwordGiven;
  const secondOk =
    mode === "COLLECTOR_CONFIRMS" ||
    secondGiven ||
    (mode === "OPTIONAL_WITH_REASON" && !loginGiven && !passwordGiven);
  const singlePerson = mode === "OPTIONAL_WITH_REASON" && !secondGiven;
  const otherWarning = checks.some(
    (c) => c.status === "WARN" && c.code !== "SECOND_PERSON",
  );
  const reasonRequired = otherWarning || singlePerson;
  const filled = (value) => value.trim() !== "";
  const canIssue =
    !!check?.allowed &&
    unitMatches &&
    !busy &&
    filled(form.requestingClinician) &&
    filled(form.clinicalReason) &&
    filled(form.location) &&
    filled(form.collectorName) &&
    secondOk &&
    (!reasonRequired || filled(form.warningReason));

  const issue = () => {
    setBusy(true);
    setError(null);
    BloodBankService.emergencyIssue(unit.id, { ...form, patientId })
      .then((result) => {
        onIssued(result, unit);
        onClose();
      })
      .catch((e) => {
        setError(errorText(intl, e));
        if (e.checks && e.checks.length) setChecks(e.checks);
      })
      .finally(() => {
        // the password is never kept after an attempt
        setForm((f) => ({
          ...f,
          secondCheckerPassword: "",
          secondCheckerOtp: "",
        }));
        setBusy(false);
      });
  };

  const field = (name) => (e) =>
    setForm((f) => ({ ...f, [name]: e.target.value }));

  return (
    <Modal
      open={open}
      danger
      size="lg"
      modalHeading={t("bloodbank.emergency.title")}
      modalLabel={t("bloodbank.emergency.label")}
      primaryButtonText={t("bloodbank.emergency.submit")}
      secondaryButtonText={t("bloodbank.action.cancel")}
      primaryButtonDisabled={!canIssue}
      onRequestSubmit={issue}
      onRequestClose={onClose}
      preventCloseOnClickOutside
    >
      <p className="bb-helper">{t("bloodbank.emergency.helper")}</p>
      <h5>{t("bloodbank.emergency.step1")}</h5>
      {loading && <InlineLoading />}
      {!loading && units.length === 0 && !error && (
        <InlineNotification
          kind="warning"
          lowContrast
          hideCloseButton
          title=""
          subtitle={t("bloodbank.emergency.noUnits")}
        />
      )}
      <div className="bb-row">
        <Select
          id="bb-emergency-unit"
          labelText={t("bloodbank.emergency.unit")}
          value={form.unitId}
          onChange={(e) =>
            setIdentity({
              unitId: e.target.value,
              rhdPositiveAcknowledged: false,
            })
          }
        >
          <SelectItem value="" text={t("bloodbank.emergency.unit.choose")} />
          {units.map((u) => (
            <SelectItem
              key={u.id}
              value={String(u.id)}
              text={`${u.donationNumber} · ${productText(u)} · ${groupText(intl, u.abo, u.rhd)} · ${t("bloodbank.emergency.unit.expires", { when: formatInstant(intl, u.expiresAt) })}`}
            />
          ))}
        </Select>
      </div>
      <div className="bb-row">
        <TextInput
          id="bb-emergency-scan-unit"
          labelText={t("bloodbank.issueModal.scanUnit")}
          value={form.unitScan}
          autoComplete="off"
          invalid={!!unit && form.unitScan.trim() !== "" && !unitMatches}
          invalidText={
            unit
              ? t("bloodbank.issueModal.unitMismatch", {
                  din: unit.donationNumber,
                })
              : ""
          }
          onChange={(e) => scanUnit(e.target.value)}
        />
        <TextInput
          id="bb-emergency-scan-patient"
          labelText={t("bloodbank.issueModal.scanPatient")}
          value={form.patientIdentifier}
          autoComplete="off"
          onChange={(e) => setIdentity({ patientIdentifier: e.target.value })}
        />
      </div>
      {askAcknowledgement && (
        <div className="bb-section" data-testid="bb-emergency-rhd-ack">
          <Checkbox
            id="bb-emergency-rhd-ack"
            labelText={t("bloodbank.emergency.rhdAck")}
            helperText={t("bloodbank.emergency.rhdAck.helper")}
            checked={form.rhdPositiveAcknowledged}
            onChange={(_, { checked }) =>
              setIdentity({ rhdPositiveAcknowledged: checked })
            }
          />
        </div>
      )}
      <div className="bb-row">
        <Button
          kind="secondary"
          size="md"
          disabled={
            !unitMatches ||
            !form.patientIdentifier.trim() ||
            busy ||
            (askAcknowledgement && !form.rhdPositiveAcknowledged)
          }
          onClick={runCheck}
        >
          {t("bloodbank.issueModal.runCheck")}
        </Button>
        {busy && <InlineLoading />}
      </div>

      <SafetyChecklist checks={checks} />

      {error && (
        <InlineNotification
          kind="error"
          lowContrast
          hideCloseButton
          title={t("bloodbank.error.title")}
          subtitle={error}
        />
      )}

      {check?.allowed && (
        <>
          <h5 className="bb-section">{t("bloodbank.emergency.step2")}</h5>
          <div className="bb-row">
            <TextInput
              id="bb-emergency-clinician"
              labelText={t("bloodbank.emergency.clinician")}
              value={form.requestingClinician}
              maxLength={120}
              onChange={field("requestingClinician")}
            />
            <TextInput
              id="bb-emergency-location"
              labelText={t("bloodbank.field.location")}
              value={form.location}
              onChange={field("location")}
            />
            <TextInput
              id="bb-emergency-collector"
              labelText={t("bloodbank.field.collector")}
              value={form.collectorName}
              onChange={field("collectorName")}
            />
          </div>
          <TextArea
            id="bb-emergency-reason"
            labelText={t("bloodbank.emergency.reason")}
            helperText={t("bloodbank.emergency.reason.helper")}
            value={form.clinicalReason}
            maxCount={500}
            enableCounter
            onChange={field("clinicalReason")}
          />
          <h5 className="bb-section">{t("bloodbank.issueModal.step3")}</h5>
          {mode === "COLLECTOR_CONFIRMS" ? (
            <p className="bb-helper" data-testid="bb-collector-confirms">
              {t("bloodbank.issueModal.collectorConfirmsHelper")}
            </p>
          ) : (
            <>
              <p className="bb-helper">
                {t(
                  mode === "OPTIONAL_WITH_REASON"
                    ? "bloodbank.issueModal.secondOptionalHelper"
                    : "bloodbank.issueModal.secondHelper",
                )}
              </p>
              <div className="bb-row">
                <TextInput
                  id="bb-emergency-second-login"
                  labelText={t("bloodbank.issueModal.secondLogin")}
                  value={form.secondCheckerLogin}
                  autoComplete="off"
                  onChange={field("secondCheckerLogin")}
                />
                <PasswordInput
                  id="bb-emergency-second-password"
                  labelText={t("bloodbank.issueModal.secondPassword")}
                  value={form.secondCheckerPassword}
                  autoComplete="new-password"
                  onChange={field("secondCheckerPassword")}
                />
                <TextInput
                  id="bb-emergency-second-otp"
                  labelText={t("bloodbank.issueModal.secondOtp")}
                  value={form.secondCheckerOtp}
                  inputMode="numeric"
                  autoComplete="one-time-code"
                  onChange={field("secondCheckerOtp")}
                />
              </div>
            </>
          )}
          {reasonRequired && (
            <TextArea
              id="bb-emergency-warning-reason"
              labelText={t(
                singlePerson && !otherWarning
                  ? "bloodbank.issueModal.singlePersonReason"
                  : "bloodbank.issueModal.warningReason",
              )}
              helperText={t("bloodbank.issueModal.warningReason.helper")}
              value={form.warningReason}
              maxCount={500}
              enableCounter
              onChange={field("warningReason")}
            />
          )}
        </>
      )}
    </Modal>
  );
};

export default EmergencyIssueModal;
