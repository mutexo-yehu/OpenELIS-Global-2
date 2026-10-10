import React from "react";
import { vi } from "vitest";
import { render, fireEvent } from "@testing-library/react";
import "@testing-library/jest-dom";
import { IntlProvider } from "react-intl";
import messages from "../../languages/en.json";
import CameraScanButton from "./CameraScanButton";
import { cameraScanSupported, startCameraScan } from "../utils/cameraBarcode";

vi.mock("../utils/cameraBarcode", () => ({
  cameraScanSupported: vi.fn(),
  startCameraScan: vi.fn(),
}));

const renderButton = (onDetected = vi.fn()) =>
  render(
    <IntlProvider locale="en" messages={messages}>
      <CameraScanButton id="labNo" onDetected={onDetected} />
    </IntlProvider>,
  );

describe("CameraScanButton", () => {
  afterEach(() => vi.clearAllMocks());

  it("renders nothing where the camera can't be used", () => {
    cameraScanSupported.mockReturnValue(false);
    const { queryByTestId } = renderButton();
    expect(queryByTestId("camera-scan-button")).toBeNull();
  });

  it("hands the scanned code over and closes", async () => {
    cameraScanSupported.mockReturnValue(true);
    const stop = vi.fn();
    startCameraScan.mockImplementation(async (video, onCode) => {
      setTimeout(() => onCode("DEV01260000000000061"), 0);
      return stop;
    });
    const onDetected = vi.fn();
    const { getByTestId, queryByTestId } = renderButton(onDetected);

    fireEvent.click(getByTestId("camera-scan-button"));
    expect(getByTestId("camera-scan-video")).toBeInTheDocument();

    await vi.waitFor(() =>
      expect(onDetected).toHaveBeenCalledWith("DEV01260000000000061"),
    );
    await vi.waitFor(() =>
      expect(queryByTestId("camera-scan-video")).toBeNull(),
    );
  });

  it("explains a refused camera", async () => {
    cameraScanSupported.mockReturnValue(true);
    startCameraScan.mockRejectedValue(
      Object.assign(new Error("denied"), { name: "NotAllowedError" }),
    );
    const { getByTestId, findByText } = renderButton();

    fireEvent.click(getByTestId("camera-scan-button"));

    expect(
      await findByText(messages["camera.scan.error.denied"]),
    ).toBeInTheDocument();
  });
});
