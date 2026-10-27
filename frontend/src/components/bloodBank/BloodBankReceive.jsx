import React, { useEffect, useRef, useState } from "react";
import {
  Button,
  InlineNotification,
  Select,
  SelectItem,
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
  fieldLabel,
  formatInstant,
  groupText,
  lineErrorsText,
  messageFor,
} from "./bloodBankFormat";
import "./BloodBank.css";

// The eight ABO/RhD groups: a fixed list, sent as typed ("O NEG").
export const BLOOD_GROUPS = ["O", "A", "B", "AB"].flatMap((abo) =>
  ["NEG", "POS"].map((rhd) => ({ abo, rhd, value: `${abo} ${rhd}` })),
);

// An ISBT 128 product code is 8 characters: the 5-character product
// description code, the donation type and a 2-character division (E0291V00).
const PRODUCT_CODE_LENGTH = 8;
const DEFAULT_SUFFIX = "V00";

const EMPTY_MANUAL = {
  donationNumber: "",
  productPrefix: "",
  productSuffix: DEFAULT_SUFFIX,
  bloodGroup: "",
  expiresAt: "",
  volumeMl: "",
};

// what a line needs before it can be added, from the form or the scans
const REQUIRED = ["donationNumber", "productCode", "bloodGroup", "expiresAt"];

/** The configured code that is the longest prefix of a scanned product code. */
export const matchProductCode = (codes, productCode) =>
  (codes || [])
    .filter((c) => productCode && productCode.startsWith(c.isbtCodePrefix))
    .sort((a, b) => b.isbtCodePrefix.length - a.isbtCodePrefix.length)[0];

/** The product code chosen in the form: the code, then type and division. */
const chosenProductCode = (manual) => {
  if (!manual.productPrefix) return "";
  if (manual.productPrefix.length >= PRODUCT_CODE_LENGTH) {
    return manual.productPrefix;
  }
  return (manual.productPrefix + manual.productSuffix.trim()).toUpperCase();
};

/**
 * Receipt: scan each label barcode of a bag into the scan field (the server
 * parses them), or enter the fields by hand; entered fields override scans,
 * and a group or product scan fills its list. Lines collect in a table and are
 * sent together; each line gets its own result.
 */
