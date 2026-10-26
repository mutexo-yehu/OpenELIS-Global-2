import React, { useEffect, useState } from "react";
import {
  Button,
  InlineNotification,
  RadioButton,
  RadioButtonGroup,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableHeader,
  TableRow,
  TextInput,
} from "@carbon/react";
import { useIntl } from "react-intl";
import { useLocation } from "react-router-dom";
import SearchPatientForm from "../patient/SearchPatientForm";
import BloodBankService from "./BloodBankService";
import { errorText, formatInstant } from "./bloodBankFormat";
import "./BloodBank.css";

const personText = (name, id) => name || (id ? `#${id}` : "");

/** The trail entries, oldest first: who, when, why, second checker, ward. */
export const TrailTable = ({ events }) => {
  const intl = useIntl();
  const t = (id) => intl.formatMessage({ id });
  return (
    <TableContainer>
      <Table size="md" data-testid="bb-trail">
        <TableHead>
          <TableRow>
            <TableHeader>{t("bloodbank.trace.col.when")}</TableHeader>
            <TableHeader>{t("bloodbank.trace.col.event")}</TableHeader>
            <TableHeader>{t("bloodbank.field.din")}</TableHeader>
            <TableHeader>{t("bloodbank.trace.col.patient")}</TableHeader>
            <TableHeader>{t("bloodbank.trace.col.by")}</TableHeader>
            <TableHeader>{t("bloodbank.trace.col.secondChecker")}</TableHeader>
            <TableHeader>{t("bloodbank.trace.col.where")}</TableHeader>
            <TableHeader>{t("bloodbank.trace.col.reason")}</TableHeader>
          </TableRow>
        </TableHead>
        <TableBody>
          {events.length === 0 && (
            <TableRow>
              <TableCell colSpan={8}>{t("bloodbank.trace.empty")}</TableCell>
            </TableRow>
          )}
          {events.map((e) => (
            <TableRow key={e.id}>
              <TableCell>{formatInstant(intl, e.occurredAt)}</TableCell>
              <TableCell>
                {intl.formatMessage({
                  id: `bloodbank.event.${e.eventType}`,
                  defaultMessage: e.eventType,
                })}
              </TableCell>
              <TableCell>
                {[e.donationNumber, e.productCode].filter(Boolean).join(" · ")}
              </TableCell>
              <TableCell>{e.patientId ? `#${e.patientId}` : ""}</TableCell>
              <TableCell>
                {personText(e.performedByName, e.performedBy)}
              </TableCell>
              <TableCell>
                {personText(e.secondCheckerName, e.secondCheckerId)}
              </TableCell>
              <TableCell>
                {[e.location, e.collectorName].filter(Boolean).join(" · ")}
              </TableCell>
              <TableCell>
                {[e.reason, e.details].filter(Boolean).join(" · ")}
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </TableContainer>
  );
};

/**
 * Traceability: the full trail of a unit (by DIN, or from the stock page) or
 * of a patient, on screen and as CSV.
 */
const BloodBankTrace = () => {
  const intl = useIntl();
  const location = useLocation();
  const t = (id, values) => intl.formatMessage({ id }, values);
  const [mode, setMode] = useState("din");
  const [din, setDin] = useState("");
  const [criterion, setCriterion] = useState(null);
  const [title, setTitle] = useState("");
  const [events, setEvents] = useState(null);
  const [error, setError] = useState(null);

  const search = (next, heading) => {
    setCriterion(next);
    setTitle(heading);
    setError(null);
    BloodBankService.trace(next)
      .then((list) => {
        setEvents(list || []);
        if (next.unit && list && list.length) setTitle(list[0].donationNumber);
      })
      .catch((e) => {
        setEvents(null);
        setError(errorText(intl, e));
      });
  };

  // links from the stock and issue pages: ?unit=<id> or ?patient=<id>
  useEffect(() => {
    const params = new URLSearchParams(location.search);
    if (params.get("unit")) {
      search({ unit: params.get("unit") }, t("bloodbank.trace.unitTitle"));
    } else if (params.get("patient")) {
      setMode("patient");
      search(
        { patient: params.get("patient") },
        t("bloodbank.trace.patientTitle"),
      );
    } else if (params.get("din")) {
      setDin(params.get("din"));
      search({ din: params.get("din") }, params.get("din"));
    }
  }, [location.search]);

  return (
    <div>
      <RadioButtonGroup
        legendText={t("bloodbank.trace.by")}
        name="bb-trace-mode"
        valueSelected={mode}
        onChange={(value) => setMode(value)}
      >
        <RadioButton
          id="bb-trace-din"
          value="din"
          labelText={t("bloodbank.field.din")}
        />
        <RadioButton
          id="bb-trace-patient"
          value="patient"
          labelText={t("bloodbank.trace.col.patient")}
        />
      </RadioButtonGroup>
      {mode === "din" ? (
        <div className="bb-row bb-section">
          <TextInput
            id="bb-trace-din-input"
            labelText={t("bloodbank.trace.dinLabel")}
            value={din}
            autoComplete="off"
            onChange={(e) => setDin(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter" && din.trim())
                search({ din: din.trim() }, din.trim());
            }}
          />
          <Button
            kind="primary"
            size="md"
            disabled={!din.trim()}
            onClick={() => search({ din: din.trim() }, din.trim())}
          >
            {t("bloodbank.action.search")}
          </Button>
        </div>
      ) : (
        <div className="bb-section">
          <SearchPatientForm
            getSelectedPatient={(p) =>
              search(
                { patient: p.patientPK },
                [p.lastName, p.firstName].filter(Boolean).join(", "),
              )
            }
          />
        </div>
      )}
      {error && (
        <InlineNotification
          kind="error"
          lowContrast
          title={t("bloodbank.error.title")}
          subtitle={error}
          onClose={() => setError(null)}
        />
      )}
      {events && (
        <div className="bb-section">
          <div className="bb-row">
            <h4>{t("bloodbank.trace.resultTitle", { what: title })}</h4>
            <Button
              kind="tertiary"
              size="sm"
              href={BloodBankService.traceExportUrl(criterion)}
              disabled={events.length === 0}
            >
              {t("bloodbank.action.exportCsv")}
            </Button>
          </div>
          <TrailTable events={events} />
        </div>
      )}
    </div>
  );
};

export default BloodBankTrace;
