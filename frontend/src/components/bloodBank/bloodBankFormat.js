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

/** Message for a server key with positional arguments ({0}, {1}, ...). */
export const messageFor = (intl, key, args = []) =>
  intl.formatMessage(
    { id: key, defaultMessage: key },
    Object.fromEntries(
      (args || []).map((a, i) => [String(i), argText(intl, a)]),
    ),
  );

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

export const productText = (unit) =>
  [unit.productType?.name, unit.productCode].filter(Boolean).join(" · ");
