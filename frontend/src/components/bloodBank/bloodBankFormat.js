// Display helpers shared by the blood bank pages. The server sends message keys
// and raw arguments (enum names, ISO instants); these turn them into text.

const ISO_INSTANT = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d+)?)?Z$/;

export const formatInstant = (intl, iso) =>
  iso
    ? `${intl.formatDate(iso, { year: "numeric", month: "2-digit", day: "2-digit" })} ${intl.formatTime(iso)}`
    : "";

const rhdText = (intl, rhd) =>
  rhd ? intl.formatMessage({ id: `bloodbank.rhd.${rhd}` }) : "";

export const groupText = (intl, abo, rhd) =>
  abo || rhd
    ? `${abo || "?"} ${rhd ? rhdText(intl, rhd) : "?"}`
    : intl.formatMessage({ id: "bloodbank.group.unknown" });

const argText = (intl, arg) => {
  if (typeof arg !== "string") return arg;
  if (ISO_INSTANT.test(arg)) return formatInstant(intl, arg);
  if (arg === "POS" || arg === "NEG") return rhdText(intl, arg);
  return arg;
};

// The server names a missing receipt field by its request name; show its label.
const FIELD_LABELS = {
  donationNumber: "bloodbank.field.din",
  productCode: "bloodbank.field.productCode",
  bloodGroup: "bloodbank.field.group",
  expiresAt: "bloodbank.field.expiresAt",
};

const MISSING_FIELD = "bloodbank.receive.error.missingField";

/** The on-screen label of a receipt field ("productCode" -> "Product code"). */
export const fieldLabel = (intl, field) =>
  FIELD_LABELS[field]
    ? intl.formatMessage({ id: FIELD_LABELS[field] })
    : String(field);

/** Message for a server key with positional arguments ({0}, {1}, ...). */
export const messageFor = (intl, key, args = []) =>
  intl.formatMessage(
    { id: key, defaultMessage: key },
    Object.fromEntries(
      (args || []).map((a, i) => [
        String(i),
        key === MISSING_FIELD ? fieldLabel(intl, a) : argText(intl, a),
      ]),
    ),
  );

/**
 * A refused receipt line's errors in one text; the missing fields are named
 * together by their labels ("Missing: Product code, Blood group").
 */
export const lineErrorsText = (intl, errors = []) => {
  const list = errors || [];
  const missing = list
    .filter((e) => e.code === MISSING_FIELD)
    .flatMap((e) => e.args || []);
  const others = list
    .filter((e) => e.code !== MISSING_FIELD)
    .map((e) => messageFor(intl, e.code, e.args));
  if (missing.length) {
    others.unshift(
      intl.formatMessage(
        { id: MISSING_FIELD },
        { 0: missing.map((field) => fieldLabel(intl, field)).join(", ") },
      ),
    );
  }
  return others.join("; ");
};

/** Text for a rejected BloodBankService call. */
export const errorText = (intl, error) => {
  if (error && error.errorCode) {
    return messageFor(intl, error.errorCode, error.params);
  }
  return intl.formatMessage(
    { id: "bloodbank.error.request" },
    { status: (error && error.httpStatus) || 0 },
  );
};

export const statusText = (intl, status) =>
  intl.formatMessage({
    id: `bloodbank.status.${status}`,
    defaultMessage: status,
  });

/**
 * What returning an issued unit at this time would do, from its issue time and
 * the return window the server sent with it: late means past the window (the
 * server discards it). Null when the unit carries no issue time. The server
 * decides when the return is made.
 */
export const returnOutcome = (unit, now = Date.now()) => {
  if (!unit?.issuedAt || unit.returnWindowMinutes == null) return null;
  const issued = new Date(unit.issuedAt).getTime();
  if (Number.isNaN(issued)) return null;
  return {
    late: now > issued + unit.returnWindowMinutes * 60000,
    minutesOut: Math.max(0, Math.floor((now - issued) / 60000)),
    windowMinutes: unit.returnWindowMinutes,
  };
};

/** "1 h 47 min" or "12 min". */
export const durationText = (intl, totalMinutes) => {
  const hours = Math.floor(totalMinutes / 60);
  const minutes = totalMinutes % 60;
  return hours > 0
    ? intl.formatMessage(
        { id: "bloodbank.duration.hoursMinutes" },
        { hours, minutes },
      )
    : intl.formatMessage({ id: "bloodbank.duration.minutes" }, { minutes });
};

export const productText = (unit) =>
  [unit.productType?.name, unit.productCode].filter(Boolean).join(" · ");
