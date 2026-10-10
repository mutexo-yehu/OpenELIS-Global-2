import React, { useState, useContext, useEffect, useRef } from "react";
import { TextInput } from "@carbon/react";
import { convertAlphaNumLabNumForDisplay } from "../utils/Utils";
import { ConfigurationContext } from "../layout/Layout";
import CameraScanButton from "./CameraScanButton";

// Delay before acting on a camera scan, so the scanned value has rendered first
const SCAN_SUBMIT_DELAY_MS = 50;

/**
 * Acts like a barcode scanner's Enter key: presses the form's default submit
 * button if it has one (the browser's implicit submission), otherwise sends
 * Enter to the field for screens that handle the key themselves.
 */
export const submitLikeScanner = (input) => {
  if (!input) {
    return;
  }
  const form = input.form;
  const submit =
    form &&
    form.querySelector(
      "button[type=submit]:not([disabled]), input[type=submit]:not([disabled])",
    );
  if (submit && typeof form.requestSubmit === "function") {
    form.requestSubmit(submit);
  } else {
    input.dispatchEvent(
      new KeyboardEvent("keydown", {
        key: "Enter",
        code: "Enter",
        keyCode: 13,
        which: 13,
        bubbles: true,
      }),
    );
  }
};

const CustomLabNumberInput = ({ cameraScan = true, onScan, ...props }) => {
  const { configurationProperties } = useContext(ConfigurationContext);

  const [formattedInput, setFormattedInput] = useState("");
  const wrapperRef = useRef(null);

  useEffect(() => {
    setDisplayValue();
  }, [props.value]);

  const setDisplayValue = () => {
    if (
      configurationProperties.AccessionFormat === "ALPHANUM" &&
      props.value?.length < 13
    ) {
      const formatted = convertAlphaNumLabNumForDisplay(props.value); // use your own format function here
      setFormattedInput(formatted);
    } else {
      setFormattedInput(props.value);
    }
  };

  // A camera scan fills the field the way a typed or scanned value does, then
  // submits like a scanner's Enter (or hands the code to onScan instead).
  const handleScan = (code) => {
    if (props.onChange) {
      props.onChange(
        { target: { value: code, name: props.name, id: props.id } },
        code,
      );
    }
    if (onScan) {
      onScan(code);
      return;
    }
    setTimeout(() => {
      const input =
        wrapperRef.current &&
        wrapperRef.current.querySelector("input:not([type=hidden])");
      submitLikeScanner(input);
    }, SCAN_SUBMIT_DELAY_MS);
  };

  const withCamera = (field) =>
    cameraScan ? (
      <div
        ref={wrapperRef}
        className="lab-number-with-camera"
        style={{ display: "flex", alignItems: "flex-end", gap: "0.25rem" }}
      >
        <div style={{ flex: 1, minWidth: 0 }}>{field}</div>
        <CameraScanButton
          id={props.id}
          disabled={props.disabled}
          onDetected={handleScan}
        />
      </div>
    ) : (
      field
    );

  if (configurationProperties.AccessionFormat !== "ALPHANUM") {
    return <>{withCamera(<TextInput {...props} />)}</>;
  } else {
    return (
      <>
        <input
          type="hidden"
          value={props.value ? props.value : ""}
          name={props.name}
          id={props.id}
        />
        {withCamera(
          <TextInput
            {...props}
            onChange={(e) => {
              let val = e.target.value;
              for (
                let numDashes = (e.target.value.match(/-/g) || []).length;
                numDashes > 1;
                --numDashes
              ) {
                val = val.replace("-", "");
              }
              let vals = val.split("-");
              if (vals.length > 1) {
                //combine values after dashes unless a full accession number exists before the dash
                //this will fail if 100+ tests are run on a sample
                if (vals[1].length > 2 || vals[0].length <= 7) {
                  vals = [vals[0] + vals[1]];
                } else {
                  vals = [vals[0] + "-" + vals[1]];
                }
              }
              props.onChange(e, vals[0]);
            }}
            labelText={props.labelText}
            id={"display_" + props.id}
            value={formattedInput}
            enableCounter
            maxCount={23}
            name={"display_" + props.name}
          />,
        )}
      </>
    );
  }
};
export default CustomLabNumberInput;
