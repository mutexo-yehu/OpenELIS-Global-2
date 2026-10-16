import React from "react";
import { render, screen } from "@testing-library/react";
import { waitFor } from "@testing-library/dom";
import userEvent from "@testing-library/user-event";
import "@testing-library/jest-dom";
import { IntlProvider } from "react-intl";
import { vi } from "vitest";
import messages from "../../languages/en.json";
import UserSessionDetailsContext from "../../UserSessionDetailsContext";

const api = vi.hoisted(() => ({
  isEsigEnabled: vi.fn(),
  isUserCertified: vi.fn(),
  getSessionStatus: vi.fn(),
  executeSignature: vi.fn(),
  certifyUser: vi.fn(),
}));

vi.mock("./api", async (importOriginal) => ({
  ...(await importOriginal()),
  ...api,
}));

import ESignatureModal from "./ESignatureModal";

const renderModal = () =>
  render(
    <IntlProvider locale="en" messages={messages}>
      <UserSessionDetailsContext.Provider
        value={{ userSessionDetails: { loginName: "sci1" } }}
      >
        <ESignatureModal
          open
          onClose={vi.fn()}
          onSuccess={vi.fn()}
          meaning="VALIDATED_AND_RELEASED"
          recordType="RESULT"
          recordId={42}
        />
      </UserSessionDetailsContext.Provider>
    </IntlProvider>,
  );

beforeEach(() => {
  Object.values(api).forEach((fn) => fn.mockReset());
  api.isUserCertified.mockResolvedValue({ certified: true });
  api.getSessionStatus.mockResolvedValue({
    sessionActive: false,
    signingCount: 0,
  });
  api.executeSignature.mockResolvedValue({ id: 1 });
});

describe("signing with single sign-on (Keycloak checks the signature)", () => {
  beforeEach(() => {
    api.isEsigEnabled.mockResolvedValue({ enabled: true, otpRequired: true });
  });

  it("asks for the authenticator code and sends it with the password", async () => {
    renderModal();
    await userEvent.type(
      await screen.findByLabelText("Password"),
      "correct horse",
    );
    await userEvent.type(screen.getByLabelText("Authenticator code"), "123456");
    await userEvent.click(screen.getByRole("button", { name: /sign/i }));

    await waitFor(() => expect(api.executeSignature).toHaveBeenCalled());
    expect(api.executeSignature.mock.calls[0][0]).toMatchObject({
      username: "sci1",
      password: "correct horse",
      otp: "123456",
      recordId: 42,
    });
  });

  it("refuses to sign without the code", async () => {
    renderModal();
    await userEvent.type(await screen.findByLabelText("Password"), "pw");
    await userEvent.click(screen.getByRole("button", { name: /sign/i }));

    expect(
      await screen.findByText(
        "The code from your authenticator app is required",
      ),
    ).toBeInTheDocument();
    expect(api.executeSignature).not.toHaveBeenCalled();
  });
});

describe("signing with a local account", () => {
  it("shows no code field and sends no code", async () => {
    api.isEsigEnabled.mockResolvedValue({ enabled: true, otpRequired: false });
    renderModal();
    await userEvent.type(await screen.findByLabelText("Password"), "pw");

    expect(screen.queryByLabelText("Authenticator code")).toBeNull();
    await userEvent.click(screen.getByRole("button", { name: /sign/i }));
    await waitFor(() => expect(api.executeSignature).toHaveBeenCalled());
    expect(api.executeSignature.mock.calls[0][0].otp).toBeUndefined();
  });
});
