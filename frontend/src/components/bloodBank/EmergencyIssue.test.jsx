import React from "react";
import { fireEvent, render, screen, within } from "@testing-library/react";
import { waitFor } from "@testing-library/dom";
import "@testing-library/jest-dom";
import { IntlProvider } from "react-intl";
import { MemoryRouter } from "react-router-dom";
import UserSessionDetailsContext from "../../UserSessionDetailsContext";
import EmergencyIssueModal, {
  emergencyEligible,
  orderForEmergency,
} from "./EmergencyIssueModal";
import EmergencyIssuePanel from "./EmergencyIssuePanel";
import BloodBankPatientIssue from "./BloodBankPatientIssue";
import { DetailsText, splitEmergencyDetails } from "./BloodBankTrace";
import BloodBankService from "./BloodBankService";
import messages from "../../languages/en.json";

vi.mock("../patient/SearchPatientForm", () => ({
  default: ({ getSelectedPatient }) => (
    <button
      type="button"
      onClick={() =>
        getSelectedPatient({
          patientPK: 501,
          lastName: "Example",
          firstName: "Fictional",
        })
      }
    >
      pick patient
    </button>
  ),
}));

vi.mock("./BloodBankService", () => ({
  default: {
    listUnits: vi.fn(),
    emergencyCheck: vi.fn(),
    emergencyIssue: vi.fn(),
    emergencyIssues: vi.fn(),
    retrospectiveCheck: vi.fn(),
    profile: vi.fn(),
    candidates: vi.fn(),
    productTypes: vi.fn(),
  },
}));

const RED = { id: 1, name: "Red cells", componentClass: "RED_CELLS" };
const FFP = { id: 2, name: "Plasma", componentClass: "PLASMA" };
const PLT = { id: 3, name: "Platelets", componentClass: "PLATELETS" };

const unit = (id, productType, abo, rhd, expiresAt) => ({
  id,
  donationNumber: `G12349800000${id}`,
  productCode: "E0291V00",
  productType,
  abo,
  rhd,
  expiresAt,
  status: "AVAILABLE",
});

const O_NEG = unit(1, RED, "O", "NEG", "2026-11-05T10:00:00Z");
const O_POS = unit(2, RED, "O", "POS", "2026-11-01T10:00:00Z");
const O_FFP = unit(3, FFP, "O", "POS", "2026-11-01T10:00:00Z");
const O_PLT = unit(4, PLT, "O", "NEG", "2026-11-01T10:00:00Z");
const AB_FFP = unit(5, FFP, "AB", "POS", "2026-12-01T10:00:00Z");
const AB_RED = unit(6, RED, "AB", "POS", "2026-11-01T10:00:00Z");
const O_NEG_SOONER = unit(7, RED, "O", "NEG", "2026-10-30T10:00:00Z");

const pass = (code) => ({ code, status: "PASS", messageKey: "x", args: [] });
const allowed = {
  unitId: 1,
  allowed: true,
  warningReasonRequired: false,
  secondCheckMode: "REQUIRED",
  checks: [
    pass("UNIT_STATUS"),
    pass("UNIT_MATCH"),
    pass("EXPIRY"),
    pass("PATIENT_MATCH"),
    pass("EMERGENCY_PRODUCT"),
    pass("EMERGENCY_RHD"),
    {
      code: "SECOND_PERSON",
      status: "PENDING",
      messageKey: "bloodbank.check.secondPerson.required",
      args: [],
    },
  ],
};

const pendingIssue = {
  reservationId: 80,
  unitId: 1,
  donationNumber: "G123498000001",
  productCode: "E0291V00",
  productType: RED,
  abo: "O",
  rhd: "NEG",
  unitStatus: "ISSUED",
  issuedAt: "2026-10-27T14:00:00Z",
  requestingClinician: "Dr Example",
  clinicalReason: "Massive haemorrhage",
  rhdPositiveAcknowledged: false,
};

const withIntl = (ui, roles = ["Results"]) => (
  <IntlProvider locale="en" messages={messages}>
    <UserSessionDetailsContext.Provider
      value={{ userSessionDetails: { roles } }}
    >
      <MemoryRouter>{ui}</MemoryRouter>
    </UserSessionDetailsContext.Provider>
  </IntlProvider>
);

