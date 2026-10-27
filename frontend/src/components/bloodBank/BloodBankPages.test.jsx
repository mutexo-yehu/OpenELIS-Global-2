import React from "react";
import { fireEvent, render, screen } from "@testing-library/react";
import { waitFor } from "@testing-library/dom";
import "@testing-library/jest-dom";
import { createIntl, IntlProvider } from "react-intl";
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
import { lineErrorsText } from "./bloodBankFormat";
import messages from "../../languages/en.json";

vi.mock("../patient/SearchPatientForm", () => ({
  default: () => <div data-testid="patient-search" />,
}));

vi.mock("./BloodBankService", () => ({
  default: {
    productTypes: vi.fn(),
    productCodes: vi.fn(),
    parseScans: vi.fn(),
    receive: vi.fn(),
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
  BloodBankService.productCodes.mockResolvedValue([
    {
      id: 1,
      isbtCodePrefix: "E0291",
      description: "Red cells (example)",
      active: true,
      productType: { id: 1, name: "Red cells", active: true },
    },
    {
      id: 2,
      isbtCodePrefix: "E0869",
      active: false,
      productType: { id: 2, name: "Plasma", active: true },
    },
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

  it("stock tags a unit that went out as an emergency issue", async () => {
    BloodBankService.listUnits.mockResolvedValue([
      unit({
        id: 7,
        donationNumber: "G123498654327",
        status: "ISSUED",
        issuedAt: "2026-10-27T13:00:00Z",
        returnWindowMinutes: 30,
        emergency: true,
      }),
      unit({ id: 8, donationNumber: "G123498654328", status: "ISSUED" }),
    ]);
    renderPage(BloodBankStockPage);
    expect(await screen.findByText("G123498654327")).toBeInTheDocument();
    const tags = screen.getAllByTestId("bb-emergency-tag");
    expect(tags).toHaveLength(1);
    expect(tags[0]).toHaveTextContent(messages["bloodbank.emergency.tag"]);
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

  it("receive asks for every required field, by its label, before adding a line", async () => {
    renderPage(BloodBankReceivePage);
    fireEvent.change(screen.getByLabelText(messages["bloodbank.field.din"]), {
      target: { value: "X999926100002" },
    });
    fireEvent.click(
      screen.getByRole("button", {
        name: messages["bloodbank.receive.addLine"],
      }),
    );

    expect(
      await screen.findByText(
        "Still needed for this line: Product code, Blood group, Expires",
      ),
    ).toBeInTheDocument();
    expect(
      screen.getByText(messages["bloodbank.receive.pending.empty"]),
    ).toBeInTheDocument();
    expect(BloodBankService.receive).not.toHaveBeenCalled();
  });

  it("receive fills the group and product lists from scans and shows a typed expiry as local time", async () => {
    BloodBankService.parseScans.mockResolvedValue({
      donationNumber: "X999926100002",
      abo: "A",
      rhd: "POS",
      productCode: "E0291V01",
      productType: { id: 1, name: "Red cells" },
      errors: [],
    });
    renderPage(BloodBankReceivePage);
    const groupList = screen.getByLabelText(messages["bloodbank.field.group"]);
    const productList = screen.getByLabelText(
      messages["bloodbank.field.productCode"],
    );
    // the eight ABO/RhD groups after "Choose"
    expect(groupList.querySelectorAll("option")).toHaveLength(9);
    // only the active configured codes, with their names
    await waitFor(() =>
      expect(productList.querySelectorAll("option")).toHaveLength(2),
    );
    expect(productList).toHaveTextContent("E0291 · Red cells");

    const scan = screen.getByLabelText(
      messages["bloodbank.receive.scan.label"],
    );
    fireEvent.change(scan, { target: { value: "=%6200" } });
    fireEvent.keyDown(scan, { key: "Enter" });

    await waitFor(() => expect(groupList).toHaveValue("A POS"));
    expect(productList).toHaveValue("E0291");
    expect(
      screen.getByLabelText(messages["bloodbank.receive.productSuffix"]),
    ).toHaveValue("V01");

    fireEvent.change(
      screen.getByLabelText(messages["bloodbank.field.expiresAt"]),
      {
        target: { value: "2026-11-27T03:01" },
      },
    );
    fireEvent.click(
      screen.getByRole("button", {
        name: messages["bloodbank.receive.addLine"],
      }),
    );

    const table = screen.getByTestId("bb-receive-lines");
    expect(table).toHaveTextContent("E0291V01");
    expect(table).toHaveTextContent("A RhD pos");
    expect(table).toHaveTextContent("11/27/2026");
    expect(table).not.toHaveTextContent("T03:01");
  });

  it("the scan hint does not tell the user to press Enter", () => {
    renderPage(BloodBankReceivePage);
    expect(
      screen.getByText(messages["bloodbank.receive.scan.helper"]),
    ).toBeInTheDocument();
    expect(messages["bloodbank.receive.scan.helper"]).not.toMatch(/Enter/);
  });

  it("a refused line names the missing fields by their labels", () => {
    const intl = createIntl({ locale: "en", messages });
    expect(
      lineErrorsText(intl, [
        { code: "bloodbank.receive.error.missingField", args: ["productCode"] },
        { code: "bloodbank.receive.error.missingField", args: ["bloodGroup"] },
        { code: "bloodbank.receive.error.missingField", args: ["expiresAt"] },
      ]),
    ).toBe("Missing: Product code, Blood group, Expires");
    expect(
      lineErrorsText(intl, [
        {
          code: "bloodbank.receive.error.groupConflict",
          args: ["X999926100002", "O", "NEG"],
        },
      ]),
    ).toBe("Donation X999926100002 already received as O RhD neg");
  });

  it("stock shows 25 units a page", async () => {
    BloodBankService.listUnits.mockResolvedValue(
      Array.from({ length: 30 }, (_, i) =>
        unit({
          id: 100 + i,
          donationNumber: `W00002600${String(100 + i)}`,
        }),
      ),
    );
    renderPage(BloodBankStockPage);

    expect(await screen.findByText("W00002600100")).toBeInTheDocument();
    expect(screen.getAllByText(/^W00002600\d{3}$/)).toHaveLength(25);
    expect(screen.queryByText("W00002600125")).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: /next page/i }));

    expect(await screen.findByText("W00002600125")).toBeInTheDocument();
    expect(screen.getAllByText(/^W00002600\d{3}$/)).toHaveLength(5);
  });

  it("the return action says what a return will do at this time", async () => {
    const minutesAgo = (minutes) =>
      new Date(Date.now() - (minutes * 60 + 10) * 1000).toISOString();
    BloodBankService.listUnits.mockResolvedValue([
      unit({
        id: 7,
        donationNumber: "G123498654327",
        status: "ISSUED",
        issuedAt: minutesAgo(107),
        returnWindowMinutes: 30,
      }),
      unit({
        id: 8,
        donationNumber: "G123498654328",
        status: "ISSUED",
        issuedAt: minutesAgo(5),
        returnWindowMinutes: 30,
      }),
    ]);
    renderPage(BloodBankStockPage);
    await screen.findByText("G123498654327");
    const menus = () =>
      screen.getAllByRole("button", {
        name: messages["bloodbank.action.menu"],
      });

    fireEvent.click(menus()[0]);
    fireEvent.click(
      await screen.findByText("Return and discard (past the 30 min window)"),
    );
    expect(
      await screen.findByText("Return and discard unit G123498654327"),
    ).toBeInTheDocument();
    expect(
      screen.getByText(
        "Unit G123498654327 was issued 1 h 47 min ago: it will be discarded (return window 30 min).",
      ),
    ).toBeInTheDocument();
    // the button says it too (Carbon keeps the closing modal out of the
    // accessibility tree in jsdom, so look for its text)
    expect(
      screen.getByText(messages["bloodbank.return.submitLate"], {
        selector: "button",
      }),
    ).toBeInTheDocument();

    fireEvent.click(menus()[1]);
    expect(
      await screen.findByText(messages["bloodbank.action.return"]),
    ).toBeInTheDocument();
    expect(
      screen.queryByText("Return and discard (past the 30 min window)"),
    ).toBeNull();
  });

  it("the trail is not shut in a scroll box of its own", async () => {
    BloodBankService.trace.mockResolvedValue([
      {
        id: 10,
        unitId: 1,
        donationNumber: "G123498654321",
        eventType: "ISSUED",
        occurredAt: "2026-11-01T10:00:00Z",
        details: "checks: UNIT_STATUS=PASS, EXPIRY=PASS; secondCheck: REQUIRED",
      },
    ]);
    renderPage(BloodBankTracePage, "/bloodbank/trace?unit=1");
    const trail = await screen.findByTestId("bb-trail");
    expect(trail.closest(".bb-trail-container")).not.toBeNull();
  });
});
