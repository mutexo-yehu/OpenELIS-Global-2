import { vi } from "vitest";
import { cameraScanSupported, startCameraScan } from "./cameraBarcode";

vi.mock("@zxing/browser", () => ({
  BrowserMultiFormatReader: vi.fn(),
}));

const fakeStream = () => {
  const track = { stop: vi.fn() };
  return { track, stream: { getTracks: () => [track] } };
};

const fakeVideo = () => ({
  srcObject: null,
  play: vi.fn().mockResolvedValue(undefined),
});

describe("cameraBarcode", () => {
  const originalSecure = window.isSecureContext;
  let getUserMedia;

  beforeEach(() => {
    getUserMedia = vi.fn();
    Object.defineProperty(navigator, "mediaDevices", {
      value: { getUserMedia },
      configurable: true,
    });
    Object.defineProperty(window, "isSecureContext", {
      value: true,
      configurable: true,
    });
  });

  afterEach(() => {
    delete window.BarcodeDetector;
    Object.defineProperty(window, "isSecureContext", {
      value: originalSecure,
      configurable: true,
    });
    vi.clearAllMocks();
  });

  it("offers the camera only in a secure context with a camera API", () => {
    expect(cameraScanSupported()).toBe(true);
    Object.defineProperty(window, "isSecureContext", {
      value: false,
      configurable: true,
    });
    expect(cameraScanSupported()).toBe(false);
  });

  it("reads with the browser's BarcodeDetector and releases the camera", async () => {
    const { track, stream } = fakeStream();
    getUserMedia.mockResolvedValue(stream);
    const detect = vi
      .fn()
      .mockResolvedValueOnce([])
      .mockResolvedValue([{ rawValue: " DEV01260000000000061 " }]);
    window.BarcodeDetector = vi.fn(function () {
      return { detect };
    });
    window.BarcodeDetector.getSupportedFormats = vi
      .fn()
      .mockResolvedValue(["code_128", "qr_code"]);
    const onCode = vi.fn();

    await startCameraScan(fakeVideo(), onCode);

    await vi.waitFor(() =>
      expect(onCode).toHaveBeenCalledWith("DEV01260000000000061"),
    );
    expect(onCode).toHaveBeenCalledTimes(1);
    expect(track.stop).toHaveBeenCalled();
    expect(window.BarcodeDetector).toHaveBeenCalledWith({
      formats: ["code_128", "qr_code"],
    });
    expect(getUserMedia.mock.calls[0][0].video.facingMode).toEqual({
      ideal: "environment",
    });
  });

  it("falls back to ZXing when the browser can't read Code 128", async () => {
    const { track, stream } = fakeStream();
    getUserMedia.mockResolvedValue(stream);
    window.BarcodeDetector = vi.fn();
    window.BarcodeDetector.getSupportedFormats = vi
      .fn()
      .mockResolvedValue(["qr_code"]);
    const controls = { stop: vi.fn() };
    const { BrowserMultiFormatReader } = await import("@zxing/browser");
    BrowserMultiFormatReader.mockImplementation(function () {
      return {
        decodeFromStream: vi.fn(async (s, video, callback) => {
          callback(undefined);
          callback({ getText: () => "DEV01260000000000062" });
          return controls;
        }),
      };
    });
    const onCode = vi.fn();

    await startCameraScan(fakeVideo(), onCode);

    expect(onCode).toHaveBeenCalledWith("DEV01260000000000062");
    expect(onCode).toHaveBeenCalledTimes(1);
    expect(track.stop).toHaveBeenCalled();
    expect(window.BarcodeDetector).not.toHaveBeenCalled();
  });

  it("passes on the camera error, e.g. when access is refused", async () => {
    const refused = Object.assign(new Error("denied"), {
      name: "NotAllowedError",
    });
    getUserMedia.mockRejectedValue(refused);

    await expect(startCameraScan(fakeVideo(), vi.fn())).rejects.toBe(refused);
  });

  it("stop() releases the camera when a scan is cancelled", async () => {
    const { track, stream } = fakeStream();
    getUserMedia.mockResolvedValue(stream);
    window.BarcodeDetector = vi.fn(function () {
      return { detect: vi.fn().mockResolvedValue([]) };
    });
    window.BarcodeDetector.getSupportedFormats = vi
      .fn()
      .mockResolvedValue(["code_128"]);
    const onCode = vi.fn();

    const stop = await startCameraScan(fakeVideo(), onCode);
    stop();

    expect(track.stop).toHaveBeenCalled();
    expect(onCode).not.toHaveBeenCalled();
  });
});
