import React, { useEffect, useState } from "react";
import {
  Button,
  DefinitionTooltip,
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

const ISO_IN_TEXT = /\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d+)?)?Z/g;

/** "Name · national ID · lab number", falling back to the record number. */
export const patientText = (intl, e) => {
  if (!e.patientId) return "";
  const parts = [
    e.patientName,
    e.patientNationalId &&
      intl.formatMessage(
        { id: "bloodbank.trace.nationalId" },
        { id: e.patientNationalId },
      ),
    e.labNumber &&
      intl.formatMessage(
        { id: "bloodbank.trace.labNumber" },
        { lab: e.labNumber },
      ),
  ].filter(Boolean);
  return parts.length ? parts.join(" · ") : `#${e.patientId}`;
};

/**
 * The issue entry's details, as the server writes them:
 * "checks: ABO=PASS, RHD=WARN; secondCheck: REQUIRED" (older entries have no
 * second-check part). Null for any other text.
 */
export const parseIssueChecks = (details) => {
  const match = /^checks: ([^;]*?)(?:; secondCheck: ([A-Z_]+))?$/.exec(
    details || "",
  );
  if (!match) return null;
  const checks = match[1]
    .split(",")
    .map((item) => item.trim().split("="))
    .filter(([code, status]) => code && status)
    .map(([code, status]) => ({ code, status }));
  return { checks, secondCheck: match[2] || null };
};

const IssueChecks = ({ parsed }) => {
  const intl = useIntl();
  const t = (id, values) => intl.formatMessage({ id }, values);
  const { checks, secondCheck } = parsed;
  const passed = checks.filter((c) => c.status === "PASS").length;
  const notPassed = checks
    .filter((c) => c.status !== "PASS")
    .map(
      (c) =>
        `${t(`bloodbank.checkCode.${c.code}`)} (${t(`bloodbank.checkStatus.${c.status}`)})`,
    );
  const summary =
    notPassed.length === 0
      ? t("bloodbank.trace.details.allPassed", { count: checks.length })
      : t("bloodbank.trace.details.somePassed", {
          passed,
          count: checks.length,
          list: notPassed.join(", "),
        });
  const mode =
    secondCheck && secondCheck !== "REQUIRED"
      ? t(`bloodbank.trace.details.secondCheck.${secondCheck}`)
      : null;
  return (
    <span className="bb-trail-details">
      <DefinitionTooltip
        openOnHover
        align="bottom-left"
        definition={
          <ul data-testid="bb-trail-check-list">
            {checks.map((c) => (
              <li key={c.code}>
                {t(`bloodbank.checkCode.${c.code}`)}:{" "}
                {t(`bloodbank.checkStatus.${c.status}`)}
              </li>
            ))}
          </ul>
        }
      >
        {summary}
      </DefinitionTooltip>
      {mode && ` · ${mode}`}
    </span>
  );
};

/** Readable details: known server formats in words, instants in local time. */
export const DetailsText = ({ details }) => {
  const intl = useIntl();
  if (!details) return null;
  const parsed = parseIssueChecks(details);
  if (parsed) return <IssueChecks parsed={parsed} />;
  const reserved =
    /^crossmatch result \d+ \(([^)]*)\), valid until (\S+)$/.exec(details);
  if (reserved) {
    return intl.formatMessage(
      { id: "bloodbank.trace.details.reserved" },
      { accession: reserved[1], when: formatInstant(intl, reserved[2]) },
    );
  }
  const returned = /^returned (-?\d+) min after issue; window (\d+) min$/.exec(
    details,
  );
  if (returned) {
    return intl.formatMessage(
      { id: "bloodbank.trace.details.returned" },
      { minutes: returned[1], window: returned[2] },
    );
  }
  if (details === "returned after the return window") {
    return intl.formatMessage({ id: "bloodbank.trace.details.returnedLate" });
  }
  return details.replace(ISO_IN_TEXT, (iso) => formatInstant(intl, iso));
};

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
              <TableCell>{patientText(intl, e)}</TableCell>
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
                {e.reason}
                {e.reason && e.details && " · "}
                <DetailsText details={e.details} />
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
        // linked from the issue page: name the patient, not the record number
        if (next.patient && !heading.includes(",") && list && list.length) {
          const named = list.find((e) => e.patientId && e.patientName);
          if (named) setTitle(patientText(intl, named));
        }
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
