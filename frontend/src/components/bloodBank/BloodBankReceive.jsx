import React, { useEffect, useRef, useState } from "react";
import {
  Button,
  InlineNotification,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableHeader,
  TableRow,
  Tag,
  TextInput,
  Tile,
} from "@carbon/react";
import { useIntl } from "react-intl";
import BloodBankService from "./BloodBankService";
import {
  errorText,
  formatInstant,
  groupText,
  messageFor,
} from "./bloodBankFormat";
import "./BloodBank.css";

const EMPTY_MANUAL = {
  donationNumber: "",
  productCode: "",
  bloodGroup: "",
  expiresAt: "",
  volumeMl: "",
};

/**
 * Receipt: scan each label barcode of a bag into the scan field (the server
 * parses them), or type the fields by hand; typed fields override scans. Lines
 * collect in a table and are sent together; each line gets its own result.
 */
const BloodBankReceive = () => {
  const intl = useIntl();
  const scanRef = useRef(null);
  const [scan, setScan] = useState("");
  const [scans, setScans] = useState([]);
  const [parsed, setParsed] = useState(null);
  const [manual, setManual] = useState(EMPTY_MANUAL);
  const [supplier, setSupplier] = useState("");
  const [lines, setLines] = useState([]);
  const [results, setResults] = useState([]);
  const [error, setError] = useState(null);
  const [sending, setSending] = useState(false);

  useEffect(() => {
    scanRef.current?.focus();
  }, []);

  const addScan = () => {
    const value = scan.trim();
    if (!value) return;
    const next = [...scans, value];
    setScan("");
    setError(null);
    BloodBankService.parseScans(next)
      .then((label) => {
        setScans(next);
        setParsed(label);
      })
      .catch((e) => setError(errorText(intl, e)));
  };

  const clearDraft = () => {
    setScans([]);
    setParsed(null);
    setManual(EMPTY_MANUAL);
    scanRef.current?.focus();
  };

  const draftHasContent =
    scans.length > 0 || Object.values(manual).some((v) => v.trim() !== "");

  const addLine = () => {
    setLines([...lines, { scans, ...manual, parsed }]);
    clearDraft();
  };

  const submit = () => {
    setSending(true);
    setError(null);
    BloodBankService.receive(lines.map((line) => ({ ...line, supplier })))
      .then((lineResults) => {
        setResults(lineResults);
        // keep only the lines that were refused, so they can be corrected
        const refused = new Set(
          lineResults.filter((r) => !r.received).map((r) => r.index),
        );
        setLines(lines.filter((_, i) => refused.has(i)));
      })
      .catch((e) => setError(errorText(intl, e)))
      .finally(() => {
        setSending(false);
        scanRef.current?.focus();
      });
  };

  const t = (id, values) => intl.formatMessage({ id }, values);
  const shown = (line, field) =>
    line[field] || (line.parsed && line.parsed[field]) || "";
  const lineGroup = (line) =>
    line.bloodGroup ||
    (line.parsed && (line.parsed.abo || line.parsed.rhd)
      ? groupText(intl, line.parsed.abo, line.parsed.rhd)
      : "");
  const lineExpiry = (line) =>
    line.expiresAt ||
    (line.parsed?.expiresAt ? formatInstant(intl, line.parsed.expiresAt) : "");

  return (
    <div>
      <Tile>
        <TextInput
          id="bb-scan"
          ref={scanRef}
          labelText={t("bloodbank.receive.scan.label")}
          helperText={t("bloodbank.receive.scan.helper")}
          value={scan}
          autoComplete="off"
          onChange={(e) => setScan(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter") {
              e.preventDefault();
              addScan();
            }
          }}
        />
        {parsed && (
          <div className="bb-section" data-testid="bb-parsed">
            <h5>{t("bloodbank.receive.parsed.title")}</h5>
            <p>
              {[
                parsed.donationNumber,
                parsed.abo || parsed.rhd
                  ? groupText(intl, parsed.abo, parsed.rhd)
                  : null,
                parsed.productCode,
                parsed.productType?.name,
                parsed.expiresAt ? formatInstant(intl, parsed.expiresAt) : null,
              ]
                .filter(Boolean)
                .join(" · ")}
            </p>
            {(parsed.errors || []).map((e, i) => (
              <Tag key={i} type="red" size="sm">
                {messageFor(intl, e.code, e.args)}
              </Tag>
            ))}
          </div>
        )}
        <h5 className="bb-section">{t("bloodbank.receive.manual.title")}</h5>
        <div className="bb-row">
          <TextInput
            id="bb-din"
            labelText={t("bloodbank.field.din")}
            value={manual.donationNumber}
            onChange={(e) =>
              setManual({ ...manual, donationNumber: e.target.value })
            }
          />
          <TextInput
            id="bb-product-code"
            labelText={t("bloodbank.field.productCode")}
            value={manual.productCode}
            onChange={(e) =>
              setManual({ ...manual, productCode: e.target.value })
            }
          />
          <TextInput
            id="bb-group"
            labelText={t("bloodbank.field.group")}
            helperText={t("bloodbank.receive.group.helper")}
            value={manual.bloodGroup}
            onChange={(e) =>
              setManual({ ...manual, bloodGroup: e.target.value })
            }
          />
          <TextInput
            id="bb-expires"
            type="datetime-local"
            labelText={t("bloodbank.field.expiresAt")}
            value={manual.expiresAt}
            onChange={(e) =>
              setManual({ ...manual, expiresAt: e.target.value })
            }
          />
          <TextInput
            id="bb-volume"
            type="number"
            labelText={t("bloodbank.field.volumeMl")}
            value={manual.volumeMl}
            onChange={(e) => setManual({ ...manual, volumeMl: e.target.value })}
          />
        </div>
        <div className="bb-row">
          <Button kind="primary" disabled={!draftHasContent} onClick={addLine}>
            {t("bloodbank.receive.addLine")}
          </Button>
          <Button kind="ghost" disabled={!draftHasContent} onClick={clearDraft}>
            {t("bloodbank.receive.clearDraft")}
          </Button>
        </div>
      </Tile>

      {error && (
        <InlineNotification
          kind="error"
          lowContrast
          title={t("bloodbank.error.title")}
          subtitle={error}
          onClose={() => setError(null)}
        />
      )}
      {results.map((r) => (
        <InlineNotification
          key={`r-${r.index}-${r.donationNumber}`}
          kind={r.received ? "success" : "error"}
          lowContrast
          title={
            r.received
              ? t("bloodbank.receive.line.received", {
                  index: r.index + 1,
                  din: r.donationNumber,
                })
              : t("bloodbank.receive.line.refused", { index: r.index + 1 })
          }
          subtitle={(r.errors || [])
            .map((e) => messageFor(intl, e.code, e.args))
            .join("; ")}
          onClose={() => setResults(results.filter((x) => x !== r))}
        />
      ))}

      <div className="bb-section">
        <h4>{t("bloodbank.receive.pending.title")}</h4>
        <TableContainer>
          <Table size="md">
            <TableHead>
              <TableRow>
                <TableHeader>#</TableHeader>
                <TableHeader>{t("bloodbank.field.din")}</TableHeader>
                <TableHeader>{t("bloodbank.field.productCode")}</TableHeader>
                <TableHeader>{t("bloodbank.field.group")}</TableHeader>
                <TableHeader>{t("bloodbank.field.expiresAt")}</TableHeader>
                <TableHeader />
              </TableRow>
            </TableHead>
            <TableBody>
              {lines.length === 0 && (
                <TableRow>
                  <TableCell colSpan={6}>
                    {t("bloodbank.receive.pending.empty")}
                  </TableCell>
                </TableRow>
              )}
              {lines.map((line, i) => (
                <TableRow key={i}>
                  <TableCell>{i + 1}</TableCell>
                  <TableCell>{shown(line, "donationNumber")}</TableCell>
                  <TableCell>{shown(line, "productCode")}</TableCell>
                  <TableCell>{lineGroup(line)}</TableCell>
                  <TableCell>{lineExpiry(line)}</TableCell>
                  <TableCell>
                    <Button
                      kind="ghost"
                      size="sm"
                      onClick={() => setLines(lines.filter((_, j) => j !== i))}
                    >
                      {t("bloodbank.action.remove")}
                    </Button>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
        <div className="bb-row bb-section">
          <TextInput
            id="bb-supplier"
            labelText={t("bloodbank.field.supplier")}
            value={supplier}
            onChange={(e) => setSupplier(e.target.value)}
          />
          <Button
            kind="primary"
            disabled={lines.length === 0 || sending}
            onClick={submit}
          >
            {t("bloodbank.receive.submit", { count: lines.length })}
          </Button>
        </div>
      </div>
    </div>
  );
};

export default BloodBankReceive;
