import React from "react";
import { fireEvent, render, screen } from "@testing-library/react";
import { waitFor } from "@testing-library/dom";
import "@testing-library/jest-dom";
import { IntlProvider } from "react-intl";
import IssueModal, { dinKey } from "./IssueModal";
import BloodBankService from "./BloodBankService";
import messages from "../../languages/en.json";

vi.mock("./BloodBankService", () => ({
  default: { issueCheck: vi.fn(), issue: vi.fn() },
}));

const unit = {
  id: 5,
  donationNumber: "G123498654321",
  productCode: "E0336V00",
  productType: { name: "Platelets", componentClass: "PLATELETS" },
  abo: "O",
  rhd: "POS",
};

const pass = (code) => ({
  code,
  status: "PASS",
  messageKey: "bloodbank.check.patient.ok",
  args: [],
});
const checksWith = (special) => [
  pass("UNIT_STATUS"),
  pass("EXPIRY"),
  pass("PATIENT_MATCH"),
  pass("GROUP_CONFIRMED"),
  pass("ABO"),
  special,
  pass("CROSSMATCH"),
  {
    code: "SECOND_PERSON",
    status: "PENDING",
    messageKey: "bloodbank.check.secondPerson.required",
    args: [],
  },
];

const renderModal = (onIssued = vi.fn()) =>
  render(
    <IntlProvider locale="en" messages={messages}>
      <IssueModal open unit={unit} onClose={vi.fn()} onIssued={onIssued} />
    </IntlProvider>,
  );

const type = (label, value) =>
  fireEvent.change(screen.getByLabelText(label), { target: { value } });

const scanAndCheck = async () => {
  type(messages["bloodbank.issueModal.scanUnit"], "=G123498654321 00");
  type(messages["bloodbank.issueModal.scanPatient"], "26-000123");
  fireEvent.click(
    screen.getByRole("button", {
      name: messages["bloodbank.issueModal.runCheck"],
    }),
  );
  await screen.findByTestId("bb-checklist");
};

const issueButton = () =>
  screen.getByRole("button", { name: messages["bloodbank.issueModal.submit"] });

const fillCollectionAndSecondCheck = () => {
  type(messages["bloodbank.field.location"], "Ward 3");
  type(messages["bloodbank.field.collector"], "Porter Example");
  type(messages["bloodbank.issueModal.secondLogin"], "checker");
  type(messages["bloodbank.issueModal.secondPassword"], "secret");
};

beforeEach(() => vi.clearAllMocks());