const type = (label, value) =>
  fireEvent.change(screen.getByLabelText(label), { target: { value } });

// Carbon adds a hidden "danger" to a danger button's name: match the label
const named = (label) =>
  new RegExp(label.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"));
const button = (label) => screen.getByRole("button", { name: named(label) });

beforeEach(() => {
  vi.clearAllMocks();
  BloodBankService.listUnits.mockImplementation(({ abo }) =>
    Promise.resolve(
      abo === "O"
        ? [O_NEG, O_POS, O_FFP, O_PLT, O_NEG_SOONER]
        : [AB_FFP, AB_RED],
    ),
  );
});

describe("emergency issue: the units offered", () => {
  it("are group O red cells and group AB plasma only", () => {
    expect(
      [O_NEG, O_POS, O_FFP, O_PLT, AB_FFP, AB_RED]
        .filter(emergencyEligible)
        .map((u) => u.id),
    ).toEqual([1, 2, 5]);
  });

  it("put O RhD negative first, each kind by earliest expiry", () => {
    expect(
      orderForEmergency([AB_FFP, O_POS, O_NEG, O_NEG_SOONER]).map((u) => u.id),
    ).toEqual([7, 1, 2, 5]);
  });
});

describe("EmergencyIssueModal", () => {
  const renderModal = (onIssued = vi.fn(), onClose = vi.fn()) =>
    render(
      withIntl(
        <EmergencyIssueModal
          open
          patientId={501}
          onClose={onClose}
          onIssued={onIssued}
        />,
      ),
    );

  it("lists only the units that may go out before crossmatch", async () => {
    renderModal();
    const select = await screen.findByLabelText(
      messages["bloodbank.emergency.unit"],
    );
    await waitFor(() =>
      expect(within(select).getAllByRole("option")).toHaveLength(5),
    );
    const texts = within(select)
      .getAllByRole("option")
      .map((o) => o.textContent);
    expect(texts[1]).toContain("G123498000007");
    expect(texts[2]).toContain("G123498000001");
    expect(texts[3]).toContain("G123498000002");
    expect(texts[4]).toContain("G123498000005");
    expect(texts.join(" ")).not.toContain("G123498000003");
    expect(texts.join(" ")).not.toContain("G123498000006");
  });

  it("asks for the O RhD positive acknowledgement before the checks and sends it", async () => {
    BloodBankService.emergencyCheck.mockResolvedValue(allowed);
    renderModal();
    await screen.findByText(/G123498000002/);

    // the scanned bag picks its unit
    type(messages["bloodbank.issueModal.scanUnit"], "=G123498000002 00");
    type(messages["bloodbank.issueModal.scanPatient"], "26-000123");
    expect(screen.getByTestId("bb-emergency-rhd-ack")).toBeInTheDocument();
    expect(button(messages["bloodbank.issueModal.runCheck"])).toBeDisabled();

    fireEvent.click(
      screen.getByLabelText(messages["bloodbank.emergency.rhdAck"]),
    );
    fireEvent.click(button(messages["bloodbank.issueModal.runCheck"]));

    await screen.findByTestId("bb-checklist");
    expect(BloodBankService.emergencyCheck).toHaveBeenCalledWith(
      2,
      expect.objectContaining({
        patientId: 501,
        patientIdentifier: "26-000123",
        unitScan: "=G123498000002 00",
        rhdPositiveAcknowledged: true,
      }),
    );
  });

  it("needs clinician, reason, ward, collector and the second check before it issues", async () => {
    BloodBankService.emergencyCheck.mockResolvedValue(allowed);
    BloodBankService.emergencyIssue.mockResolvedValue({
      unit: { ...O_NEG, status: "ISSUED" },
      events: [],
    });
    const onIssued = vi.fn();
    renderModal(onIssued);
    await screen.findByText(/G123498000001/);

    type(messages["bloodbank.issueModal.scanUnit"], "G123498000001");
    type(messages["bloodbank.issueModal.scanPatient"], "26-000123");
    // O RhD negative: no acknowledgement asked
    expect(screen.queryByTestId("bb-emergency-rhd-ack")).toBeNull();
    fireEvent.click(button(messages["bloodbank.issueModal.runCheck"]));
    await screen.findByLabelText(messages["bloodbank.emergency.clinician"]);

    const submit = button(messages["bloodbank.emergency.submit"]);
    type(messages["bloodbank.field.location"], "Emergency department");
    type(messages["bloodbank.field.collector"], "Porter Example");
    type(messages["bloodbank.issueModal.secondLogin"], "checker");
    type(messages["bloodbank.issueModal.secondPassword"], "secret");
    expect(submit).toBeDisabled();
    type(messages["bloodbank.emergency.clinician"], "Dr Example");
    expect(submit).toBeDisabled();
    type(messages["bloodbank.emergency.reason"], "Massive haemorrhage");
    expect(submit).toBeEnabled();

    fireEvent.click(submit);

    await waitFor(() => expect(onIssued).toHaveBeenCalled());
    expect(BloodBankService.emergencyIssue).toHaveBeenCalledWith(
      1,
      expect.objectContaining({
        patientId: 501,
        unitScan: "G123498000001",
        patientIdentifier: "26-000123",
        requestingClinician: "Dr Example",
        clinicalReason: "Massive haemorrhage",
        location: "Emergency department",
        collectorName: "Porter Example",
        secondCheckerLogin: "checker",
        secondCheckerPassword: "secret",
      }),
    );
  });

  it("shows the server's refusal and its failed check", async () => {
    BloodBankService.emergencyCheck.mockResolvedValue({
      ...allowed,
      allowed: false,
      checks: [
        {
          code: "EMERGENCY_RHD",
          status: "FAIL",
          messageKey:
            "bloodbank.emergency.check.rhd.positiveNeedsAcknowledgement",
          args: [],
        },
      ],
    });
    renderModal();
    await screen.findByText(/G123498000001/);
    type(messages["bloodbank.issueModal.scanUnit"], "G123498000001");
    type(messages["bloodbank.issueModal.scanPatient"], "26-000123");
    fireEvent.click(button(messages["bloodbank.issueModal.runCheck"]));

    expect(
      await screen.findByText(
        messages["bloodbank.emergency.check.rhd.positiveNeedsAcknowledgement"],
      ),
    ).toBeInTheDocument();
    expect(
      screen.queryByLabelText(messages["bloodbank.emergency.clinician"]),
    ).toBeNull();
    expect(button(messages["bloodbank.emergency.submit"])).toBeDisabled();
  });
});

describe("EmergencyIssuePanel", () => {
  it("shows the red banner for a pending unit and records an incompatible check", async () => {
    BloodBankService.retrospectiveCheck.mockResolvedValue({
      ...pendingIssue,
      retrospectiveResult: "INCOMPATIBLE",
    });
    const onRecorded = vi.fn();
    render(
      withIntl(
        <EmergencyIssuePanel
          issues={[pendingIssue]}
          canRecord
          onRecorded={onRecorded}
        />,
      ),
    );

    const banner = screen.getByTestId("bb-emergency-pending");
    expect(
      within(banner).getByText(messages["bloodbank.emergency.banner.title"]),
    ).toBeInTheDocument();
    expect(within(banner).getByText(/G123498000001/)).toBeInTheDocument();

    fireEvent.click(button(messages["bloodbank.retro.record"]));
    fireEvent.click(
      screen.getByLabelText(messages["bloodbank.retro.INCOMPATIBLE"]),
    );
    expect(
      screen.getByText(messages["bloodbank.retro.reaction"]),
    ).toBeInTheDocument();
    const submit = button(messages["bloodbank.retro.submit"]);
    expect(submit).toBeDisabled();
    type(messages["bloodbank.retro.note"], "Anti-K found on LAB-0002");
    fireEvent.click(submit);

    await waitFor(() => expect(onRecorded).toHaveBeenCalled());
    expect(BloodBankService.retrospectiveCheck).toHaveBeenCalledWith(
      80,
      "INCOMPATIBLE",
      "Anti-K found on LAB-0002",
    );
  });

  it("offers no record button to a user without Results or Validation", () => {
    render(
      withIntl(
        <EmergencyIssuePanel
          issues={[pendingIssue]}
          canRecord={false}
          onRecorded={vi.fn()}
        />,
      ),
    );
    expect(screen.getByTestId("bb-emergency-pending")).toBeInTheDocument();
    expect(
      screen.queryByRole("button", {
        name: named(messages["bloodbank.retro.record"]),
      }),
    ).toBeNull();
  });

  it("keeps a red warning for an incompatible unit and nothing for a compatible one", () => {
    const { container, rerender } = render(
      withIntl(
        <EmergencyIssuePanel
          issues={[
            {
              ...pendingIssue,
              retrospectiveResult: "INCOMPATIBLE",
              retrospectiveNote: "Anti-K",
              retrospectiveCheckedAt: "2026-10-27T16:00:00Z",
            },
          ]}
          canRecord
          onRecorded={vi.fn()}
        />,
      ),
    );
    expect(screen.queryByTestId("bb-emergency-pending")).toBeNull();
    const warning = screen.getByTestId("bb-emergency-incompatible");
    expect(
      within(warning).getByText(messages["bloodbank.retro.reaction"]),
    ).toBeInTheDocument();
    expect(within(warning).getByText(/Anti-K/)).toBeInTheDocument();

    rerender(
      withIntl(
        <EmergencyIssuePanel
          issues={[{ ...pendingIssue, retrospectiveResult: "COMPATIBLE" }]}
          canRecord
          onRecorded={vi.fn()}
        />,
      ),
    );
    expect(container).toBeEmptyDOMElement();
  });
});

describe("patient and issue page", () => {
  const profile = {
    group: {
      abo: null,
      rhd: null,
      confirmed: false,
      discrepancy: false,
      aboSampleCount: 0,
      rhdSampleCount: 0,
    },
    crossmatches: [],
    typings: [],
  };

  const openPatient = async (emergency) => {
    BloodBankService.productTypes.mockResolvedValue([]);
    BloodBankService.profile.mockResolvedValue(profile);
    BloodBankService.candidates.mockResolvedValue([]);
    BloodBankService.listUnits.mockResolvedValue([]);
    BloodBankService.emergencyIssues.mockResolvedValue(emergency);
    render(withIntl(<BloodBankPatientIssue />));
    fireEvent.click(screen.getByText("pick patient"));
    await waitFor(() =>
      expect(BloodBankService.emergencyIssues).toHaveBeenCalledWith(501),
    );
    await screen.findByTestId("bb-profile");
  };

  it("offers the danger Emergency issue button and shows the pending banner", async () => {
    await openPatient({
      enabled: true,
      secondCheckMode: "REQUIRED",
      issues: [pendingIssue],
    });
    const emergencyButton = button(messages["bloodbank.emergency.button"]);
    expect(emergencyButton.className).toContain("cds--btn--danger");
    expect(screen.getByTestId("bb-emergency-pending")).toBeInTheDocument();
  });

  it("hides the button when the site has turned emergency issue off", async () => {
    await openPatient({
      enabled: false,
      secondCheckMode: "REQUIRED",
      issues: [],
    });
    expect(
      screen.queryByRole("button", {
        name: named(messages["bloodbank.emergency.button"]),
      }),
    ).toBeNull();
    expect(screen.queryByTestId("bb-emergency-pending")).toBeNull();
  });
});

describe("the trail of an emergency issue", () => {
  const details =
    "EMERGENCY issue before group and crossmatch; requested by: Dr Example; clinical reason: Massive haemorrhage;" +
    " retrospective group and crossmatch pending; checks: UNIT_STATUS=PASS, EMERGENCY_PRODUCT=PASS; secondCheck: REQUIRED";

  it("splits the emergency text from the checks", () => {
    const split = splitEmergencyDetails(details);
    expect(split.text).toContain("requested by: Dr Example");
    expect(split.parsed.checks).toEqual([
      { code: "UNIT_STATUS", status: "PASS" },
      { code: "EMERGENCY_PRODUCT", status: "PASS" },
    ]);
    expect(splitEmergencyDetails("checks: ABO=PASS")).toBeNull();
  });

  it("shows the emergency text in red, and an incompatible check in red", () => {
    const { container } = render(withIntl(<DetailsText details={details} />));
    expect(container.querySelector(".bb-emergency-text")).toHaveTextContent(
      "EMERGENCY issue before group and crossmatch",
    );
    const incompatible = render(
      withIntl(
        <DetailsText details="retrospective group and crossmatch: INCOMPATIBLE; possible transfusion reaction: inform the clinician and follow the reaction procedure" />,
      ),
    );
    expect(
      incompatible.container.querySelector(".bb-emergency-text"),
    ).toBeInTheDocument();
  });
});
