import {
  dateFormattingLocale,
  installDateLocaleDefault,
} from "./dateLocaleDefault";

describe("dateLocaleDefault", () => {
  // 10 October 2026: day and month differ, so the order is visible
  const date = new Date(2026, 9, 10, 14, 5);

  afterEach(() => installDateLocaleDefault(undefined));

  test("English interface with day-first dates formats as en-GB", () => {
    expect(dateFormattingLocale("fr-FR", "en-US")).toBe("en-GB");
    expect(dateFormattingLocale("fr-FR", "fr-FR")).toBe("fr-FR");
    expect(dateFormattingLocale("en-US", "en-US")).toBe("en-US");
    expect(dateFormattingLocale(undefined, "en-US")).toBeUndefined();
  });

  test("calls without a locale use the installed one", () => {
    installDateLocaleDefault("en-GB");

    expect(date.toLocaleDateString()).toBe("10/10/2026");
    expect(new Date(2026, 0, 31).toLocaleDateString()).toBe("31/01/2026");
    expect(new Date(2026, 0, 31).toLocaleDateString([])).toBe("31/01/2026");
    expect(
      new Date(2026, 0, 31).toLocaleString(undefined, {
        day: "2-digit",
        month: "2-digit",
        year: "numeric",
      }),
    ).toBe("31/01/2026");
  });

  test("calls that name a locale keep it", () => {
    installDateLocaleDefault("en-GB");

    expect(new Date(2026, 0, 31).toLocaleDateString("ja-JP")).toBe(
      "2026/1/31",
    );
  });

  test("the browser's own language counts as no locale", () => {
    // screens pass navigator.language to mean "the default"
    installDateLocaleDefault("en-GB");

    expect(new Date(2026, 0, 31).toLocaleDateString(navigator.language)).toBe(
      "31/01/2026",
    );
  });

  test("toLocaleString is wrapped too", () => {
    installDateLocaleDefault("en-GB");

    expect(new Date(2026, 0, 31, 14, 5).toLocaleString()).toBe(
      "31/01/2026, 14:05:00",
    );
  });

  test("uninstalling restores the browser default", () => {
    const before = new Date(2026, 0, 31).toLocaleDateString();
    installDateLocaleDefault("en-GB");
    installDateLocaleDefault(undefined);

    expect(new Date(2026, 0, 31).toLocaleDateString()).toBe(before);
  });
});
