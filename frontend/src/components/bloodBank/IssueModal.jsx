import React, { useEffect, useState } from "react";
import {
  Button,
  InlineLoading,
  InlineNotification,
  Modal,
  PasswordInput,
  TextArea,
  TextInput,
} from "@carbon/react";
import { useIntl } from "react-intl";
import BloodBankService from "./BloodBankService";
import SafetyChecklist from "./SafetyChecklist";
import { errorText, groupText, productText } from "./bloodBankFormat";
import "./BloodBank.css";

// A DIN as scanned ("=G123498654321 00") or typed: the 13 characters that
// identify the donation, without the leading "=" and the flag characters.
export const dinKey = (text) =>
  (text || "").replace(/\s/g, "").replace(/^=/, "").toUpperCase().slice(0, 13);

const EMPTY = {
  unitScan: "",
  patientIdentifier: "",
  location: "",
  collectorName: "",
  secondCheckerLogin: "",
  secondCheckerPassword: "",
  secondCheckerOtp: "",
  warningReason: "",
};

/**
 * Issue at the point of collection. Step 1: scan the bag and the patient's
 * identifier, and run the server's safety checks. A failed check blocks the
 * issue; a warning needs a documented reason. Step 2: ward and collector, then
 * a second, different user re-authenticates. The server repeats every check
 * when the issue is submitted, including that the scanned bag is this unit
 * (the match below only guides the user; the server decides).
 */
const IssueModal = ({ open, unit, onClose, onIssued }) => {
  const intl = useIntl();
  const t = (id, values) => intl.formatMessage({ id }, values);
  const [form, setForm] = useState(EMPTY);
  const [check, setCheck] = useState(null);
  const [checks, setChecks] = useState([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  useEffect(() => {
    if (open) {
      setForm(EMPTY);
      setCheck(null);
      setChecks([]);
      setError(null);
      setBusy(false);
    }
  }, [open, unit]);

  if (!unit) return null;

  const unitMatches =
    form.unitScan.trim() !== "" &&
    dinKey(form.unitScan) === dinKey(unit.donationNumber);

  // changing what was scanned invalidates the checks already shown
  const setIdentity = (field, value) => {
    setForm({ ...form, [field]: value });
    setCheck(null);
    setChecks([]);
  };

  const runCheck = () => {
    setBusy(true);
    setError(null);
    BloodBankService.issueCheck(unit.id, form.patientIdentifier, form.unitScan)
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

  const reasonRequired = !!check?.warningReasonRequired;
  const canIssue =
    !!check?.allowed &&
    unitMatches &&
    !busy &&
    form.location.trim() !== "" &&
    form.collectorName.trim() !== "" &&
    form.secondCheckerLogin.trim() !== "" &&
    form.secondCheckerPassword !== "" &&
    (!reasonRequired || form.warningReason.trim() !== "");

  const issue = () => {
    setBusy(true);
    setError(null);
    BloodBankService.issue(unit.id, form)
      .then((result) => {
        onIssued(result);
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

  return (
    <Modal
      open={open}
      size="lg"
      modalHeading={t("bloodbank.issueModal.title", {
        din: unit.donationNumber,
      })}
      modalLabel={`${productText(unit)} · ${groupText(intl, unit.abo, unit.rhd)}`}
      primaryButtonText={t("bloodbank.issueModal.submit")}
      secondaryButtonText={t("bloodbank.action.cancel")}
      primaryButtonDisabled={!canIssue}
      onRequestSubmit={issue}
      onRequestClose={onClose}
      preventCloseOnClickOutside
    >
      <h5>{t("bloodbank.issueModal.step1")}</h5>
      <div className="bb-row">
        <TextInput
          id="bb-issue-unit"
          labelText={t("bloodbank.issueModal.scanUnit")}
          value={form.unitScan}
          autoComplete="off"
          invalid={form.unitScan.trim() !== "" && !unitMatches}
          invalidText={t("bloodbank.issueModal.unitMismatch", {
            din: unit.donationNumber,
          })}
          onChange={(e) => setIdentity("unitScan", e.target.value)}
        />
        <TextInput
          id="bb-issue-patient"
          labelText={t("bloodbank.issueModal.scanPatient")}
          value={form.patientIdentifier}
          autoComplete="off"
          onChange={(e) => setIdentity("patientIdentifier", e.target.value)}
        />
        <Button
          kind="secondary"
          size="md"
          disabled={!unitMatches || !form.patientIdentifier.trim() || busy}
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
          {reasonRequired && (
            <TextArea
              id="bb-issue-warning-reason"
              labelText={t("bloodbank.issueModal.warningReason")}
              helperText={t("bloodbank.issueModal.warningReason.helper")}
              value={form.warningReason}
              maxCount={500}
              enableCounter
              onChange={(e) =>
                setForm({ ...form, warningReason: e.target.value })
              }
            />
          )}
          <h5 className="bb-section">{t("bloodbank.issueModal.step2")}</h5>
          <div className="bb-row">
            <TextInput
              id="bb-issue-location"
              labelText={t("bloodbank.field.location")}
              value={form.location}
              onChange={(e) => setForm({ ...form, location: e.target.value })}
            />
            <TextInput
              id="bb-issue-collector"
              labelText={t("bloodbank.field.collector")}
              value={form.collectorName}
              onChange={(e) =>
                setForm({ ...form, collectorName: e.target.value })
              }
            />
          </div>
          <h5 className="bb-section">{t("bloodbank.issueModal.step3")}</h5>
          <p className="bb-helper">{t("bloodbank.issueModal.secondHelper")}</p>
          <div className="bb-row">
            <TextInput
              id="bb-issue-second-login"
              labelText={t("bloodbank.issueModal.secondLogin")}
              value={form.secondCheckerLogin}
              autoComplete="off"
              onChange={(e) =>
                setForm({ ...form, secondCheckerLogin: e.target.value })
              }
            />
            <PasswordInput
              id="bb-issue-second-password"
              labelText={t("bloodbank.issueModal.secondPassword")}
              value={form.secondCheckerPassword}
              autoComplete="new-password"
              onChange={(e) =>
                setForm({ ...form, secondCheckerPassword: e.target.value })
              }
            />
            <TextInput
              id="bb-issue-second-otp"
              labelText={t("bloodbank.issueModal.secondOtp")}
              value={form.secondCheckerOtp}
              inputMode="numeric"
              autoComplete="one-time-code"
              onChange={(e) =>
                setForm({ ...form, secondCheckerOtp: e.target.value })
              }
            />
          </div>
        </>
      )}
    </Modal>
  );
};

export default IssueModal;