const BloodBankReceive = () => {
  const intl = useIntl();
  const scanRef = useRef(null);
  const [scan, setScan] = useState("");
  const [scans, setScans] = useState([]);
  const [parsed, setParsed] = useState(null);
  const [manual, setManual] = useState(EMPTY_MANUAL);
  const [codes, setCodes] = useState([]);
  const [missing, setMissing] = useState([]);
  const [supplier, setSupplier] = useState("");
  const [lines, setLines] = useState([]);
  const [results, setResults] = useState([]);
  const [error, setError] = useState(null);
  const [sending, setSending] = useState(false);

  useEffect(() => {
    scanRef.current?.focus();
    BloodBankService.productCodes()
      .then((list) =>
        setCodes(
          (list || [])
            .filter((c) => c.active && c.productType?.active !== false)
            .sort((a, b) => a.isbtCodePrefix.localeCompare(b.isbtCodePrefix)),
        ),
      )
      .catch(() => setCodes([]));
  }, []);

  const t = (id, values) => intl.formatMessage({ id }, values);

  // a group or product scan fills its list; a code that is not configured
  // leaves the list as it is (the scan still goes with the line and the server
  // refuses it)
  const fillFromScan = (label) => {
    setManual((current) => {
      const next = { ...current };
      if (label.abo && label.rhd) next.bloodGroup = `${label.abo} ${label.rhd}`;
      const code = matchProductCode(codes, label.productCode);
      if (code) {
        next.productPrefix = code.isbtCodePrefix;
        next.productSuffix =
          label.productCode.slice(code.isbtCodePrefix.length) || DEFAULT_SUFFIX;
      }
      return next;
    });
  };

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
        fillFromScan(label);
        setMissing([]);
      })
      .catch((e) => setError(errorText(intl, e)));
  };

  const clearDraft = () => {
    setScans([]);
    setParsed(null);
    setManual(EMPTY_MANUAL);
    setMissing([]);
    scanRef.current?.focus();
  };

  const draftHasContent =
    scans.length > 0 ||
    ["donationNumber", "productPrefix", "bloodGroup", "expiresAt", "volumeMl"]
      .map((field) => manual[field])
      .some((v) => v.trim() !== "");

  // what the line will carry for each required field, from the form or the scans
  const draftValue = {
    donationNumber: manual.donationNumber.trim() || parsed?.donationNumber,
    productCode: chosenProductCode(manual) || parsed?.productCode,
    bloodGroup: manual.bloodGroup || (parsed?.abo && parsed?.rhd),
    expiresAt: manual.expiresAt || parsed?.expiresAt,
  };

  const addLine = () => {
    const absent = REQUIRED.filter((field) => !draftValue[field]);
    setMissing(absent);
    if (absent.length) return;
    setLines([
      ...lines,
      {
        scans,
        donationNumber: manual.donationNumber,
        productCode: chosenProductCode(manual),
        bloodGroup: manual.bloodGroup,
        expiresAt: manual.expiresAt,
        volumeMl: manual.volumeMl,
        parsed,
      },
    ]);
    clearDraft();
  };

  // an entry clears its "missing" mark; the product list's field is productCode
  const setField = (field, value, requiredField = field) => {
    setManual({ ...manual, [field]: value });
    setMissing(missing.filter((m) => m !== requiredField));
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

  const missingProps = (field) => ({
    invalid: missing.includes(field),
    invalidText: t("bloodbank.receive.missing.field"),
  });
  const shown = (line, field) =>
    line[field] || (line.parsed && line.parsed[field]) || "";
  const lineGroup = (line) => {
    if (line.bloodGroup) {
      const [abo, rhd] = line.bloodGroup.split(" ");
      return groupText(intl, abo, rhd);
    }
    return line.parsed && (line.parsed.abo || line.parsed.rhd)
      ? groupText(intl, line.parsed.abo, line.parsed.rhd)
      : "";
  };
  // a typed expiry is local time ("2026-11-27T03:01"), a scanned one an instant
  const lineExpiry = (line) => {
    if (line.expiresAt) return formatInstant(intl, new Date(line.expiresAt));
    return line.parsed?.expiresAt
      ? formatInstant(intl, line.parsed.expiresAt)
      : "";
  };
  const selectedCode = codes.find(
    (c) => c.isbtCodePrefix === manual.productPrefix,
  );
  const suffixLength = selectedCode
    ? PRODUCT_CODE_LENGTH - selectedCode.isbtCodePrefix.length
    : 0;

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
        <div className="bb-row bb-row--top" data-testid="bb-receive-fields">
          <TextInput
            id="bb-din"
            labelText={t("bloodbank.field.din")}
            value={manual.donationNumber}
            autoComplete="off"
            {...missingProps("donationNumber")}
            onChange={(e) => setField("donationNumber", e.target.value)}
          />
          <Select
            id="bb-product-code"
            labelText={t("bloodbank.field.productCode")}
            value={manual.productPrefix}
            {...missingProps("productCode")}
            onChange={(e) =>
              setField("productPrefix", e.target.value, "productCode")
            }
          >
            <SelectItem value="" text={t("bloodbank.receive.choose")} />
            {codes.map((c) => (
              <SelectItem
                key={c.id}
                value={c.isbtCodePrefix}
                text={[c.isbtCodePrefix, c.productType?.name]
                  .filter(Boolean)
                  .join(" · ")}
              />
            ))}
          </Select>
          {suffixLength > 0 && (
            <TextInput
              id="bb-product-suffix"
              labelText={t("bloodbank.receive.productSuffix")}
              value={manual.productSuffix}
              maxLength={suffixLength}
              autoComplete="off"
              onChange={(e) =>
                setField("productSuffix", e.target.value, "productCode")
              }
            />
          )}
          <Select
            id="bb-group"
            labelText={t("bloodbank.field.group")}
            value={manual.bloodGroup}
            {...missingProps("bloodGroup")}
            onChange={(e) => setField("bloodGroup", e.target.value)}
          >
            <SelectItem value="" text={t("bloodbank.receive.choose")} />
            {BLOOD_GROUPS.map((g) => (
              <SelectItem
                key={g.value}
                value={g.value}
                text={groupText(intl, g.abo, g.rhd)}
              />
            ))}
          </Select>
          <TextInput
            id="bb-expires"
            type="datetime-local"
            labelText={t("bloodbank.field.expiresAt")}
            value={manual.expiresAt}
            {...missingProps("expiresAt")}
            onChange={(e) => setField("expiresAt", e.target.value)}
          />
          <TextInput
            id="bb-volume"
            type="number"
            labelText={t("bloodbank.field.volumeMl")}
            value={manual.volumeMl}
            onChange={(e) => setField("volumeMl", e.target.value)}
          />
        </div>
        {missing.length > 0 && (
          <InlineNotification
            kind="warning"
            lowContrast
            hideCloseButton
            title=""
            subtitle={t("bloodbank.receive.missing", {
              fields: missing.map((f) => fieldLabel(intl, f)).join(", "),
            })}
          />
        )}
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
          subtitle={lineErrorsText(intl, r.errors)}
          onClose={() => setResults(results.filter((x) => x !== r))}
        />
      ))}

      <div className="bb-section">
        <h4>{t("bloodbank.receive.pending.title")}</h4>
        <TableContainer>
          <Table size="md" data-testid="bb-receive-lines">
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
