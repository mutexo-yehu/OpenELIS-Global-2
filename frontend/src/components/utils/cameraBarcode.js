/**
 * Reads a barcode from the device camera, for phones and tablets that have no
 * barcode scanner attached. Uses the browser's own BarcodeDetector where it
 * reads Code 128 (Android Chrome, Safari 17+), otherwise ZXing in JavaScript
 * (desktop Chrome on Windows, Firefox).
 */

// Specimen labels are Code 128 (BarCodeType=BARCODE) or QR (BarCodeType=QR);
// the others cover labels printed by other systems and referral labs.
export const SCAN_FORMATS = ["code_128", "qr_code", "data_matrix", "code_39"];

const DETECT_INTERVAL_MS = 150;

/** The camera is only offered over HTTPS (or localhost) with a camera API. */
export function cameraScanSupported() {
  return Boolean(
    typeof window !== "undefined" &&
      window.isSecureContext &&
      typeof navigator !== "undefined" &&
      navigator.mediaDevices &&
      typeof navigator.mediaDevices.getUserMedia === "function",
  );
}

async function nativeDetector() {
  if (typeof window === "undefined" || !("BarcodeDetector" in window)) {
    return null;
  }
  try {
    const supported = await window.BarcodeDetector.getSupportedFormats();
    const formats = SCAN_FORMATS.filter((f) => supported.includes(f));
    if (!formats.includes("code_128")) {
      return null;
    }
    return new window.BarcodeDetector({ formats });
  } catch {
    return null;
  }
}

async function zxingReader() {
  const [{ BrowserMultiFormatReader }, { BarcodeFormat, DecodeHintType }] =
    await Promise.all([import("@zxing/browser"), import("@zxing/library")]);
  const hints = new Map();
  hints.set(DecodeHintType.POSSIBLE_FORMATS, [
    BarcodeFormat.CODE_128,
    BarcodeFormat.QR_CODE,
    BarcodeFormat.DATA_MATRIX,
    BarcodeFormat.CODE_39,
  ]);
  return new BrowserMultiFormatReader(hints);
}

/**
 * Starts the rear camera in `video` and calls `onCode(text)` once, with the
 * first barcode read. Resolves to a stop() function that releases the camera;
 * call it when the scan is cancelled. Rejects with the getUserMedia error
 * (NotAllowedError, NotFoundError, ...) when the camera can't be opened.
 */
export async function startCameraScan(video, onCode) {
  const stream = await navigator.mediaDevices.getUserMedia({
    video: {
      facingMode: { ideal: "environment" },
      width: { ideal: 1280 },
      height: { ideal: 720 },
    },
    audio: false,
  });
  let stopped = false;
  let timer = null;
  let controls = null;

  const stop = () => {
    stopped = true;
    if (timer) {
      clearTimeout(timer);
    }
    if (controls) {
      controls.stop();
    }
    stream.getTracks().forEach((track) => track.stop());
    if (video) {
      video.srcObject = null;
    }
  };

  const found = (text) => {
    const code = (text || "").trim();
    if (stopped || !code) {
      return;
    }
    stop();
    onCode(code);
  };

  try {
    const detector = await nativeDetector();
    if (detector) {
      video.srcObject = stream;
      await video.play();
      const tick = async () => {
        if (stopped) {
          return;
        }
        try {
          const codes = await detector.detect(video);
          if (codes.length > 0) {
            found(codes[0].rawValue);
            return;
          }
        } catch {
          // a frame that can't be read yet (camera still starting): try the next one
        }
        timer = setTimeout(tick, DETECT_INTERVAL_MS);
      };
      tick();
    } else {
      const reader = await zxingReader();
      controls = await reader.decodeFromStream(stream, video, (result) => {
        if (result) {
          found(result.getText());
        }
      });
      if (stopped) {
        controls.stop();
      }
    }
  } catch (error) {
    stop();
    throw error;
  }
  return stop;
}
