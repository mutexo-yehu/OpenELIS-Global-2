import React, { useEffect, useState } from "react";
import { InlineNotification, Modal, Select, SelectItem } from "@carbon/react";
import { useIntl } from "react-intl";
import BloodBankService from "./BloodBankService";
import SafetyChecklist from "./SafetyChecklist";
import {
  errorText,
  formatInstant,
  groupText,
  productText,
} from "./bloodBankFormat";

export const needsCrossmatch = (unit) =>
  !!unit?.productType?.requiresCrossmatch ||
  ["RED_CELLS", "WHOLE_BLOOD"].includes(unit?.productType?.componentClass);

// Crossmatches a new reservation can use: compatible, still valid, not linked
// to another unit. The server checks all of this again.
export const usableCrossmatches = (profile, now = new Date()) =>
  (profile?.crossmatches || []).filter(
    (x) =>
      x.compatible &&
      !x.linkedUnitId &&
      x.validUntil &&
      new Date(x.validUntil) > now,
  );

const ReserveModal = ({
  open,
  unit,
  patientId,
  profile,
  onClose,
  onReserved,
}) => {
  const intl = useIntl();
  const t = (id, values) => intl.formatMessage({ id }, values);
  const [crossmatchId, setCrossmatchId] = useState("");
  const [error, setError] = useState(null);
  const [checks, setChecks] = useState([]);
  const [sending, setSending] = useState(false);

  const options = usableCrossmatches(profile);
  const required = needsCrossmatch(unit);

  useEffect(() => {
    if (open) {
      setCrossmatchId(options.length === 1 ? String(options[0].resultId) : "");
      setError(null);
      setChecks([]);
      setSending(false);
    }
    // options derive from profile; reset only when the modal opens
  }, [open, unit]);

  if (!unit) return null;

  const submit = () => {
    setSending(true);
    setError(null);
    BloodBankService.reserve({
      unitId: unit.id,
      patientId,
      crossmatchResultId: required ? crossmatchId : undefined,
    })
      .then((reservation) => {
        onReserved(reservation);
        onClose();
      })
      .catch((e) => {
        setError(errorText(intl, e));
        setChecks(e.checks || []);
      })
      .finally(() => setSending(false));
  };

  return (
    <Modal
      open={open}
      modalHeading={t("bloodbank.reserve.title", { din: unit.donationNumber })}
      modalLabel={`${productText(unit)} · ${groupText(intl, unit.abo, unit.rhd)}`}
      primaryButtonText={t("bloodbank.action.reserve")}
      secondaryButtonText={t("bloodbank.action.cancel")}
      primaryButtonDisabled={sending || (required && !crossmatchId)}
      onRequestSubmit={submit}
      onRequestClose={onClose}
    >
      {required && options.length === 0 && (
        <InlineNotification
          kind="warning"
          lowContrast
          hideCloseButton
          title=""
          subtitle={t("bloodbank.reserve.noCrossmatch")}
        />
      )}
      {required && options.length > 0 && (
        <Select
          id="bb-reserve-crossmatch"
          labelText={t("bloodbank.reserve.crossmatch")}
          value={crossmatchId}
          onChange={(e) => setCrossmatchId(e.target.value)}
        >
          <SelectItem
            value=""
            text={t("bloodbank.reserve.crossmatch.choose")}
          />
          {options.map((x) => (
            <SelectItem
              key={x.resultId}
              value={String(x.resultId)}
              text={t("bloodbank.reserve.crossmatch.option", {
                accession: x.accessionNumber,
                value: x.value,
                validUntil: formatInstant(intl, x.validUntil),
              })}
            />
          ))}
        </Select>
      )}
      {!required && <p>{t("bloodbank.reserve.crossmatchNotRequired")}</p>}
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
    </Modal>
  );
};

export default ReserveModal;
