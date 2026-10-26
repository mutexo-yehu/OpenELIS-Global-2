import BloodBankService, { isFailedResponse } from "./BloodBankService";
import {
  getFromOpenElisServer,
  postToOpenElisServerJsonResponse,
} from "../utils/Utils";

vi.mock("../utils/Utils", () => ({
  getFromOpenElisServer: vi.fn(),
  postToOpenElisServerJsonResponse: vi.fn(),
}));

// What the last POST sent: its endpoint and its parsed JSON body.
const lastPost = () => {
  const [endpoint, payload] =
    postToOpenElisServerJsonResponse.mock.calls[
      postToOpenElisServerJsonResponse.mock.calls.length - 1
    ];
  return { endpoint, body: JSON.parse(payload) };
};

const answerPostsWith = (json) =>
  postToOpenElisServerJsonResponse.mockImplementation(
    (endpoint, payload, callback) => callback(json),
  );

beforeEach(() => {
  vi.clearAllMocks();
  answerPostsWith({});
});

describe("receive request", () => {
  it("sends scans and typed fields per line, without empty fields", async () => {
    answerPostsWith([{ index: 0, received: true, unitId: 5, errors: [] }]);

    await BloodBankService.receive([
      {
        scans: ["=G123498654321 00", "=%9500"],
        donationNumber: "",
        productCode: " E0336V00 ",
        bloodGroup: "",
        expiresAt: "2026-11-30T23:59",
        volumeMl: "280",
        supplier: "Example Blood Service",
        parsed: { donationNumber: "G123498654321" },
      },
      {
        scans: [],
        donationNumber: "G123498654322",
        productCode: "E0336V00",
        bloodGroup: "O NEG",
        expiresAt: "2026-11-30",
        volumeMl: "",
        supplier: "",
      },
    ]);

    expect(lastPost()).toEqual({
      endpoint: "/rest/bloodbank/units/receive",
      body: {
        lines: [
          {
            scans: ["=G123498654321 00", "=%9500"],
            productCode: "E0336V00",
            expiresAt: "2026-11-30T23:59",
            volumeMl: 280,
            supplier: "Example Blood Service",
          },
          {
            donationNumber: "G123498654322",
            productCode: "E0336V00",
            bloodGroup: "O NEG",
            expiresAt: "2026-11-30",
          },
        ],
      },
    });
  });

  it("sends the scans to the parser as entered", async () => {
    await BloodBankService.parseScans(["=G123498654321 00"]);
    expect(lastPost()).toEqual({
      endpoint: "/rest/bloodbank/units/parse",
      body: { scans: ["=G123498654321 00"] },
    });
  });
});

describe("reserve request", () => {
  it("sends unit, patient and crossmatch ids as numbers", async () => {
    answerPostsWith({ id: 9, unitId: 5, status: "ACTIVE" });

    const reservation = await BloodBankService.reserve({
      unitId: 5,
      patientId: "41",
      crossmatchResultId: "77",
    });

    expect(lastPost()).toEqual({
      endpoint: "/rest/bloodbank/reservations",
      body: { unitId: 5, patientId: 41, crossmatchResultId: 77 },
    });
    // a reservation's string status is not mistaken for an HTTP error
    expect(reservation.status).toBe("ACTIVE");
  });

  it("leaves the crossmatch out when none applies", async () => {
    await BloodBankService.reserve({ unitId: 6, patientId: 41 });
    expect(lastPost().body).toEqual({ unitId: 6, patientId: 41 });
  });

  it("rejects with the server's code and safety checks on a 422", async () => {
    const checks = [
      {
        code: "ABO",
        status: "FAIL",
        messageKey: "bloodbank.check.abo.incompatible",
        args: ["A", "O"],
      },
    ];
    answerPostsWith({
      errorCode: "bloodbank.reserve.error.unsuitable",
      params: [],
      checks,
      status: 422,
      statusCode: 422,
    });

    await expect(
      BloodBankService.reserve({
        unitId: 5,
        patientId: 41,
        crossmatchResultId: 77,
      }),
    ).rejects.toMatchObject({
      errorCode: "bloodbank.reserve.error.unsuitable",
      httpStatus: 422,
      checks,
    });
  });
});

describe("issue requests", () => {
  const form = {
    unitScan: "=G123498654321 00",
    patientIdentifier: " 26-000123 ",
    location: " Ward 3 ",
    collectorName: " Porter Example ",
    secondCheckerLogin: " checker ",
    secondCheckerPassword: " pass word ",
    secondCheckerOtp: "",
    warningReason: "",
  };

  it("sends the dry-run check with the scanned patient identifier", async () => {
    await BloodBankService.issueCheck(5, " 26-000123 ");
    expect(lastPost()).toEqual({
      endpoint: "/rest/bloodbank/units/5/issue/check",
      body: { patientIdentifier: "26-000123" },
    });
  });

  it("sends exactly the issue fields, the password as typed, and no empty extras", async () => {
    await BloodBankService.issue(5, form);
    expect(lastPost()).toEqual({
      endpoint: "/rest/bloodbank/units/5/issue",
      body: {
        patientIdentifier: "26-000123",
        location: "Ward 3",
        collectorName: "Porter Example",
        secondCheckerLogin: "checker",
        secondCheckerPassword: " pass word ",
      },
    });
  });

  it("sends the authenticator code and the warning reason when given", async () => {
    await BloodBankService.issue(5, {
      ...form,
      secondCheckerOtp: " 123456 ",
      warningReason: " No RhD-negative platelets in stock ",
    });
    expect(lastPost().body).toMatchObject({
      secondCheckerOtp: "123456",
      warningReason: "No RhD-negative platelets in stock",
    });
  });

  it("rejects a dropped request instead of resolving", async () => {
    answerPostsWith({
      error: "Network error",
      message: "Network error",
      status: 0,
    });
    await expect(BloodBankService.issue(5, form)).rejects.toMatchObject({
      httpStatus: 0,
    });
  });
});

describe("reads", () => {
  it("builds the stock query from the filters it is given", async () => {
    getFromOpenElisServer.mockImplementation((endpoint, callback) =>
      callback([]),
    );
    await BloodBankService.listUnits({
      status: ["AVAILABLE", "RESERVED"],
      abo: "O",
      rhd: "",
      expiringWithinHours: 48,
    });
    expect(getFromOpenElisServer.mock.calls[0][0]).toBe(
      "/rest/bloodbank/units?status=AVAILABLE%2CRESERVED&abo=O&expiringWithinHours=48",
    );
  });

  it("treats only numeric statuses as failures", () => {
    expect(isFailedResponse({ id: 1, status: "ACTIVE" })).toBe(false);
    expect(isFailedResponse([])).toBe(false);
    expect(isFailedResponse({ statusCode: 409 })).toBe(true);
    expect(isFailedResponse(undefined)).toBe(true);
  });
});
