import React from "react";
import { fireEvent, render, screen } from "@testing-library/react";
import { waitFor } from "@testing-library/dom";
import "@testing-library/jest-dom";
import { IntlProvider } from "react-intl";
import { MemoryRouter } from "react-router-dom";
import UserSessionDetailsContext from "../../UserSessionDetailsContext";
import {
  BloodBankIssuePage,
  BloodBankReceivePage,
  BloodBankStockPage,
  BloodBankTracePage,
} from "./BloodBankPages";
import { ProfilePanel } from "./BloodBankPatientIssue";
import BloodBankService from "./BloodBankService";
import messages from "../../languages/en.json";

vi.mock("../patient/SearchPatientForm", () => ({
  default: () => <div data-testid="patient-search" />,
}));

vi.mock("./BloodBankService", () => ({
  default: {
    productTypes: vi.fn(),
    listUnits: vi.fn(),
    summary: vi.fn(),
    trace: vi.fn(),
    traceExportUrl: vi.fn(),
  },
}));

const unit = (over) => ({
  id: 1,
  donationNumber: "G123498654321",
  productCode: "E0336V00",
  productType: { id: 1, name: "Red cells", componentClass: "RED_CELLS" },
  abo: "O",
  rhd: "NEG",
  expiresAt: "2026-11-02T03:59:00Z",
  status: "AVAILABLE",
  expired: false,
  expiringSoon: false,
  ...over,
});

const renderPage = (Page, path = "/") =>
  render(
    <IntlProvider locale="en" messages={messages}>
      <UserSessionDetailsContext.Provider
        value={{ userSessionDetails: { roles: ["Results"] } }}
      >
        <MemoryRouter initialEntries={[path]}>
          <Page />
        </MemoryRouter>
      </UserSessionDetailsContext.Provider>
    </IntlProvider>,
  );

beforeEach(() => {
  vi.clearAllMocks();
  BloodBankService.productTypes.mockResolvedValue([
    { id: 1, name: "Red cells", componentClass: "RED_CELLS" },
  ]);
  BloodBankService.listUnits.mockResolvedValue([
    unit({ id: 1, donationNumber: "G123498654321", expiringSoon: true }),
    unit({ id: 2, donationNumber: "G123498654322", expired: true }),
  ]);
  BloodBankService.summary.mockResolvedValue({
    rows: [
      {
        productType: { id: 1, name: "Red cells" },
        abo: "O",
        rhd: "NEG",
        count: 2,
      },
    ],
    expiringSoon: 1,
    expired: 1,
    expiryWarnHours: 48,
  });
});