describe("IssueModal", () => {
  it("compares donation numbers without the leading = and the flags", () => {
    expect(dinKey("=G123498654321 00")).toBe("G123498654321");
    expect(dinKey("g123498654321")).toBe("G123498654321");
  });

  it("blocks the issue when a check fails, and shows which one", async () => {
    BloodBankService.issueCheck.mockResolvedValue({
      unitId: 5,
      allowed: false,
      warningReasonRequired: false,
      checks: checksWith({
        code: "RHD",
        status: "FAIL",
        messageKey: "bloodbank.check.rhd.positiveForNegative",
        args: ["POS", "NEG"],
      }),
    });
    renderModal();

    await scanAndCheck();

    expect(BloodBankService.issueCheck).toHaveBeenCalledWith(
      5,
      "26-000123",
      "=G123498654321 00",
    );
    expect(
      screen.getByText(messages["bloodbank.checks.blocked.title"]),
    ).toBeInTheDocument();
    expect(
      screen.getByText(messages["bloodbank.check.rhd.positiveForNegative"]),
    ).toBeInTheDocument();
    expect(issueButton()).toBeDisabled();
    // the collection and second-check fields are not offered at all
    expect(
      screen.queryByLabelText(messages["bloodbank.issueModal.secondPassword"]),
    ).toBeNull();
  });

  it("needs a reason on the warn path before it can issue, and sends it", async () => {
    BloodBankService.issueCheck.mockResolvedValue({
      unitId: 5,
      allowed: true,
      warningReasonRequired: true,
      checks: checksWith({
        code: "RHD",
        status: "WARN",
        messageKey: "bloodbank.check.rhd.positivePlateletsForNegative",
        args: ["POS", "NEG"],
      }),
    });
    BloodBankService.issue.mockResolvedValue({
      unit: { ...unit, status: "ISSUED" },
      events: [],
    });
    const onIssued = vi.fn();
    renderModal(onIssued);

    await scanAndCheck();
    fillCollectionAndSecondCheck();
    expect(issueButton()).toBeDisabled();

    type(
      messages["bloodbank.issueModal.warningReason"],
      "No RhD-negative platelets in stock",
    );
    expect(issueButton()).toBeEnabled();
    fireEvent.click(issueButton());

    await waitFor(() => expect(onIssued).toHaveBeenCalled());
    expect(BloodBankService.issue).toHaveBeenCalledWith(5, {
      unitScan: "=G123498654321 00",
      patientIdentifier: "26-000123",
      location: "Ward 3",
      collectorName: "Porter Example",
      secondCheckerLogin: "checker",
      secondCheckerPassword: "secret",
      secondCheckerOtp: "",
      warningReason: "No RhD-negative platelets in stock",
    });
  });

  it("shows a refused second check and clears the password", async () => {
    BloodBankService.issueCheck.mockResolvedValue({
      unitId: 5,
      allowed: true,
      warningReasonRequired: false,
      checks: checksWith(pass("RHD")),
    });
    const refused = new Error("refused");
    refused.errorCode = "bloodbank.issue.error.checksFailed";
    refused.params = [];
    refused.checks = checksWith(pass("RHD")).map((c) =>
      c.code === "SECOND_PERSON"
        ? {
            ...c,
            status: "FAIL",
            messageKey: "bloodbank.check.secondPerson.sameUser",
          }
        : c,
    );
    BloodBankService.issue.mockRejectedValue(refused);
    renderModal();

    await scanAndCheck();
    fillCollectionAndSecondCheck();
    fireEvent.click(issueButton());

    expect(
      await screen.findByText(
        messages["bloodbank.check.secondPerson.sameUser"],
      ),
    ).toBeInTheDocument();
    expect(
      screen.getByText(messages["bloodbank.issue.error.checksFailed"]),
    ).toBeInTheDocument();
    expect(
      screen.getByLabelText(messages["bloodbank.issueModal.secondPassword"]),
    ).toHaveValue("");
    expect(issueButton()).toBeDisabled();
  });

  it("lets one person issue with a reason when the second check is optional", async () => {
    BloodBankService.issueCheck.mockResolvedValue({
      unitId: 5,
      allowed: true,
      warningReasonRequired: true,
      secondCheckMode: "OPTIONAL_WITH_REASON",
      checks: checksWith(pass("RHD")).map((c) =>
        c.code === "SECOND_PERSON"
          ? {
              ...c,
              status: "WARN",
              messageKey: "bloodbank.check.secondPerson.singlePerson",
            }
          : c,
      ),
    });
    BloodBankService.issue.mockResolvedValue({
      unit: { ...unit, status: "ISSUED" },
      events: [],
    });
    const onIssued = vi.fn();
    renderModal(onIssued);

    await scanAndCheck();
    type(messages["bloodbank.field.location"], "Ward 3");
    type(messages["bloodbank.field.collector"], "Porter Example");
    expect(issueButton()).toBeDisabled();

    // half a second sign-in is neither one person nor two
    type(messages["bloodbank.issueModal.secondLogin"], "checker");
    expect(issueButton()).toBeDisabled();
    type(messages["bloodbank.issueModal.secondLogin"], "");

    type(
      messages["bloodbank.issueModal.singlePersonReason"],
      "Night shift, one scientist on duty",
    );
    expect(issueButton()).toBeEnabled();
    fireEvent.click(issueButton());

    await waitFor(() => expect(onIssued).toHaveBeenCalled());
    expect(BloodBankService.issue).toHaveBeenCalledWith(
      5,
      expect.objectContaining({
        secondCheckerLogin: "",
        secondCheckerPassword: "",
        warningReason: "Night shift, one scientist on duty",
      }),
    );
  });

  it("asks for no second sign-in when the collector confirms", async () => {
    BloodBankService.issueCheck.mockResolvedValue({
      unitId: 5,
      allowed: true,
      warningReasonRequired: false,
      secondCheckMode: "COLLECTOR_CONFIRMS",
      checks: checksWith(pass("RHD")).map((c) =>
        c.code === "SECOND_PERSON"
          ? {
              ...c,
              messageKey: "bloodbank.check.secondPerson.collectorPending",
            }
          : c,
      ),
    });
    BloodBankService.issue.mockResolvedValue({
      unit: { ...unit, status: "ISSUED" },
      events: [],
    });
    const onIssued = vi.fn();
    renderModal(onIssued);

    await scanAndCheck();

    expect(screen.getByTestId("bb-collector-confirms")).toBeInTheDocument();
    expect(
      screen.queryByLabelText(messages["bloodbank.issueModal.secondPassword"]),
    ).toBeNull();
    type(messages["bloodbank.field.location"], "Ward 3");
    expect(issueButton()).toBeDisabled();
    type(messages["bloodbank.field.collector"], "Porter Example");
    expect(issueButton()).toBeEnabled();
    fireEvent.click(issueButton());
    await waitFor(() => expect(onIssued).toHaveBeenCalled());
  });

  it("keeps the browser from filling in the second checker's sign-in", async () => {
    BloodBankService.issueCheck.mockResolvedValue({
      unitId: 5,
      allowed: true,
      warningReasonRequired: false,
      checks: checksWith(pass("RHD")),
    });
    renderModal();

    await scanAndCheck();

    expect(
      screen.getByLabelText(messages["bloodbank.issueModal.secondLogin"]),
    ).toHaveAttribute("autocomplete", "off");
    expect(
      screen.getByLabelText(messages["bloodbank.issueModal.secondPassword"]),
    ).toHaveAttribute("autocomplete", "new-password");
    expect(
      screen.getByLabelText(messages["bloodbank.issueModal.secondOtp"]),
    ).toHaveAttribute("autocomplete", "one-time-code");
  });

  it("explains a refused sign-in, naming the authenticator code", async () => {
    BloodBankService.issueCheck.mockResolvedValue({
      unitId: 5,
      allowed: true,
      warningReasonRequired: false,
      checks: checksWith(pass("RHD")),
    });
    const refused = new Error("refused");
    refused.errorCode = "bloodbank.issue.error.checksFailed";
    refused.params = [];
    refused.checks = checksWith(pass("RHD")).map((c) =>
      c.code === "SECOND_PERSON"
        ? {
            ...c,
            status: "FAIL",
            messageKey: "bloodbank.check.secondPerson.invalid",
          }
        : c,
    );
    BloodBankService.issue.mockRejectedValue(refused);
    renderModal();

    await scanAndCheck();
    fillCollectionAndSecondCheck();
    fireEvent.click(issueButton());

    expect(
      await screen.findByText(
        /^Login, password or authenticator code not accepted\. .*every 30 seconds\)\.$/,
      ),
    ).toBeInTheDocument();
  });

  it("still needs the second sign-in when it is required", async () => {
    BloodBankService.issueCheck.mockResolvedValue({
      unitId: 5,
      allowed: true,
      warningReasonRequired: false,
      secondCheckMode: "REQUIRED",
      checks: checksWith(pass("RHD")),
    });
    renderModal();

    await scanAndCheck();
    type(messages["bloodbank.field.location"], "Ward 3");
    type(messages["bloodbank.field.collector"], "Porter Example");
    expect(issueButton()).toBeDisabled();
    expect(
      screen.queryByLabelText(messages["bloodbank.issueModal.warningReason"]),
    ).toBeNull();
    type(messages["bloodbank.issueModal.secondLogin"], "checker");
    type(messages["bloodbank.issueModal.secondPassword"], "secret");
    expect(issueButton()).toBeEnabled();
  });
});
