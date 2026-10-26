import React from "react";
import { render, screen } from "@testing-library/react";
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
});
