/**
 * A camera scan into a lab-number field behaves like a barcode scanner: the
 * value is filled in, then the form's default button is pressed (or Enter is
 * sent where the form has none).
 */
import React from "react";
import { vi } from "vitest";
import { render, fireEvent } from "@testing-library/react";
import "@testing-library/jest-dom";
import { IntlProvider } from "react-intl";
import messages from "../../languages/en.json";
import CustomLabNumberInput from "./CustomLabNumberInput";
import { ConfigurationContext } from "../layout/Layout";

vi.mock("./CameraScanButton", () => ({
  default: ({ onDetected }) => (
    <button
      type="button"
      data-testid="fake-camera"
      onClick={() => onDetected("DEV01260000000000061")}
    />
  ),
}));

const renderField = (props, { withSubmit = true, onSubmit, onKeyDown } = {}) =>
  render(
    <ConfigurationContext.Provider
      value={{ configurationProperties: { AccessionFormat: "ALPHANUM" } }}
    >
      <IntlProvider locale="en" messages={messages}>
        <form
          onSubmit={(e) => {
            e.preventDefault();
            onSubmit && onSubmit();
          }}
          onKeyDown={onKeyDown}
        >
          <CustomLabNumberInput
            id="accessionNumber"
            name="accessionNumber"
            labelText="Lab number"
            value=""
            {...props}
          />
          {withSubmit && <button type="submit">Search</button>}
        </form>
      </IntlProvider>
    </ConfigurationContext.Provider>,
  );

describe("CustomLabNumberInput camera scan", () => {
  it("fills the value and presses the form's search button", async () => {
    const onChange = vi.fn();
    const onSubmit = vi.fn();
    const { getByTestId } = renderField({ onChange }, { onSubmit });

    fireEvent.click(getByTestId("fake-camera"));

    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({
        target: expect.objectContaining({ value: "DEV01260000000000061" }),
      }),
      "DEV01260000000000061",
    );
    await vi.waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
  });

  it("sends Enter when the form has no submit button", async () => {
    const onKeyDown = vi.fn();
    const { getByTestId } = renderField(
      { onChange: vi.fn() },
      { withSubmit: false, onKeyDown },
    );

    fireEvent.click(getByTestId("fake-camera"));

    await vi.waitFor(() =>
      expect(onKeyDown).toHaveBeenCalledWith(
        expect.objectContaining({ key: "Enter" }),
      ),
    );
  });

  it("hands the code to onScan instead, when given", async () => {
    const onScan = vi.fn();
    const onSubmit = vi.fn();
    const { getByTestId } = renderField(
      { onChange: vi.fn(), onScan },
      { onSubmit },
    );

    fireEvent.click(getByTestId("fake-camera"));

    expect(onScan).toHaveBeenCalledWith("DEV01260000000000061");
    await new Promise((r) => setTimeout(r, 100));
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it("can leave the camera out", () => {
    const { queryByTestId } = renderField({
      onChange: vi.fn(),
      cameraScan: false,
    });
    expect(queryByTestId("fake-camera")).toBeNull();
  });
});
