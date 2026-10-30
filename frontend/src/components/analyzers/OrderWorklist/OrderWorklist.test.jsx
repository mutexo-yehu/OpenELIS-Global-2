import React from "react";
import { render, screen, within } from "@testing-library/react";
import { waitFor } from "@testing-library/dom";
import userEvent from "@testing-library/user-event";
import { IntlProvider } from "react-intl";
import { beforeEach, describe, expect, it, vi } from "vitest";

import messages from "../../../languages/en.json";
import {
  getFromOpenElisServer,
  postToOpenElisServerJsonResponse,
} from "../../utils/Utils";
import OrderWorklist from "./OrderWorklist";

vi.mock("../../utils/Utils", () => ({
  getFromOpenElisServer: vi.fn(),
  postToOpenElisServerJsonResponse: vi.fn(),
}));

const failed = {
  id: "9",
  accessionNumber: "DEV01260000000000130",
  analyzerId: "5",
  analyzerName: "GeneXpert (mock) - Molecular",
  tests: "COVID-19 PCR",
  loincCodes: ["94500-6"],
  status: "FAILED",
  attempts: 2,
  lastError: "HTTP 502: ASTM send failed",
  createdAt: "2026-10-30T12:00:00Z",
  sentAt: null,
};

const sent = {
  ...failed,
  id: "10",
  accessionNumber: "DEV01260000000000131",
  status: "SENT",
  attempts: 1,
  lastError: null,
  sentAt: "2026-10-30T12:00:01Z",
};

const respondWith = (rows, enabled = true) =>
  getFromOpenElisServer.mockImplementation((_url, callback) =>
    callback({
      status: "success",
      data: { enabled, count: rows.length, rows },
    }),
  );

const renderPage = () =>
  render(
    <IntlProvider locale="en" messages={messages}>
      <OrderWorklist />
    </IntlProvider>,
  );

const rowFor = (text) => screen.getByText(text).closest("tr");

describe("OrderWorklist", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("lists the orders with their status, and Retry only on a failed one", async () => {
    respondWith([failed, sent]);
    renderPage();

    const failedRow = await waitFor(() => rowFor("DEV01260000000000130"));
    expect(within(failedRow).getByText("Failed")).toBeTruthy();
    expect(
      within(failedRow).getByText("GeneXpert (mock) - Molecular"),
    ).toBeTruthy();
    expect(within(failedRow).getByText("COVID-19 PCR")).toBeTruthy();
    expect(
      within(failedRow).getByText("HTTP 502: ASTM send failed"),
    ).toBeTruthy();
    expect(
      within(failedRow).getByRole("button", { name: "Retry" }),
    ).toBeTruthy();

    const sentRow = rowFor("DEV01260000000000131");
    expect(within(sentRow).getByText("Sent")).toBeTruthy();
    expect(within(sentRow).queryByRole("button", { name: "Retry" })).toBeNull();
  });

  it("links each row to the results of its analyser", async () => {
    respondWith([failed, sent]);
    renderPage();

    const failedRow = await waitFor(() => rowFor("DEV01260000000000130"));
    expect(
      within(failedRow)
        .getByRole("link", { name: "View results" })
        .getAttribute("href"),
    ).toBe("/AnalyzerResults?id=5");
    const sentRow = rowFor("DEV01260000000000131");
    expect(
      within(sentRow)
        .getByRole("link", { name: "View results" })
        .getAttribute("href"),
    ).toBe("/AnalyzerResults?id=5");
  });

  it("retries a failed order and reloads the list", async () => {
    respondWith([failed]);
    postToOpenElisServerJsonResponse.mockImplementation(
      (_url, _body, callback) => callback({ status: "success", id: "9" }),
    );
    renderPage();

    const row = await waitFor(() => rowFor("DEV01260000000000130"));
    await userEvent.click(within(row).getByRole("button", { name: "Retry" }));

    expect(postToOpenElisServerJsonResponse).toHaveBeenCalledWith(
      "/rest/analyzer/worklist/9/retry",
      "{}",
      expect.any(Function),
    );
    expect(getFromOpenElisServer).toHaveBeenCalledTimes(2);
  });

  it("sends an order by lab number", async () => {
    respondWith([]);
    postToOpenElisServerJsonResponse.mockImplementation(
      (_url, _body, callback) => callback({ status: "success", queued: 1 }),
    );
    renderPage();

    await userEvent.type(
      screen.getByLabelText("Send an order"),
      "DEV01260000000000130",
    );
    await userEvent.click(screen.getByRole("button", { name: "Send" }));

    expect(postToOpenElisServerJsonResponse).toHaveBeenCalledWith(
      "/rest/analyzer/worklist/send?accessionNumber=DEV01260000000000130",
      "{}",
      expect.any(Function),
    );
    expect(
      await screen.findByText(
        "Order DEV01260000000000130 queued for its analyzers.",
      ),
    ).toBeTruthy();
  });

  it("shows the server's reason when nothing can be sent", async () => {
    respondWith([]);
    postToOpenElisServerJsonResponse.mockImplementation(
      (_url, _body, callback) =>
        callback({
          status: "error",
          messageKey: "analyzer.worklist.error.nothingToSend",
        }),
    );
    renderPage();

    await userEvent.type(screen.getByLabelText("Send an order"), "DEV1");
    await userEvent.click(screen.getByRole("button", { name: "Send" }));

    expect(
      await screen.findByText(
        "None of this order's tests is mapped to an analyzer that takes orders.",
      ),
    ).toBeTruthy();
  });

  it("warns when sending orders is turned off", async () => {
    respondWith([], false);
    renderPage();

    expect(
      await screen.findByText(
        "Sending orders to analyzers is turned off (setting analyzer.worklist.enabled).",
      ),
    ).toBeTruthy();
  });
});
