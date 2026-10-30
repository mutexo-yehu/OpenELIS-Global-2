import React from "react";
import { render, screen, within } from "@testing-library/react";
import { waitFor } from "@testing-library/dom";
import { IntlProvider } from "react-intl";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

import messages from "../../../languages/en.json";
import { getFromOpenElisServer } from "../../utils/Utils";
import AnalyzerResultsIndex from "./AnalyzerResultsIndex";

vi.mock("../../utils/Utils", () => ({
  getFromOpenElisServer: vi.fn(),
}));

const respondWith = (response) =>
  getFromOpenElisServer.mockImplementation((_url, callback) =>
    callback(response),
  );

const renderPage = () =>
  render(
    <IntlProvider locale="en" messages={messages}>
      <MemoryRouter>
        <AnalyzerResultsIndex />
      </MemoryRouter>
    </IntlProvider>,
  );

describe("AnalyzerResultsIndex", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("lists each analyser with a View results link to its results", async () => {
    respondWith({
      status: "success",
      data: [
        { id: "5", name: "GeneXpert (mock) - Molecular" },
        { id: "7", name: "Mindray BC-5380" },
      ],
    });
    renderPage();

    expect(
      await screen.findByRole("heading", { name: "Analyser results" }),
    ).toBeTruthy();
    expect(getFromOpenElisServer).toHaveBeenCalledWith(
      "/rest/analyzer/worklist/analyzers",
      expect.any(Function),
    );

    const geneXpert = screen
      .getByText("GeneXpert (mock) - Molecular")
      .closest("tr");
    expect(
      within(geneXpert)
        .getByRole("link", { name: "View results" })
        .getAttribute("href"),
    ).toBe("/AnalyzerResults?id=5");
    const mindray = screen.getByText("Mindray BC-5380").closest("tr");
    expect(
      within(mindray)
        .getByRole("link", { name: "View results" })
        .getAttribute("href"),
    ).toBe("/AnalyzerResults?id=7");
  });

  it("says so when there are no active analysers", async () => {
    respondWith({ status: "success", data: [] });
    renderPage();

    expect(
      await screen.findByText("There are no active analysers."),
    ).toBeTruthy();
    expect(screen.queryByRole("link", { name: "View results" })).toBeNull();
  });

  it("says so when the analysers could not be loaded", async () => {
    respondWith({ status: "error" });
    renderPage();

    await waitFor(() =>
      expect(
        screen.getByText("The analysers could not be loaded."),
      ).toBeTruthy(),
    );
  });
});
