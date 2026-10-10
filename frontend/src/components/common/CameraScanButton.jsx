import React, { useEffect, useRef, useState } from "react";
import { Button, InlineNotification, Modal } from "@carbon/react";
import { Camera } from "@carbon/icons-react";
import { useIntl } from "react-intl";
import { cameraScanSupported, startCameraScan } from "../utils/cameraBarcode";

const ERROR_KEYS = {
  NotAllowedError: "camera.scan.error.denied",
  SecurityError: "camera.scan.error.denied",
  NotFoundError: "camera.scan.error.noCamera",
  OverconstrainedError: "camera.scan.error.noCamera",
  NotReadableError: "camera.scan.error.busy",
};

/**
 * A camera button for barcode fields, for phones and tablets without a
 * scanner. Opens the rear camera and calls onDetected(code) with the first
 * barcode read. Renders nothing where the camera can't be used (plain HTTP,
 * no camera API), so a USB or Bluetooth scanner remains the default path.
 */
const CameraScanButton = ({ onDetected, disabled = false, id }) => {
  const intl = useIntl();
  const [open, setOpen] = useState(false);
  const [errorKey, setErrorKey] = useState(null);
  const videoRef = useRef(null);

  useEffect(() => {
    if (!open) {
      return undefined;
    }
    let stop = null;
    let cancelled = false;
    setErrorKey(null);
    startCameraScan(videoRef.current, (code) => {
      if (cancelled) {
        return;
      }
      if (navigator.vibrate) {
        navigator.vibrate(80);
      }
      setOpen(false);
      onDetected(code);
    })
      .then((stopFn) => {
        stop = stopFn;
        if (cancelled) {
          stopFn();
        }
      })
      .catch((error) => {
        if (!cancelled) {
          setErrorKey(ERROR_KEYS[error && error.name] || "camera.scan.error");
        }
      });
    return () => {
      cancelled = true;
      if (stop) {
        stop();
      }
    };
  }, [open]);

  if (!cameraScanSupported()) {
    return null;
  }

  const label = intl.formatMessage({ id: "camera.scan" });
  return (
    <>
      <Button
        kind="ghost"
        size="md"
        hasIconOnly
        renderIcon={Camera}
        iconDescription={label}
        tooltipPosition="left"
        disabled={disabled}
        onClick={() => setOpen(true)}
        data-testid="camera-scan-button"
        id={id ? id + "_cameraScan" : undefined}
        className="camera-scan-button"
      />
      {open && (
        <Modal
          open
          passiveModal
          size="sm"
          modalHeading={label}
          onRequestClose={() => setOpen(false)}
          className="camera-scan-modal"
        >
          {errorKey ? (
            <InlineNotification
              kind="error"
              lowContrast
              hideCloseButton
              title={intl.formatMessage({ id: errorKey })}
            />
          ) : (
            <p>{intl.formatMessage({ id: "camera.scan.hint" })}</p>
          )}
          <video
            ref={videoRef}
            muted
            playsInline
            autoPlay
            data-testid="camera-scan-video"
            style={{ width: "100%", maxHeight: "60vh", background: "#000" }}
          />
        </Modal>
      )}
    </>
  );
};

export default CameraScanButton;
