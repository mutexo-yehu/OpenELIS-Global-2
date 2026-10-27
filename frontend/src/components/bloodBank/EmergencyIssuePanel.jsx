import React, { useState } from "react";
import {
  Button,
  InlineNotification,
  Modal,
  RadioButton,
  RadioButtonGroup,
  TextArea,
} from "@carbon/react";
import { useIntl } from "react-intl";
import BloodBankService from "./BloodBankService";
import {
  errorText,
  formatInstant,
  groupText,
  productText,
} from "./bloodBankFormat";
import "./BloodBank.css";

/**
 * Records the lab's retrospective group and crossmatch of one emergency unit:
 * compatible or incompatible, with a note. The server keeps the first record
 * only. The panel mounts it afresh for each unit, so it starts empty.
 */
export const RetrospectiveCheckModal = ({ issue, onClose, onRecorded }) => {
  const intl = useIntl();
  const t = (id, values) => intl.formatMessage({ id }, values);
  const [outcome, setOutcome] = useState("");
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  if (!issue) return null;

  const submit = () => {
    setBusy(true);
    setError(null);
    BloodBankService.retrospectiveCheck(issue.reservationId, outcome, note)
      .then((result) => {
        onRecorded(result);
        onClose();
      })
      .catch((e) => setError(errorText(intl, e)))
      .finally(() => setBusy(false));
  };

  return (
    <Modal
      open
      danger={outcome === "INCOMPATIBLE"}
      modalHeading={t("bloodbank.retro.title", { din: issue.donationNumber })}
      modalLabel={`${productText(issue)} · ${groupText(intl, issue.abo, issue.rhd)}`}
      primaryButtonText={t("bloodbank.retro.submit")}
      secondaryButtonText={t("bloodbank.action.cancel")}
      primaryButtonDisabled={busy || !outcome || note.trim() === ""}
      onRequestSubmit={submit}
      onRequestClose={onClose}
      preventCloseOnClickOutside
    >
      <RadioButtonGroup
        legendText={t("bloodbank.retro.outcome")}
        name="bb-retro-outcome"
        orientation="vertical"
        valueSelected={outcome}
        onChange={(value) => setOutcome(value)}
      >
        <RadioButton
          id="bb-retro-compatible"
          labelText={t("bloodbank.retro.COMPATIBLE")}
          value="COMPATIBLE"
        />
        <RadioButton
          id="bb-retro-incompatible"
          labelText={t("bloodbank.retro.INCOMPATIBLE")}
          value="INCOMPATIBLE"
        />
      </RadioButtonGroup>
      {outcome === "INCOMPATIBLE" && (
        <InlineNotification
          kind="error"
          lowContrast
          hideCloseButton
          title=""
          subtitle={t("bloodbank.retro.reaction")}
        />
      )}
      <TextArea
        id="bb-retro-note"
        labelText={t("bloodbank.retro.note")}
        helperText={t("bloodbank.retro.note.helper")}
        value={note}
        maxCount={500}
        enableCounter
        onChange={(e) => setNote(e.target.value)}
      />
      {error && (
        <InlineNotification
          kind="error"
          lowContrast
          hideCloseButton
          title={t("bloodbank.error.title")}
          subtitle={error}
        />
      )}
    </Modal>
  );
};

const unitLine = (intl, issue) =>
  `${issue.donationNumber} · ${productText(issue)} · ${groupText(intl, issue.abo, issue.rhd)}`;

/**
 * The patient's emergency issues that still need attention: a red banner while
 * a unit's retrospective group and crossmatch is pending, and a red warning
 * that stays for any unit found incompatible.
 *
 * @param canRecord the user may record the retrospective check (Results or
 *                  Validation)
 */
const EmergencyIssuePanel = ({ issues, canRecord, onRecorded }) => {
  const intl = useIntl();
  const t = (id, values) => intl.formatMessage({ id }, values);
  const [recording, setRecording] = useState(null);
  const pending = (issues || []).filter((i) => !i.retrospectiveResult);
  const incompatible = (issues || []).filter(
    (i) => i.retrospectiveResult === "INCOMPATIBLE",
  );
  if (pending.length === 0 && incompatible.length === 0) return null;

  return (
    <div className="bb-section">
      {pending.length > 0 && (
        <div className="bb-emergency-banner" data-testid="bb-emergency-pending">
          <InlineNotification
            kind="error"
            hideCloseButton
            title={t("bloodbank.emergency.banner.title")}
            subtitle={t("bloodbank.emergency.banner.subtitle", {
              count: pending.length,
            })}
          />
          <ul className="bb-emergency-list">
            {pending.map((issue) => (
              <li key={issue.reservationId}>
                <strong>{unitLine(intl, issue)}</strong>{" "}
                {t("bloodbank.emergency.banner.issued", {
                  when: formatInstant(intl, issue.issuedAt),
                  clinician: issue.requestingClinician,
                })}{" "}
                {canRecord && (
                  <Button
                    kind="danger--tertiary"
                    size="sm"
                    onClick={() => setRecording(issue)}
                  >
                    {t("bloodbank.retro.record")}
                  </Button>
                )}
              </li>
            ))}
          </ul>
        </div>
      )}
      {incompatible.length > 0 && (
        <div
          className="bb-emergency-banner"
          data-testid="bb-emergency-incompatible"
        >
          <InlineNotification
            kind="error"
            hideCloseButton
            title={t("bloodbank.retro.incompatible.title")}
            subtitle={t("bloodbank.retro.reaction")}
          />
          <ul className="bb-emergency-list">
            {incompatible.map((issue) => (
              <li key={issue.reservationId}>
                <strong>{unitLine(intl, issue)}</strong>{" "}
                {t("bloodbank.retro.incompatible.line", {
                  when: formatInstant(intl, issue.retrospectiveCheckedAt),
                  who: issue.retrospectiveCheckedByName || "",
                  note: issue.retrospectiveNote || "",
                })}
              </li>
            ))}
          </ul>
        </div>
      )}
      <RetrospectiveCheckModal
        key={recording ? recording.reservationId : "none"}
        issue={recording}
        onClose={() => setRecording(null)}
        onRecorded={onRecorded}
      />
    </div>
  );
};

export default EmergencyIssuePanel;
