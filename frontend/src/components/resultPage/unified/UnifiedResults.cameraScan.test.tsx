import React from "react";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import "@testing-library/jest-dom";
import { IntlProvider } from "react-intl";
import messages from "../../../languages/en.json";

/**
 * Camera scan on the unified worklist's lab-number search: a phone or tablet
 * without a barcode scanner reads the label with its camera, and the read acts
 * like a scanner's Enter, filling the search box and loading that sample's
 * results.
 */

const { utilsMock } = vi.hoisted(() => ({
  utilsMock: {
    getFromOpenElisServer: vi.fn(),
    postToOpenElisServerJsonResponse: vi.fn(),
    postToOpenElisServerFormData: vi.fn(),
    deleteFromOpenElisServer: vi.fn(),
  },
}));

vi.mock("../../utils/Utils", () => utilsMock);

vi.mock("../../layout/Layout", () => ({
  NotificationContext: React.createContext({
    notificationVisible: false,
    setNotificationVisible: vi.fn(),
    addNotification: vi.fn(),
  }),
  ConfigurationContext: React.createContext({
    configurationProperties: { allowResultRejection: "false" },
  }),
}));

vi.mock("../../common/CustomNotification", () => ({
  AlertDialog: () => <div />,
  NotificationKinds: { success: "success", error: "error", warning: "warning" },
}));

vi.mock("../../esignature/ESignatureButton", () => ({
  __esModule: true,
  default: ({ children }: any) => <button type="button">{children}</button>,
  SignatureMeaning: { AUTHORED: "AUTHORED", MODIFIED: "MODIFIED" },
}));

vi.mock("../../common/PageBreadCrumb", () => ({ default: () => <div /> }));

const SCANNED = "DEV01260000000000061";

// the real button needs a camera; this one "reads" a fixed label
vi.mock("../../common/CameraScanButton", () => ({
  default: ({ onDetected, disabled }: any) => (
    <button
      type="button"
      data-testid="fake-camera"
      disabled={disabled}
      onClick={() => onDetected(SCANNED)}
    />
  ),
}));

const PATIENT = {
  patientPK: "123",
  firstName: "Ada",
  lastName: "Lovelace",
  subjectNumber: "S-1",
};

vi.mock("../../patient/SearchPatientForm", () => ({
  __esModule: true,
  default: ({ getSelectedPatient }: any) => (
    <button
      type="button"
      data-testid="mock-pick-patient"
      onClick={() => getSelectedPatient(PATIENT)}
    >
      pick
    </button>
  ),
}));

import UnifiedResults from "./UnifiedResults";

const renderPage = () => {
  utilsMock.getFromOpenElisServer.mockImplementation(
    (endPoint: string, callback: any) => {
      if (endPoint.startsWith("/rest/LogbookResults"))
        return callback({ testResult: [] });
      if (endPoint.startsWith("/rest/patient-details?patientID=123"))
        return callback(PATIENT);
      return callback([]);
    },
  );
  render(
    <IntlProvider locale="en" messages={messages}>
      <UnifiedResults />
    </IntlProvider>,
  );
};

const worklistCalls = () =>
  utilsMock.getFromOpenElisServer.mock.calls
    .map((c: any[]) => String(c[0]))
    .filter((url) => url.startsWith("/rest/LogbookResults"));

describe("Camera scan on the unified worklist search", () => {
  beforeEach(() => {
    cleanup();
    utilsMock.getFromOpenElisServer.mockReset();
    window.localStorage.clear();
    window.history.replaceState(null, "", "/Results");
  });

  it("fills the search box with the read lab number and loads its results", () => {
    renderPage();
    const before = worklistCalls().length;

    fireEvent.click(screen.getByTestId("fake-camera"));

    expect(document.getElementById("unifiedResultsSearch")).toHaveValue(
      SCANNED,
    );
    const calls = worklistCalls();
    expect(calls.length).toBe(before + 1);
    const params = new URLSearchParams(calls[calls.length - 1].split("?")[1]);
    expect(params.get("labNumber")).toBe(SCANNED);
    expect(window.location.search).toContain("accessionNumber=" + SCANNED);
  });

  it("is disabled, like the search box, while a patient is selected", () => {
    renderPage();
    expect(screen.getByTestId("fake-camera")).toBeEnabled();

    fireEvent.click(screen.getByTestId("search-by-patient"));
    fireEvent.click(screen.getByTestId("mock-pick-patient"));

    expect(screen.getByTestId("fake-camera")).toBeDisabled();
  });
});