describe("blood bank pages", () => {
  it("stock lists units with their expiry warnings", async () => {
    renderPage(BloodBankStockPage);
    expect(await screen.findByText("G123498654321")).toBeInTheDocument();
    expect(
      screen.getByText(messages["bloodbank.stock.tag.expiringSoon"]),
    ).toBeInTheDocument();
    expect(
      screen.getAllByText(messages["bloodbank.stock.tag.expired"]).length,
    ).toBeGreaterThan(0);
    expect(BloodBankService.listUnits).toHaveBeenCalledWith(
      expect.objectContaining({
        status: ["AVAILABLE", "RESERVED", "QUARANTINED"],
      }),
    );
  });

  it("receive opens with the scan field focused", () => {
    renderPage(BloodBankReceivePage);
    expect(
      screen.getByLabelText(messages["bloodbank.receive.scan.label"]),
    ).toHaveFocus();
  });

  it("patient and issue starts with the patient search", () => {
    renderPage(BloodBankIssuePage);
    expect(screen.getByTestId("patient-search")).toBeInTheDocument();
  });

  it("traceability shows the unit trail linked from the stock page", async () => {
    BloodBankService.trace.mockResolvedValue([
      {
        id: 10,
        unitId: 1,
        donationNumber: "G123498654321",
        eventType: "ISSUED",
        performedByName: "Example Issuer",
        secondCheckerName: "Example Checker",
        occurredAt: "2026-11-01T10:00:00Z",
        location: "Ward 3",
        reason: "No RhD-negative platelets in stock",
      },
    ]);
    renderPage(BloodBankTracePage, "/bloodbank/trace?unit=1");
    expect(await screen.findByText("Example Checker")).toBeInTheDocument();
    expect(BloodBankService.trace).toHaveBeenCalledWith({ unit: "1" });
    expect(
      screen.getByText(messages["bloodbank.event.ISSUED"]),
    ).toBeInTheDocument();
  });

  it("stock survives a filter and a tab change that replace every row", async () => {
    renderPage(BloodBankStockPage);
    expect(await screen.findByText("G123498654321")).toBeInTheDocument();

    // the filtered list shares no unit with the one on screen
    BloodBankService.listUnits.mockResolvedValue([
      unit({ id: 3, donationNumber: "G123498654323", abo: "A", rhd: "POS" }),
    ]);
    fireEvent.change(screen.getByLabelText(messages["bloodbank.field.abo"]), {
      target: { value: "A" },
    });
    expect(await screen.findByText("G123498654323")).toBeInTheDocument();
    expect(screen.queryByText("G123498654321")).toBeNull();

    BloodBankService.listUnits.mockResolvedValue([
      unit({ id: 4, donationNumber: "G123498654324", status: "ISSUED" }),
    ]);
    fireEvent.click(
      screen.getByRole("tab", {
        name: messages["bloodbank.stock.tab.issued"],
      }),
    );
    expect(await screen.findByText("G123498654324")).toBeInTheDocument();
    await waitFor(() =>
      expect(BloodBankService.listUnits).toHaveBeenLastCalledWith(
        expect.objectContaining({ status: ["ISSUED"], abo: "A" }),
      ),
    );
    expect(screen.queryByText("G123498654323")).toBeNull();
  });

  it("the trail names the patient and reads the issue checks", async () => {
    BloodBankService.trace.mockResolvedValue([
      {
        id: 11,
        unitId: 1,
        donationNumber: "G123498654321",
        eventType: "RESERVED",
        patientId: 63,
        patientName: "Example Patient",
        patientNationalId: "DEMO-0001",
        labNumber: "26-000123",
        performedByName: "Example Issuer",
        occurredAt: "2026-11-01T09:00:00Z",
        details:
          "crossmatch result 900 (26-000123), valid until 2026-11-03T10:00:00Z",
      },
      {
        id: 12,
        unitId: 1,
        donationNumber: "G123498654321",
        eventType: "ISSUED",
        patientId: 63,
        patientName: "Example Patient",
        patientNationalId: "DEMO-0001",
        performedByName: "Example Issuer",
        occurredAt: "2026-11-01T10:00:00Z",
        details:
          "checks: UNIT_STATUS=PASS, UNIT_MATCH=PASS, EXPIRY=PASS; secondCheck: REQUIRED",
      },
    ]);
    renderPage(BloodBankTracePage, "/bloodbank/trace?patient=63");

    expect(
      await screen.findByText(
        messages["bloodbank.trace.details.allPassed"].replace("{count}", "3"),
      ),
    ).toBeInTheDocument();
    expect(
      screen.getAllByText(/Example Patient · ID DEMO-0001/).length,
    ).toBeGreaterThan(0);
    expect(screen.queryByText("#63")).toBeNull();
    expect(screen.queryByText(/UNIT_MATCH=PASS/)).toBeNull();
    expect(
      screen.getByText(/^Crossmatch 26-000123, valid until /),
    ).not.toHaveTextContent("Z");
  });

  it("a discrepancy shows each sample's result, not one group", () => {
    render(
      <IntlProvider locale="en" messages={messages}>
        <ProfilePanel
          profile={{
            group: {
              abo: "B",
              rhd: "POS",
              confirmed: false,
              discrepancy: true,
              aboSampleCount: 2,
              rhdSampleCount: 2,
            },
            typings: [
              {
                kind: "ABO",
                sampleId: 1,
                accessionNumber: "26-0001",
                collectedAt: "2026-10-26T10:00:00Z",
                value: "A",
              },
              {
                kind: "RHD",
                sampleId: 1,
                accessionNumber: "26-0001",
                collectedAt: "2026-10-26T10:00:00Z",
                value: "Positive",
              },
              {
                kind: "ABO",
                sampleId: 2,
                accessionNumber: "26-0002",
                collectedAt: "2026-10-27T10:00:00Z",
                value: "B",
              },
              {
                kind: "RHD",
                sampleId: 2,
                accessionNumber: "26-0002",
                collectedAt: "2026-10-27T10:00:00Z",
                value: "Positive",
              },
            ],
            crossmatches: [],
            notConfigured: [],
          }}
        />
      </IntlProvider>,
    );

    expect(
      screen.getByText(messages["bloodbank.profile.discrepancy"]),
    ).toBeInTheDocument();
    expect(screen.queryByText("B RhD pos")).toBeNull();
    expect(
      screen.getByText(/^26-0001 .*ABO A, RhD Positive$/),
    ).toBeInTheDocument();
    expect(
      screen.getByText(/^26-0002 .*ABO B, RhD Positive$/),
    ).toBeInTheDocument();
  });
});
