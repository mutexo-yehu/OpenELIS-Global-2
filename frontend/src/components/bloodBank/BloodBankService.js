import {
  getFromOpenElisServer,
  postToOpenElisServerJsonResponse,
} from "../utils/Utils";
import config from "../../config.json";

/**
 * Calls to /rest/bloodbank. Every call returns a promise: it resolves with the
 * response body, or rejects with a BloodBankError carrying the server's
 * errorCode, params and (for a refused reserve or issue) the safety checks.
 */
export const BLOOD_BANK_BASE = "/rest/bloodbank";

export class BloodBankError extends Error {
  constructor({ errorCode, params, checks, httpStatus, message } = {}) {
    super(message || errorCode || `HTTP ${httpStatus || 0}`);
    this.errorCode = errorCode;
    this.params = params || [];
    this.checks = checks || [];
    this.httpStatus = httpStatus || 0;
  }
}

// Utils hands a failed POST to the callback as the error body plus a numeric
// statusCode, or as { error, status: 0 } when nothing reached the server. A
// successful body never has either; it may have a string status (a
// reservation's), so only a number counts.
export const isFailedResponse = (json) =>
  !json ||
  typeof json.statusCode === "number" ||
  typeof json.status === "number" ||
  !!json.error;

const toError = (json) =>
  new BloodBankError({
    errorCode: json?.errorCode,
    params: json?.params,
    checks: json?.checks,
    httpStatus: json?.statusCode ?? json?.status,
    message: json?.message,
  });

const get = (path) =>
  new Promise((resolve, reject) => {
    // GET failures arrive as undefined (Utils drops the error body)
    getFromOpenElisServer(BLOOD_BANK_BASE + path, (json) =>
      json === undefined ? reject(new BloodBankError()) : resolve(json),
    );
  });

const post = (path, body) =>
  new Promise((resolve, reject) => {
    postToOpenElisServerJsonResponse(
      BLOOD_BANK_BASE + path,
      JSON.stringify(body),
      (json) =>
        isFailedResponse(json) ? reject(toError(json)) : resolve(json),
    );
  });

const query = (params) => {
  const search = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== "") {
      search.append(key, Array.isArray(value) ? value.join(",") : value);
    }
  });
  const text = search.toString();
  return text ? `?${text}` : "";
};

// Leaves out empty optional fields, so the request carries only what was entered.
const compact = (object) =>
  Object.fromEntries(
    Object.entries(object).filter(
      ([, value]) => value !== undefined && value !== null && value !== "",
    ),
  );

const trimmed = (value) => (typeof value === "string" ? value.trim() : value);

const UNIT_ACTIONS = {
  quarantine: "quarantine",
  discard: "discard",
  returnToSupplier: "return-to-supplier",
  release: "release",
};

const traceCriterion = ({ din, unit, patient }) => {
  if (din) return { din };
  if (unit) return { unit };
  return { patient };
};

const BloodBankService = {
  // stock
  listUnits: ({
    status,
    productType,
    abo,
    rhd,
    expiringWithinHours,
    patientId,
  } = {}) =>
    get(
      "/units" +
        query({
          status,
          productType,
          abo,
          rhd,
          expiringWithinHours,
          patientId,
        }),
    ),
  summary: () => get("/units/summary"),
  unitDetail: (unitId) => get(`/units/${unitId}`),
  productTypes: () => get("/product-types"),
  unitAction: (unitId, action, reason) =>
    post(`/units/${unitId}/${UNIT_ACTIONS[action]}`, compact({ reason })),

  // receipt
  parseScans: (scans) => post("/units/parse", { scans }),
  receive: (lines) =>
    post("/units/receive", {
      lines: lines.map((line) =>
        compact({
          scans: line.scans && line.scans.length ? line.scans : undefined,
          donationNumber: trimmed(line.donationNumber),
          productCode: trimmed(line.productCode),
          bloodGroup: trimmed(line.bloodGroup),
          expiresAt: trimmed(line.expiresAt),
          collectedAt: trimmed(line.collectedAt),
          supplier: trimmed(line.supplier),
          volumeMl:
            line.volumeMl === undefined || line.volumeMl === ""
              ? undefined
              : Number(line.volumeMl),
        }),
      ),
    }),

  // patient, reservation, issue
  profile: (patientId) => get(`/patients/${patientId}/profile`),
  candidates: (patientId, productTypeId) =>
    get(
      `/patients/${patientId}/candidate-units` +
        query({ productType: productTypeId }),
    ),
  reserve: ({ unitId, patientId, crossmatchResultId }) =>
    post(
      "/reservations",
      compact({
        unitId: Number(unitId),
        patientId: Number(patientId),
        crossmatchResultId:
          crossmatchResultId === undefined || crossmatchResultId === ""
            ? undefined
            : Number(crossmatchResultId),
      }),
    ),
  releaseReservation: (reservationId, reason) =>
    post(`/reservations/${reservationId}/release`, { reason }),
  issueCheck: (unitId, patientIdentifier) =>
    post(`/units/${unitId}/issue/check`, {
      patientIdentifier: patientIdentifier.trim(),
    }),
  issue: (
    unitId,
    {
      patientIdentifier,
      location,
      collectorName,
      secondCheckerLogin,
      secondCheckerPassword,
      secondCheckerOtp,
      warningReason,
    },
  ) =>
    post(
      `/units/${unitId}/issue`,
      compact({
        patientIdentifier: patientIdentifier.trim(),
        location: location.trim(),
        collectorName: collectorName.trim(),
        secondCheckerLogin: secondCheckerLogin.trim(),
        // the password is sent as typed, never trimmed or stored
        secondCheckerPassword,
        secondCheckerOtp: secondCheckerOtp
          ? secondCheckerOtp.trim()
          : undefined,
        warningReason: warningReason ? warningReason.trim() : undefined,
      }),
    ),
  returnUnit: (unitId, reason) =>
    post(`/units/${unitId}/return`, compact({ reason })),
  transfused: (unitId, reactionNote) =>
    post(`/units/${unitId}/transfused`, compact({ reactionNote })),

  // traceability
  trace: (criterion) => get("/trace" + query(traceCriterion(criterion))),
  traceExportUrl: (criterion) =>
    config.serverBaseUrl +
    BLOOD_BANK_BASE +
    "/trace/export" +
    query(traceCriterion(criterion)),
};

export default BloodBankService;
